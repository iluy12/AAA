package com.iluy.imutest

import android.content.Context

/**
 * רישום "התגברות" — נקודה אחת שדרכה עוברים כל מקורות-הדיווח.
 *
 * נבנה כדי שלוגיקת ההסלמה (היכון שעתי, cooldown) לא תשוכפל בין מסך-השעון
 * לבין השירות. שכפול לוגיקה כזו הוא בדיוק מה שגרם קודם לכך ששכבת-הגנה
 * נעלמה בשקט בלי שאיש שם לב.
 */
object OvercomingReporter {

    enum class Outcome {
        IGNORED_COOLDOWN,
        /** התגברות רגילה — הודעת חיזוק קצרה בלבד. */
        ACKNOWLEDGED,
        /** הגיע לסף האישי שדיווח עליו בשאלון — חיזוק מורחב. */
        AT_PERSONAL_THRESHOLD,
        /** הכפיל את הסף — מוצע חיוג למלווה, לא מחויג אוטומטית. */
        OFFER_MENTOR
    }

    /** הטקסט שהמסך אמור להציג. נגזר כאן כדי שהקוראים לא ימציאו ניסוחים. */
    data class Report(val outcome: Outcome, val message: String, val todayCount: Int)

    /**
     * @param source תיאור לצורך לוג ולתג-הדיבאג, למשל "✕ על מסך השעון".
     * @param launchUi האם להציג מסך (אישור קל / RISK A). מסך-השעון מציג
     *        משוב משלו במקום, ולכן מעביר false.
     */
    fun record(context: Context, source: String, launchUi: Boolean = true): Report {
        val now = System.currentTimeMillis()

        if (now < LocalStore.getCooldownUntil(context)) {
            EventLog.log(context, "INFO", "report_ignored_cooldown_active;source=$source")
            return Report(Outcome.IGNORED_COOLDOWN, Encouragements.ordinary(), 0)
        }

        // נרשם ללוח לפני כל שאר הלוגיקה: התגברות נספרת גם כשהיא מסלימה,
        // כי גם היא התגברות. (סוכם עם נבו — לא רק הראשונה בשעה נספרת.)
        val todayCount = CalendarStore.recordOvercoming(context)
        // ⚠️ פותח את שער הקירור. בלי זה המערכת יכולה להציע שנייה אחרי
        // שהוא בעצמו סימן ✕ — כלומר בדיוק כשהוא כבר טיפל בזה.
        OfferBudget.recordUserReport(context, "overcoming")
        val threshold = LocalStore.getPersonalThreshold(context)
        EventLog.log(
            context, "INFO",
            "overcoming_recorded;today_total=$todayCount;personal_threshold=$threshold"
        )

        // ---- הסלמה לפי הסף האישי (חלון = יום קלנדרי) ----
        //
        // המונה לא מתאפס בסף: הוא ממשיך לרוץ לעבר ההכפלה, כי מי שכבר
        // עבר את מה שהוא מחזיק בדרך כלל נמצא ביום חריג — ושם צריך יותר
        // ליווי, לא פחות.
        if (todayCount >= threshold * 2) {
            EventLog.log(
                context, "TRIGGER",
                "personal_threshold_doubled;today=$todayCount;threshold=$threshold;source=$source"
            )
            if (launchUi) {
                RiskFlowActivity.launch(
                    context, source = source,
                    variant = RiskFlowActivity.VARIANT_SECOND_TAP_IN_HOUR
                )
            }
            return Report(Outcome.OFFER_MENTOR, Encouragements.atThreshold(), todayCount)
        }

        if (todayCount >= threshold) {
            // יום חריג ביחס לעצמו — מעל הממוצע האישי — מקבל רמיזת-מלווה
            // בוודאות. ביום רגיל היא מופיעה רק בערך בשליש מהפעמים, כדי
            // שלא תישחק ותיבלע.
            val average = CalendarStore.averageActiveDayOvercomings(context)
            val roughDay = average > 0 && todayCount > average * 1.5
            // ⚠️ פעם מארבע ולא משלוש. הרמיזה הפכה **לשאלה** שדורשת תשובה,
            // ולכן היא מטרידה יותר מטקסט שנבלע. ביום חריג — תמיד.
            val hint = roughDay || (0..3).random() == 0

            EventLog.log(
                context, "TRIGGER",
                "personal_threshold_reached;today=$todayCount;threshold=$threshold;" +
                    "avg=${"%.1f".format(average)};rough_day=$roughDay;ask_mentor=$hint"
            )

            // ⚠️ **מחושב פעם אחת.** קודם `atThreshold(hint)` נקרא פעמיים —
            // אחת למסך ואחת לדוח — ומכיוון שהוא בוחר טקסט אקראי, המשתמש
            // ראה משפט אחד ובלוג נרשם אחר. באג שקט שהיה מקשה על כל ניתוח
            // בדיעבד.
            val message = Encouragements.atThreshold()
            val question = if (hint) Encouragements.mentorQuestion() else null

            if (launchUi) {
                TapAcknowledgedActivity.launch(
                    context, source = source,
                    message = message,
                    askAfter = question
                )
            }
            return Report(Outcome.AT_PERSONAL_THRESHOLD, message, todayCount)
        }

        // ---- התגברות רגילה ----
        //
        // הכלל השעתי ("דיווח שני באותה שעה מסלים") הוסר. הוא היה מספר
        // שהמצאתי, והוא **הקדים תמיד את הסף האישי ולכן ניטרל אותו**:
        // בלוג נראתה הסלמה בהתגברות מספר 2 בזמן שהסף שנבו דיווח עליו
        // בשאלה 10 היה 5. שתי מערכות הסלמה מתחרות זו בזו, והאגרסיבית
        // מביניהן ניצחה — למרות שהיא חסרת המשמעות מבין השתיים.
        //
        // מכאן ההסלמה נקבעת רק לפי הסף שהמשתמש דיווח עליו על עצמו.
        EventLog.log(context, "TRIGGER", "report_acknowledged;source=$source")
        val message = Encouragements.ordinary()
        if (launchUi) {
            TapAcknowledgedActivity.launch(context, source = source, message = message)
        }
        return Report(Outcome.ACKNOWLEDGED, message, todayCount)
    }
}
