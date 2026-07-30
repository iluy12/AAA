package com.iluy.imutest

import android.content.Context
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
     * נרשמת רק הלחיצה הראשונה (`repeatCount == 0`) ורק ACTION_DOWN — מקש
     * מוחזק היה מייצר עשרות שורות זהות ודוחק את סיכומי-הדופק מההעלאה.
     *
     * `screen` מאפשר לדעת מאיזה מסך בא האירוע, וזה בדיוק מה שחסר בפעם
     * הקודמת.
     */
    fun record(context: Context, screen: String, event: KeyEvent) {
        if (!DebugConfig.DEBUG_TAG_ENABLED) return
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount != 0) return
        EventLog.log(
            context, "DEBUG",
            "key;screen=$screen;code=${event.keyCode};" +
                "name=${KeyEvent.keyCodeToString(event.keyCode)};" +
                "device=${event.deviceId};source=${event.source}"
        )
    }
}
