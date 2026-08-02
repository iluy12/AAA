package com.iluy.imutest

import android.content.Context

/**
 * איפה המשתמש עומד **ביחס לעצמו**, עכשיו.
 *
 * ## למה זה נבנה
 *
 * נבו ניסח את זה: *"אם אמר שנופל כל שבוע וכבר שבועיים לא דיווח — המערכת
 * צריכה לדעת את זה ולכייל את עצמה **ואת המסרים** לפי זה."*
 *
 * זה משנה שני דברים שונים:
 *
 * 1. **את הזיהוי** — ככל שהוא רחוק מהקצב הרגיל שלו, הסיכוי לנפילה עולה.
 * 2. **את המסרים** — ⚠️ וזה החלק שבלעדיו הבנקים נשארים גנריים. מי שבשיא
 *    אישי צריך לשמוע משהו אחר לגמרי ממי שנפל אתמול, **ומשפט גנרי נשחק
 *    תוך שבוע.**
 *
 * ## שילוב ולא החלפה
 *
 * ⚠️ הכלל שנקבע לכל הנתונים האישיים: **השאלון הוא נקודת הפתיחה, והמצב
 * בפועל נכנס בהדרגה — לא מחליף.** בהתחלה יש רק מה שהוא אמר; אחרי מספיק
 * ימים יש גם מה שקרה; ומכאן שניהם נשקללים, כשמשקל הנתונים האמיתיים עולה
 * ככל שיש יותר מהם.
 *
 * הסיבה שלא מחליפים: שבועיים של נתונים אינם מבטלים מה שהוא מכיר על עצמו
 * שנים, והם גם עלולים להיות שבועיים חריגים.
 */
object Standing {

    /** מעל זה נתוני האמת שולטים כמעט לגמרי. */
    private const val MATURITY_DAYS = 60.0

    /**
     * המרווח האופייני בין נפילות, בימים.
     *
     * `declaredDays` מגיע מהשאלון, `observedDays` מהלוח. אם אין נתון
     * אמיתי — מוחזר המוצהר כמו שהוא.
     */
    fun typicalInterval(declaredDays: Double, observedDays: Double?, observedCount: Int): Double {
        if (observedDays == null || observedCount <= 0) return declaredDays
        // משקל הנתונים האמיתיים עולה עם כמותם, ונעצר ב-1.
        val w = (observedCount / MATURITY_DAYS).coerceIn(0.0, 1.0)
        return declaredDays * (1 - w) + observedDays * w
    }

    /**
     * איפה הוא עומד עכשיו.
     *
     * `ratio` הוא הימים הנקיים חלקי המרווח האופייני שלו:
     * 1.0 = בדיוק בקצב הרגיל · 2.0 = כפול מהרגיל · 0.1 = נפל ממש עכשיו.
     */
    data class Position(
        val daysClean: Int,
        val typicalDays: Double,
        val longestEverDays: Int,
        val ratio: Double
    ) {
        /** נפל היום או אתמול. הרגע הרגיש ביותר. */
        val justFell: Boolean get() = daysClean <= 1

        /** מחזיק מעבר לקצב הרגיל שלו. */
        val aboveUsual: Boolean get() = ratio >= 1.5

        /**
         * ⚠️ **קרוב לשיא האישי שלו.** זה הרגע שבו עידוד שווה הכי הרבה —
         * ובדיוק בו גם הסיכון גבוה, כי יש מה לאבד.
         */
        val nearRecord: Boolean
            get() = longestEverDays > 0 && daysClean >= longestEverDays * 0.8

        val atRecord: Boolean get() = longestEverDays > 0 && daysClean > longestEverDays
    }

    /**
     * ⚠️ **המספר מגיע ישירות מהמשתמש.** קודם הייתה כאן טבלת המרה מטקסט —
     * "כמה חודשים" הפך ל-75 ימים — כלומר נקודת הייחוס האישית שלו הייתה
     * ניחוש שלי. עכשיו הוא בוחר מספר, ואין מה לפרש.
     */
    fun longestStreakToDays(answer: String?): Int = answer?.toIntOrNull() ?: 0

    fun position(context: Context, daysClean: Int, typicalDays: Double): Position {
        val longest = longestStreakToDays(
            LocalStore.getSingleChoice(context, LocalStore.KEY_Q11_LONGEST_STREAK)
        )
        val ratio = if (typicalDays > 0) daysClean / typicalDays else 0.0
        return Position(daysClean, typicalDays, longest, ratio)
    }
}
