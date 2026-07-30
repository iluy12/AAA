package com.iluy.imutest

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager

/**
 * זיהוי שיחה יוצאת, ומשך השיחה.
 *
 * ## למה זה אות חשוב
 *
 * המסלול הוא **לחיצה ארוכה על כפתור הצד → השעון מחייג למלווה.** זה עוקף
 * את האפליקציה לגמרי, ולכן בלי זיהוי כזה האירוע פשוט לא קיים אצלנו —
 * למרות שהוא אחד האותות החזקים שיש: המשתמש פנה לעזרה בעצמו.
 *
 * ⚠️ **והחיוג הוא האות האמין, לא הלחיצה.** לחיצה ארוכה מגיעה לאפליקציה
 * רק אם מסך שלנו בחזית, ובמסך כבוי היא לא תגיע. השיחה עצמה נתפסת תמיד.
 *
 * ## למה המשך קריטי
 *
 * נבו ניסח את זה מדויק: השיחה יכולה להיות אמיתית, או **לחיצה בטעות**, או
 * **חרטה מיד אחרי צלצול או שניים.** שלושתן נראות זהות בלי משך:
 *
 * | משך | פירוש סביר |
 * |---|---|
 * | שניות בודדות | טעות, או חרטה — ⚠️ **ועדיין רגע שבו הרגיש צורך** |
 * | דקות | פנייה אמיתית |
 *
 * גם הקצרה אינה "שווא" מבחינת הגלאי — היא מסמנת רגע-סיכון. ההבדל הוא
 * במשקל, לא בשאלה אם לרשום.
 *
 * ## ⚠️ פרטיות
 *
 * הלוג עולה לשירות **ציבורי**, ומספר של מלווה אסור שיתפרסם. לכן נרשמות
 * שלוש הספרות האחרונות בלבד — מספיק כדי לדעת שזה אותו מספר בין אירועים,
 * ולא מספיק כדי להתקשר אליו.
 */
object SosCallWatcher {

    /** שלוש ספרות אחרונות בלבד. ראו הערת-הפרטיות למעלה. */
    private fun mask(number: String?): String {
        if (number.isNullOrBlank()) return "—"
        val digits = number.filter { it.isDigit() }
        return if (digits.length <= 3) "***" else "***" + digits.takeLast(3)
    }

    /**
     * ⚠️ נרשם ברמת התהליך ולא ברמת ה-Activity: שיחה יוצאת מתרחשת כשהשעון
     * מחייג בעצמו, כלומר כשאף מסך שלנו אינו בחזית.
     */
    class OutgoingReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            if (intent?.action != Intent.ACTION_NEW_OUTGOING_CALL) return
            val number = intent.getStringExtra(Intent.EXTRA_PHONE_NUMBER)

            // ⚠️ **התבנית, לא השיחה.** המשתמש עושה שיחות רגילות, ולכן
            // "שיחה יוצאת" לבדה אינה אות. מה שהופך אותה לאות הוא שקדמה לה
            // לחיצה ארוכה — כך שחיוג רגיל וחיוג-SOS נראים שונה בלוג.
            val gap = KeyLog.lastLongPressElapsedMs?.let {
                SystemClock.elapsedRealtime() - it
            }
            val afterLongPress = gap != null && gap <= LONG_PRESS_WINDOW_MS

            EventLog.log(
                context, "SOS_CALL",
                "outgoing;to=${mask(number)};after_long_press=$afterLongPress;" +
                    "gap_ms=${gap ?: -1}"
            )
        }
    }

    /**
     * כמה זמן אחרי לחיצה ארוכה שיחה עוד נחשבת נובעת ממנה. 15 שניות
     * בנדיבות: בין הלחיצה לחיוג בפועל יש מסך-אישור או השהיה של היצרן,
     * ואי-אפשר לדעת מראש כמה היא לוקחת.
     */
    const val LONG_PRESS_WINDOW_MS = 15_000L

    private var listener: PhoneStateListener? = null
    private var offHookElapsedMs: Long? = null

    /**
     * עוקב אחרי מצב השיחה כדי למדוד משך. `NEW_OUTGOING_CALL` אומר שהתחילה
     * שיחה אבל לא כמה היא נמשכה, ובלי המשך אי-אפשר להבחין בין חרטה לבין
     * פנייה אמיתית.
     *
     * ⚠️ `OFFHOOK` הוא הרגע שבו השיחה **נענתה או יצאה לדרך**, ולא רגע
     * ההקשה. המשך שנמדד ממנו הוא המשך בפועל.
     */
    fun startWatching(context: Context) {
        if (listener != null) return
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        if (tm == null) {
            EventLog.log(context, "INFO", "sos_watch_unavailable;no_telephony")
            return
        }
        val appContext = context.applicationContext
        listener = object : PhoneStateListener() {
            override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                when (state) {
                    TelephonyManager.CALL_STATE_OFFHOOK -> {
                        offHookElapsedMs = SystemClock.elapsedRealtime()
                        EventLog.log(appContext, "SOS_CALL", "offhook")
                    }
                    TelephonyManager.CALL_STATE_IDLE -> {
                        val start = offHookElapsedMs ?: return
                        offHookElapsedMs = null
                        val ms = SystemClock.elapsedRealtime() - start
                        EventLog.log(
                            appContext, "SOS_CALL",
                            "ended;duration_ms=$ms;short=${ms < SHORT_CALL_MS}"
                        )
                    }
                }
            }
        }
        runCatching { tm.listen(listener, PhoneStateListener.LISTEN_CALL_STATE) }
            .onSuccess { EventLog.log(context, "INFO", "sos_watch_started") }
            .onFailure {
                EventLog.log(context, "INFO", "sos_watch_failed;${it.javaClass.simpleName}")
                listener = null
            }
    }

    /**
     * מתחת לזה השיחה כנראה טעות או חרטה. 20 שניות ולא 5: צלצול אחד או
     * שניים לוקחים כ-10 שניות, ו"התחרט אחרי שני צלצולים" צריך ליפול
     * בצד הקצר.
     */
    const val SHORT_CALL_MS = 20_000L
}
