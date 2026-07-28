package com.iluy.imutest

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * שירות שרץ ברקע תמיד (כולל מסך כבוי) ומזהה את מחוות-הדיווח.
 *
 * המחווה היא **ניעור-יד**, לא הקשה. ההקשה נזנחה אחרי ארבעה סבבי-כיוונון
 * בשטח שלא התכנסו: ב-25Hz (תקרת-החומרה) הקשה נמשכת 1-2 מדגמים, והעוצמה
 * שלה (15-17) יושבת בתוך טווח ההליכה (12-20) — אין סף שמפריד ביניהן.
 * הניעור נבדל בתדירות, ממד שבו אין חפיפה. ראו ShakeDetector.
 *
 * לוגיקת-הזיהוי עצמה חיה ב-ShakeDetector (Kotlin טהור, בלי תלות
 * ב-Context). השירות אחראי רק על מה שתלוי בחיישני-רקע ובמצב-שירות:
 *  - worn-gating (WORN_GATING_ENABLED) — אם יש חיישן off-body/דופק זמין,
 *    מתעלמים מהמחווה כשהשעון לא על היד. נופל בחזרה בבטחה אם אין
 *    חיישן/הרשאה.
 *  - היכון שעתי ו-cooldown (LocalStore) — הסלמה בניעור שני באותה שעה.
 *
 * אין כאן כיול-אישי: תדירות-ניעור אינה תלוית-אדם כמו עוצמת-הקשה, ולכן
 * נמחקו הסף האישי ותנוחת-הייחוס שהיו נדרשים למסלול ההקשה.
 *
 * זו עדיין לא הפרדה בין Sleep/Active/Moving לצורך חיישן פיזיולוגי (לא
 * רלוונטי ל-v1 — אין חיישן פיזיולוגי זמין עדיין, תלוי בתשובת ויקי).
 */
class TapDetectorService : Service(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null

    // --- worn-gating ---
    private var offBodySensor: Sensor? = null
    private var heartRateSensor: Sensor? = null
    private var wornSensorAvailable = false
    private var wornState = true // אופטימי כברירת מחדל — לא חוסם אם אין נתון

    private var isListening = false

    private lateinit var detector: ShakeDetector

    // --- אבחון-דופק (בדיקה אמפירית: TYPE_HEART_RATE מדווח ברקע באופן
    // רציף על החומרה הזו, או רק על-דרישה? תלוי-חומרה, לא ידוע מראש —
    // ראו סעיף 6 במסמך: פיצ'ר-החיזוי צריך את זה כתשתית). מאחורי
    // DEBUG_TAG_ENABLED כמו שאר האבחון, כמו מקבילו אצל ה-accelerometer.
    private var hrSampleCountInWindow = 0
    private var hrMinValueInWindow = Float.MAX_VALUE
    private var hrMaxValueInWindow = Float.MIN_VALUE
    private var hrLastSampleMs: Long? = null
    private var hrIntervalSumMs = 0L
    private var hrIntervalCountInWindow = 0
    private var hrDiagnosticStarted = false
    private val hrDiagnosticHandler = Handler(Looper.getMainLooper())
    private val hrDiagnosticSummaryRunnable = object : Runnable {
        override fun run() {
            val hasSamples = hrSampleCountInWindow > 0
            val avgIntervalMs = if (hrIntervalCountInWindow > 0) hrIntervalSumMs / hrIntervalCountInWindow else -1L
            EventLog.log(
                this@TapDetectorService, "INFO",
                "hr_diagnostic_summary;samples=$hrSampleCountInWindow;" +
                    "min=${if (hasSamples) "%.1f".format(hrMinValueInWindow) else "—"};" +
                    "max=${if (hasSamples) "%.1f".format(hrMaxValueInWindow) else "—"};" +
                    "avg_interval_ms=$avgIntervalMs"
            )
            hrSampleCountInWindow = 0
            hrMinValueInWindow = Float.MAX_VALUE
            hrMaxValueInWindow = Float.MIN_VALUE
            hrIntervalSumMs = 0L
            hrIntervalCountInWindow = 0
            hrDiagnosticHandler.postDelayed(this, 60_000L)
        }
    }

    companion object {
        const val CHANNEL_ID = "iluy_tap_service_channel"
        const val NOTIFICATION_ID = 42

        fun start(context: Context) {
            val intent = Intent(context, TapDetectorService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TapDetectorService::class.java))
        }
    }

    override fun onCreate() {
        super.onCreate()
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        logAvailableSensors()
        setupWornGating()

        detector = ShakeDetector(
            onLog = { tag, detail -> EventLog.log(this, tag, detail) },
            onShakeDetected = { reversals, peak -> onReportGestureDetected(reversals, peak) }
        )
    }

    /**
     * אינוונטר-חיישנים חד-פעמי (מאחורי DEBUG_TAG_ENABLED). נבנה אחרי כמה
     * סבבים שבהם הנחות על החומרה התבררו כשגויות (סף 22.0, תקרת 25Hz,
     * הכובד מתחת לסף-הכיול, off-body שלא קיים) — עדיף לקרוא מהמכשיר מה
     * באמת יש בו.
     *
     * power_ma ו-min_delay_us הם העיקר כאן, לא רק שמות: הראשון עונה על
     * "מה אפשר להרשות לעצמנו מבחינת סוללה", השני נותן את תקרת-הקצב
     * האמיתית של כל חיישן בלי לגלות אותה בשדה כמו שקרה עם ה-accelerometer.
     */
    private fun logAvailableSensors() {
        if (!DebugConfig.DEBUG_TAG_ENABLED) return

        val sensors = sensorManager.getSensorList(Sensor.TYPE_ALL)
        EventLog.log(this, "INFO", "sensor_inventory_start;total=${sensors.size}")
        for (s in sensors) {
            EventLog.log(
                this, "INFO",
                "sensor;type=${s.type};name=${s.name};" +
                    "power_ma=${"%.2f".format(s.power)};" +
                    "min_delay_us=${s.minDelay};" +
                    "max_range=${"%.1f".format(s.maximumRange)}"
            )
        }

        // סיכום ממוקד למועמדים שרלוונטיים להחלטת-המחווה, כדי לא לחפש
        // ידנית בתוך רשימה ארוכה על מסך 2 אינץ'
        val has = { type: Int -> sensorManager.getDefaultSensor(type) != null }
        EventLog.log(
            this, "INFO",
            "sensor_candidates;gyroscope=${has(Sensor.TYPE_GYROSCOPE)};" +
                "proximity=${has(Sensor.TYPE_PROXIMITY)};" +
                "light=${has(Sensor.TYPE_LIGHT)};" +
                "step_detector=${has(Sensor.TYPE_STEP_DETECTOR)};" +
                "step_counter=${has(Sensor.TYPE_STEP_COUNTER)};" +
                "significant_motion=${has(Sensor.TYPE_SIGNIFICANT_MOTION)};" +
                "rotation_vector=${has(Sensor.TYPE_ROTATION_VECTOR)};" +
                "game_rotation_vector=${has(Sensor.TYPE_GAME_ROTATION_VECTOR)}"
        )
    }

    private fun setupWornGating() {
        if (!DebugConfig.WORN_GATING_ENABLED) return

        // ניסיון ראשון: חיישן off-body ייעודי (לא דורש הרשאה מיוחדת)
        offBodySensor = sensorManager.getDefaultSensor(Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT)
        if (offBodySensor != null) {
            wornSensorAvailable = true
            EventLog.log(this, "INFO", "worn_gating_using_offbody_sensor")
            return
        }

        // נפילה-חזרה: חיישן דופק, רק אם כבר יש הרשאת BODY_SENSORS
        val hasBodySensorPermission = ContextCompat.checkSelfPermission(
            this, Manifest.permission.BODY_SENSORS
        ) == PackageManager.PERMISSION_GRANTED
        if (hasBodySensorPermission) {
            heartRateSensor = sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE)
            if (heartRateSensor != null) {
                wornSensorAvailable = true
                EventLog.log(this, "INFO", "worn_gating_using_heart_rate_sensor")
                return
            }
        }

        // אין שום חיישן זמין — worn-gating לא פעיל בפועל, לא חוסם כלום
        wornSensorAvailable = false
        EventLog.log(this, "INFO", "worn_gating_unavailable_defaulting_to_worn")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        if (!isListening) {
            val sensor = accelerometer
            if (sensor != null) {
                // חזרה ל-GAME: נמדד בפועל (hz_actual בלוג התרגול) שהחיישן
                // מספק ~25Hz בכל מקרה, בין אם מבקשים FASTEST ובין אם GAME —
                // זו תקרת-חומרה, לא מגבלת-הדגל. FASTEST לא מוסיף רזולוציה,
                // רק עלות-סוללה מיותרת בשירות-רקע תמידי. נשאר FASTEST רק
                // בחלון-התרגול הקצר (QuestionnaireActivity), ששם העלות זניחה.
                sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
                offBodySensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
                heartRateSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
                if (DebugConfig.DEBUG_TAG_ENABLED && heartRateSensor != null && !hrDiagnosticStarted) {
                    hrDiagnosticStarted = true
                    hrDiagnosticHandler.postDelayed(hrDiagnosticSummaryRunnable, 60_000L)
                }
                isListening = true
                EventLog.log(this, "INFO", "tap_service_started")
            } else {
                EventLog.log(this, "ERROR", "no_accelerometer_sensor_found")
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        sensorManager.unregisterListener(this)
        isListening = false
        if (hrDiagnosticStarted) {
            hrDiagnosticHandler.removeCallbacks(hrDiagnosticSummaryRunnable)
            hrDiagnosticStarted = false
        }
        EventLog.log(this, "INFO", "tap_service_stopped")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                // x,y,z מלאים, לא רק העוצמה: הכיוון הוא מה שמזין את
                // שער-התנוחה, והוא מגיע כאן ממילא בכל מדגם.
                detector.onSample(
                    event.values[0], event.values[1], event.values[2],
                    System.currentTimeMillis()
                )
            }
            Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT -> {
                wornState = (event.values.getOrNull(0) ?: 1f) >= 0.5f
            }
            Sensor.TYPE_HEART_RATE -> {
                val hr = event.values.getOrNull(0) ?: 0f
                wornState = hr > 0f
                if (DebugConfig.DEBUG_TAG_ENABLED) {
                    EventLog.log(this, "DEBUG", "hr_sample;value=${"%.1f".format(hr)}")
                    val now = System.currentTimeMillis()
                    hrSampleCountInWindow++
                    if (hr < hrMinValueInWindow) hrMinValueInWindow = hr
                    if (hr > hrMaxValueInWindow) hrMaxValueInWindow = hr
                    hrLastSampleMs?.let { last ->
                        hrIntervalSumMs += (now - last)
                        hrIntervalCountInWindow++
                    }
                    hrLastSampleMs = now
                }
            }
        }
    }

    private fun onReportGestureDetected(reversals: Int, peak: Double) {
        val now = System.currentTimeMillis()

        // worn-gating: היה בתוך TapClusterDetector שנמחק, ולכן הועבר לכאן.
        // ShakeDetector נשאר Kotlin טהור בלי תלות בחיישני-רקע.
        if (DebugConfig.WORN_GATING_ENABLED && wornSensorAvailable && !wornState) {
            EventLog.log(this, "DEBUG", "shake_ignored_not_worn")
            return
        }

        if (now < LocalStore.getCooldownUntil(this)) {
            EventLog.log(this, "INFO", "tap_ignored_cooldown_active")
            return
        }

        val debugDetail = "ניעור ($reversals החלפות, שיא ${"%.0f".format(peak)})"
        val standbyUntil = LocalStore.getTapStandbyUntil(this)

        if (now < standbyUntil) {
            EventLog.log(this, "TRIGGER", "tap_second_in_hour;$debugDetail")
            RiskFlowActivity.launch(
                this,
                source = debugDetail,
                variant = RiskFlowActivity.VARIANT_SECOND_TAP_IN_HOUR
            )
        } else {
            LocalStore.setTapStandbyUntil(this, now + DebugConfig.STANDBY_DURATION_MS)
            EventLog.log(this, "TRIGGER", "tap_first_in_hour;$debugDetail")
            // חלק 3.1 (הועלה-בעדיפות): ההקשה היא אירוע חיובי, לא צריכה
            // מסך-בחירה. מחליף את ה-notification+VideoPlaceholderActivity
            // הישנים באישור-קל שמדליק מסך+צליל ונסגר לבד, בלי כפתורים.
            TapAcknowledgedActivity.launch(this, source = debugDetail)
        }
    }

    private fun buildNotification(): android.app.Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "עילוי — שירות פעיל", NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("עילוי פעיל")
            .setContentText("איתך ברקע")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .build()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) { /* not used */ }
}
