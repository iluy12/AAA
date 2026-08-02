package com.iluy.imutest

import android.content.Context

/**
 * האותות שמגיעים מהשאלון ומהלוח — לא מהחיישנים.
 *
 * ## ⚠️ הכלל האחיד: שילוב, לא החלפה
 *
 * לכל נתון אישי יש שני מקורות — מה שהוא **הצהיר** בשאלון, ומה ש**קרה**
 * בפועל. הוכרע שהם נשקללים ביחד, ומשקל הנתונים האמיתיים עולה ככל שיש
 * יותר מהם.
 *
 * הסיבה שלא מחליפים: שבועיים של תצפית אינם מבטלים מה שהוא מכיר על עצמו
 * שנים, והם גם עלולים להיות שבועיים חריגים. ⚠️ ויש סיבה שנייה, חשובה
 * לא פחות: **אם הוא יפסיק לדווח, הנתון הנצפה יראה "מצוין" בדיוק כשהוא
 * נעלם.** הערך המוצהר הוא העוגן שמונע את זה.
 */
object RiskContext {

    /** מעל זה נתוני האמת שולטים כמעט לגמרי. */
    private const val MATURITY = 60.0

    /**
     * האם השעה הנוכחית נופלת בטווח שהוא סימן בשאלון.
     *
     * ⚠️ הטווחים גסים בכוונה — השאלה מציעה "בוקר / צהריים / לילה", ואין
     * טעם להעמיד פנים שיש כאן דיוק של שעה.
     */
    /** האם בכלל ענה על שאלת השעות. בלי זה האות אינו ניתן לחישוב. */
    fun hasDeclaredHours(context: Context): Boolean =
        LocalStore.getMultiChoice(context, LocalStore.KEY_Q1_TIMES).isNotEmpty()

    fun hourMatchesDeclared(context: Context, hourOfDay: Int): Boolean {
        val declared = LocalStore.getMultiChoice(context, LocalStore.KEY_Q1_TIMES)
        if (declared.isEmpty()) return false
        return declared.any { label ->
            when (label.trim()) {
                "בוקר" -> hourOfDay in 5..11
                "צהריים" -> hourOfDay in 12..17
                "לילה" -> hourOfDay >= 22 || hourOfDay <= 4
                else -> false
            }
        }
    }

    /**
     * המרווח שהצהיר עליו, בימים בין נפילות.
     *
     * ⚠️ **נגזר ממספר ולא מטקסט.** השאלה היא כמה פעמים נפל בחודש האחרון,
     * ומכאן המרווח הוא 30 חלקי המספר. קודם היו כאן קטגוריות טקסט
     * ("כל שבועיים") שאני המרתי למספרים בניחוש — כלומר האלגוריתם ניזון
     * מהערכות שלי ולא מנתון של המשתמש.
     */
    private fun declaredIntervalDays(context: Context): Double {
        val perMonth = LocalStore.getSingleChoice(context, LocalStore.KEY_Q2_FREQUENCY)
            .toIntOrNull() ?: 0
        if (perMonth <= 0) return 0.0
        return 30.0 / perMonth
    }

    /**
     * כמה ימים עברו מהנפילה האחרונה, ביחס לקצב הרגיל שלו.
     *
     * מוחזר 0..1: ככל שהוא קרוב יותר לקצב שלו — האות חזק יותר. `null`
     * כשאין מספיק מידע.
     *
     * ⚠️ **מעל הקצב האות לא ממשיך לטפס.** מי שהחזיק פי שלושה מהרגיל
     * אינו בסיכון פי שלושה — הוא בשיא אישי, וזה מצב אחר לגמרי שמטופל
     * ב-[Standing].
     */
    fun daysSinceFallFraction(context: Context): Double? {
        val declared = declaredIntervalDays(context)
        val days = daysSinceLastFall(context) ?: return null
        val observed = observedIntervalDays(context)
        val typical = Standing.typicalInterval(declared, observed?.first, observed?.second ?: 0)
        if (typical <= 0.0) return null
        return (days / typical).coerceIn(0.0, 1.0)
    }

    /**
     * ⚠️ **`recentDays` מחזיר מהחדש לישן** — אינדקס 0 הוא היום. לכן
     * `indexOfFirst` הוא הנפילה **האחרונה**, והאינדקס עצמו כבר שווה
     * למספר הימים שעברו.
     *
     * הגרסה הראשונה השתמשה ב-`indexOfLast` וגם הפכה את החישוב, ולכן
     * נפילה מאתמול דווחה כ-88 ימים. האות הזה היה שקר מלא, והוא ניזון
     * ישירות לציון.
     */
    fun daysSinceLastFall(context: Context): Int? {
        val days = CalendarStore.recentDays(context, 90)
        val idx = days.indexOfFirst { it.hasFall }
        return if (idx < 0) null else idx
    }

    /** המרווח הממוצע בין נפילות שנצפו בפועל, ומספר המרווחים. */
    private fun observedIntervalDays(context: Context): Pair<Double, Int>? {
        val days = CalendarStore.recentDays(context, 90)
        val fallIdx = days.mapIndexedNotNull { i, d -> if (d.hasFall) i else null }
        if (fallIdx.size < 2) return null
        // הרשימה מהחדש לישן, ולכן ההפרש בין אינדקסים עוקבים הוא כבר מספר
        // הימים בין שתי נפילות — הכיוון לא משנה כאן.
        val gaps = fallIdx.zipWithNext { a, b -> (b - a).toDouble() }
        return gaps.average() to gaps.size
    }

    /**
     * כמה התגברויות היום ביחס לרגיל שלו.
     *
     * ⚠️ **אות אחד ולא שניים.** מספר מוחלט בלי השוואה לא אומר כלום —
     * שמונה התגברויות הן הרבה למי שרגיל לשלוש, ויום רגיל למי שרגיל לתשע.
     */
    fun todayVsUsualFraction(context: Context): Double? {
        val today = CalendarStore.overcomingsToday(context)
        if (today <= 0) return null
        val declared = declaredThresholdCount(context)
        val observed = CalendarStore.averageActiveDayOvercomings(context)
        val observedDays = CalendarStore.recentDays(context, 30).count { it.overcomings > 0 }
        val usual = blend(declared, if (observed > 0) observed else null, observedDays)
        if (usual <= 0.0) return null
        return (today / (usual * 2.0)).coerceIn(0.0, 1.0)
    }

    /** כמה פעמים הוא אומר "לא" לפני שקורה משהו. מספר ישיר מהשאלון. */
    private fun declaredThresholdCount(context: Context): Double =
        LocalStore.getSingleChoice(context, LocalStore.KEY_Q10_REFUSALS).toDoubleOrNull() ?: 0.0

    private fun blend(declared: Double, observed: Double?, observedCount: Int): Double {
        if (observed == null || observedCount <= 0) return declared
        val w = (observedCount / MATURITY).coerceIn(0.0, 1.0)
        return declared * (1 - w) + observed * w
    }
}
