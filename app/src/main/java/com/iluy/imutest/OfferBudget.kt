package com.iluy.imutest

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * כמה פעמים מותר למערכת לפתוח את הפה היום.
 *
 * ## ⚠️ זה חשוב יותר מהאלגוריתם עצמו
 *
 * ב-288 חלונות של חמש דקות ביום, אפילו סגוליות 95% נותנת כ-14 התראות
 * שווא ביום. **המגבלה מעקרת את החישוב** — המערכת לא מנסה לצדוק בכל רגע,
 * אלא לבחור מעט רגעים ולהשתמש בהם. **גם גלאי בינוני נעשה שמיש ככה.**
 *
 * ⚠️ ומימושית זה חייב להיות **סף + תקציב + קירור**, ולא "שני הגבוהים
 * ביום": ב-14:00 אי-אפשר לדעת מה יקרה ב-22:00.
 *
 * ## התקציב גמיש לפי עוצמת היום
 *
 * נבו: *"שיהיה גמיש לפי עוצמת היום."* יום שבו הוא מתמודד הרבה יותר
 * מהרגיל הוא בדיוק היום שבו הוא צריך יותר נוכחות.
 *
 * ⚠️ **ומעל הכל תקרה קשיחה.** לא בגלל האלגוריתם, אלא כי שעון שמדבר יותר
 * מזה מכבים — ואז אין מוצר. זה בלם חירום, לא פרמטר לכיוונון.
 */
object OfferBudget {

    private const val PREFS_NAME = "iluy_budget"

    /** יום רגיל. */
    private const val BUDGET_NORMAL = 2

    /** מעל הממוצע שלו. */
    private const val BUDGET_ELEVATED = 3

    /** הרבה מעל הממוצע, או אחרי נפילה. */
    private const val BUDGET_ROUGH = 5

    /** ⚠️ בלם חירום. לא נשבר בשום מצב. */
    private const val HARD_CEILING = 6

    private const val GAP_NORMAL_MS = 60 * 60 * 1000L
    private const val GAP_ELEVATED_MS = 45 * 60 * 1000L
    private const val GAP_ROUGH_MS = 30 * 60 * 1000L

    /** אחרי שהוא דיווח בעצמו — לא לקפוץ עליו מיד. */
    private const val COOLDOWN_AFTER_REPORT_MS = 20 * 60 * 1000L

    private val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun todayKey() = dayFmt.format(Date())

    /** כמה הצעות כבר נאמרו היום. */
    fun usedToday(context: Context): Int = prefs(context).getInt("used_${todayKey()}", 0)

    /**
     * עוצמת היום, שממנה נגזרים גם התקציב וגם המרווח.
     *
     * 0 = רגיל · 1 = מוגבר · 2 = חריג.
     */
    private fun intensity(context: Context): Int {
        if (FallReport.todayCount(context) > 0) return 2
        // ⚠️ יומיים אחרי נפילת "עיניים" נחשבים חריגים גם בלי שום אות חדש.
        // הסיווג הזה מתאר **תחזית** ולא רק עבר, ולכן הוא ממשיך להשפיע
        // אחרי שהיום עצמו נגמר.
        if (FallAftermath.inHeightenedWindow(context)) return 2
        val today = CalendarStore.overcomingsToday(context)
        val average = CalendarStore.averageActiveDayOvercomings(context)
        if (average <= 0) return 0
        return when {
            today > average * 1.8 -> 2
            today > average * 1.2 -> 1
            else -> 0
        }
    }

    fun budgetToday(context: Context): Int =
        when (intensity(context)) {
            2 -> BUDGET_ROUGH
            1 -> BUDGET_ELEVATED
            else -> BUDGET_NORMAL
        }.coerceAtMost(HARD_CEILING)

    private fun requiredGapMs(context: Context): Long =
        when (intensity(context)) {
            2 -> GAP_ROUGH_MS
            1 -> GAP_ELEVATED_MS
            else -> GAP_NORMAL_MS
        }

    fun hasRoomToday(context: Context): Boolean =
        usedToday(context) < budgetToday(context) && usedToday(context) < HARD_CEILING

    fun enoughTimeSinceLast(context: Context): Boolean {
        val last = prefs(context).getLong("last_offer_ms", 0L)
        if (last == 0L) return true
        return System.currentTimeMillis() - last >= requiredGapMs(context)
    }

    fun inCooldownAfterReport(context: Context): Boolean {
        val last = prefs(context).getLong("last_report_ms", 0L)
        if (last == 0L) return false
        return System.currentTimeMillis() - last < COOLDOWN_AFTER_REPORT_MS
    }

    /** נקרא כשההצעה נאמרה בפועל — ולא כשהיא רק חושבה. */
    fun recordOffer(context: Context) {
        prefs(context).edit()
            .putInt("used_${todayKey()}", usedToday(context) + 1)
            .putLong("last_offer_ms", System.currentTimeMillis())
            .apply()
    }

    /** נקרא בכל דיווח של המשתמש — ✕, נפילה, מצב-רוח. */
    fun recordUserReport(context: Context, kind: String = "report") {
        prefs(context).edit()
            .putLong("last_report_ms", System.currentTimeMillis())
            .putString("last_report_kind", kind)
            .apply()
    }

    /**
     * סוג הדיווח האחרון, אם היה בשעה האחרונה. אחרת ריק.
     *
     * ⚠️ **זה מה שהופך את הנתונים לניתנים לניתוח.** בלעדיו, השאלה "איך
     * נראה הציון בדקות שלפני דיווח" דורשת להצליב שני מקורות לפי חותמות
     * זמן ולקוות שהשעונים מסונכרנים. עם התיוג היא שאילתה על עמודה אחת.
     *
     * שעה ולא פחות: החלון שמעניין ללמידה הוא חצי שעה עד שעה שקדמה, ולכן
     * רשומות מהטווח הזה צריכות לשאת את התיוג.
     */
    fun recentReportLabel(context: Context): String {
        val last = prefs(context).getLong("last_report_ms", 0L)
        if (last == 0L) return ""
        if (System.currentTimeMillis() - last > 60 * 60 * 1000L) return ""
        return prefs(context).getString("last_report_kind", "report") ?: "report"
    }

    fun describe(context: Context): String =
        "used=${usedToday(context)}/${budgetToday(context)};intensity=${intensity(context)}"
}
