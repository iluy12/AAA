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
    /** תנועה עדינה בלי צעדים. ⚠️ היה `W_LOW_MOTION` והיה הפוך — ראו שימוש. */
    private const val W_MOTION_NO_STEPS = 6
    private const val W_POSTURE = 8
    private const val W_PULSE_ABOVE = 8
    private const val W_PULSE_RISING = 8
    private const val W_HOUR_MATCHES = 6
    private const val W_POSTURE_MATCHES = 6
    private const val W_DAYS_SINCE_FALL = 3
    private const val W_TODAY_VS_USUAL = 6
    private const val W_USUAL_PLACE = 6

    /**
     * ⚠️ **8 ולא 6.** נבו נתן 6 ל"במקום הרגיל שלו", אבל זה אות אחר וחזק
     * יותר: לא "האם הוא בבית" אלא **"האם הוא בדיוק במקום שבו זה כבר קרה
     * פעמיים"**. הוא נלמד מהתנהגות ולא מהצהרה, ולכן הוא גם לא סובל
     * מהבעיה של "רוב הזמן הוא בבית".
     */
    private const val W_KNOWN_FALL_PLACE = 8

    // ⚠️ **אין כאן MAX_SCORE בכוונה.** היה כזה, והציון חולק בו — אבל
    // המקסימום התיאורטי אינו בר-השגה ברוב הזמן, כי אותות שלמים אינם
    // ניתנים לחישוב עד שיצטברו נתונים. הנרמול עבר ל-`available`, שנצבר
    // בזמן החישוב מהאותות שבאמת נבדקו.

    /** מתחת ל-2 דקות ללא צעד אין בכלל על מה לדבר. */
    private const val STILL_MIN_MS = 2 * 60 * 1000L

    /** מעל זה חוסר-התנועה כבר לא מוסיף. */
    private const val STILL_FULL_MS = 20 * 60 * 1000L

    /** חריגת דופק שמעליה האות מלא, ביחידות אישיות. */
    private const val DEV_FULL = 4.0

    /**
     * `score` הוא הסכום הגולמי. `available` הוא סכום המשקלים של האותות
     * שהיו **בכלל ניתנים לחישוב** ברגע הזה.
     *
     * ⚠️ **ההפרדה הזו קריטית לכיול, וכמעט פספסתי אותה.** בשבוע הראשון אין
     * בסיס דופק, אין תנוחה נלמדת, ואין היסטוריית נפילות — כלומר רוב
     * המשקלים לא יכולים לתרום. חלוקה במקסימום התיאורטי הייתה גורמת
     * ל"ציון 30" בשבוע הראשון ול"ציון 30" בחודש הבא להיות שני דברים
     * שונים לגמרי, **וסף שנקבע על הראשון היה שגוי על השני.**
     */
    data class Result(
        val gatesPassed: Boolean,
        val blockedBy: String?,
        val score: Int,
        val available: Int,
        val parts: String,
        /**
         * מאיזו דרגה בסולם הגיע הבסיס: `hour` / `block` / `all` / `none`.
         *
         * ⚠️ **בלי זה, ציון שנשען על "ממוצע כל השעות" נראה בניתוח בדיוק
         * כמו ציון שנשען על תא שעתי מלא.** הערך נרשם ללוג האירועים כבר
         * היום, אבל הלוג מוגבל ל-500 שורות — ואילו הניתוח שמצב-הצל קיים
         * בשבילו רץ על אלפי רשומות מהייצוא.
         */
        val baselineSource: String = "none"
    )

    /**
     * ⚠️ שערים ראשונים, ורק אז משקלים. `blockedBy` נרשם כדי שאפשר יהיה
     * לדעת **איזה** שער עצר — בלי זה "לא הוצע" הוא תיבה שחורה.
     */
    fun evaluate(context: Context, r: SampleStore.Record): Result {
        // ⚠️ **השער נבדק, אבל החישוב ממשיך בכל מקרה.**
        //
        // עד היום הייתה כאן יציאה מוקדמת: שער שנסגר החזיר `score = 0`
        // ו-`available = 0` בלי לחשב כלום. כלומר הציון לא "לא נרשם" —
        // הוא **לא היה קיים**.
        //
        // ומכאן שכל שאלה על איכות הגלאי לא ניתנת למענה: "כמה דקות לפני
        // השיא היינו מזהים" נמדד על רשומות שרובן חסומות, ומה שהיינו
        // מודדים בפועל הוא **את השערים ולא את הגלאי**.
        //
        // מצב-צל אמור לצלם הכל. במקום זה הוא הפסיק לחשוב בדיוק ברגע
        // שהחליט לשתוק — וזה הדפוס שחוזר כאן: כישלון שנרשם כהיעדר.
        val blocked = gateBlock(context, r)

        val parts = StringBuilder()
        var score = 0
        var available = 0

        /**
         * `fraction = null` פירושו **האות לא ניתן לחישוב עכשיו**, וזה שונה
         * מ-0 שפירושו "נבדק ולא מתקיים". רק אות שניתן לחישוב נכנס
         * ל-[available].
         */
        fun add(name: String, weight: Int, fraction: Double?) {
            if (fraction == null) return
            available += weight
            val f = fraction.coerceIn(0.0, 1.0)
            if (f <= 0.0) return
            val v = (weight * f).toInt()
            if (v <= 0) return
            score += v
            parts.append("$name=$v,")
        }

        // --- חוסר תנועה: מטפס עם הזמן ---
        // ⚠️ `stillMs = -1` פירושו שלא נראה אף צעד מאז שהשירות עלה. זה
        // כנראה חוסר-תנועה מוחלט, אבל זה יכול גם להיות מונה-צעדים תקוע —
        // ולכן האות מסומן כלא-זמין ולא כ"דומם מאוד". ניחוש כאן היה נכנס
        // ישירות לציון.
        add(
            "still", W_STILL,
            if (r.stillMs >= 0)
                ramp(r.stillMs.toDouble(), STILL_MIN_MS.toDouble(), STILL_FULL_MS.toDouble())
            else null
        )

        // --- ⚠️ תנועה עדינה **בלי צעדים** ---
        //
        // ⚠️ **זה היה הפוך, והנתונים הוכיחו את זה.** האות נקרא `low_motion`
        // ותגמל על **מיעוט** תנועה, בהנחה ששכיבה דוממת היא התרחיש. אבל
        // בנפילה של 2026-08-02 בשעה 18:30 התנועה העדינה עלתה מ-4 ל-78
        // בשעה שהצעדים נשארו אפס — כלומר האות הכי רלוונטי שקלל **נגד**
        // הסיכון, והציון ירד מ-28 ל-16 ככל שהנפילה התקרבה.
        //
        // ההיגיון הנכון הוא אותו היגיון של כל המוצר: **אות בלי הסבר.**
        // צעדים מסבירים תנועה; תנועה עדינה בלי צעדים אינה מוסברת.
        add(
            "motion_no_steps", W_MOTION_NO_STEPS,
            if (r.motion >= 0 && r.steps == 0) ramp(r.motion.toDouble(), 10.0, 70.0) else null
        )

        // --- תנוחה: נמדדת כשונות מהמצב הרגיל שלו, לא כסיווג מוחלט ---
        add("posture", W_POSTURE, Posture.deviationFromUsual(context, r))
        add("posture_q", W_POSTURE_MATCHES, if (Posture.matchesDeclared(context, r)) 1.0 else null)

        // --- דופק מעל הרגיל שלו בשעה הזאת, בפרופורציה ---
        val level = Baseline.levelFor(context)
        add(
            "pulse_above", W_PULSE_ABOVE,
            if (level != null && r.bpm > 0) Baseline.deviation(level, r.bpm) / DEV_FULL else null
        )

        // --- הדופק מטפס בתוך הפרץ עצמו ---
        add("pulse_rising", W_PULSE_RISING, if (r.bpm > 0) r.bpmTrend / 6.0 else null)

        // --- שעה שהצהיר עליה ---
        //
        // ⚠️ **עם דעיכה בקצוות ולא כן/לא.** ב-18:30 השעה עברה מ-17 ל-18,
        // ו-6 נקודות נעלמו בבת אחת בגלל גבול שרירותי — בדיוק בשעה שבה
        // הנפילה קרתה. "צהריים" נגמר ב-17:59 זה חיתוך של לוח שנה, לא של
        // התנהגות אדם.
        add(
            "hour_q", W_HOUR_MATCHES,
            if (RiskContext.hasDeclaredHours(context))
                RiskContext.hourProximityToDeclared(context, r.hourOfDay)
            else null
        )

        // --- מרחק מהנפילה האחרונה ביחס לקצב שלו ---
        add("since_fall", W_DAYS_SINCE_FALL, RiskContext.daysSinceFallFraction(context))

        // --- כמה התגברויות היום ביחס לרגיל שלו ---
        add("today_vs_usual", W_TODAY_VS_USUAL, RiskContext.todayVsUsualFraction(context))

        // --- במקום הרגיל שלו ---
        add(
            "place", W_USUAL_PLACE,
            PlaceTracker.atUsualPlace(context)?.let { if (it) 1.0 else 0.0 }
        )

        // --- ⚠️ במקום שבו כבר נפל בעבר ---
        //
        // זהו אות **חזק יותר** מ"במקום הרגיל", כי הוא לא שואל אם הוא בבית
        // אלא אם הוא **בדיוק שם**. הוא נלמד לבד מדיווחי נפילה ודורש שתיים
        // לפחות מאותו מקום — נפילה אחת אינה דפוס.
        add(
            "known_place", W_KNOWN_FALL_PLACE,
            RoomPrint.capture(context, LastMagnitude.value)
                ?.let { RoomPrint.matchesKnownFallPlace(context, it) }
        )

        return Result(
            gatesPassed = blocked == null,
            blockedBy = blocked,
            score = score,
            available = available,
            parts = parts.toString().trimEnd(','),
            baselineSource = level?.source ?: "none"
        )
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
        // ⚠️ **לא "שגוי", "לא רלוונטי".** בשקט ממושך — שינה, או מצב זהה
        // לה תפקודית — אין מה להציע: אי-אפשר להציע "לצאת קצת אוויר"
        // למי שישן. הציון עצמו עדיין מחושב ונשמר (ראו למטה), כי הנתון
        // הפיזיולוגי עצמו יקר; רק ההצעה נחסמת.
        if (Baseline.isProlongedStill(r)) return "prolonged_still"
        if (!OfferBudget.hasRoomToday(context)) return "daily_budget"
        if (!OfferBudget.enoughTimeSinceLast(context)) return "too_soon"
        if (OfferBudget.inCooldownAfterReport(context)) return "cooldown"
        // ⚠️ **הכלל של נבו: סט אחד בלבד.** אם כבר רץ מנגנון חזק יותר —
        // אחרי נפילה, או אחרי סימון מצב-רוח — הצעה תמימה מהגלאי היא בדיוק
        // הסט השני שאסור שיישלח. המנגנון החזק בעלים על הרגע.
        if (Escalation.levelNow(context) >= Escalation.Level.RISK_A) return "escalated"
        return null
    }
}
