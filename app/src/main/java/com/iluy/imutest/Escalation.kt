package com.iluy.imutest

import android.content.Context

/**
 * רמת ההסלמה — **מצב אחד מצטבר, לא ארבעה מנגנונים נפרדים.**
 *
 * ## ⚠️ הבעיה שזה פותר
 *
 * עד עכשיו כל טריגר טיפל בעצמו: מצב-רוח פתח מסך, נפילה תזמנה הודעה,
 * הכפלת הסף הציעה מלווה. **שלושתם יכלו לרוץ באותה שעה ולשלוח שלוש
 * סדרות הודעות במקביל** — כלומר המשתמש מקבל הצפה, ובדיוק ברגע שבו
 * הוא הכי פחות סובל אותה.
 *
 * נבו ניסח את שני הכללים:
 *
 * > **1. אירועים מצטברים.** מצב-רוח ואחריו נפילה בעיניים — *"זה אוי
 * > ואבוי, ישר RISK B"*.
 * >
 * > **2. סט אחד בלבד.** *"תמיד לקחת את ההתראות של המנגנון היותר
 * > אגרסיבי ולבטל את הפחות."*
 *
 * ## איך זה עובד
 *
 * לכל אירוע יש ניקוד. הניקוד מצטבר בחלון זמן ודועך. הרמה הנוכחית היא
 * הסכום, **ורק היא קובעת מה נאמר** — לא האירוע האחרון.
 *
 * ⚠️ ולכן כשאירוע חדש מעלה את הרמה, כל מה שתוזמן ברמה נמוכה יותר
 * **מבוטל ומוחלף**, ולא מצטרף.
 */
object Escalation {

    private const val PREFS_NAME = "iluy_escalation"

    /** מעבר לזה אירוע כבר לא נחשב. */
    private const val WINDOW_MS = 6 * 60 * 60 * 1000L

    enum class Level(val threshold: Int) {
        /** שקט. */
        NONE(0),

        /** הצעה תמימה בלבד. ⚠️ הגלאי לבדו לעולם לא עובר את זה. */
        GENTLE(1),

        /** נוכחות שקטה, נגיעה אחת לעזרה. */
        RISK_A(3),

        /** דוחף שיחה למלווה. */
        RISK_B(5);
    }

    /**
     * ניקוד לכל אירוע.
     *
     * ⚠️ המספרים מסודרים כך שהדוגמה של נבו יוצאת נכון: מצב-רוח קשה (2)
     * ועיניים (3) = 5 = **RISK B**. וזה לא מקרי — נפילה בעיניים אחרי
     * שהוא כבר סימן שקשה לו היא בדיוק הצירוף שהוא תיאר.
     */
    private fun points(kind: String): Int = when {
        kind.startsWith("fall_${FallSeverity.SEED.label}") -> 3
        kind.startsWith("fall_${FallSeverity.EYES.label}") -> 3
        kind.startsWith("fall_${FallSeverity.THOUGHT.label}") -> 1
        // ⚠️ קרי לילה לא מסלים. לא הייתה בחירה, ולחץ נוסף על מי שלא
        // בחר הוא ענישה על משהו שלא בשליטתו.
        kind.startsWith("fall_${FallSeverity.NOCTURNAL.label}") -> 0
        kind.startsWith("mood_") -> 2
        // ⚠️ **התגברות רגילה שווה אפס, וזה מכוון.** היא ניצחון, לא סיכון.
        // מה שמסלים הוא הכמות ביחס לסף שהוא הצהיר עליו: הגיע לסף — יום
        // חריג; הכפיל אותו — יום שכבר קורה בו משהו.
        kind == "overcoming_doubled" -> 3
        kind == "overcoming_threshold" -> 1
        else -> 0
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * רושם אירוע ומחזיר את הרמה **החדשה**.
     *
     * ⚠️ הקורא חייב לפעול לפי הרמה שחוזרת ולא לפי האירוע שהוא שלח —
     * זו כל הנקודה של המנגנון הזה.
     */
    fun record(context: Context, kind: String): Level {
        val now = System.currentTimeMillis()
        val fresh = activeEvents(context, now) + Event(now, points(kind), kind)
        write(context, fresh)
        val level = levelNow(context)
        EventLog.log(
            context, "ESCALATION",
            "event=$kind;points=${points(kind)};total=${total(context)};level=$level"
        )
        return level
    }

    /**
     * ⚠️ **`kind` נשמר ולא רק הניקוד.** בלעדיו אי-אפשר לבטל אירוע
     * מסוים: נבו ביקש שאפשר יהיה לבטל נפילה שנרשמה בטעות ו**"לאותת
     * למוח לא להתייחס אליה"**, וכדי להסיר בדיוק אותה צריך לדעת מה היא
     * הייתה. ביטול לפי ניקוד בלבד היה יכול להסיר אירוע אחר באותו שווי.
     */
    private data class Event(val at: Long, val points: Int, val kind: String)

    private fun activeEvents(context: Context, now: Long): List<Event> =
        (prefs(context).getString("events", "") ?: "")
            .split(";")
            .mapNotNull {
                val p = it.split(",")
                if (p.size < 2) return@mapNotNull null
                val t = p[0].toLongOrNull() ?: return@mapNotNull null
                val v = p[1].toIntOrNull() ?: return@mapNotNull null
                // תאימות לאחור: רשומות מהגרסה הקודמת נשמרו בלי הסוג.
                if (now - t > WINDOW_MS) null else Event(t, v, p.getOrElse(2) { "" })
            }

    private fun write(context: Context, events: List<Event>) {
        prefs(context).edit()
            .putString("events", events.joinToString(";") { "${it.at},${it.points},${it.kind}" })
            .apply()
    }

    /**
     * מבטל את האירוע האחרון מסוג נתון.
     *
     * ⚠️ **זה מה שהיה חסר, והמד נשאר זהוב אחרי ביטול.** ביטול נפילה
     * בלוח סימן דגל ב-[CalendarStore] בלבד — הניקוד נשאר, ההסלמה נשארה,
     * ומסך השעון המשיך להראות מצב מוגבר על אירוע שהמשתמש כבר אמר שלא
     * קרה. **וזה גרוע פי כמה מהיעדר ביטול**, כי הוא מרגיש שהמערכת
     * מתעלמת ממנו.
     */
    fun revoke(context: Context, kind: String): Boolean {
        val now = System.currentTimeMillis()
        val events = activeEvents(context, now)
        val idx = events.indexOfLast { it.kind == kind }
        if (idx < 0) {
            EventLog.log(context, "ESCALATION", "revoke_miss;kind=$kind")
            return false
        }
        write(context, events.filterIndexed { i, _ -> i != idx })
        EventLog.log(
            context, "ESCALATION",
            "revoked;kind=$kind;total=${total(context)};level=${levelNow(context)}"
        )
        return true
    }

    fun total(context: Context): Int =
        activeEvents(context, System.currentTimeMillis()).sumOf { it.points }

    fun levelNow(context: Context): Level {
        val t = total(context)
        return when {
            t >= Level.RISK_B.threshold -> Level.RISK_B
            t >= Level.RISK_A.threshold -> Level.RISK_A
            t >= Level.GENTLE.threshold -> Level.GENTLE
            else -> Level.NONE
        }
    }

    /** אחרי שיחה שנענתה, או יום חדש — מתחילים נקי. */
    fun clear(context: Context) {
        prefs(context).edit().remove("events").apply()
        EventLog.log(context, "ESCALATION", "cleared")
    }
}
