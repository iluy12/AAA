package com.iluy.imutest

import android.content.Context

/**
 * פירוק צריכת הסוללה לשעה, לפי רכיבים.
 *
 * ## ⚠️ למה מספר אחד לא מספיק
 *
 * יש לנו שתי מדידות סוללה: 3.3% לשעה בלילה שקט, ו-5.2% בערב של בדיקות.
 * **שתיהן חסרות ערך להחלטה** — הראשונה נמדדה על תצורה שכבר לא קיימת
 * (הפרץ התארך מ-45 ל-69 שניות מאז), והשנייה כוללת ערב שלם של הדלקות
 * מסך שלא מייצג שום שימוש רגיל.
 *
 * ומעבר לזה: **מספר אחד לא אומר ממה הוא מורכב.** אם יוצא 6% לשעה, אי-
 * אפשר לדעת אם להוריד מדידות, לקצר חלונות, או לוותר על משהו אחר. עם
 * הפירוק אפשר לסחור; בלעדיו רק לדעת שזה לא מספיק.
 *
 * ## מה נספר
 *
 * - **מדידות** — הצרכן הידוע הגדול ביותר, חיישן אופטי דלוק 45-69 שניות
 * - **שניות מסך** — הצרכן הגדול ביותר בכלל, ובלתי-צפוי לגמרי
 * - **חלונות חמים** — עוד לא קיימים. הספירה מוכנה מראש כדי שהמדידה
 *   הראשונה איתם תהיה בת-השוואה למדידה בלעדיהם
 * - **קליטות WiFi** — ⚠️ **לא סריקות.** אנחנו קוראים `scanResults` בלבד
 *   ואף פעם לא קוראים ל-`startScan`, כלומר נטפלים על סריקות שהמערכת
 *   עושה ממילא. הספירה כאן היא של קליטות מוצלחות, לא של צריכה שלנו
 *
 * ## ⚠️ שעה ולא יום
 *
 * דוח יומי אחד היה מסתיר בדיוק את מה שמחפשים: הלילה זול והערב יקר,
 * וממוצע של שניהם אינו מתאר אף אחד מהם. שורה לשעה מאפשרת לראות **מתי**
 * הסוללה נגמרת, ולא רק שהיא נגמרת.
 */
object PowerLog {

    private const val PREFS_NAME = "iluy_power"
    private const val HOUR_MS = 60 * 60 * 1000L

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun countBurst(context: Context) = bump(context, "bursts")
    fun countHotWindow(context: Context) = bump(context, "hot")
    fun countWifiHit(context: Context) = bump(context, "wifi")

    private fun bump(context: Context, key: String) {
        prefs(context).edit().putInt(key, prefs(context).getInt(key, 0) + 1).apply()
    }

    /** נצבר ב-[ScreenWakeWatcher] — הצרכן הגדול ביותר, ולכן נמדד בנפרד. */
    fun addScreenMs(context: Context, ms: Long) {
        prefs(context).edit().putLong("screen_ms", prefs(context).getLong("screen_ms", 0L) + ms)
            .apply()
    }

    /**
     * נקרא בסוף כל פרץ. פולט שורה אם עברה שעה מאז הקודמת.
     *
     * ⚠️ **נתלה על הפרץ ולא על אזעקה משלו.** אזעקה נוספת הייתה מעירה את
     * המעבד רק כדי לרשום כמה סוללה נשארה — כלומר המדידה עצמה הייתה
     * משנה את מה שהיא מודדת.
     */
    fun tick(context: Context, battery: Int, charging: Boolean) {
        val p = prefs(context)
        val now = System.currentTimeMillis()
        val last = p.getLong("last_ms", 0L)

        if (last == 0L) {
            p.edit().putLong("last_ms", now).putInt("last_batt", battery).apply()
            return
        }
        if (now - last < HOUR_MS) return

        val minutes = (now - last) / 60000.0
        val drop = p.getInt("last_batt", battery) - battery
        val perHour = if (minutes > 0) drop * 60.0 / minutes else 0.0

        EventLog.log(
            context, "POWER",
            "hour;batt=$battery;drop=$drop;per_hour=${"%.1f".format(perHour)};" +
                "charging=$charging;minutes=${minutes.toInt()};" +
                "bursts=${p.getInt("bursts", 0)};hot=${p.getInt("hot", 0)};" +
                "wifi_hits=${p.getInt("wifi", 0)};screen_s=${p.getLong("screen_ms", 0L) / 1000}"
        )

        p.edit()
            .putLong("last_ms", now).putInt("last_batt", battery)
            .putInt("bursts", 0).putInt("hot", 0).putInt("wifi", 0)
            .putLong("screen_ms", 0L)
            .apply()
    }
}
