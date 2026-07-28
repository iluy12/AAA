package com.iluy.imutest

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
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
 * שירות שרץ ברקע תמיד (כולל מסך כבוי) ומזהה צרור-דפיקות על גוף השעון —
 * הבסיס שהוכח עובד באפליקציית הבדיקה (testtap3 / ImuLoggerService), עכשיו
 * עם לוגיקת זיהוי-דפוס מלאה במקום רק תיעוד גולמי.
 *
 * לוגיקת-הדפוס עצמה (שכבות 1–7 — עוצמה, jerk, refractory, פולס-קצר,
 * תיחום-צרור, סדירות-קצב, דמיון-לדפיקה-ראשונה) חיה ב-TapClusterDetector,
 * משותפת עם מסך התרגול בשאלון. השירות עצמו אחראי רק על שכבות שתלויות
 * בחיישני-רקע/מצב-שירות:
 *  8. worn-gating (WORN_GATING_ENABLED) — אם יש חיישן off-body/דופק
 *     זמין, מתעלמים מדפיקות כשהשעון לא על היד. נופל בחזרה בבטחה אם אין
 *     חיישן/הרשאה.
 *  9. השתקת-תנועה-רציפה (סעיף 5 במסמך) — הליכה/נסיעה משתיקה זיהוי.
 *
 * הסף האישי המכויל בתרגול (LocalStore.getPersonalTapThreshold) נקרא מחדש
 * בכל onStartCommand (לא רק פעם אחת ב-onCreate) — כדי שגם שירות שכבר רץ
 * בזיכרון יקלוט כיול חדש (למשל אחרי מילוי-שאלון-מחדש). נופל בחזרה
 * ל-DebugConfig.TAP_MAGNITUDE_THRESHOLD הגלובלי אם אין ערך-אישי שמור.
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

    private lateinit var detector: TapClusterDetector

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
        setupWornGating()

        detector = TapClusterDetector(
            magnitudeThreshold = DebugConfig.TAP_MAGNITUDE_THRESHOLD,
            wornGatingEnabled = DebugConfig.WORN_GATING_ENABLED,
            isWorn = { wornState },
            wornSensorAvailable = { wornSensorAvailable },
            sustainedMotionSuppressMs = DebugConfig.SUSTAINED_MOTION_SUPPRESS_MS,
            onLog = { tag, detail -> EventLog.log(this, tag, detail) },
            onTapPatternDetected = { count, _ -> onTapPatternDetected(count) }
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
        detector.magnitudeThreshold = LocalStore.getPersonalTapThreshold(this)
            ?: DebugConfig.TAP_MAGNITUDE_THRESHOLD
        if (!isListening) {
            val sensor = accelerometer
            if (sensor != null) {
                // SENSOR_DELAY_FASTEST, לא GAME (~20ms/50Hz): הקשה על מסגרת
                // קשיחה היא פולס-הלם קצר-מאוד — ב-50Hz יש סיכוי אמיתי
                // לדגום בין הפסגה לפסגה ולפספס את העוצמה האמיתית, לא רק
                // לתפוס אותה נמוכה מדי.
                sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_FASTEST)
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
                val x = event.values[0]; val y = event.values[1]; val z = event.values[2]
                val magnitude = Math.sqrt((x * x + y * y + z * z).toDouble())
                detector.onSample(magnitude, System.currentTimeMillis())
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

    private fun onTapPatternDetected(spikeCount: Int) {
        val now = System.currentTimeMillis()

        if (now < LocalStore.getCooldownUntil(this)) {
            EventLog.log(this, "INFO", "tap_ignored_cooldown_active")
            return
        }

        val debugDetail = "הקשה ($spikeCount זוהו, קצב-דלת)"
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
            sendVideoNotification(debugDetail)
        }
    }

    private fun sendVideoNotification(debugDetail: String) {
        val intent = Intent(this, VideoPlaceholderActivity::class.java).apply {
            putExtra(VideoPlaceholderActivity.EXTRA_SOURCE, debugDetail)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        else PendingIntent.FLAG_UPDATE_CURRENT
        val pendingIntent = PendingIntent.getActivity(this, 1, intent, flags)

        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "iluy_video_channel", "עילוי — הודעות", NotificationManager.IMPORTANCE_HIGH
            )
            nm?.createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(this, "iluy_video_channel")
            .setContentTitle("עילוי שלחו לך סרטון")
            .setContentText("לחץ לצפייה")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        nm?.notify(NOTIFICATION_ID + 1, notification)
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
