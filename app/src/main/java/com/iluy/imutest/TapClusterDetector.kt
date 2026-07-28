package com.iluy.imutest

/**
 * מנוע-הזיהוי הטהור לצרור-הקשות (סעיף 3 במסמך המסירה). בלי תלות ב-Context
 * או SensorManager — רק לוגיקת-דפוס נקייה — כדי שיהיה משותף בין
 * TapDetectorService (רקע, שירות מלא) לבין מסך התרגול בשאלון (חד-פעמי,
 * קדימה, עם סף-כיול נמוך יותר).
 *
 * magnitudeThreshold ניתן כפרמטר בכוונה: השירות מזין אותו עם הסף האישי
 * המכויל (או הגלובלי כברירת מחדל), והתרגול מזין אותו עם סף-רצפה נמוך כדי
 * לתפוס הקשות חלשות-יחסית ולכייל מהן.
 */
class TapClusterDetector(
    // var, לא val: TapDetectorService מעדכן את זה בכל onStartCommand (לא
    // רק פעם אחת ב-onCreate) כדי לתפוס כיול-אישי שנשמר אחרי שהשירות כבר
    // קיים בזיכרון (למשל אחרי מילוי-שאלון-מחדש).
    var magnitudeThreshold: Double,
    private val wornGatingEnabled: Boolean = false,
    private val isWorn: () -> Boolean = { true },
    private val wornSensorAvailable: () -> Boolean = { false },
    private val sustainedMotionSuppressMs: Long = DebugConfig.SUSTAINED_MOTION_SUPPRESS_MS,
    private val onLog: (tag: String, detail: String) -> Unit = { _, _ -> },
    private val onTapPatternDetected: (count: Int, magnitudes: List<Double>) -> Unit
) {
    private val recentSpikes = ArrayDeque<Long>()
    private val recentMagnitudes = ArrayDeque<Double>()

    // "דוגמה" — הדפיקה הראשונה בכל צרור, כל דפיקה נוספת נבדקת מולה
    private var referenceMagnitude: Double? = null
    private var referencePulseSamples: Int? = null

    // מעקב תנועה-רציפה (הליכה/נסיעה)
    private var sustainedMotionStartMs: Long? = null
    private var lastSpikeAboveThresholdMs: Long = 0L
    private var suppressed = false

    // jerk ופולס-קצר
    private var lastMagnitude: Double? = null
    private var lastAcceptedSpikeMs: Long = 0L
    private var consecutiveAboveThresholdSamples = 0

    fun onSample(magnitude: Double, now: Long) {
        val prevMagnitude = lastMagnitude
        lastMagnitude = magnitude

        val aboveThreshold = magnitude > magnitudeThreshold
        val jumpedSuddenly = prevMagnitude != null &&
            Math.abs(magnitude - prevMagnitude) > DebugConfig.TAP_MIN_DELTA

        if (aboveThreshold) {
            consecutiveAboveThresholdSamples++
            trackSustainedMotion(now)

            val debounced = now - lastAcceptedSpikeMs < DebugConfig.TAP_REFRACTORY_MS
            when {
                suppressed -> { /* מושתק בגלל תנועה רציפה */ }
                debounced -> onLog("DEBUG", "tap_candidate_rejected_refractory")
                !jumpedSuddenly -> { /* אין קפיצה חדה — כנראה המשך אותו פולס, לא דפיקה חדשה */ }
                else -> evaluateCandidate(now, magnitude, consecutiveAboveThresholdSamples)
            }
        } else {
            consecutiveAboveThresholdSamples = 0
            sustainedMotionStartMs = null
            if (suppressed) {
                suppressed = false
                onLog("INFO", "tap_detection_resumed_after_stillness")
            }
        }
    }

    private fun trackSustainedMotion(now: Long) {
        if (sustainedMotionStartMs == null || now - lastSpikeAboveThresholdMs > 1_500L) {
            sustainedMotionStartMs = now
        }
        lastSpikeAboveThresholdMs = now

        val sustainedFor = now - (sustainedMotionStartMs ?: now)
        if (sustainedFor > sustainedMotionSuppressMs && !suppressed) {
            suppressed = true
            onLog("INFO", "tap_detection_suppressed_sustained_motion")
        }
    }

    private fun evaluateCandidate(now: Long, magnitude: Double, pulseSamples: Int) {
        if (pulseSamples > DebugConfig.TAP_MAX_CONSECUTIVE_ABOVE_THRESHOLD_SAMPLES) {
            onLog("DEBUG", "tap_candidate_rejected_sustained_pulse")
            return
        }

        if (wornGatingEnabled && wornSensorAvailable() && !isWorn()) {
            onLog("DEBUG", "tap_candidate_rejected_not_worn")
            return
        }

        // תיחום-צרור: מרווח ארוך מדי מהדפיקה האחרונה = צרור חדש, לא המשך
        if (recentSpikes.isNotEmpty() && now - recentSpikes.last() > DebugConfig.TAP_MAX_INTERVAL_MS) {
            onLog("DEBUG", "tap_cluster_reset_interval_too_long")
            recentSpikes.clear()
            recentMagnitudes.clear()
            referenceMagnitude = null
            referencePulseSamples = null
        }

        if (recentSpikes.isEmpty()) {
            // דפיקה ראשונה בצרור — הופכת ל"דוגמה" לכל השאר
            referenceMagnitude = magnitude
            referencePulseSamples = pulseSamples
            acceptIntoCluster(now, magnitude)
            return
        }

        val refMag = referenceMagnitude ?: magnitude
        val refPulse = referencePulseSamples ?: pulseSamples
        val magDiffRatio = if (refMag > 0) Math.abs(magnitude - refMag) / refMag else 0.0
        val pulseDiff = Math.abs(pulseSamples - refPulse)

        if (magDiffRatio > DebugConfig.TAP_SIMILARITY_MAGNITUDE_TOLERANCE ||
            pulseDiff > DebugConfig.TAP_SIMILARITY_PULSE_TOLERANCE_SAMPLES
        ) {
            onLog(
                "DEBUG",
                "tap_candidate_rejected_dissimilar_from_first;magDiff=${"%.2f".format(magDiffRatio)};pulseDiff=$pulseDiff"
            )
            return
        }

        acceptIntoCluster(now, magnitude)
    }

    private fun acceptIntoCluster(now: Long, magnitude: Double) {
        lastAcceptedSpikeMs = now
        recentSpikes.addLast(now)
        recentMagnitudes.addLast(magnitude)
        while (recentSpikes.isNotEmpty() && now - recentSpikes.first() > DebugConfig.TAP_WINDOW_MS) {
            recentSpikes.removeFirst()
            recentMagnitudes.removeFirst()
        }

        // חור-אבחוני שתוקן: קודם רק דחיות נכתבו ללוג, אף שורה על קבלה —
        // אי-אפשר היה להבדיל "כלום לא התקבל" מ"התקבלו 3, צריך 4".
        onLog("DEBUG", "tap_candidate_accepted;count=${recentSpikes.size};magnitude=${"%.2f".format(magnitude)}")

        if (recentSpikes.size >= DebugConfig.TAP_COUNT_THRESHOLD) {
            if (isRhythmRegular(recentSpikes)) {
                val count = recentSpikes.size
                val magnitudes = recentMagnitudes.toList()
                recentSpikes.clear()
                recentMagnitudes.clear()
                referenceMagnitude = null
                referencePulseSamples = null
                onTapPatternDetected(count, magnitudes)
            } else {
                onLog("DEBUG", "tap_candidate_rejected_irregular")
                recentSpikes.removeFirst()
                recentMagnitudes.removeFirst()
            }
        }
    }

    private fun isRhythmRegular(spikes: ArrayDeque<Long>): Boolean {
        if (spikes.size < 3) return true
        val gaps = spikes.zipWithNext { a: Long, b: Long -> (b - a).toDouble() }
        val mean = gaps.average()
        val variance = gaps.sumOf { (it - mean) * (it - mean) } / gaps.size
        val stddev = Math.sqrt(variance)
        return stddev <= DebugConfig.TAP_RHYTHM_MAX_STDDEV_MS
    }
}
