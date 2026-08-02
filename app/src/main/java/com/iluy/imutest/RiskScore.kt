package com.iluy.imutest

import android.content.Context

/**
 * מנוע הציון: שערים, ואז משקלים.
 *
 * ## המבנה, ולמה דווקא כך
 *
 * ⚠️ **שער הוא וטו, לא משקל.** אם השעון על השידה, שום כמות של "השעה
 * מתאימה" לא צריכה לייצר הצעה. ברגע שמערבבים את השניים למספר אחד יוצא
 * שטויות — ולכן [gatesPassed] מוכרע לפני שמחשבים ציון בכלל.
 *
 * ## המשקלים הם של נבו
 *
 * הוא דירג אותם 3-8, והם **נקודת ההתחלה בלבד**. המחקר מראה שסף אחיד
 * לכל האוכלוסייה נכשל בשאלה הזו, ושלכל אדם יש חתימה עקבית משלו — ולכן
 * המשקלים אמורים לזוז לכיוון המשתמש ככל שיצטברו דיווחים.
 *
 * ## אות "כמה" מטפס, אות "כן/לא" נותן הכל או כלום
 *
 * הוכרע: דופק יחידה אחת מעל הרגיל אינו שווה לדופק חמש יחידות מעל.
 * אחרת המערכת מתלהבת מחריגה זעירה בדיוק כמו מחריגה גדולה, ואיבדנו את
 * ההבחנה בין "קצת" ל"הרבה".
 *
 * ## ⚠️ הסף עצמו אינו נקבע כאן
 *
 * [SILENT_MODE] דלוק, ולכן שום דבר לא נאמר למשתמש. המנוע מחשב, מחליט,
 * **רושם ללוג ושותק.** רק אחרי שיצטבר פיזור אמיתי של ציונים אפשר יהיה
 * לדעת איפה עובר הקו — כל מספר שהיה נכתב כאן עכשיו הוא ניחוש.
 */
object RiskScore {

    /**
     * ⚠️ **מצב-צל. אסור לכבות לפני שראינו נתונים.**
     *
     * ב-288 חלונות של חמש דקות ביום, אפילו סגוליות 95% נותנת כ-14
     * התראות שווא ביום — ושעון שמדבר 14 פעמים ביום מכבים אחרי יומיים.
     * מצב-צל זול, והוא מונע שהפיצ'ר המרכזי יישרף בשבוע הראשון.
     */
    const val SILENT_MODE = true

    /** משקלי נבו. שם התנאי → כמה הוא שווה. */
    private const val W_STILL = 5
    private const val W_LOW_MOTION = 6
    private const val W_POSTURE = 8
    private const val W_PULSE_ABOVE = 8
    private const val W_PULSE_RISING = 8
    private const val W_HOUR_MATCHES = 6
    private const val W_POSTURE_MATCHES = 6
    private const val W_DAYS_SINCE_FALL = 3
    private const val W_TODAY_VS_USUAL = 6
    private const val W_USUAL_PLACE = 6

    private const val MAX_SCORE =
        W_STILL + W_LOW_MOTION + W_POSTURE + W_PULSE_ABOVE + W_PULSE_RISING +
            W_HOUR_MATCHES + W_POSTURE_MATCHES + W_DAYS_SINCE_FALL +
            W_TODAY_VS_USUAL + W_USUAL_PLACE

    /** מתחת ל-2 דקות ללא צעד אין בכלל על מה לדבר. */
    private const val STILL_MIN_MS = 2 * 60 * 1000L

    /** מעל זה חוסר-התנועה כבר לא מוסיף. */
    private const val STILL_FULL_MS = 20 * 60 * 1000L

    /** חריגת דופק שמעליה האות מלא, ביחידות אישיות. */
    private const val DEV_FULL = 4.0

    data class Result(
        val gatesPassed: Boolean,
        val blockedBy: String?,
        val score: Int,
        val parts: String
    )

    /**
     * ⚠️ שערים ראשונים, ורק אז משקלים. `blockedBy` נרשם כדי שאפשר יהיה
     * לדעת **איזה** שער עצר — בלי זה "לא הוצע" הוא תיבה שחורה.
     */
    fun evaluate(context: Context, r: SampleStore.Record): Result {
        gateBlock(context, r)?.let {
            return Result(false, it, 0, "")
        }

        val parts = StringBuilder()
        var score = 0

        fun add(name: String, weight: Int, fraction: Double) {
            val f = fraction.coerceIn(0.0, 1.0)
            if (f <= 0.0) return
            val v = (weight * f).toInt()
            if (v <= 0) return
            score += v
            parts.append("$name=$v,")
        }

        // --- חוסר תנועה: מטפס עם הזמן ---
        add("still", W_STILL, ramp(r.stillMs.toDouble(), STILL_MIN_MS.toDouble(), STILL_FULL_MS.toDouble()))

        // --- תנועה עדינה נמוכה. הפוך: ככל שפחות תנועה, האות חזק יותר ---
        if (r.motion >= 0) add("low_motion", W_LOW_MOTION, 1.0 - ramp(r.motion.toDouble(), 5.0, 60.0))

        // --- תנוחה: נמדדת כשונות מהמצב הרגיל שלו, לא כסיווג מוחלט ---
        Posture.deviationFromUsual(context, r)?.let { add("posture", W_POSTURE, it) }
        if (Posture.matchesDeclared(context, r)) add("posture_q", W_POSTURE_MATCHES, 1.0)

        // --- דופק מעל הרגיל שלו בשעה הזאת, בפרופורציה ---
        val level = Baseline.levelFor(context, r.hourOfDay)
        if (level != null && r.bpm > 0) {
            val dev = Baseline.deviation(level, r.bpm)
            add("pulse_above", W_PULSE_ABOVE, dev / DEV_FULL)
        }

        // --- הדופק מטפס בתוך הפרץ עצמו ---
        if (r.bpmTrend > 0) add("pulse_rising", W_PULSE_RISING, r.bpmTrend / 6.0)

        // --- שעה שהצהיר עליה ---
        if (RiskContext.hourMatchesDeclared(context, r.hourOfDay)) add("hour_q", W_HOUR_MATCHES, 1.0)

        // --- מרחק מהנפילה האחרונה ביחס לקצב שלו ---
        RiskContext.daysSinceFallFraction(context)?.let { add("since_fall", W_DAYS_SINCE_FALL, it) }

        // --- כמה התגברויות היום ביחס לרגיל שלו ---
        RiskContext.todayVsUsualFraction(context)?.let { add("today_vs_usual", W_TODAY_VS_USUAL, it) }

        // --- במקום הרגיל שלו ---
        if (PlaceTracker.atUsualPlace(context) == true) add("place", W_USUAL_PLACE, 1.0)

        val normalised = (score * 100) / MAX_SCORE
        return Result(true, null, normalised, parts.toString().trimEnd(','))
    }

    /** עולה מ-0 ל-1 בין שני גבולות. מתחת לתחתון — אפס, מעל העליון — אחד. */
    private fun ramp(v: Double, from: Double, to: Double): Double {
        if (v <= from) return 0.0
        if (v >= to) return 1.0
        return (v - from) / (to - from)
    }

    /**
     * השער הראשון שנכשל, או null אם כולם עברו.
     *
     * ⚠️ הסדר מכוון: הזול והוודאי קודם. אין טעם לחשב תקציב יומי אם השעון
     * בכלל לא על היד.
     */
    private fun gateBlock(context: Context, r: SampleStore.Record): String? {
        if (r.bpm <= 0 || r.noContact > 0) return "not_worn"
        if (r.samples < Baseline.MIN_SAMPLES_IN_BURST) return "too_few_samples"
        if (r.stillMs in 0 until STILL_MIN_MS) return "moving"
        if (!OfferBudget.hasRoomToday(context)) return "daily_budget"
        if (!OfferBudget.enoughTimeSinceLast(context)) return "too_soon"
        if (OfferBudget.inCooldownAfterReport(context)) return "cooldown"
        return null
    }
}
