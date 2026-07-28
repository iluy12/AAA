package com.iluy.imutest

/**
 * זיהוי ניעור-יד מכוון — מחליף את זיהוי-ההקשה (TapClusterDetector), שלא
 * הצליח להתכנס אחרי ארבעה סבבי-כיוונון בשטח.
 *
 * ## למה ניעור ולא הקשה
 *
 * שתי סיבות מדידות, לא תיאורטיות:
 *
 * 1. **כמות מידע.** תקרת-החומרה היא ~25Hz (נמדד: hz_actual). הקשה נמשכת
 *    1-2 מדגמים — כמעט אפס מידע להחליט עליו. ניעור של שנייה נותן ~25
 *    מדגמים.
 *
 * 2. **חפיפה בנתונים.** הקשה טבעית נמדדה 15-17, והליכה רגילה 12-20 —
 *    הם יושבים אחד בתוך השני. שום סף-עוצמה לא יכול להפריד ביניהם, ולכן
 *    הכיוונון לא התכנס. ניעור נבדל בממד שבו *אין* חפיפה: תדירות. הליכה
 *    מתנדנדת ב-~2Hz; ניעור מכוון ב-4-7Hz.
 *
 * לכן התנאי כאן הוא לא "כמה חזק" אלא "כמה החלפות-כיוון בחלון-זמן" —
 * וזה מה שנותן מרווח-ביטחון אמיתי בין דיווח לרעש.
 *
 * ## אין כיול
 *
 * במכוון. תדירות-ניעור אינה תלוית-אדם כמו עוצמת-הקשה או תנוחת-פרק-יד,
 * ולכן אין ערך-ייחוס אישי, אין סף אישי, ואין בעיה מעגלית של "צריך שער
 * כדי לכייל את השער". פחות מנגנונים = פחות מקומות להתקלקל בשקט.
 *
 * ## שמרנות
 *
 * הערכים כאן מכוונים לאפס-התרעות-שווא על חשבון דרישת-מאמץ ברורה מהמשתמש.
 * ניעור נמרץ עובר אותם בקלות; כמעט שום תנועה יומיומית לא.
 */
class ShakeDetector(
    private val onLog: (tag: String, detail: String) -> Unit = { _, _ -> },
    private val onShakeDetected: (reversals: Int, peak: Double) -> Unit
) {
    private val magnitudes = ArrayDeque<Double>()
    private val sampleTimes = ArrayDeque<Long>()
    private val reversalTimes = ArrayDeque<Long>()

    /** 1 = בשלב "גבוה", -1 = בשלב "נמוך", 0 = עוד לא נקבע */
    private var phase = 0
    private var lastFiredMs = 0L

    fun onSample(x: Float, y: Float, z: Float, now: Long) {
        val magnitude = Math.sqrt((x * x + y * y + z * z).toDouble())

        magnitudes.addLast(magnitude)
        sampleTimes.addLast(now)
        while (sampleTimes.isNotEmpty() && now - sampleTimes.first() > DebugConfig.SHAKE_WINDOW_MS) {
            sampleTimes.removeFirst()
            magnitudes.removeFirst()
        }
        while (reversalTimes.isNotEmpty() && now - reversalTimes.first() > DebugConfig.SHAKE_WINDOW_MS) {
            reversalTimes.removeFirst()
        }

        // היסטרזיס: בין הספים נשארים בשלב הנוכחי, כדי שרעש קטן סביב
        // הסף לא ייספר כהחלפת-כיוון.
        val newPhase = when {
            magnitude >= DebugConfig.SHAKE_HIGH_THRESHOLD -> 1
            magnitude <= DebugConfig.SHAKE_LOW_THRESHOLD -> -1
            else -> phase
        }
        if (phase != 0 && newPhase != phase) {
            reversalTimes.addLast(now)
        }
        phase = newPhase

        if (now - lastFiredMs < DebugConfig.SHAKE_COOLDOWN_MS) return
        if (magnitudes.isEmpty()) return

        val reversals = reversalTimes.size
        if (reversals < DebugConfig.SHAKE_MIN_REVERSALS) return

        val peak = magnitudes.max()
        val trough = magnitudes.min()
        val range = peak - trough

        // שתי בדיקות-משרעת אחרי בדיקת-התדירות: מסננות תנודה מהירה אך
        // חלשה (למשל נסיעה ברכב על כביש משובש).
        if (peak < DebugConfig.SHAKE_MIN_PEAK) {
            onLog(
                "DEBUG",
                "shake_near_miss_weak_peak;reversals=$reversals;peak=${"%.1f".format(peak)}"
            )
            return
        }
        if (range < DebugConfig.SHAKE_MIN_RANGE) {
            onLog(
                "DEBUG",
                "shake_near_miss_small_range;reversals=$reversals;range=${"%.1f".format(range)}"
            )
            return
        }

        lastFiredMs = now
        reversalTimes.clear()
        magnitudes.clear()
        sampleTimes.clear()
        phase = 0

        onLog(
            "INFO",
            "shake_detected;reversals=$reversals;peak=${"%.1f".format(peak)};" +
                "range=${"%.1f".format(range)}"
        )
        onShakeDetected(reversals, peak)
    }

    /**
     * לשלב-התרגול: כמה החלפות-כיוון נצברו כרגע בחלון. מאפשר להראות
     * התקדמות חיה על המסך במקום "או שנקלט או שלא".
     */
    fun currentReversals(): Int = reversalTimes.size

    fun currentPeak(): Double = magnitudes.maxOrNull() ?: 0.0

    fun reset() {
        magnitudes.clear()
        sampleTimes.clear()
        reversalTimes.clear()
        phase = 0
        lastFiredMs = 0L
    }
}
