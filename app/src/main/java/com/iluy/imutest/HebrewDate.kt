package com.iluy.imutest

import java.util.Date

/**
 * התאריך העברי.
 *
 * ## ⚠️ למה לא טבלה שהורדנו
 *
 * נבו הציע להוריד את התאריכים לשלוש שנים קדימה ולשמור אותם בקובץ. זה
 * היה עובד — **ונשבר בשקט בקיץ 2029.** תאריך שגוי על מסך השעון אינו
 * מתריע על עצמו; הוא פשוט מוצג, ואיש לא יבדוק.
 *
 * לאנדרואיד יש לוח עברי מובנה מגרסה 24 (`android.icu.util.HebrewCalendar`),
 * והאפליקציה דורשת 26. הוא מטפל לבד בשנים מעוברות, באדר א׳ ואדר ב׳,
 * ובכל מה שטבלה ידנית הייתה צריכה לזכור — ואין לו תאריך תפוגה.
 *
 * ⚠️ **אבל הוא לא יודע להציג בגימטריה** — הוא מחזיר מספרים. ההמרה
 * לאותיות נכתבת כאן, וזו כל העבודה שנשארה.
 *
 * ## היום מתחיל בערב
 *
 * ⚠️ לוח עברי מחליף תאריך בשקיעה ולא בחצות. הזמן המדויק תלוי במיקום
 * ובעונה, ולכן [SUNSET_HOUR] הוא קירוב. **מוצג במפורש כאן כדי שלא
 * ייראה כאילו זה מדויק** — אדם שרואה תאריך שקפץ ב-18:00 בדצמבר צריך
 * לדעת שזה קירוב ולא באג.
 */
object HebrewDate {

    /** קירוב לשקיעה. ראו הערת-המחלקה. */
    private const val SUNSET_HOUR = 18

    private val MONTHS = arrayOf(
        "תשרי", "חשוון", "כסלו", "טבת", "שבט",
        "אדר א׳", "אדר", "ניסן", "אייר", "סיוון", "תמוז", "אב", "אלול"
    )

    private val LETTERS = listOf(
        400 to "ת", 300 to "ש", 200 to "ר", 100 to "ק",
        90 to "צ", 80 to "פ", 70 to "ע", 60 to "ס", 50 to "נ",
        40 to "מ", 30 to "ל", 20 to "כ", 10 to "י",
        9 to "ט", 8 to "ח", 7 to "ז", 6 to "ו", 5 to "ה",
        4 to "ד", 3 to "ג", 2 to "ב", 1 to "א"
    )

    /**
     * מספר לגימטריה.
     *
     * ⚠️ **15 ו-16 הם יוצאי דופן, ולא קישוט.** הצירופים הרגילים היו
     * יוצאים י״ה ו-י״ו — שני שמות קדושים — ולכן נהוג ט״ו ו-ט״ז. תאריך
     * שמציג את זה לא נכון על שעון של בן תורה הוא לא באג טכני.
     */
    fun gematria(n: Int): String {
        if (n <= 0) return ""
        val sb = StringBuilder()
        var left = n
        while (left > 0) {
            if (left == 15) { sb.append("טו"); left = 0; break }
            if (left == 16) { sb.append("טז"); left = 0; break }
            val pair = LETTERS.first { it.first <= left }
            sb.append(pair.second)
            left -= pair.first
        }
        val s = sb.toString()
        return when {
            s.length == 1 -> "$s׳"
            s.length > 1 -> s.dropLast(1) + "״" + s.last()
            else -> s
        }
    }

    /**
     * למשל "כ׳ באב תשפ״ו". מחרוזת ריקה אם הלוח אינו זמין — עדיף כלום
     * מתאריך שגוי.
     */
    fun format(date: Date = Date()): String = runCatching {
        val cal = android.icu.util.HebrewCalendar()
        cal.time = date
        // ⚠️ אחרי השקיעה זה כבר היום הבא. ראו הערת-המחלקה.
        val civil = java.util.Calendar.getInstance().apply { time = date }
        if (civil.get(java.util.Calendar.HOUR_OF_DAY) >= SUNSET_HOUR) {
            cal.add(android.icu.util.HebrewCalendar.DATE, 1)
        }

        val day = cal.get(android.icu.util.HebrewCalendar.DAY_OF_MONTH)
        val month = cal.get(android.icu.util.HebrewCalendar.MONTH)
        // השנה נכתבת בלי האלפים, כנהוג: 5786 ← תשפ״ו
        val year = cal.get(android.icu.util.HebrewCalendar.YEAR) % 1000

        val name = MONTHS.getOrNull(month) ?: return@runCatching ""
        // "ב" לפני שם החודש, חוץ מאלה שמתחילים באות שנבלעת
        "${gematria(day)} ב$name ${gematria(year)}"
    }.getOrDefault("")
}
