package com.iluy.imutest

import android.content.Context
import android.net.wifi.WifiManager
import kotlin.math.abs

/**
 * טביעת אצבע של מקום, ברזולוציה של **חדר**.
 *
 * ## ⚠️ למה זה בכלל אפשרי — תיקון לקביעה קודמת שלי
 *
 * אמרתי שחדר אינו ניתן לחישה בשום דיוק. **זה נכון לגבי GPS ושגוי
 * כהצהרה כללית.** GPS מדויק למאות מטרים ובית שלם נכנס בטעות שלו — אבל
 * יש אותות אחרים שמשתנים תוך מטרים.
 *
 * ## שלושה מקורות, ולכל אחד חולשה שהאחרים מכסים
 *
 * | מקור | דיוק | מתי נכשל |
 * |---|---|---|
 * | **WiFi** | הגבוה — מטרים | כשאין רשתות בסביבה |
 * | **מגנטומטר** | טוב בתוך מבנה | ליד ברזל נייד, או כשהיד מסתובבת |
 * | **מיקום גס** | מאות מטרים | בתוך מבנים |
 *
 * ⚠️ **המגנטומטר הוא התוספת שנבו הזכיר ואני שכחתי לגמרי.** לשדה המגנטי
 * בתוך מבנה יש חתימה שמשתנה בין חדרים בגלל ברזל ומבנה, והוא **עובד גם
 * בלי רשתות ובלי קליטה** — כלומר מכסה בדיוק את המקרה שבו WiFi חסר תועלת.
 *
 * ## ולמה לא שואלים אותו "איזה חדר"
 *
 * לא צריך. בכל דיווח נפילה נשמרת טביעת האצבע של אותו רגע, ואחרי כמה
 * דיווחים המקום נלמד לבד. **שאלה כאן הייתה חיכוך על משהו שנמדד ממילא** —
 * והכלל הוא ששואלים רק מה שאי-אפשר לחוש.
 */
object RoomPrint {

    private const val PREFS_NAME = "iluy_rooms"

    /** מעל זה שתי טביעות נחשבות לאותו מקום. 0..1. */
    const val MATCH_THRESHOLD = 0.72

    /**
     * טביעה אחת: עוצמות WiFi לפי מזהה רשת, ועוצמת השדה המגנטי.
     *
     * ⚠️ **מזהי הרשתות עוברים גיבוב ולא נשמרים כטקסט.** שם רשת מזהה בית
     * ומשפחה, והנתונים האלה נשמרים לצד דיווחי נפילה — כלומר בדיוק המידע
     * הרגיש ביותר במוצר. הגיבוב מאפשר להשוות בלי לדעת.
     */
    data class Print(val wifi: Map<Int, Int>, val magnitude: Int) {
        fun serialise(): String =
            wifi.entries.joinToString("|") { "${it.key}:${it.value}" } + "#" + magnitude

        companion object {
            fun parse(s: String?): Print? {
                if (s.isNullOrBlank()) return null
                val parts = s.split("#")
                val wifi = parts[0].split("|").mapNotNull {
                    val kv = it.split(":")
                    if (kv.size == 2) (kv[0].toIntOrNull() ?: return@mapNotNull null) to
                        (kv[1].toIntOrNull() ?: return@mapNotNull null) else null
                }.toMap()
                val mag = parts.getOrNull(1)?.toIntOrNull() ?: 0
                if (wifi.isEmpty() && mag == 0) return null
                return Print(wifi, mag)
            }
        }
    }

    /**
     * טביעה של הרגע הנוכחי, או null אם אין ממה לבנות.
     *
     * `magnitude` מגיע מבחוץ כי המגנטומטר נקרא בתוך פרץ החיישנים —
     * ⚠️ ובמכשיר הזה כבר התברר שהדלקת מקלט מפריעה לחיישנים אחרים, ולכן
     * אין קריאה עצמאית כאן.
     */
    fun capture(context: Context, magnitude: Int): Print? {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val scans = runCatching { wm?.scanResults }.getOrNull().orEmpty()
        val wifi = scans.associate { it.BSSID.hashCode() to it.level }
        if (wifi.isEmpty() && magnitude <= 0) return null
        return Print(wifi, magnitude)
    }

    /**
     * כמה שתי טביעות דומות, 0..1.
     *
     * ⚠️ **רק רשתות שנראות בשתיהן נספרות.** רשת שנעלמה יכולה להיות שכן
     * שכיבה את הנתב, לא מעבר לחדר אחר — ולהעניש על היעדרות היה הופך כל
     * טביעה ללא-יציבה תוך יומיים.
     */
    fun similarity(a: Print, b: Print): Double {
        val shared = a.wifi.keys.intersect(b.wifi.keys)
        val wifiScore = if (shared.isEmpty()) null else {
            // ממוצע הקרבה בעוצמות: 10dBm הפרש ≈ אותו מקום, 40 ≈ אחר.
            shared.map { k ->
                (1.0 - abs(a.wifi[k]!! - b.wifi[k]!!) / 40.0).coerceIn(0.0, 1.0)
            }.average() * coverage(a, b, shared.size)
        }

        val magScore = if (a.magnitude <= 0 || b.magnitude <= 0) null else
            (1.0 - abs(a.magnitude - b.magnitude) / 25.0).coerceIn(0.0, 1.0)

        return when {
            wifiScore != null && magScore != null -> wifiScore * 0.75 + magScore * 0.25
            wifiScore != null -> wifiScore
            magScore != null -> magScore
            else -> 0.0
        }
    }

    /** כמה מהרשתות משותפות. שתי רשתות משותפות מתוך עשרים אינן "אותו מקום". */
    private fun coverage(a: Print, b: Print, sharedCount: Int): Double {
        val smaller = minOf(a.wifi.size, b.wifi.size)
        if (smaller == 0) return 0.0
        return (sharedCount.toDouble() / smaller).coerceIn(0.0, 1.0)
    }

    /** שומר טביעה שנלקחה ברגע דיווח נפילה. */
    fun rememberFallLocation(context: Context, print: Print) {
        val p = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val n = p.getInt("n", 0)
        p.edit().putString("fall_$n", print.serialise()).putInt("n", n + 1).apply()
        EventLog.log(context, "ROOM", "fall_print_saved;total=${n + 1};nets=${print.wifi.size}")
    }

    /**
     * האם המקום הנוכחי דומה למקום שבו כבר דווחו נפילות, וכמה.
     *
     * ⚠️ **דורש שתי נפילות לפחות מאותו מקום.** נפילה אחת אינה דפוס — היא
     * יכולה להיות המקום שבו הוא במקרה היה. שתיים כבר אומרות משהו.
     */
    fun matchesKnownFallPlace(context: Context, now: Print): Double? {
        val p = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val n = p.getInt("n", 0)
        if (n < 2) return null
        val scores = (0 until n).mapNotNull { Print.parse(p.getString("fall_$it", null)) }
            .map { similarity(now, it) }
        if (scores.isEmpty()) return null
        val matches = scores.count { it >= MATCH_THRESHOLD }
        if (matches < 2) return null
        return scores.max()
    }
}
