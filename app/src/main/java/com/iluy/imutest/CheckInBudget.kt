package com.iluy.imutest

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * תקציב נפרד למסך הכיול, בכוונה מבודד מ-[OfferBudget].
 *
 * ⚠️ **זה לא ההצעה העדינה, וזה לא צריך לחלוק איתה תקציב.** ההצעה
 * העדינה עדיין כבויה לגמרי (`RiskScore.SILENT_MODE`). זה מסך שאלה,
 * במצב כיול מוגדר בזמן, ותקציב משלו מבטיח שהוא לא יזלוג ולא יוזל
 * על ידי מנגנון אחר שישתנה בעתיד.
 */
object CheckInBudget {

    private const val PREFS_NAME = "iluy_checkin_budget"

    /**
     * ⚠️ שני המספרים האלה, ולא אחד.
     *
     * "לא פעמיים באותן 10 דקות, ולא יותר מ-5 ביום גם אם הדופק משתולל" —
     * קירור מונע ספאם ברצף, תקרה מונעת יום גרוע אחד מלייצר 20 שאלות.
     */
    private const val COOLDOWN_MS = 10 * 60 * 1000L
    private const val DAILY_CAP = 5

    private val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private fun todayKey() = dayFmt.format(Date())

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun hasRoom(context: Context): Boolean {
        val p = prefs(context)
        val now = System.currentTimeMillis()
        if (now - p.getLong("last_ms", 0L) < COOLDOWN_MS) return false
        return p.getInt("count_${todayKey()}", 0) < DAILY_CAP
    }

    fun record(context: Context) {
        val p = prefs(context)
        val key = "count_${todayKey()}"
        p.edit()
            .putLong("last_ms", System.currentTimeMillis())
            .putInt(key, p.getInt(key, 0) + 1)
            .apply()
    }

    fun describe(context: Context): String =
        "used=${prefs(context).getInt("count_${todayKey()}", 0)}/$DAILY_CAP"
}
