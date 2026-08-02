package com.iluy.imutest

import android.content.Context
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * תנוחה — **כשונות מהרגיל שלו, לא כסיווג מוחלט.**
 *
 * ⚠️ **למה לא "שוכב / יושב".** השעון על פרק היד ולא על הגוף. אדם שוכב
 * יכול להחזיק יד באוויר, ואדם עומד יכול להניח יד אופקית. סיווג תנוחה
 * מראש היה סף אוכלוסייה בתחפושת — בדיוק כמו "+15 פעימות" שנפסל.
 *
 * לכן: נלמד **הכיוון הממוצע של פרק היד במנוחה** אצל המשתמש הזה, וכל
 * סטייה ממנו נמדדת ביחס אליו. המערכת לא צריכה לדעת ששוכבים; היא צריכה
 * לדעת שזה **לא הכיוון הרגיל**.
 */
object Posture {

    private const val PREFS_NAME = "iluy_posture"
    private const val KEEP = 60

    /**
     * מעדכן את הכיוון הרגיל מרשומת מנוחה.
     *
     * ⚠️ **רק ממנוחה.** כיוון שנלמד מתנועה הוא ממוצע של הכל, וממוצע של
     * הכל אינו תנוחה של כלום.
     */
    fun learn(context: Context, r: SampleStore.Record) {
        if (!Baseline.isResting(r)) return
        if (r.gravityX == 0 && r.gravityY == 0 && r.gravityZ == 0) return
        val p = context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
        val n = p.getInt("n", 0).coerceAtMost(KEEP)
        // ממוצע נע: כל דגימה מזיזה את הכיוון קצת. מתכנס לכיוון השכיח.
        val w = 1.0 / (n + 1)
        p.edit()
            .putFloat("x", ((p.getFloat("x", 0f) * (1 - w)) + r.gravityX * w).toFloat())
            .putFloat("y", ((p.getFloat("y", 0f) * (1 - w)) + r.gravityY * w).toFloat())
            .putFloat("z", ((p.getFloat("z", 0f) * (1 - w)) + r.gravityZ * w).toFloat())
            .putInt("n", n + 1)
            .apply()
    }

    /**
     * כמה הכיוון הנוכחי שונה מהרגיל שלו, בין 0 ל-1. `null` עד שיש
     * מספיק דגימות ללמוד מהן.
     */
    fun deviationFromUsual(context: Context, r: SampleStore.Record): Double? {
        val p = context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
        if (p.getInt("n", 0) < 10) return null
        if (r.gravityX == 0 && r.gravityY == 0 && r.gravityZ == 0) return null
        val dx = r.gravityX - p.getFloat("x", 0f)
        val dy = r.gravityY - p.getFloat("y", 0f)
        val dz = r.gravityZ - p.getFloat("z", 0f)
        val d = sqrt((dx * dx + dy * dy + dz * dz).toDouble())
        // 100 יחידות ≈ סיבוב של 90 מעלות בקירוב, בסקאלה של m/s²×10.
        return (d / 100.0).coerceIn(0.0, 1.0)
    }

    /**
     * האם התנוחה תואמת למה שהצהיר בשאלון.
     *
     * ⚠️ **מימוש חלקי ומסומן ככזה.** התשובה בשאלון היא מילה ("שוכב"),
     * והכיוון הוא וקטור — ואין ביניהם מיפוי בלי לכייל פעם אחת על המשתמש
     * עצמו. עד שיהיה, מוחזר `false` תמיד, כלומר האות פשוט לא תורם.
     *
     * **מוטב אות שקט מאות שמנחש.**
     */
    fun matchesDeclared(context: Context, r: SampleStore.Record): Boolean {
        val declared = LocalStore.getSingleChoice(context, LocalStore.KEY_Q3_POSITION)
        if (declared.isBlank()) return false
        return false
    }
}
