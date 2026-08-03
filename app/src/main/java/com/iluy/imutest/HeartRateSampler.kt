package com.iluy.imutest

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock

/**
 * דגימת דופק בפרצים במקום רישום רצוף.
 *
 * ## מה זה מתקן, ולמה זה נמדד ולא משוער
 *
 * בלוג של 2026-07-30 מד הדופק **נפסק ב-05:51 ולא חזר עד 14:06** — 8 שעות
 * ורבע של `samples=0`. השירות עצמו המשיך לחיות כל הזמן הזה (שורות הסיכום
 * המשיכו להיכתב), כלומר לא הוא שמת אלא הזרם.
 *
 * ⚠️ **וההוכחה שזה לא רק בעיית שינה:** בשורות האחרונות `steps=153` —
 * נבו היה ער והלך, והחיישן נשאר מת. שום דבר חיצוני לא מחיה אותו.
 *
 * מה שכן עובד: **רישום טרי.** ב-03:50, מיד עם עליית השירות, הזרם התחיל
 * מיד. וגם אפליקציית היצרן חזרה ב-13:40 — כי היא יוזמת מדידה מחדש.
 * אנחנו רשמנו מאזין פעם אחת וחיכינו לנצח.
 *
 * לכן: כל פרץ הוא **רישום חדש**. מוות נמשך לכל היותר עד הפרץ הבא.
 *
 * ## ומה זה מתקן בסוללה
 *
 * מדידה מאותו לוג: מד דופק פעיל **5.2% לשעה** (19 שעות לטעינה), כבוי
 * **1.9% לשעה** (53 שעות). כלומר רצף לא שורד יממה גם אם היה עובד.
 * בפרצים הצריכה יורדת לכ-2.2-3.1% לשעה, תלוי בפעילות.
 *
 * ## למה AlarmManager
 *
 * `Handler.postDelayed` נשען על `uptimeMillis` שקופא בשינה עמוקה. באותו
 * לוג ריצה שנקבעה ל-60 שניות הגיעה באיחור של עד 19.7 דקות.
 * `setExactAndAllowWhileIdle` הוא היחיד שחוצה שינה עמוקה — ⚠️ ואנדרואיד
 * מגביל אותו לפעם ב-9 דקות בשינה, ולכן [INTERVAL_IDLE_MS] הוא 10 דקות
 * ולא פחות. זו רצפה של המערכת, לא בחירה.
 */
object HeartRateSampler {

    /** כמה זמן החיישן דלוק בכל פרץ. */
    const val BURST_MS = 45_000L

    /**
     * תקרה מוחלטת לאורך הפרץ, כולל זמן החימום של החיישן.
     *
     * ⚠️ **נולד ממדידה, לא מהערכה.** בייצוא של 2026-08-03 הדגימה הראשונה
     * הגיעה אחרי **23.7 שניות** ב-47 מתוך 49 הפרצים — כלומר יותר ממחצית
     * מכל פרץ הלכה על המתנה לחיישן, ונשארו 21 שניות של נתונים בלבד.
     * הקצב עצמו היה תקין לחלוטין (3.15 דגימות בשנייה), ולכן זו לא תקלה
     * בחיישן אלא **בחלון שקצבנו לו**.
     *
     * [BURST_MS] נמדד מכאן ואילך מהדגימה הראשונה ולא מרגע ההדלקה, והמספר
     * הזה חוסם את המקרה שבו החיישן לא נתפס בכלל — 23.7 + 45 = 68.7, ולכן
     * 75 מכסה בנוחות.
     */
    const val MAX_BURST_MS = 75_000L

    /**
     * כשיש תנועה — כל 2 דקות. אין הגבלת-שינה כשהמכשיר ער, ואלה הרגעים
     * שבהם הזיהוי בכלל רלוונטי.
     */
    const val INTERVAL_ACTIVE_MS = 120_000L

    /**
     * כשדומם — כל 10 דקות. ⚠️ לא פחות: אנדרואיד מגביל
     * `setExactAndAllowWhileIdle` לפעם ב-9 דקות בשינה עמוקה, ובקשה צפופה
     * יותר פשוט תידחה ותיראה כמו באג.
     */
    const val INTERVAL_IDLE_MS = 600_000L

    /** מתחת לזה נחשב "יש תנועה" לצורך בחירת המרווח. */
    const val ACTIVE_IF_STEP_WITHIN_MS = 300_000L

    const val ACTION_BURST = "com.iluy.imutest.HR_BURST"
    private const val REQUEST_CODE = 4301

    /**
     * מתזמן את הפרץ הבא. `stillMs` הוא הזמן מאז הצעד האחרון, או null אם
     * מעולם לא נראה צעד — במקרה כזה מניחים דומם, כלומר המרווח החסכוני.
     */
    fun scheduleNext(context: Context, stillMs: Long?) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val active = stillMs != null && stillMs < ACTIVE_IF_STEP_WITHIN_MS
        val interval = if (active) INTERVAL_ACTIVE_MS else INTERVAL_IDLE_MS
        val at = System.currentTimeMillis() + interval

        val pi = PendingIntent.getBroadcast(
            context, REQUEST_CODE,
            Intent(context, Receiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } else {
            am.setExact(AlarmManager.RTC_WAKEUP, at, pi)
        }
        EventLog.log(
            context, "INFO",
            "hr_burst_scheduled;in_ms=$interval;mode=${if (active) "active" else "idle"}"
        )
    }

    fun cancel(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        am.cancel(
            PendingIntent.getBroadcast(
                context, REQUEST_CODE,
                Intent(context, Receiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT
            )
        )
    }

    /**
     * מעביר את הפרץ לשירות, שם יושב המאזין.
     *
     * `startService` מותר כאן למרות מגבלות-הרקע של אנדרואיד 8, כי השירות
     * כבר רץ כשירות-חזית — זו אינה הפעלה מרקע אלא פנייה לשירות קיים.
     */
    class Receiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            EventLog.log(context, "INFO", "hr_burst_alarm;elapsed=${SystemClock.elapsedRealtime()}")
            runCatching {
                context.startService(
                    Intent(context, TapDetectorService::class.java).setAction(ACTION_BURST)
                )
            }.onFailure {
                EventLog.log(context, "INFO", "hr_burst_start_failed;${it.javaClass.simpleName}")
                // בלי תזמון מחדש כאן, כישלון בודד היה עוצר את השרשרת לנצח.
                scheduleNext(context, null)
            }
        }
    }
}
