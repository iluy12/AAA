package com.iluy.imutest

import android.app.Activity
import android.os.Build
import android.view.View

/**
 * מסך מלא זמני — מחזיר לאפליקציה את הפס שהמערכת לוקחת.
 *
 * ## למה זה נדרש
 *
 * בלוג של 3.8 בשעה 21:52 שלוש משיכות **קפאו**: המסך דיווח 10-16 אירועי
 * תנועה, וכולם על אותה נקודה בדיוק.
 *
 * ```
 * from=192,404  trace=[192,404 192,404 192,404 ...]
 * from=210,440  trace=[210,440 210,440 210,440 ...]
 * ```
 *
 * ובאותה דקה תשע משיכות אחרות נרשמו **מושלם**, באורך 308-384 פיקסלים.
 *
 * ההבדל היחיד הוא **איפה האצבע נחתה**: הקופאות התחילו ב-y=404 ומטה,
 * ואחת שנחטפה לגמרי התחילה ב-x=20 — הקצה השמאלי, מחוות "חזרה".
 *
 * ⚠️ **החלון שלנו הוא 365 פיקסלים והמסך הוא 480.** זה נגזר מהלוג עצמו:
 * סף האורך `need=73` הוא 20% מהצלע הקטנה. כלומר **כמעט רבע מהמסך שייך
 * למערכת**, ואצבע שנוחתת שם מתה.
 *
 * ## ⚠️ ולמה זה אסור על מסך-השעון
 *
 * זו לא זהירות-יתר אלא תקלה שכבר קרתה: `FLAG_FULLSCREEN` על מסך-הבית
 * חוסם גם את משיכת-ההתראות מלמעלה. ומכיוון שעילוי **מחליפה את הלאנצ'ר**
 * — ואיתו החלקה-שמאלה לתפריט — נבו נשאר נעול במסך אחד בלי גישה
 * להגדרות ובלי יכולת להסיר את האפליקציה.
 *
 * לכן זה חל **רק על מסכים זמניים**: בורר הנפילה, מסך רמה א', וכל מסך
 * שנסגר מעצמו. שם אין סכנת נעילה — המסך נגמר וחוזרים.
 *
 * `IMMERSIVE_STICKY` ולא `IMMERSIVE` רגיל: הרגיל מחזיר את הפסים בנגיעה
 * הראשונה, כלומר בדיוק במגע שאנחנו מנסים לקלוט.
 */
object Immersive {

    fun apply(activity: Activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) return
        activity.window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
    }

    /**
     * רושם את הגודל שהתקבל בפועל.
     *
     * ⚠️ **בלי המספר הזה אני מנחש.** הסקתי את גודל החלון בעקיפין, מסף
     * שנרשם בלוג — וזה בדיוק סוג ההסקה שכבר הטעה אותי כאן פעמיים היום.
     */
    fun logGeometry(activity: Activity, screen: String) {
        val d = activity.resources.displayMetrics
        val v = activity.window.decorView
        EventLog.log(
            activity, "DEBUG",
            "geometry;screen=$screen;view=${v.width}x${v.height};" +
                "metrics=${d.widthPixels}x${d.heightPixels};density=${d.density}"
        )
    }
}
