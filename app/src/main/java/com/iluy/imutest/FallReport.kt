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
        prefs(context).edit()
            .putInt("count_${todayKey()}", count)
            .putLong("last_ms", now)
            .apply()

        // הלוח הקיים שומר קטגוריה אחת ליום. נשמר לתאימות עם מסך המידע,
        // אבל הספירה האמיתית יושבת כאן — ראו הערת-המחלקה על נפילה שנייה.
        CalendarStore.recordFall(context, category ?: "לא צוין")

        markLearningWindow(context, now, count)
        scheduleEncouragement(context, count)

        EventLog.log(
            context, "FALL",
            "reported;category=${category ?: "none"};today_count=$count"
        )
    }

    /**
     * מסמן את החלון שקדם לדיווח — זה מה שהופך דיווח לתווית ללמידה.
     *
     * ⚠️ **החלון שלפני הוא מה שמעניין, לא האירוע עצמו.** הוא תקופת השקט
     * שבה ההתערבות הייתה מועילה. הנפילה עצמה כוללת תנועה ולכן מזוהמת כאות.
     */
    private fun markLearningWindow(context: Context, nowMs: Long, count: Int) {
        val lookback = if (count > 1) LOOKBACK_ROUGH_MS else LOOKBACK_MS
        val from = nowMs - lookback
        val records = SampleStore.since(context, from)
        val withPulse = records.count { it.bpm > 0 }
        EventLog.log(
            context, "FALL",
            "window;lookback_min=${lookback / 60000};records=${records.size};" +
                "with_pulse=$withPulse;" +
                "bpm_range=${records.filter { it.bpm > 0 }.let { r ->
                    if (r.isEmpty()) "—" else "${r.minOf { it.bpm }}-${r.maxOf { it.bpm }}"
                }}"
        )
    }

    /**
     * ⚠️ AlarmManager ולא Handler. `postDelayed` נשען על `uptimeMillis`
     * שקופא בשינה עמוקה, ובלוגים נמדד איחור של עד 19.7 דקות על ריצה
     * שנקבעה ל-60 שניות. הודעה שאמורה להגיע בעוד 10 דקות **חייבת** להגיע.
     */
    private fun scheduleEncouragement(context: Context, count: Int) {
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

    const val EXTRA_COUNT = "fall_count"

    class Receiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            val count = intent?.getIntExtra(EXTRA_COUNT, 1) ?: 1
            EventLog.log(context, "FALL", "encouragement_shown;count=$count")

            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID, "אחרי דיווח", NotificationManager.IMPORTANCE_DEFAULT
                    ).apply { enableVibration(false) }
                )
            }

            // ⚠️ טקסטים זמניים. **המילים חייבות להיות של נבו** — זה הרגע
            // הרגיש ביותר במוצר, וניסוח שגוי כאן שורף את הפיצ'ר.
            val text = if (count > 1) {
                Encouragements.afterSecondFall()
            } else {
                Encouragements.afterFirstFall()
            }

            // נפילה שנייה — ישירות למסך העזרה, לא רק הודעה. זו המשמעות של
            // "מהר יותר וגם ישיר יותר".
            val target = if (count > 1) {
                // המפתח חייב להיות EXTRA_SOURCE ולא "source" — המסך קורא
                // ממנו, ומחרוזת אחרת הייתה נותנת "לא ידוע" בלוג.
                Intent(context, HelpMenuActivity::class.java)
                    .putExtra(HelpMenuActivity.EXTRA_SOURCE, "אחרי נפילה שנייה")
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
