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

    enum class Outcome { IGNORED_COOLDOWN, ACKNOWLEDGED, ESCALATED }

    /**
     * @param source תיאור לצורך לוג ולתג-הדיבאג, למשל "✕ על מסך השעון".
     * @param launchUi האם להציג מסך (אישור קל / RISK A). מסך-השעון מציג
     *        משוב משלו במקום, ולכן מעביר false.
     */
    fun record(context: Context, source: String, launchUi: Boolean = true): Outcome {
        val now = System.currentTimeMillis()

        if (now < LocalStore.getCooldownUntil(context)) {
            EventLog.log(context, "INFO", "report_ignored_cooldown_active;source=$source")
            return Outcome.IGNORED_COOLDOWN
        }

        val standbyUntil = LocalStore.getTapStandbyUntil(context)

        return if (now < standbyUntil) {
            // דיווח שני באותה שעה — הסלמה לניסוח המחמיר הקיים
            EventLog.log(context, "TRIGGER", "report_second_in_hour;source=$source")
            if (launchUi) {
                RiskFlowActivity.launch(
                    context,
                    source = source,
                    variant = RiskFlowActivity.VARIANT_SECOND_TAP_IN_HOUR
                )
            }
            Outcome.ESCALATED
        } else {
            LocalStore.setTapStandbyUntil(context, now + DebugConfig.STANDBY_DURATION_MS)
            EventLog.log(context, "TRIGGER", "report_first_in_hour;source=$source")
            if (launchUi) {
                TapAcknowledgedActivity.launch(context, source = source)
            }
            Outcome.ACKNOWLEDGED
        }
    }
}
