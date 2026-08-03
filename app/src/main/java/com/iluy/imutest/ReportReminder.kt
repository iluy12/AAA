package com.iluy.imutest

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import java.util.Calendar

/**
 * תזכורת שעתית לדווח.
 *
 * ## שני תפקידים, ושניהם אמיתיים
 *
 * 1. **ניסוי:** נבו מסמן ✕ בכל תזכורת, ואז אפשר להצליב — האם כל סימון
 *    נרשם, והאם נרשם משהו שהוא **לא** סימן. זיהוי-שווא של המחווה הוא
 *    תווית שגויה שתלמד את האלגוריתם דבר לא נכון, ולכן חייבים לדעת.
 * 2. **פיצ'ר:** זו התזכורת שנבו ביקש לרשימת בנקי-החיזוקים — "אם הוא לא
 *    מדווח הרבה זמן, שווה לשלוח תזכורת". כאן היא קבועה בשעה; בהמשך היא
 *    תופעל לפי שתיקה בפועל.
 *
 * ## למה AlarmManager ולא Handler
 *
 * `Handler.postDelayed` נשען על `uptimeMillis`, שקופא בשינה עמוקה. בלוג
 * של 2026-07-30 ריצה שנקבעה ל-60 שניות הגיעה באיחור של עד 19.7 דקות.
 * תזכורת שעתית שנשענת על זה הייתה מחמיצה שעות שלמות.
 * `setExactAndAllowWhileIdle` הוא היחיד שחוצה שינה עמוקה.
 *
 * ⚠️ **הוא חד-פעמי** — חייבים לתזמן מחדש בכל ירייה, אחרת יש תזכורת אחת
 * וזהו. וגם באתחול, כי אזעקות לא שורדות כיבוי.
 */
object ReportReminder {

    /**
     * ⚠️ **כבוי. נבו ביקש לבטל אותה ב-3.8: "הכל טוב עם ה-✕".**
     *
     * היא נבנתה כניסוי — לוודא שכל סימון ✕ נרשם ושלא נרשם סימון שלא
     * נעשה. הניסוי הסתיים, המחווה עובדת, ותזכורת שעתית שאין לה עוד
     * תפקיד היא בדיוק הרעש שהמוצר הזה נבנה כדי לא לייצר: **הסיכוי
     * להגיב להתראה יורד בכ-30% על כל תזכורת חוזרת.**
     *
     * ⚠️ הקוד נשאר ולא נמחק — התזכורת אמורה לחזור בגרסה אחרת, **לפי
     * שתיקה בפועל ולא לפי שעון**. זה בנק 7 בקובץ הטקסטים ("שתק הרבה
     * זמן"), והוא עדיין מתוכנן.
     */
    private const val ENABLED = false

    /** מחוץ לטווח הזה לא מזכירים — אין טעם להעיר אותו ב-04:00. */
    private const val FIRST_HOUR = 10
    private const val LAST_HOUR = 23

    private const val CHANNEL_ID = "iluy_report_reminder"
    private const val NOTIFICATION_ID = 4201
    private const val REQUEST_CODE = 4201

    /**
     * מתזמן את התזכורת הבאה. בטוח לקרוא לזה כמה פעמים — `PendingIntent`
     * עם אותו request code מחליף את הקודם ולא מוסיף אזעקה שנייה.
     */
    fun schedule(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        if (!ENABLED) {
            // ⚠️ **מבטלים אזעקה שכבר נקבעה, ולא רק נמנעים מלקבוע חדשה.**
            // מכשיר שרץ עם הגרסה הקודמת נושא אזעקה חיה; בלי הביטול היא
            // הייתה ממשיכה לירות אחרי העדכון, וזה נראה בדיוק כמו שהכיבוי
            // לא עבד.
            am.cancel(
                PendingIntent.getBroadcast(
                    context, REQUEST_CODE,
                    Intent(context, Receiver::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
            EventLog.log(context, "INFO", "report_reminder_disabled")
            return
        }
        val next = nextFireTimeMs()
        val pi = PendingIntent.getBroadcast(
            context, REQUEST_CODE,
            Intent(context, Receiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi)
        } else {
            am.setExact(AlarmManager.RTC_WAKEUP, next, pi)
        }
        EventLog.log(context, "INFO", "report_reminder_scheduled;at=${clock(next)}")
    }

    /**
     * השעה העגולה הבאה שנמצאת בטווח. אם השעה הבאה מחוץ לטווח — מדלגים
     * ל-[FIRST_HOUR] של המחר.
     */
    private fun nextFireTimeMs(): Long {
        val c = Calendar.getInstance().apply {
            add(Calendar.HOUR_OF_DAY, 1)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val hour = c.get(Calendar.HOUR_OF_DAY)
        if (hour < FIRST_HOUR || hour > LAST_HOUR) {
            if (hour > LAST_HOUR) c.add(Calendar.DAY_OF_YEAR, 1)
            c.set(Calendar.HOUR_OF_DAY, FIRST_HOUR)
        }
        return c.timeInMillis
    }

    private fun clock(ms: Long): String {
        val c = Calendar.getInstance().apply { timeInMillis = ms }
        return "%02d:%02d".format(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
    }

    class Receiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            // ⚠️ הרישום קודם לכל השאר. בלעדיו אין למה להשוות את הסימונים,
            // וזו כל מטרת הניסוי.
            EventLog.log(context, "INFO", "report_reminder_fired;elapsed=${SystemClock.elapsedRealtime()}")
            // ⚠️ בדיקה גם כאן: אזעקה שכבר הייתה בתור על גרסה קודמת יכולה
            // עוד לירות פעם אחת אחרי העדכון, וההודעה היא מה שנבו רואה.
            if (!ENABLED) {
                EventLog.log(context, "INFO", "report_reminder_suppressed")
                return
            }
            notify(context)
            // מיד מתזמנים את הבאה — setExactAndAllowWhileIdle הוא חד-פעמי.
            schedule(context)
        }

        /**
         * בעדינות במפורש: חשיבות נמוכה, בלי רטט משלנו, ונעלמת בנגיעה.
         * זו ההחלטה שאושרה לגבי הדלקה כשהמסך כבוי — "לא מפריע לי בלילה".
         */
        private fun notify(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID, "תזכורת לדווח", NotificationManager.IMPORTANCE_DEFAULT
                    ).apply { enableVibration(false) }
                )
            }
            val open = PendingIntent.getActivity(
                context, 0,
                Intent(context, WatchFaceActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT
            )
            nm.notify(
                NOTIFICATION_ID,
                NotificationCompat.Builder(context, CHANNEL_ID)
                    .setContentTitle("איתך")
                    .setContentText("אם היה משהו — סמן ✕")
                    .setSmallIcon(android.R.drawable.ic_menu_compass)
                    .setAutoCancel(true)
                    .setContentIntent(open)
                    .build()
            )
            AlertHelper.playAlertSound(context)
        }
    }
}
