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
 *
 * ## שער-תנוחה (referenceGravity)
 *
 * אם הוזן וקטור-כובד-ייחוס אישי, נפתחת שכבה נוספת: המנוע עוקב ברציפות
 * אחרי התנוחה (כיוון הכובד) והיציבות (שונות בעוצמה), ומסמן "שער חם"
 * כששניהם מתקיימים. הקשה שמגיעה כששער חם נבדקת מול ספים מוקלים
 * משמעותית — כי התנוחה עצמה כבר עשתה את עבודת ההבחנה מרעש.
 *
 * referenceGravity == null → השער כבוי לגמרי והמנוע מתנהג בדיוק כמו קודם.
 * זו נפילה-חזרה בטוחה: משתמש שלא כייל מקבל את ההתנהגות הקיימת, לא שבורה.
 *
 * הכל כאן רץ על ה-accelerometer שכבר פועל ממילא — x,y,z כבר מגיעים בכל
 * מדגם. אין חיישן נוסף ואין עלות-סוללה נוספת.
 */
class TapClusterDetector(
    // var, לא val: TapDetectorService מעדכן את זה בכל onStartCommand (לא
    // רק פעם אחת ב-onCreate) כדי לתפוס כיול-אישי שנשמר אחרי שהשירות כבר
    // קיים בזיכרון (למשל אחרי מילוי-שאלון-מחדש).
    var magnitudeThreshold: Double,
    // אותה סיבה — מתעדכן בכל onStartCommand. null = שער כבוי.
    var referenceGravity: FloatArray? = null,
    private val wornGatingEnabled: Boolean = false,
    private val isWorn: () -> Boolean = { true },
    private val wornSensorAvailable: () -> Boolean = { false },
    private val sustainedMotionSuppressMs: Long = DebugConfig.SUSTAINED_MOTION_SUPPRESS_MS,
    private val onLog: (tag: String, detail: String) -> Unit = { _, _ -> },
    private val onTapPatternDetected: (count: Int, magnitudes: List<Double>, gravityAtStart: FloatArray?) -> Unit
) {
    companion object {
        /**
         * רצפה מוחלטת שלעולם לא יורדים מתחתיה, ללא קשר למה שהוזן
         * ב-magnitudeThreshold. נמדד בפועל: כוח-הכובד הבסיסי (מנוחה) הוא
         * ~9.8 m/s². סף מתחת לזה תוקע לצמיתות: המדגם אף פעם לא יורד
         * מתחת לסף, אז consecutiveAboveThresholdSamples אף פעם לא
         * מתאפס, ואחרי TAP_MAX_CONSECUTIVE_ABOVE_THRESHOLD_SAMPLES מדגמים
         * הכל נדחה כ-sustained_pulse עד סוף החלון — בדיוק הבאג שגילינו
         * ב-TAP_CALIBRATION_MAGNITUDE_FLOOR=8.0. זו הגנה מבנית: שינוי-
         * ערך עתידי לא יכול להחזיר את הבאג הזה בשקט.
         */
        private const val ABSOLUTE_MINIMUM_MAGNITUDE_THRESHOLD = 10.5
    }

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

    private var warnedLowThreshold = false

    // --- שער-תנוחה ויציבות ---
    private val gravity = FloatArray(3)
    private var gravityInitialized = false
    private val stillnessWindow = ArrayDeque<Double>()
    private var lastGateSatisfiedMs = 0L
    private var gateSatisfiedNow = false
    private var gravityAtClusterStart: FloatArray? = null

    fun onSample(x: Float, y: Float, z: Float, now: Long) {
        val magnitude = Math.sqrt((x * x + y * y + z * z).toDouble())

        updateGravity(x, y, z)
        updateStillnessWindow(magnitude)
        evaluateGate(now)

        // כששער חם — ספים מוקלים. זה כל הרווח: הקשה עדינה מספיקה, כי
        // התנוחה+היציבות כבר הבדילו מרעש במקום העוצמה.
        val gateWarm = isGateWarm(now)
        val effectiveThreshold = if (gateWarm) {
            DebugConfig.TAP_MAGNITUDE_THRESHOLD_GATED
        } else {
            clampConfiguredThreshold()
        }
        val effectiveMinDelta = if (gateWarm) {
            DebugConfig.TAP_MIN_DELTA_GATED
        } else {
            DebugConfig.TAP_MIN_DELTA
        }

        val prevMagnitude = lastMagnitude
        lastMagnitude = magnitude

        val aboveThreshold = magnitude > effectiveThreshold
        val jumpedSuddenly = prevMagnitude != null &&
            Math.abs(magnitude - prevMagnitude) > effectiveMinDelta

        if (aboveThreshold) {
            consecutiveAboveThresholdSamples++
            trackSustainedMotion(now)

            val debounced = now - lastAcceptedSpikeMs < DebugConfig.TAP_REFRACTORY_MS
            when {
                suppressed -> { /* מושתק בגלל תנועה רציפה */ }
                debounced -> onLog("DEBUG", "tap_candidate_rejected_refractory")
                !jumpedSuddenly -> { /* אין קפיצה חדה — כנראה המשך אותו פולס, לא דפיקה חדשה */ }
                else -> evaluateCandidate(now, magnitude, consecutiveAboveThresholdSamples, gateWarm)
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

    /**
     * שכבה 9 — השתקת-תנועה-רציפה (הליכה/נסיעה). נפרדת משער-התנוחה
     * ומשלימה אותו: השער שואל "האם היד מוחזקת בכוונה", וזו שואלת "האם
     * הגוף בתנועה ממושכת".
     */
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

    /** סינון-נמוך: מפריד את הכובד (איטי) מתאוצה-לינארית (מהירה). */
    private fun updateGravity(x: Float, y: Float, z: Float) {
        if (!gravityInitialized) {
            gravity[0] = x; gravity[1] = y; gravity[2] = z
            gravityInitialized = true
            return
        }
        val a = DebugConfig.GRAVITY_FILTER_ALPHA.toFloat()
        gravity[0] = a * gravity[0] + (1 - a) * x
        gravity[1] = a * gravity[1] + (1 - a) * y
        gravity[2] = a * gravity[2] + (1 - a) * z
    }

    private fun updateStillnessWindow(magnitude: Double) {
        stillnessWindow.addLast(magnitude)
        while (stillnessWindow.size > DebugConfig.STILLNESS_WINDOW_SAMPLES) {
            stillnessWindow.removeFirst()
        }
    }

    private fun isStill(): Boolean {
        if (stillnessWindow.size < DebugConfig.STILLNESS_WINDOW_SAMPLES) return false
        val mean = stillnessWindow.average()
        val variance = stillnessWindow.sumOf { (it - mean) * (it - mean) } / stillnessWindow.size
        return Math.sqrt(variance) <= DebugConfig.STILLNESS_MAX_STDDEV
    }

    /** מכפלה-סקלרית מנורמלת בין הכובד הנוכחי לווקטור-הייחוס האישי. */
    private fun orientationMatches(): Boolean {
        val ref = referenceGravity ?: return false
        if (!gravityInitialized || ref.size < 3) return false

        val magNow = Math.sqrt(
            (gravity[0] * gravity[0] + gravity[1] * gravity[1] + gravity[2] * gravity[2]).toDouble()
        )
        val magRef = Math.sqrt(
            (ref[0] * ref[0] + ref[1] * ref[1] + ref[2] * ref[2]).toDouble()
        )
        if (magNow < 1e-3 || magRef < 1e-3) return false

        val dot = (gravity[0] * ref[0] + gravity[1] * ref[1] + gravity[2] * ref[2]).toDouble() /
            (magNow * magRef)
        return dot >= DebugConfig.ORIENTATION_MATCH_MIN_DOT
    }

    /**
     * נבדק בכל מדגם, לא רק בזמן הקשה — זו כל הנקודה. ההקשה עצמה שוברת
     * את היציבות, אז היא שואלת אחר-כך רק "האם השער היה מסופק לאחרונה".
     */
    private fun evaluateGate(now: Long) {
        if (referenceGravity == null) return // אין כיול → שער כבוי

        val satisfied = orientationMatches() && isStill()
        if (satisfied) lastGateSatisfiedMs = now
        if (satisfied != gateSatisfiedNow) {
            gateSatisfiedNow = satisfied
            onLog("DEBUG", "orientation_gate_${if (satisfied) "satisfied" else "lost"}")
        }
    }

    private fun isGateWarm(now: Long): Boolean =
        referenceGravity != null &&
            lastGateSatisfiedMs > 0L &&
            (now - lastGateSatisfiedMs) <= DebugConfig.GATE_VALIDITY_MS

    private fun clampConfiguredThreshold(): Double {
        if (magnitudeThreshold >= ABSOLUTE_MINIMUM_MAGNITUDE_THRESHOLD) return magnitudeThreshold
        if (!warnedLowThreshold) {
            warnedLowThreshold = true
            onLog(
                "ERROR",
                "magnitude_threshold_below_safe_floor;configured=${"%.2f".format(magnitudeThreshold)};" +
                    "using=$ABSOLUTE_MINIMUM_MAGNITUDE_THRESHOLD"
            )
        }
        return ABSOLUTE_MINIMUM_MAGNITUDE_THRESHOLD
    }

    private fun evaluateCandidate(now: Long, magnitude: Double, pulseSamples: Int, gateWarm: Boolean) {
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
            // דפיקה ראשונה בצרור — הופכת ל"דוגמה" לכל השאר. שומרים גם את
            // הכובד ברגע הזה: זו התנוחה שממנה אפשר לכייל ייחוס אישי חדש.
            referenceMagnitude = magnitude
            referencePulseSamples = pulseSamples
            gravityAtClusterStart = gravity.copyOf()
            acceptIntoCluster(now, magnitude, gateWarm)
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

        acceptIntoCluster(now, magnitude, gateWarm)
    }

    private fun acceptIntoCluster(now: Long, magnitude: Double, gateWarm: Boolean) {
        lastAcceptedSpikeMs = now
        recentSpikes.addLast(now)
        recentMagnitudes.addLast(magnitude)
        while (recentSpikes.isNotEmpty() && now - recentSpikes.first() > DebugConfig.TAP_WINDOW_MS) {
            recentSpikes.removeFirst()
            recentMagnitudes.removeFirst()
        }

        // חור-אבחוני שתוקן: קודם רק דחיות נכתבו ללוג, אף שורה על קבלה —
        // אי-אפשר היה להבדיל "כלום לא התקבל" מ"התקבלו 3, צריך 4".
        onLog(
            "DEBUG",
            "tap_candidate_accepted;count=${recentSpikes.size};" +
                "magnitude=${"%.2f".format(magnitude)};gated=$gateWarm"
        )

        if (recentSpikes.size >= DebugConfig.TAP_COUNT_THRESHOLD) {
            if (isRhythmRegular(recentSpikes)) {
                val count = recentSpikes.size
                val magnitudes = recentMagnitudes.toList()
                val gravitySnapshot = gravityAtClusterStart
                recentSpikes.clear()
                recentMagnitudes.clear()
                referenceMagnitude = null
                referencePulseSamples = null
                gravityAtClusterStart = null
                onTapPatternDetected(count, magnitudes, gravitySnapshot)
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
