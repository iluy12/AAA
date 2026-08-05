package com.iluy.imutest

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock

/**
 * זיהוי הדלקות-מסך, ובעיקר **שתיים רצופות**.
 *
 * ## למה זה נבנה
 *
 * נבו רצה מחווה שעובדת במסך כבוי: שתי לחיצות על כפתור הצד = דיווח נפילה.
 * בלי מסך, בלי ציור, אפשר לעשות את זה מתחת לשמיכה — אפס חיכוך, וזה בדיוק
 * מה שנדרש כדי שדיווח ברגע של בושה בכלל יקרה.
 *
 * ⚠️ **אבל דרך המקשים זה בלתי-אפשרי, וזה נמדד.** ב-2026-07-30 בשעה 15:20
 * נלחץ הכפתור פעמיים על מסך כבוי, והלוג חזר בלי אף אירוע-מקש — למרות
 * שבאותה גרסה נרשמה לחיצה אחרת באותה שעה (15:00:17). המערכת בולעת את
 * הלחיצה כדי להדליק את המסך, והיא לא מגיעה לאף Activity שלנו.
 *
 * מה שכן מגיע: **`ACTION_SCREEN_ON`.** אנדרואיד משדר אותו בכל הדלקה, בלי
 * קשר למי גרם לה. שתי הדלקות בתוך חלון קצר הן לחיצה כפולה — בלי לתפוס
 * אף מקש, ובלי לחטוף את כפתור "אחורה" שנבו משתמש בו לניווט.
 *
 * ## מה זה כרגע ומה לא
 *
 * ⚠️ **כרגע זה רישום בלבד. שום דבר לא מופעל מהמחווה הזו.** קודם מודדים אם
 * התבנית בכלל מובחנת בפועל — כמה זמן עובר בין שתי הדלקות, והאם הדלקות
 * רגילות (הרמת יד, התראה) נראות אחרת. רק אחרי שיהיו מספרים אמיתיים אפשר
 * לקבוע סף ולחבר אליו דיווח.
 */
object ScreenWakeWatcher {

    /**
     * שתי הדלקות בתוך החלון הזה נחשבות "כפולה". 2500ms בנדיבות: על מסך
     * כבוי הלחיצה הראשונה מדליקה, ולוקח רגע עד שהמשתמש לוחץ שוב.
     */
    const val DOUBLE_WINDOW_MS = 2_500L

    private var receiver: BroadcastReceiver? = null
    private var lastOnElapsedMs: Long? = null

    fun startWatching(context: Context) {
        if (receiver != null) return
        val appContext = context.applicationContext
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_ON -> onScreenOn(appContext)
                    Intent.ACTION_SCREEN_OFF -> {
                        // ⚠️ **המסך הוא הצרכן הגדול ביותר, והיחיד שלא
                        // בשליטתנו.** מדידת הסוללה של 3.8 יצאה 5.2%
                        // לשעה מול 3.3% בלילה — וההפרש כולו היה הדלקות
                        // מסך בזמן בדיקות. בלי לספור שניות מסך, כל
                        // מדידת סוללה עתידית תערבב שימוש עם צריכה.
                        onElapsedMs?.let {
                            PowerLog.addScreenMs(appContext, SystemClock.elapsedRealtime() - it)
                        }
                        onElapsedMs = null
                        EventLog.log(appContext, "DEBUG", "screen;state=off")
                    }
                }
            }
        }
        // ⚠️ חייב להיות רישום בקוד ולא במניפסט: מ-אנדרואיד 8
        // ACTION_SCREEN_ON/OFF אינם נמסרים למקבלים שמוצהרים במניפסט.
        appContext.registerReceiver(r, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        })
        receiver = r
        EventLog.log(context, "INFO", "screen_watch_started")
    }

    /** מתי המסך נדלק, לחישוב משך. ראו PowerLog. */
    private var onElapsedMs: Long? = null

    private fun onScreenOn(context: Context) {
        val now = SystemClock.elapsedRealtime()
        onElapsedMs = now
        val gap = lastOnElapsedMs?.let { now - it }
        lastOnElapsedMs = now
        val double = gap != null && gap <= DOUBLE_WINDOW_MS
        EventLog.log(
            context, "DEBUG",
            "screen;state=on;gap_ms=${gap ?: -1};double=$double"
        )
    }

    fun stopWatching(context: Context) {
        val r = receiver ?: return
        runCatching { context.applicationContext.unregisterReceiver(r) }
        receiver = null
    }
}
