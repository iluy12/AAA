package com.iluy.imutest

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

/**
 * דיווח נפילה, והתגובה שאחריו.
 *
 * ## ⚠️ העיקרון: הזמן הוא חלק מהמסר
 *
 * זו התובנה של נבו, והיא משנה את העיצוב. **מיד אחרי נפילה זה לא הזמן לדבר
 * איתו** — זה רגע של חרטה ובושה עמוקה. מערכת שמגיבה מיד נקראת כתחקור.
 *
 * לכן: משפט קצר, נסגר, ואז **שתיקה מוחלטת**. וכעבור 5-15 דקות — הודעה
 * מעודדת שמניעה לפעולה.
 *
 * > **שתיקה של עשר דקות אומרת "לא באתי לתחקר אותך" חזק יותר מכל ניסוח.
 * > וזה גם מה שהופך את ההודעה שכן מגיעה למשהו שמקשיבים לו — היא לא נופלת
 * > על מי שעדיין בתוך הבושה.**
 *
 * ומכאן נובע שהטקסטים של הרגע הראשון ושל אחרי עשר דקות הם **שני בנקים
 * שונים לגמרי**: הראשון קצר וסוגר, השני פותח.
 *
 * ## נפילה שנייה באותו יום
 *
 * ⚠️ **יותר מנפילה אחת ביום היא תמיד סימן להתדרדרות** (נבו). לכן התגובה
 * השנייה **מהירה יותר וגם ישירה יותר** — לא רומזת על מלווה אלא מציעה.
 */
object FallReport {

    private const val PREFS_NAME = "iluy_falls"
    private const val CHANNEL_ID = "iluy_after_fall"
    private const val NOTIFICATION_ID = 4301
    private const val REQUEST_CODE = 4302

    /** חלון ההשהיה לנפילה ראשונה. אקראי בתוכו, כדי שלא יורגש מכני. */
    private const val FIRST_MIN_MS = 5 * 60 * 1000L
    private const val FIRST_MAX_MS = 15 * 60 * 1000L

    /** נפילה שנייה ואילך — מהר יותר. */
    private const val REPEAT_MIN_MS = 2 * 60 * 1000L
    private const val REPEAT_MAX_MS = 3 * 60 * 1000L

    /**
     * כמה אחורה נסרק אחרי דיווח. חצי שעה ברגיל, שעה ביום חריג — ⚠️ **ולא
     * שואלים אותו מתי זה קרה.** זו הוספת חיכוך ברגע הכי קשה; העקומה עצמה
     * תאתר את האירוע על פני הרבה דיווחים.
     */
    private const val LOOKBACK_MS = 30 * 60 * 1000L
    private const val LOOKBACK_ROUGH_MS = 60 * 60 * 1000L

    private val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun todayKey() = dayFmt.format(Date())

    /** כמה נפילות דווחו היום. */
    fun todayCount(context: Context): Int = prefs(context).getInt("count_${todayKey()}", 0)

    /**
     * רושם דיווח נפילה, מסמן את חלון הלמידה, ומתזמן את ההודעה המעודדת.
     *
     * `category` יכול להיות null — לחיצה בלי גרירה היא דיווח תקף בפני
     * עצמו. **הנתון היקר הוא הזמן, לא הקטגוריה.**
     */
    fun record(context: Context, category: String?) {
        val count = todayCount(context) + 1
        val now = System.currentTimeMillis()

        // ⚠️ דיווח בלי סיווג נכנס כ**חמור ביותר**, אבל מסומן `assumed`.
        // בלי הסימון הזה כל דיווח עצל היה נראה בניתוח כנפילה בברית, כי
        // המסלול חסר-החיכוך הוא בדיוק זה שברירת המחדל שלו החמורה.
        val assumed = category == null
        val severity = FallSeverity.fromLabel(category)

        prefs(context).edit()
            .putInt("count_${todayKey()}", count)
            .putLong("last_ms", now)
            .putString("last_severity", severity.name)
            .apply()

        // הלוח הקיים שומר קטגוריה אחת ליום. נשמר לתאימות עם מסך המידע,
        // אבל הספירה האמיתית יושבת כאן — ראו הערת-המחלקה על נפילה שנייה.
        CalendarStore.recordFall(context, severity.label)

        // ⚠️ "עיניים" פותחת חלון ערנות של יומיים — היא מתארת עתיד ולא עבר.
        FallAftermath.record(context, severity)

        // ⚠️ **שני תנאים, לא אחד.** הקטגוריה אומרת אם הדיווח *אמור* להיות
        // סמוך לאירוע; הנתונים אומרים אם השעון בכלל **היה שם**.
        //
        // ב-2026-08-01 אין אף רשומה — שבת, והשעון היה כבוי. דיווח שנמסר
        // במוצאי שבת על משהו שקרה בבוקר נראה למערכת בדיוק כמו דיווח בזמן
        // אמת: היא הייתה מתייגת את חצי השעה שקדמה ללחיצה, ולומדת את החדר
        // שבו הוא עמד באותו רגע. **תווית שגויה גרועה מהיעדר תווית**, כי
        // אי-אפשר לזהות אותה אחר-כך.
        //
        // אפס רשומות בחלון פירושו שהשעון לא אסף — שבת, סוללה, כיבוי.
        // התנאי הזה מטפל בשלושתם בלי לשאול את המשתמש כלום, וזה חשוב:
        // הוכרע שלא מוסיפים חיכוך ברגע הזה.
        val lookback = if (count > 1) LOOKBACK_ROUGH_MS else LOOKBACK_MS
        val window = SampleStore.since(context, now - lookback)
        val watchWasThere = window.isNotEmpty()

        if (severity.reportIsNearEvent && watchWasThere) {
            RoomPrint.capture(context, LastMagnitude.value)?.let {
                RoomPrint.rememberFallLocation(context, it)
            }
        }

        // ⚠️ בלי זה שער הקירור מת: `inCooldownAfterReport` היה מחזיר false
        // תמיד, והמערכת יכלה לקפוץ עליו שנייה אחרי שדיווח על נפילה.
        OfferBudget.recordUserReport(context, "fall_${severity.label}")

        // ⚠️ קרי לילה מדווח בבוקר על מה שקרה בשינה — חצי השעה שקדמה
        // לדיווח היא ארוחת בוקר, לא האירוע. ניתוח החלון כאן היה מייצר
        // תווית שגויה, וזה גרוע מהיעדר תווית.
        when {
            !severity.reportIsNearEvent ->
                EventLog.log(context, "FALL", "window_skipped;reason=report_far_from_event")
            !watchWasThere ->
                EventLog.log(context, "FALL", "window_skipped;reason=no_records_in_window")
            else -> markLearningWindow(context, now, lookback, window)
        }
        scheduleEncouragement(context, count)

        EventLog.log(
            context, "FALL",
            "reported;severity=${severity.label};assumed=$assumed;today_count=$count"
        )
    }

    /**
     * מסמן את החלון שקדם לדיווח — זה מה שהופך דיווח לתווית ללמידה.
     *
     * ⚠️ **החלון שלפני הוא מה שמעניין, לא האירוע עצמו.** הוא תקופת השקט
     * שבה ההתערבות הייתה מועילה. הנפילה עצמה כוללת תנועה ולכן מזוהמת כאות.
     */
    private fun markLearningWindow(
        context: Context,
        nowMs: Long,
        lookback: Long,
        records: List<SampleStore.Record>
    ) {
        EventLog.log(
            context, "FALL",
            "window;lookback_min=${lookback / 60000};records=${records.size}"
        )

        // ⚠️ **הסדרה עצמה, לא סיכום שלה.** הגרסה הראשונה רשמה ארבעה
        // מספרים — כמה רשומות וטווח דופק — ובזה איבדה בדיוק את מה שהחלון
        // הזה קיים בשבילו. כאן יושב כל החידוש של המוצר, ולכן נפרשת כל
        // נקודה: כמה דקות לפני הדיווח, הדופק, המגמה בתוך הפרץ, התנוחה,
        // וחוסר-התנועה.
        for (r in records) {
            val minutesBefore = (nowMs - r.timestampMs) / 60000
            EventLog.log(
                context, "FALL",
                "  pt;t_minus=$minutesBefore;bpm=${r.bpm};min=${r.bpmMin};max=${r.bpmMax};" +
                    "trend=${r.bpmTrend};still_s=${r.stillMs / 1000};steps=${r.steps};" +
                    "grav=${r.gravityX},${r.gravityY},${r.gravityZ};motion=${r.motion}"
            )
        }
    }

    /**
     * ⚠️ AlarmManager ולא Handler. `postDelayed` נשען על `uptimeMillis`
     * שקופא בשינה עמוקה, ובלוגים נמדד איחור של עד 19.7 דקות על ריצה
     * שנקבעה ל-60 שניות. הודעה שאמורה להגיע בעוד 10 דקות **חייבת** להגיע.
     */
    private fun scheduleEncouragement(context: Context, count: Int) {
        // ⚠️ **קודם מבטלים, ורק אז מתזמנים.** הכלל של נבו: *"לא לשים 2 סט
        // התראות לאותו משתמש מ-2 דיווחים, תמיד לקחת את ההתראות של המנגנון
        // היותר אגרסיבי ולבטל את הפחות."*
        //
        // בנפילות של 20:50 ו-20:58 ההפרש היה שמונה דקות — כלומר העידוד של
        // הראשונה עוד לא ירה כשהשנייה דווחה. בלי הביטול הוא היה מגיע
        // אחריה, בנוסח של נפילה ראשונה, ומייצר שתי שיחות במקביל.
        cancelPending(context, "superseded_by_fall_$count")
        val delay = if (count > 1) {
            Random.nextLong(REPEAT_MIN_MS, REPEAT_MAX_MS)
        } else {
            Random.nextLong(FIRST_MIN_MS, FIRST_MAX_MS)
        }
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val intent = Intent(context, Receiver::class.java).putExtra(EXTRA_COUNT, count)
        val pi = PendingIntent.getBroadcast(
            context, REQUEST_CODE, intent, PendingIntent.FLAG_UPDATE_CURRENT
        )
        val at = System.currentTimeMillis() + delay
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } else {
            am.setExact(AlarmManager.RTC_WAKEUP, at, pi)
        }
        EventLog.log(context, "FALL", "encouragement_scheduled;in_min=${delay / 60000};count=$count")
    }

    /**
     * מבטל עידוד שממתין וגם כזה שכבר הוצג.
     *
     * ⚠️ **שני הביטולים נחוצים, וקל לעשות רק את אחד מהם.** ה-alarm הוא מה
     * שעוד לא ירה; ההודעה 4301 היא מה שכבר יושב בשורת ההתראות ונשאר שם עד
     * שנוגעים בו. ביטול רק של הראשון היה משאיר על המסך הודעה של נפילה
     * ראשונה בזמן שהמערכת כבר בטיפול בנפילה שנייה.
     *
     * נקרא גם מ-[RiskFlowActivity] — מסך שנפתח עכשיו חזק מהודעה שתגיע
     * בעוד עשר דקות, ולכן הוא בולע אותה.
     */
    fun cancelPending(context: Context, reason: String) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        val pi = PendingIntent.getBroadcast(
            context, REQUEST_CODE,
            Intent(context, Receiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT
        )
        am?.cancel(pi)
        context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
        EventLog.log(context, "FALL", "pending_encouragement_cancelled;reason=$reason")
    }

    const val EXTRA_COUNT = "fall_count"

    class Receiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            val count = intent?.getIntExtra(EXTRA_COUNT, 1) ?: 1
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID, "אחרי דיווח", NotificationManager.IMPORTANCE_DEFAULT
                    ).apply { enableVibration(false) }
                )
            }

            val severity = FallSeverity.valueOf(
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getString("last_severity", FallSeverity.DEFAULT.name)
                    ?: FallSeverity.DEFAULT.name
            )
            // ⚠️ **הרמה נקראת עכשיו, לא בזמן התזמון.** בין הרגע שהעידוד
            // נקבע לרגע שהוא יורה עוברות עד 15 דקות, ובהן יכול היה להתווסף
            // עוד דיווח. קריאה כאן היא מה שמבטיח שמה שנאמר בפועל תואם
            // למצב האמיתי ולא למצב שהיה.
            val level = Escalation.levelNow(context)
            EventLog.log(
                context, "FALL",
                "encouragement_shown;count=$count;level=$level;total=${Escalation.total(context)}"
            )

            // ⚠️ הדוגמה של נבו: *"אם סימן מצב רוח ואח״כ דיווח נפלתי
            // בעיניים — זה אוי ואבוי, ישר RISK B."* זו נפילה **ראשונה**
            // ביום, ולכן `count > 1` לבדו היה נותן לה את הנוסח הרך. הרמה
            // המצטברת היא מה שקובע את חומרת הניסוח.
            val line = Encouragements.afterFall(
                severity,
                secondToday = count > 1 || level >= Escalation.Level.RISK_B
            )
            val text = line.text

            // ⚠️ **הודעה שהיא שאלה חייבת דרך לענות.** הכלל שקבע נבו: כן/לא,
            // "כן" מרימה שיחה למלווה ו"לא" עונה "אם צריך אני כאן". שאלה בלי
            // כפתורים היא רק עוד טקסט שנעלם, וזו בדיוק ההצעה שלא נענית.
            val target = if (line.isQuestion) {
                AskActivity.intentFor(context, line.text, "אחרי נפילה")
            } else if (level >= Escalation.Level.RISK_A) {
                // המפתח חייב להיות EXTRA_SOURCE ולא "source" — המסך קורא
                // ממנו, ומחרוזת אחרת הייתה נותנת "לא ידוע" בלוג.
                Intent(context, HelpMenuActivity::class.java)
                    .putExtra(HelpMenuActivity.EXTRA_SOURCE, "אחרי דיווח (רמה $level)")
            } else {
                Intent(context, WatchFaceActivity::class.java)
            }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

            nm.notify(
                NOTIFICATION_ID,
                NotificationCompat.Builder(context, CHANNEL_ID)
                    .setContentTitle("איתך")
                    .setContentText(text)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                    .setSmallIcon(android.R.drawable.ic_menu_compass)
                    .setAutoCancel(true)
                    .setContentIntent(
                        PendingIntent.getActivity(
                            context, 0, target, PendingIntent.FLAG_UPDATE_CURRENT
                        )
                    )
                    .build()
            )
            // נדלק בעדינות — אושר: "לא מפריע לי בלילה".
            AlertHelper.playAlertSound(context)
        }
    }
}
