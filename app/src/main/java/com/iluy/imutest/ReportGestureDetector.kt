package com.iluy.imutest

/**
 * מחוות-הדיווח: **ניעור ואז שתי נקישות**, רצף בן שני שלבים.
 *
 * ## למה רצף ולא מחווה אחת
 *
 * כל אחד מהשניים לבדו נכשל, מסיבה אחרת:
 *
 * - **נקישות לבדן** — נמדד בשטח שהקשה טבעית (15-17) יושבת בתוך טווח
 *   ההליכה (12-20). הן חופפות בנתונים עצמם, ולכן שום סף לא הפריד
 *   ביניהן. ארבעה סבבי-כיוונון לא התכנסו.
 * - **ניעור לבדו** — נבדל היטב מהליכה בתדירות, אבל מתנגש עם ניעור מים
 *   מהידיים אחרי נטילת ידיים, שקהל היעד מבצע כמה פעמים ביום.
 *
 * הרצף פותר את שניהם: אף אחד לא מנער ידיים ואז מקיש פעמיים על השעון.
 *
 * ## למה זה גם מקל על המשתמש
 *
 * הניעור "מזיין" חלון קצר שבו מחפשים את הנקישות. בתוך החלון הזה אפשר
 * להיות מקלים מאוד בסף-הנקישה — כי בהינתן שניעור מכוון בדיוק קרה,
 * ההסתברות שרעש יזייף שתי נקישות בשלוש שניות היא זניחה. לכן הנקישות
 * יכולות להיות עדינות, וגם הניעור עצמו קצר יותר מאשר אילו נשא לבדו את
 * כל נטל-ההבחנה.
 *
 * זה מה שניסינו להשיג עם שער-התנוחה, רק ששם השלבים היו אמורים להתקיים
 * **בו-זמנית** — וההקשה עצמה שברה את השער שאמור היה לאשר אותה. כאן הם
 * **עוקבים בזמן**, ולכן אין התנגשות.
 *
 * ## שקט בשלב הביניים
 *
 * אין שום משוב על "זויין" — לא צליל ולא רטט. בכוונה: ניעור מים אחרי
 * נטילת ידיים אכן יזיין את החלון כמה פעמים ביום, וצפצוף בכל פעם היה
 * מטרד. בלי נקישות אחריו החלון פשוט נסגר בשקט.
 */
class ReportGestureDetector(
    private val onLog: (tag: String, detail: String) -> Unit = { _, _ -> },
    private val onGestureCompleted: (reversals: Int, peak: Double) -> Unit
) {
    private enum class Phase { IDLE, ARMED }

    private var phase = Phase.IDLE

    // --- שלב 1: ניעור ---
    private val magnitudes = ArrayDeque<Double>()
    private val sampleTimes = ArrayDeque<Long>()
    private val reversalTimes = ArrayDeque<Long>()
    private var swingPhase = 0 // 1 = גבוה, -1 = נמוך, 0 = לא נקבע
    private var lastShakeReversals = 0
    private var lastShakePeak = 0.0

    // --- שלב 2: נקישות בחלון המזוין ---
    private var armedAtMs = 0L
    private var tapCount = 0
    private var lastTapMs = 0L
    private var lastMagnitude: Double? = null

    private var lastCompletedMs = 0L

    fun onSample(x: Float, y: Float, z: Float, now: Long) {
        val magnitude = Math.sqrt((x * x + y * y + z * z).toDouble())
        val prevMagnitude = lastMagnitude
        lastMagnitude = magnitude

        if (now - lastCompletedMs < DebugConfig.GESTURE_COOLDOWN_MS) return

        when (phase) {
            Phase.IDLE -> trackShake(magnitude, now)
            Phase.ARMED -> trackTaps(magnitude, prevMagnitude, now)
        }
    }

    // ---------- שלב 1 ----------

    private fun trackShake(magnitude: Double, now: Long) {
        magnitudes.addLast(magnitude)
        sampleTimes.addLast(now)
        while (sampleTimes.isNotEmpty() && now - sampleTimes.first() > DebugConfig.SHAKE_WINDOW_MS) {
            sampleTimes.removeFirst()
            magnitudes.removeFirst()
        }
        while (reversalTimes.isNotEmpty() && now - reversalTimes.first() > DebugConfig.SHAKE_WINDOW_MS) {
            reversalTimes.removeFirst()
        }

        // היסטרזיס: בין הספים נשארים בשלב הנוכחי, כדי שרעש קטן סביב הסף
        // לא ייספר כהחלפת-כיוון.
        val newSwing = when {
            magnitude >= DebugConfig.SHAKE_HIGH_THRESHOLD -> 1
            magnitude <= DebugConfig.SHAKE_LOW_THRESHOLD -> -1
            else -> swingPhase
        }
        if (swingPhase != 0 && newSwing != swingPhase) {
            reversalTimes.addLast(now)
        }
        swingPhase = newSwing

        if (reversalTimes.size < DebugConfig.SHAKE_MIN_REVERSALS) return
        if (magnitudes.isEmpty()) return

        val peak = magnitudes.max()
        if (peak < DebugConfig.SHAKE_MIN_PEAK) return

        lastShakeReversals = reversalTimes.size
        lastShakePeak = peak
        armedAtMs = now
        tapCount = 0
        lastTapMs = 0L
        phase = Phase.ARMED
        clearShakeState()

        onLog(
            "DEBUG",
            "gesture_armed_by_shake;reversals=$lastShakeReversals;peak=${"%.1f".format(peak)}"
        )
    }

    private fun clearShakeState() {
        magnitudes.clear()
        sampleTimes.clear()
        reversalTimes.clear()
        swingPhase = 0
    }

    // ---------- שלב 2 ----------

    private fun trackTaps(magnitude: Double, prevMagnitude: Double?, now: Long) {
        if (now - armedAtMs > DebugConfig.GESTURE_ARM_WINDOW_MS) {
            phase = Phase.IDLE
            onLog("DEBUG", "gesture_disarmed_no_taps;taps_seen=$tapCount")
            return
        }

        // השהיית-התייצבות: מיד אחרי ניעור נמרץ היד עוד רועדת, ורעידות
        // הדעיכה היו נספרות כנקישות.
        if (now - armedAtMs < DebugConfig.GESTURE_SETTLE_MS) return

        if (now - lastTapMs < DebugConfig.GESTURE_TAP_REFRACTORY_MS) return
        if (prevMagnitude == null) return

        val aboveThreshold = magnitude > DebugConfig.GESTURE_TAP_MIN_MAGNITUDE
        val jumped = Math.abs(magnitude - prevMagnitude) > DebugConfig.GESTURE_TAP_MIN_DELTA
        if (!aboveThreshold || !jumped) return

        tapCount++
        lastTapMs = now
        onLog(
            "DEBUG",
            "gesture_tap_in_window;tap=$tapCount/${DebugConfig.GESTURE_TAP_COUNT};" +
                "magnitude=${"%.1f".format(magnitude)}"
        )

        if (tapCount < DebugConfig.GESTURE_TAP_COUNT) return

        lastCompletedMs = now
        phase = Phase.IDLE
        tapCount = 0

        onLog(
            "INFO",
            "gesture_completed;reversals=$lastShakeReversals;peak=${"%.1f".format(lastShakePeak)}"
        )
        onGestureCompleted(lastShakeReversals, lastShakePeak)
    }

    // ---------- למסך התרגול ----------

    fun isArmed(): Boolean = phase == Phase.ARMED
    fun currentReversals(): Int = reversalTimes.size
    fun currentTaps(): Int = tapCount

    fun reset() {
        phase = Phase.IDLE
        clearShakeState()
        tapCount = 0
        lastTapMs = 0L
        armedAtMs = 0L
        lastCompletedMs = 0L
        lastMagnitude = null
    }
}
