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
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * שירות שרץ ברקע תמיד (כולל מסך כבוי) ומזהה צרור-דפיקות על גוף השעון —
 * הבסיס שהוכח עובד באפליקציית הבדיקה (testtap3 / ImuLoggerService), עכשיו
 * עם לוגיקת זיהוי-דפוס מלאה במקום רק תיעוד גולמי.
 *
 * שכבות ההגנה מפני false positives (עודכן אחרי סבבי בדיקה עצמית שגילו
 * גם פספוסים וגם התרעות-שווא — סף עוצמה בלבד לא הספיק):
 *  1. סף עוצמה (TAP_MAGNITUDE_THRESHOLD) — קפיצה חדה, לא תנודה עדינה.
 *  2. jerk (TAP_MIN_DELTA) — קפיצה פתאומית מהמדגם הקודם, לא רק גובה.
 *  3. refractory (TAP_REFRACTORY_MS) — הקשה חזקה אחת לא נספרת כמה פעמים.
 *  4. פולס-קצר (TAP_MAX_CONSECUTIVE_ABOVE_THRESHOLD_SAMPLES) — רעד ממושך
 *     (לא הקשה חדה) נדחה.
 *  5. תיחום-צרור (TAP_MAX_INTERVAL_MS) — קצב דפיקה-על-דלת טבעי; מרווח
 *     ארוך מדי מתחיל צרור חדש במקום להיספר כהמשך.
 *  6. סדירות-קצב (TAP_RHYTHM_MAX_STDDEV_MS) — קצב כמעט-קבוע בין דפיקות.
 *  7. דמיון-לדפיקה-ראשונה (TAP_SIMILARITY_*) — כל דפיקה בצרור חייבת
 *     להידמות בעוצמה ובמשך-פולס לדפיקה הראשונה שפתחה את הצרור. זו
 *     ההגנה החדשה ביותר: תנועה מקרית כמעט אף פעם לא עקבית מול עצמה.
 *  8. worn-gating (WORN_GATING_ENABLED) — אם יש חיישן off-body/דופק
 *     זמין, מתעלמים מדפיקות כשהשעון לא על היד. נופל בחזרה בבטחה אם אין
 *     חיישן/הרשאה.
 *  9. השתקת-תנועה-רציפה (סעיף 5 במסמך) — הליכה/נסיעה משתיקה זיהוי.
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

    // חותמות-זמן של דפיקות שכבר התקבלו לצרור הנוכחי
    private val recentSpikes = ArrayDeque<Long>()

    // "דוגמה" — הדפיקה הראשונה בכל צרור, כל דפיקה נוספת נבדקת מולה
    private var referenceMagnitude: Double? = null
    private var referencePulseSamples: Int? = null

    // מעקב תנועה-רציפה (הליכה/נסיעה)
    private var sustainedMotionStartMs: Long? = null
    private var lastSpikeAboveThresholdMs: Long = 0L
    private var suppressed = false
    private var isListening = false

    // jerk ופולס-קצר
    private var lastMagnitude: Double? = null
    private var lastAcceptedSpikeMs: Long = 0L
    private var consecutiveAboveThresholdSamples = 0

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
                sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
                offBodySensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
                heartRateSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
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
        EventLog.log(this, "INFO", "tap_service_stopped")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> handleAccelerometer(event)
            Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT -> {
                wornState = (event.values.getOrNull(0) ?: 1f) >= 0.5f
            }
            Sensor.TYPE_HEART_RATE -> {
                val hr = event.values.getOrNull(0) ?: 0f
                wornState = hr > 0f
            }
        }
    }

    private fun handleAccelerometer(event: SensorEvent) {
        val x = event.values[0]; val y = event.values[1]; val z = event.values[2]
        val magnitude = Math.sqrt((x * x + y * y + z * z).toDouble())
        val now = System.currentTimeMillis()

        val prevMagnitude = lastMagnitude
        lastMagnitude = magnitude

        val aboveThreshold = magnitude > DebugConfig.TAP_MAGNITUDE_THRESHOLD
        val jumpedSuddenly = prevMagnitude != null &&
            Math.abs(magnitude - prevMagnitude) > DebugConfig.TAP_MIN_DELTA

        if (aboveThreshold) {
            consecutiveAboveThresholdSamples++
            trackSustainedMotion(now)

            val debounced = now - lastAcceptedSpikeMs < DebugConfig.TAP_REFRACTORY_MS
            when {
                suppressed -> { /* מושתק בגלל תנועה רציפה */ }
                debounced -> EventLog.log(this, "DEBUG", "tap_candidate_rejected_refractory")
                !jumpedSuddenly -> { /* אין קפיצה חדה — כנראה המשך אותו פולס, לא דפיקה חדשה */ }
                else -> evaluateCandidate(now, magnitude, consecutiveAboveThresholdSamples)
            }
        } else {
            consecutiveAboveThresholdSamples = 0
            sustainedMotionStartMs = null
            if (suppressed) {
                suppressed = false
                EventLog.log(this, "INFO", "tap_detection_resumed_after_stillness")
            }
        }
    }

    private fun trackSustainedMotion(now: Long) {
        if (sustainedMotionStartMs == null || now - lastSpikeAboveThresholdMs > 1_500L) {
            sustainedMotionStartMs = now
        }
        lastSpikeAboveThresholdMs = now

        val sustainedFor = now - (sustainedMotionStartMs ?: now)
        if (sustainedFor > DebugConfig.SUSTAINED_MOTION_SUPPRESS_MS && !suppressed) {
            suppressed = true
            EventLog.log(this, "INFO", "tap_detection_suppressed_sustained_motion")
        }
    }

    private fun evaluateCandidate(now: Long, magnitude: Double, pulseSamples: Int) {
        if (pulseSamples > DebugConfig.TAP_MAX_CONSECUTIVE_ABOVE_THRESHOLD_SAMPLES) {
            EventLog.log(this, "DEBUG", "tap_candidate_rejected_sustained_pulse")
            return
        }

        if (DebugConfig.WORN_GATING_ENABLED && wornSensorAvailable && !wornState) {
            EventLog.log(this, "DEBUG", "tap_candidate_rejected_not_worn")
            return
        }

        // תיחום-צרור: מרווח ארוך מדי מהדפיקה האחרונה = צרור חדש, לא המשך
        if (recentSpikes.isNotEmpty() && now - recentSpikes.last() > DebugConfig.TAP_MAX_INTERVAL_MS) {
            EventLog.log(this, "DEBUG", "tap_cluster_reset_interval_too_long")
            recentSpikes.clear()
            referenceMagnitude = null
            referencePulseSamples = null
        }

        if (recentSpikes.isEmpty()) {
            // דפיקה ראשונה בצרור — הופכת ל"דוגמה" לכל השאר
            referenceMagnitude = magnitude
            referencePulseSamples = pulseSamples
            acceptIntoCluster(now)
            return
        }

        val refMag = referenceMagnitude ?: magnitude
        val refPulse = referencePulseSamples ?: pulseSamples
        val magDiffRatio = if (refMag > 0) Math.abs(magnitude - refMag) / refMag else 0.0
        val pulseDiff = Math.abs(pulseSamples - refPulse)

        if (magDiffRatio > DebugConfig.TAP_SIMILARITY_MAGNITUDE_TOLERANCE ||
            pulseDiff > DebugConfig.TAP_SIMILARITY_PULSE_TOLERANCE_SAMPLES
        ) {
            EventLog.log(
                this, "DEBUG",
                "tap_candidate_rejected_dissimilar_from_first;magDiff=${"%.2f".format(magDiffRatio)};pulseDiff=$pulseDiff"
            )
            return
        }

        acceptIntoCluster(now)
    }

    private fun acceptIntoCluster(now: Long) {
        lastAcceptedSpikeMs = now
        recentSpikes.addLast(now)
        while (recentSpikes.isNotEmpty() && now - recentSpikes.first() > DebugConfig.TAP_WINDOW_MS) {
            recentSpikes.removeFirst()
        }

        if (recentSpikes.size >= DebugConfig.TAP_COUNT_THRESHOLD) {
            if (isRhythmRegular(recentSpikes)) {
                val count = recentSpikes.size
                recentSpikes.clear()
                referenceMagnitude = null
                referencePulseSamples = null
                onTapPatternDetected(count)
            } else {
                EventLog.log(this, "DEBUG", "tap_candidate_rejected_irregular")
                recentSpikes.removeFirst()
            }
        }
    }

    private fun isRhythmRegular(spikes: ArrayDeque<Long>): Boolean {
        if (spikes.size < 3) return true
        val gaps = spikes.zipWithNext { a: Long, b: Long -> (b - a).toDouble() }
        val mean = gaps.average()
        val variance = gaps.sumOf { (it - mean) * (it - mean) } / gaps.size
        val stddev = Math.sqrt(variance)
        return stddev <= DebugConfig.TAP_RHYTHM_MAX_STDDEV_MS
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
