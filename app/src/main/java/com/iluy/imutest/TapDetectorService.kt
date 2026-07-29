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
 * שירות-רקע. **כבר לא מזהה את מחוות-הדיווח** — היא עברה למגע על
 * מסך-השעון (WatchFaceActivity).
 *
 * חמישה סבבים של זיהוי מבוסס-תאוצה נכשלו, ולא בגלל כיוונון: ב-25Hz
 * (תקרת-החומרה) הקשה טבעית (15-17) והליכה (12-20) חופפות בנתונים עצמם,
 * וניעור מתנגש עם ניעור-מים אחרי נטילת ידיים. ה-accelerometer כבר לא
 * נרשם כאן — גם כדי לחסוך סוללה וגם כדי שלא ימשיך לייצר את אותן
 * התרעות-שווא.
 *
 * מה שנשאר באחריות השירות:
 *  - worn-gating — מצב "על היד", לשימוש עתידי
 *  - אבחון-דופק (מאחורי DEBUG_TAG_ENABLED) — לבירור האם TYPE_HEART_RATE
 *    מדווח ברקע ברציפות, מה שיקבע אם סעיף 6 (זיהוי מוקדם) ישים בכלל
 *  - אינוונטר-חיישנים חד-פעמי
 *
 * לוגיקת ההסלמה (היכון שעתי, cooldown) חיה ב-OvercomingReporter, משותפת
 * עם מסך-השעון.
 */
class TapDetectorService : Service(), SensorEventListener {

    private lateinit var sensorManager: SensorManager

    // --- worn-gating ---
    private var offBodySensor: Sensor? = null
    private var heartRateSensor: Sensor? = null
    private var wornSensorAvailable = false
    private var wornState = true // אופטימי כברירת מחדל — לא חוסם אם אין נתון

    private var isListening = false


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
    private var hrSamplesSinceLastLog = 0
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
        logAvailableSensors()
        setupWornGating()
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
            // ה-accelerometer כבר לא נרשם כאן: הדיווח עבר למחוות ✕ על
            // מסך-השעון, ולהשאיר גלאי-תנועה פעיל היה גם מבזבז סוללה
            // וגם ממשיך לייצר את אותן התרעות-שווא שבגללן ויתרנו עליו.
            offBodySensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
            heartRateSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
            if (DebugConfig.DEBUG_TAG_ENABLED && heartRateSensor != null && !hrDiagnosticStarted) {
                hrDiagnosticStarted = true
                hrDiagnosticHandler.postDelayed(hrDiagnosticSummaryRunnable, 60_000L)
            }
            isListening = true
            EventLog.log(this, "INFO", "tap_service_started")
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
            Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT -> {
                wornState = (event.values.getOrNull(0) ?: 1f) >= 0.5f
            }
            Sensor.TYPE_HEART_RATE -> {
                val hr = event.values.getOrNull(0) ?: 0f
                wornState = hr > 0f
                if (DebugConfig.DEBUG_TAG_ENABLED) {
                    // ⚠️ לא רושמים כל מדגם. החיישן מדווח ~3 פעמים בשנייה,
                    // כלומר כ-10,000 שורות בשעה — זה מה שהפך את הלוג
                    // לבלתי-ניתן להעתקה על המכשיר. סיכום הדקה נותן את אותו
                    // מידע אבחוני, ודגימה אחת ל-30 נשמרת רק כדי לראות
                    // ערכים גולמיים לדוגמה.
                    hrSamplesSinceLastLog++
                    if (hrSamplesSinceLastLog >= 30) {
                        hrSamplesSinceLastLog = 0
                        // נמדד גם לבוש וגם לא: values[0] מחזיר 0.0 או ~10^21,
                        // כלומר לא דופק. לפני שפוסלים את החיישן — בודקים אם
                        // הערך יושב בכלל בתא אחר, ומה ה-accuracy מדווח.
                        // דרייברים זולים לפעמים לא מכבדים את חוזה ה-API.
                        val slots = event.values.joinToString(",") { "%.1f".format(it) }
                        EventLog.log(
                            this, "DEBUG",
                            "hr_sample;value=${"%.1f".format(hr)};all_slots=[$slots];" +
                                "count=${event.values.size};accuracy=${event.accuracy}"
                        )
                    }
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
