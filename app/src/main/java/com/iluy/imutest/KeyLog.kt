package com.iluy.imutest

import android.content.Context
import android.os.PowerManager
import android.view.KeyEvent

/**
 * רישום לחיצות-מקש, כדי לזהות לאיזה קוד כפתור-הצד מתורגם.
 *
 * ⚠️ **למה זה יושב כאן ולא רק במסך-השעון.** בסבב הראשון הרישום הוסף
 * ל-WatchFaceActivity בלבד, ושני לוגים שלמים חזרו עם אפס לחיצות — למרות
 * שנבו לחץ "אחורה" הרבה. הסיבה: `dispatchKeyEvent` מגיע רק ל-Activity
 * **שבחזית**, ובזמן הלחיצות הוא היה בתפריטים. המסקנה השגויה שכמעט
 * הסקתי ממנו הייתה "הכפתור לא מגיע לאפליקציה".
 *
 * לכן: פונקציה אחת, ו-override קצר בכל מסך שהמשתמש באמת מבקר בו.
 */
object KeyLog {

    /**
     * מתי הייתה לחיצה ארוכה אחרונה, ב-`elapsedRealtime`.
     *
     * ⚠️ **זה מה שהופך שיחה יוצאת לאות.** המשתמש עושה שיחות רגילות, ולכן
     * "שיחה יוצאת" לבדה אינה SOS. התבנית שנבו הגדיר היא **לחיצה ארוכה ואז
     * חיוג** — הלחיצה היא העיקר, והשיחה מאמתת אותה או מחלישה אותה לפי
     * משכה. [SosCallWatcher] קורא מכאן.
     *
     * ⚠️ ומה שזה **לא** פותר: אם הלחיצה הארוכה אינה מגיעה לאפליקציה כשהמסך
     * כבוי, השדה יישאר null גם ב-SOS אמיתי. לכן זיהוי לפי **מספר** נשאר
     * נחוץ כמסלול-גיבוי, וזו הסיבה שמחפשים אותו בסריקה.
     */
    @Volatile
    var lastLongPressElapsedMs: Long? = null
        private set

    /**
     * ⚠️ **הגרסה הקודמת סיננה בדיוק את מה שצריך למדוד.** היא רשמה רק
     * `ACTION_DOWN` עם `repeatCount == 0`, ולחיצה ארוכה מייצרת אירועים עם
     * `repeatCount > 0` — כלומר לחיצה ארוכה הייתה בלתי-נראית לחלוטין,
     * והמסקנה הייתה "הכפתור לא תומך בלחיצה ארוכה".
     *
     * מה שנרשם עכשיו, וכל אחד מהם נחוץ לתבנית אחרת:
     *
     * | אירוע | בשביל מה |
     * |---|---|
     * | `down` ראשונה | לספור לחיצות — אחת, שתיים, שלוש |
     * | `long` (הדגל של אנדרואיד) | לחיצה ארוכה, כמו לחיוג |
     * | `up` עם `held_ms` | **המדד המדויק** — כמה זמן הוחזק בפועל |
     *
     * `interactive` הוא מצב המסך. הוא קריטי לרעיון "מסך כבוי + לחיצה
     * כפולה = דיווח נפילה": בלעדיו אי-אפשר להבחין בין לחיצה כפולה בחשכה
     * לבין לחיצה כפולה על מסך פעיל, ואלה שתי מחוות שונות.
     *
     * חזרות-אמצע (`repeatCount > 0` בלי דגל long) עדיין מסוננות — מקש
     * מוחזק מייצר עשרות מהן ודוחק את סיכומי-הדופק מההעלאה.
     */
    fun record(context: Context, screen: String, event: KeyEvent) {
        if (!DebugConfig.DEBUG_TAG_ENABLED) return

        val kind = when {
            event.action == KeyEvent.ACTION_UP -> "up"
            event.isLongPress -> "long"
            event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 -> "down"
            else -> return
        }

        // כמה זמן הוחזק. `downTime` הוא זמן תחילת הלחיצה, `eventTime` הוא
        // זמן האירוע הנוכחי — ההפרש הוא המשך בפועל, ולא הערכה.
        val heldMs = event.eventTime - event.downTime

        if (kind == "long") lastLongPressElapsedMs = android.os.SystemClock.elapsedRealtime()

        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val interactive = runCatching { pm?.isInteractive }.getOrNull()

        EventLog.log(
            context, "DEBUG",
            "key;kind=$kind;screen=$screen;code=${event.keyCode};" +
                "name=${KeyEvent.keyCodeToString(event.keyCode)};" +
                "held_ms=$heldMs;repeat=${event.repeatCount};" +
                "interactive=$interactive;device=${event.deviceId}"
        )
    }
}
