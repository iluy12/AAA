package com.iluy.imutest

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
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

    // --- ערוץ התנועה (§5.4) ---
    //
    // הכלל היחיד שעובד מיום 1 הוא "דופק שעולה אחרי שהתנועה כבר נעצרה",
    // ועד עכשיו לא היה לו מקור נתונים רשום בכלל: onStartCommand רשם רק
    // off-body ודופק. מונה-הצעדים מוצהר על power_ma=0.00 ומגיע כאירוע
    // on-change, כלומר הוא שותק לגמרי כשאין תנועה — ושתיקתו **היא**
    // האות, לא תקלה.
    private var stepSensor: Sensor? = null
    private var stepCountTotal = -1L
    private var stepCountAtWindowStart = -1L
    private var lastStepElapsedMs: Long? = null

    private var isListening = false


    // --- ניטור-דופק. השאלה ששאלנו כאן ("האם TYPE_HEART_RATE מדווח ברקע
    // ברציפות, או רק על-דרישה?") נענתה: ברציפות, ~3 דגימות בשנייה. גם
    // הערכים ה"שבורים" פוענחו — ראו HeartRate. לכן זה כבר לא אבחון אלא
    // מדידה, והסיכום מדווח BPM אמיתי במקום floats גולמיים.
    private var hrSampleCountInWindow = 0
    private var hrNoContactCountInWindow = 0
    private var hrMinBpmInWindow = Int.MAX_VALUE
    private var hrMaxBpmInWindow = Int.MIN_VALUE
    private var hrLastSampleMs: Long? = null
    private var hrIntervalSumMs = 0L
    private var hrIntervalCountInWindow = 0
    private var hrSamplesSinceLastLog = 0
    private var hrDiagnosticStarted = false
    private var hrLastSummaryElapsedMs: Long? = null
    private var hrLastRawBits: Int? = null
    private val hrSmoother = HeartRate.Smoother()
    private val hrDiagnosticHandler = Handler(Looper.getMainLooper())
    private val hrDiagnosticSummaryRunnable = object : Runnable {
        override fun run() {
            val hasBpm = hrMinBpmInWindow != Int.MAX_VALUE
            val avgIntervalMs = if (hrIntervalCountInWindow > 0) hrIntervalSumMs / hrIntervalCountInWindow else -1L

            // ⚠️ אורך-החלון האמיתי, ולא ה-60 שביקשנו. ההפרש בין השניים
            // הוא כל הסיפור: ה-Handler נשען על uptimeMillis שקופא בשינה
            // עמוקה, ולכן ריצה שנקבעה ל-60 שניות הגיעה בלוג של 2026-07-29
            // באיחור של עד 1544 שניות. בלי המספר הזה בשורה עצמה חישבנו
            // אותו ידנית מהפרשי חותמות-הזמן, וזה גם מה שהסתיר ש-
            // avg_interval_ms אינו קצב החיישן אלא (אורך-חלון / samples).
            val nowElapsed = SystemClock.elapsedRealtime()
            val windowMs = hrLastSummaryElapsedMs?.let { nowElapsed - it } ?: -1L

            EventLog.log(
                this@TapDetectorService, "INFO",
                "hr_summary;samples=$hrSampleCountInWindow;no_contact=$hrNoContactCountInWindow;" +
                    "min_bpm=${if (hasBpm) hrMinBpmInWindow.toString() else "—"};" +
                    "max_bpm=${if (hasBpm) hrMaxBpmInWindow.toString() else "—"};" +
                    "now_bpm=${hrSmoother.current() ?: -1};" +
                    "bpm_age_ms=${hrSmoother.ageMs()};" +
                    "avg_interval_ms=$avgIntervalMs;" +
                    // stalled = לא הגיעה אף דגימה בחלון. ⚠️ זה **לא** אותו
                    // דבר כמו no_contact: כשהזרם מת, no_contact נשאר 0
                    // בדיוק כמו כששעון תקין יושב על היד, ולכן שני המצבים
                    // נראו זהים בלוג. wornState מחזיק בינתיים את ערכו
                    // האחרון, כלומר "לבוש" — ולכן הוא לא אמין כאן.
                    "stream=${if (hrSampleCountInWindow == 0) "stalled" else "live"};" +
                    "window_ms=$windowMs;" +
                    // steps = צעדים בחלון הזה. still_ms = כמה זמן עבר מאז
                    // הצעד האחרון — זה בדיוק המדד של §5.4, "מרגע שהתנועה
                    // נעצרה מתחיל השעון לספור", ולכן הוא נרשם כבר עכשיו
                    // כדי שיהיו לו נתוני-אמת לפני שנכתב מנוע-הציון.
                    "steps=${if (stepCountAtWindowStart < 0) -1 else stepCountTotal - stepCountAtWindowStart};" +
                    "still_ms=${lastStepElapsedMs?.let { nowElapsed - it } ?: -1};" +
                    // ⚠️ ארבעת הבייטים הגולמיים של דגימה אחת מהחלון.
                    // הפענוח קורא **רק את b2** — 8 מתוך 32 ביט — ו-24
                    // הנותרים מעולם לא נבדקו. שם יכולים לשבת איכות-אות
                    // או מרווח-פעימה, שהוא הדבר היחיד שיכול לתת לנו
                    // שונוּת-דופק אמיתית. שורה אחת בדקה, ומספיקה כדי
                    // לראות אילו בייטים משתנים עם הדופק ואילו קבועים.
                    hrRawBitsFragment() +
                    batteryFragment()
            )
            hrLastSummaryElapsedMs = nowElapsed
            stepCountAtWindowStart = stepCountTotal
            hrSampleCountInWindow = 0
            hrNoContactCountInWindow = 0
            hrMinBpmInWindow = Int.MAX_VALUE
            hrMaxBpmInWindow = Int.MIN_VALUE
            hrIntervalSumMs = 0L
            hrIntervalCountInWindow = 0
            hrDiagnosticHandler.postDelayed(this, 60_000L)
        }
    }

    /**
     * `batt=<אחוז>;charging=<true|false>`, או `batt=-1` אם אין נתון.
     *
     * שתי השדות נקראים מאותו Intent יחיד ולא בשתי קריאות נפרדות, כדי
     * שאחוז-הסוללה ומצב-הטעינה שבאותה שורה יתארו בוודאות את אותו רגע.
     *
     * ⚠️ `charging` אינו קישוט: בזמן טעינה אחוז-הסוללה חסר-משמעות לחישוב
     * עלות, וצריך לפסול את הקטעים האלה בניתוח במקום להסיק מהם שהצריכה
     * אפסית.
     *
     * דרך ה-sticky broadcast ולא דרך `BatteryManager.getIntProperty`: על
     * המכשיר הזה כבר התברר פעמיים ש-API מוצהר מחזיר ערך שקרי (`Sensor.power`
     * ו-`accuracy`, §9 במסמך), ו-EXTRA_LEVEL/EXTRA_SCALE הוא הנתיב הוותיק
     * והנתמך ביותר. אינו דורש הרשאה ואינו רושם מאזין — `null` כמקלט מחזיר
     * את ה-Intent הדביק האחרון מיידית.
     */
    /**
     * `bits=<hex>;b3=..;b2=..;b1=..;b0=..` מדגימה אחת בחלון, או `bits=—`.
     *
     * `b2` הוא הדופק המפוענח. שלושת האחרים נרשמים כדי לגלות מה יש בהם:
     * בייט שמשתנה יחד עם הדופק הוא כנראה נגזרת שלו, בייט שקבוע לגמרי
     * הוא ריפוד, ובייט שמשתנה **בלי** קשר לדופק הוא המעניין — הוא יכול
     * להיות איכות-אות או מרווח בין פעימות.
     */
    private fun hrRawBitsFragment(): String {
        val bits = hrLastRawBits ?: return "bits=—;"
        return "bits=${"%08X".format(bits)};" +
            "b3=${(bits ushr 24) and 0xFF};b2=${(bits ushr 16) and 0xFF};" +
            "b1=${(bits ushr 8) and 0xFF};b0=${bits and 0xFF};"
    }

    private fun batteryFragment(): String {
        val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return "batt=-1;charging=false"
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val percent = if (level < 0 || scale <= 0) -1 else level * 100 / scale
        val charging = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        return "batt=$percent;charging=$charging"
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
                    "max_range=${"%.1f".format(s.maximumRange)};" +
                    // wakeup קובע אם החיישן ממשיך למסור אירועים כשהמעבד
                    // ישן. עד עכשיו לא רשמנו את זה, ולכן חיפשנו את סיבת
                    // עצירת-הזרם בכיוונים אחרים. fifo מראה אם יש חוצץ
                    // חומרתי שיכול לצבור דגימות בשינה ולשפוך אותן בהתעוררות.
                    "wakeup=${s.isWakeUpSensor};" +
                    "fifo_max=${s.fifoMaxEventCount}"
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
            // ⚠️ מעדיפים את וריאנט ה-wakeup במפורש. חיישן רגיל (non-wakeup)
            // מפסיק לפי החוזה של אנדרואיד למסור אירועים כשהמעבד נכנס
            // לשינה עמוקה — וזה בדיוק מה שנמדד בלוג של 2026-07-29: הזרם
            // נפסק ~8 דקות אחרי המגע האחרון ולא חזר במשך שעתיים, בזמן
            // שהשירות עצמו המשיך לחיות (אין tap_service_stopped בלוג).
            // כלומר foreground service לבדו אינו מספיק.
            //
            // אם קיים וריאנט wakeup, הוא אמור להעיר את המעבד לכל דגימה
            // ולפתור את זה בלי wakelock ובלי תלות באף אפליקציה אחרת.
            // אם אינו קיים — נופלים בחזרה לרגיל, בדיוק כמו קודם, כדי
            // שהבדיקה הזו לא תוכל להרע את המצב הקיים.
            heartRateSensor = sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE, true)
                ?: sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE)
            if (heartRateSensor != null) {
                wornSensorAvailable = true
                EventLog.log(
                    this, "INFO",
                    "worn_gating_using_heart_rate_sensor;wakeup=${heartRateSensor?.isWakeUpSensor}"
                )
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

            // מונה-הצעדים, שוב עם העדפה לוריאנט ה-wakeup מאותה סיבה כמו
            // בדופק. ⚠️ הוא **אינו** דורש הרשאה כאן רק מפני ש-targetSdk
            // הוא 28; מ-29 אנדרואיד דורש ACTIVITY_RECOGNITION, ולכן העלאת
            // targetSdk בעתיד תשתיק את הערוץ הזה בשקט אם לא תתווסף הרשאה.
            stepSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER, true)
                ?: sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
            stepSensor?.let {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
                EventLog.log(this, "INFO", "step_counter_registered;wakeup=${it.isWakeUpSensor}")
            } ?: EventLog.log(this, "INFO", "step_counter_unavailable")
            if (DebugConfig.DEBUG_TAG_ENABLED && heartRateSensor != null && !hrDiagnosticStarted) {
                hrDiagnosticStarted = true
                // נקודת-הייחוס נקבעת כאן ולא בסיכום הראשון, אחרת השורה
                // הראשונה הייתה מדווחת window_ms=-1 — וזו דווקא שורה
                // מעניינת, כי היא אומרת אם הזרם מתחיל מיד עם השירות.
                hrLastSummaryElapsedMs = SystemClock.elapsedRealtime()
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
            Sensor.TYPE_STEP_COUNTER -> {
                // הערך מצטבר מאז האתחול, ולכן מה שמעניין הוא ההפרש.
                // נשמר כ-Long למרות שהחיישן מוסר float: מעל ~16.7 מיליון
                // ל-float אין דיוק שלם, וזה גם מה שקורה כשמכשיר לא מאותחל
                // חודשים. ההמרה נעשית פעם אחת כאן.
                stepCountTotal = (event.values.getOrNull(0) ?: 0f).toLong()
                if (stepCountAtWindowStart < 0) stepCountAtWindowStart = stepCountTotal
                lastStepElapsedMs = SystemClock.elapsedRealtime()
            }
            Sensor.TYPE_HEART_RATE -> {
                val raw = event.values.getOrNull(0) ?: 0f
                val bpm = HeartRate.decodeBpm(raw)
                hrLastRawBits = java.lang.Float.floatToRawIntBits(raw)
                // ערך גולמי 0 = אין מגע עם העור, ולכן זה מדד-לבישה אמיתי.
                // קודם ההשוואה הייתה hr > 0f, שהתקיימה תמיד כי הערכים
                // ה"שבורים" היו עצומים — כלומר לא נמדד כאן כלום בפועל.
                wornState = bpm != null
                bpm?.let { hrSmoother.add(it) }

                if (DebugConfig.DEBUG_TAG_ENABLED) {
                    val now = System.currentTimeMillis()
                    hrSampleCountInWindow++
                    if (bpm == null) {
                        hrNoContactCountInWindow++
                    } else {
                        if (bpm < hrMinBpmInWindow) hrMinBpmInWindow = bpm
                        if (bpm > hrMaxBpmInWindow) hrMaxBpmInWindow = bpm
                    }
                    hrLastSampleMs?.let { last ->
                        hrIntervalSumMs += (now - last)
                        hrIntervalCountInWindow++
                    }
                    hrLastSampleMs = now

                    // ⚠️ לא רושמים כל מדגם. החיישן מדווח ~3 פעמים בשנייה,
                    // כלומר כ-10,000 שורות בשעה — זה מה שהפך את הלוג
                    // לבלתי-ניתן להעתקה על המכשיר. סיכום הדקה נותן את
                    // התמונה, ודגימה אחת ל-30 היא רק לביקורת.
                    //
                    // הערך הגולמי נשאר בלוג לצד המפוענח בכוונה: הפענוח הוא
                    // הנדסה-לאחור של דרייבר, ואם היצרן ישנה את המבנה נרצה
                    // את הביטים המקוריים כדי לפענח מחדש — בלי עוד סבב שדה.
                    hrSamplesSinceLastLog++
                    if (hrSamplesSinceLastLog >= 30) {
                        hrSamplesSinceLastLog = 0
                        EventLog.log(
                            this, "DEBUG",
                            "hr_sample;bpm=${bpm ?: -1};smoothed=${hrSmoother.current() ?: -1};" +
                                "raw=${"%.1f".format(raw)};accuracy=${event.accuracy}"
                        )
                    }
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
