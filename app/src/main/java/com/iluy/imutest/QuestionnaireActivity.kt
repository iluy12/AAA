package com.iluy.imutest

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.File

/**
 * השאלון המלא — סעיף 11 במסמך המסירה. בחירה בלבד בכל השאלות, אין שדה
 * טקסט חופשי בשום מקום (השאלה היחידה עם קלט "חופשי" היא הקלטה קולית,
 * לא טקסט). שאלה 5 (מה עוזר לך) מוזנת ישירות לתפריט הסיוע כברירת מחדל.
 */
class QuestionnaireActivity : Activity() {

    private var step = 0
    private val totalSteps = 9

    // בחירות זמניות לשלב הנוכחי, נשמרות ל-LocalStore בכל "המשך"
    private var selectedMulti = mutableSetOf<String>()
    private var selectedSingle: String? = null

    private var recorder: MediaRecorder? = null
    private var player: MediaPlayer? = null
    private var isRecording = false
    private lateinit var recordingFile: File
    private var instructionShown = false

    // הפניות למסך-ההקלטה הנוכחי, כדי שאפשר יהיה לחדש הקלטה אוטומטית
    // מ-onRequestPermissionsResult אחרי שהמשתמש אישר RECORD_AUDIO
    private var recordButtonRef: Button? = null
    private var statusTextRef: TextView? = null

    // --- תרגול-הקשה (סעיף 4) ---
    private val practiceHandler = Handler(Looper.getMainLooper())
    private var practiceSensorManager: SensorManager? = null
    private var practiceAccelerometer: Sensor? = null
    private var practiceListener: SensorEventListener? = null
    private var practiceTimeoutRunnable: Runnable? = null

    // --- אבחון-תרגול ---
    // מונים גולמיים לכל מדגם accelerometer, בלתי-תלויים בתוצאת הזיהוי —
    // כדי שיהיה מה לקרוא בלוג גם כשהמחווה לא נקלטה בכלל.
    private var practiceSampleCount = 0
    private var practiceMaxMagnitude = 0.0
    private var practiceDiagnosticTextRef: TextView? = null
    private var practiceListenStartMs: Long = 0L

    companion object {
        private const val REQUEST_RECORD_AUDIO = 601
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        recordingFile = File(filesDir, "personal_recording.3gp")
        renderStep()
    }

    private fun renderStep() {
        selectedMulti = mutableSetOf()
        selectedSingle = null

        val scroll = ScrollView(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 40, 28, 28)
        }
        scroll.addView(container)

        container.addView(TextView(this).apply {
            text = "שלב ${step + 1} מתוך $totalSteps"
            textSize = 12f
            setTextColor(ContextCompat.getColor(context, R.color.text_tertiary))
            gravity = Gravity.CENTER
        })

        when (step) {
            0 -> renderMultiChoice(
                container, "מתי זה בד\"כ קורה? (בחירה מרובה)",
                listOf("בוקר", "צהריים", "לילה"),
                LocalStore.KEY_Q1_TIMES
            )
            1 -> renderSingleChoice(
                container, "כל כמה זמן?",
                listOf("כל יום", "כמה פעמים בשבוע", "פעם בשבוע", "כל שבועיים", "כל חודש", "כל חודשיים"),
                LocalStore.KEY_Q2_FREQUENCY
            )
            2 -> renderSingleChoice(
                container, "אותו חדר בכל פעם, או משתנה?",
                listOf("אותו חדר", "משתנה"),
                LocalStore.KEY_Q3_PLACE
            )
            3 -> renderMultiChoice(
                container, "באיזו תנוחה בד\"כ? (בחירה מרובה)",
                listOf("יושב", "שוכב", "עומד"),
                LocalStore.KEY_Q3_POSITION
            )
            4 -> renderMultiChoice(
                container, "מה בד\"כ מקדים את זה? (בחירה מרובה)",
                listOf("שיעמום", "עצבנות", "עצבות", "לחץ", "עייפות", "בדידות"),
                LocalStore.KEY_Q4_MOODS
            )
            5 -> renderMultiChoice(
                container, "מה עוזר לך לצאת מזה? (בחירה מרובה)",
                listOf("הליכה קצרה", "שתיית מים", "שיחה עם חבר", "תפילה", "לימוד", "מוזיקה", "נשימות עמוקות", "יציאה מהחדר"),
                LocalStore.KEY_Q5_HELPS
            )
            6 -> renderSingleChoice(
                container, "כמה זמן זה בד\"כ נמשך?",
                listOf("0–3 דקות", "3–7 דקות", "7–12 דקות", "מעל 12 דקות"),
                LocalStore.KEY_Q6_DURATION
            )
            7 -> renderConsent(
                container, "בסדר שאתקשר מדי פעם?",
                LocalStore.KEY_Q7_CONSENT_CALL
            )
            8 -> renderConsent(
                container, "בסדר שאשלח הודעה מדי פעם?",
                LocalStore.KEY_Q8_CONSENT_MESSAGE
            )
        }

        // שלב ההקלטה מוצג בנפרד אחרי שאלה 8 (מספור נוסף, מעבר ל-9 שאלות הבחירה)
        setContentView(scroll)
    }

    // ---------- רינדור סוגי שאלות ----------

    private fun renderMultiChoice(container: LinearLayout, title: String, options: List<String>, key: String) {
        addTitle(container, title)
        val checkBoxes = mutableListOf<CheckBox>()
        for (opt in options) {
            val cb = CheckBox(this).apply {
                text = opt
                textSize = 15f
                setPadding(0, 10, 0, 10)
            }
            checkBoxes.add(cb)
            container.addView(cb)
        }
        addNextButton(container) {
            val chosen = checkBoxes.filter { it.isChecked }.map { it.text.toString() }
            LocalStore.saveMultiChoice(this, key, chosen)
            advance()
        }
    }

    private fun renderSingleChoice(container: LinearLayout, title: String, options: List<String>, key: String) {
        addTitle(container, title)
        val group = RadioGroup(this).apply { orientation = LinearLayout.VERTICAL }
        for (opt in options) {
            val rb = RadioButton(this).apply {
                text = opt
                textSize = 15f
                setPadding(0, 10, 0, 10)
            }
            group.addView(rb)
        }
        container.addView(group)
        addNextButton(container) {
            val checkedId = group.checkedRadioButtonId
            val chosen = if (checkedId != -1) group.findViewById<RadioButton>(checkedId).text.toString() else ""
            LocalStore.saveSingleChoice(this, key, chosen)
            advance()
        }
    }

    private fun renderConsent(container: LinearLayout, title: String, key: String) {
        addTitle(container, title)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val yes = Button(this).apply {
            text = "כן"
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            layoutParams = lp
            setOnClickListener {
                LocalStore.saveBoolean(this@QuestionnaireActivity, key, true)
                advance()
            }
        }
        val no = Button(this).apply {
            text = "לא"
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            lp.marginStart = 12
            layoutParams = lp
            setOnClickListener {
                LocalStore.saveBoolean(this@QuestionnaireActivity, key, false)
                advance()
            }
        }
        row.addView(yes)
        row.addView(no)
        container.addView(row)
    }

    private fun renderRecordingStep() {
        val scroll = ScrollView(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 40, 28, 28)
        }
        scroll.addView(container)

        addTitle(container, "הקלטה אישית — למה אני לא רוצה בזה")
        container.addView(TextView(this).apply {
            text = "ההקלטה נשמעת לך בלבד, ברגע קשה. מוקלטת עכשיו, ברגע רגוע — לא בזמן אמת."
            textSize = 13f
            setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            setPadding(0, 0, 0, 20)
        })

        val statusText = TextView(this).apply {
            text = if (recordingFile.exists()) "יש הקלטה שמורה" else "אין עדיין הקלטה"
            textSize = 13f
            gravity = Gravity.CENTER
        }
        container.addView(statusText)
        statusTextRef = statusText

        val recordButton = Button(this)
        recordButton.text = "🎙 הקלט"
        recordButton.setOnClickListener {
            if (!isRecording) startRecording(recordButton, statusText) else stopRecording(recordButton, statusText)
        }
        container.addView(recordButton)
        recordButtonRef = recordButton

        container.addView(Button(this).apply {
            text = "▶ נגן"
            setOnClickListener { playRecording() }
        })

        addNextButton(container, label = "סיום השאלון") {
            if (isRecording) {
                stopRecording(recordButton, statusText)
            }
            if (recordingFile.exists()) {
                LocalStore.setRecordingPath(this, recordingFile.absolutePath)
            }
            LocalStore.setQuestionnaireDone(this, true)
            EventLog.log(this, "INFO", "questionnaire_completed")
            MainActivity.start(this)
            finish()
        }

        setContentView(scroll)
    }

    private fun startRecording(button: Button, status: TextView) {
        val hasPermission = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasPermission) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_RECORD_AUDIO
            )
            return
        }
        try {
            recorder = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
                setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
                setOutputFile(recordingFile.absolutePath)
                prepare()
                start()
            }
            isRecording = true
            button.text = "⏹ עצור"
            status.text = "מקליט…"
        } catch (e: Exception) {
            status.text = "לא הצלחתי להתחיל הקלטה"
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_RECORD_AUDIO &&
            grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            val button = recordButtonRef
            val status = statusTextRef
            // רק אם המשתמש עדיין במסך-ההקלטה ולא כבר מקליט (guard כפול —
            // הפניות עלולות להיות ריקות/לא-רלוונטיות אם המסך כבר הוחלף
            // עד שתשובת-ההרשאה חזרה)
            if (button != null && status != null && !isRecording) {
                startRecording(button, status)
            }
        }
    }

    private fun stopRecording(button: Button, status: TextView) {
        try {
            recorder?.stop()
            recorder?.release()
        } catch (e: Exception) {
            // ייתכן שההקלטה הייתה קצרה מדי — מתעלמים, הקובץ פשוט לא יהיה תקין
        }
        recorder = null
        isRecording = false
        button.text = "🎙 הקלט מחדש"
        status.text = "הקלטה נשמרה"
    }

    private fun playRecording() {
        if (!recordingFile.exists()) return
        try {
            player?.release()
            player = MediaPlayer().apply {
                setDataSource(recordingFile.absolutePath)
                prepare()
                start()
            }
        } catch (e: Exception) {
            // אין מה לעשות אם הניגון נכשל — לא קריטי לשאלון
        }
    }

    // ---------- ניווט ----------

    private fun addTitle(container: LinearLayout, title: String) {
        container.addView(TextView(this).apply {
            text = title
            textSize = 17f
            gravity = Gravity.CENTER
            setPadding(0, 20, 0, 20)
        })
    }

    private fun addNextButton(container: LinearLayout, label: String = "המשך", onClick: () -> Unit) {
        container.addView(Button(this).apply {
            text = label
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = 24
            layoutParams = lp
            setOnClickListener { onClick() }
        })
    }

    private fun advance() {
        step++
        when {
            step < totalSteps -> renderStep()
            !instructionShown -> {
                instructionShown = true
                renderKnockInstructionStep()
            }
            else -> renderRecordingStep()
        }
    }

    /**
     * הוראת-המחווה: ניעור-יד נמרץ, כמו ניעור מדחום. החליף את ההקשה אחרי
     * שארבעה סבבי-כיוונון בשטח לא התכנסו — ראו ShakeDetector להסבר המלא.
     *
     * "התחל תרגול" מפעיל האזנה אמיתית ל-accelerometer דרך אותו
     * ShakeDetector ואותם ספים שרצים ברקע. אין כאן גזירת-ערכים או כיול:
     * התרגול נועד ללמד את המחווה ולאשר שהחומרה מגיבה.
     */
    private fun renderKnockInstructionStep() {
        val scroll = ScrollView(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 40, 28, 28)
        }
        scroll.addView(container)

        addTitle(container, "איך לדווח")
        container.addView(TextView(this).apply {
            text = "לפני שמתחילים — שים את השעון על היד.\n\n" +
                "בכל פעם שהתגברת על ניסיון:\n" +
                "נער את היד בחוזקה 4 פעמים הלוך-ושוב, כמו שמנערים מדחום. " +
                "בערך שנייה.\n\n" +
                "תנועה נמרצת וברורה — כדי שהשעון לא יתבלבל עם הליכה " +
                "או תנועה רגילה."
            textSize = 14f
            setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            setPadding(0, 0, 0, 20)
        })

        val statusText = TextView(this).apply {
            text = "לחץ 'התחל תרגול' ונער את היד בחוזקה"
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 16)
        }
        container.addView(statusText)

        // מצב-אבחון זמני (חלק 0): עוצמה חיה מה-accelerometer בזמן ההאזנה,
        // כדי לראות בעיניים אילו ערכים החומרה הזו בפועל מייצרת, לפני כל
        // כיוונון-ספים. יורד אוטומטית עם DEBUG_TAG_ENABLED=false.
        if (DebugConfig.DEBUG_TAG_ENABLED) {
            val diagnosticText = TextView(this).apply {
                text = ""
                textSize = 11f
                setTextColor(ContextCompat.getColor(context, R.color.text_tertiary))
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, 16)
            }
            container.addView(diagnosticText)
            practiceDiagnosticTextRef = diagnosticText
        }

        val startButton = Button(this).apply {
            text = "התחל תרגול"
        }
        container.addView(startButton)

        val skipButton = Button(this).apply {
            text = "המשך בלי תרגול"
            visibility = View.GONE
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = 12
            layoutParams = lp
            setOnClickListener {
                stopPracticeListening()
                EventLog.log(this@QuestionnaireActivity, "INFO", "tap_practice_skipped")
                renderRecordingStep()
            }
        }
        container.addView(skipButton)

        val continueButton = Button(this).apply {
            text = "המשך"
            visibility = View.GONE
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = 12
            layoutParams = lp
            setOnClickListener { renderRecordingStep() }
        }
        container.addView(continueButton)

        startButton.setOnClickListener {
            startButton.visibility = View.GONE
            skipButton.visibility = View.GONE
            statusText.text = "מקשיב… נער את היד עכשיו"
            startPracticeListening(
                onDetected = { reversals, peak ->
                    // אין מה לשמור: הניעור לא דורש כיול אישי. התרגול כאן
                    // נועד ללמד את המחווה ולאשר שהחומרה מגיבה, לא לגזור ערך.
                    EventLog.log(
                        this, "INFO",
                        "shake_practice_success;reversals=$reversals;peak=${"%.1f".format(peak)}"
                    )
                    statusText.text = "✓ נקלט"
                    continueButton.visibility = View.VISIBLE
                },
                onTimeout = {
                    EventLog.log(this, "INFO", "shake_practice_timeout")
                    statusText.text = "לא זוהה ניעור. נסה שוב, בתנועה נמרצת יותר."
                    startButton.text = "נסה שוב"
                    startButton.visibility = View.VISIBLE
                    skipButton.visibility = View.VISIBLE
                }
            )
        }

        setContentView(scroll)
    }

    /**
     * מקשיב לחלון-זמן קצר (TAP_PRACTICE_TIMEOUT_MS) עם אותו ShakeDetector
     * ואותם ספים בדיוק שרצים ברקע — התרגול חייב לשקף את המציאות, אחרת
     * הוא מאשר משהו שלא יעבוד אחר-כך. אין כאן גזירת-ערכים: הניעור לא
     * דורש כיול אישי.
     */
    private fun startPracticeListening(
        onDetected: (reversals: Int, peak: Double) -> Unit,
        onTimeout: () -> Unit
    ) {
        val sm = (practiceSensorManager
            ?: (getSystemService(Context.SENSOR_SERVICE) as SensorManager).also { practiceSensorManager = it })
        val sensor = practiceAccelerometer ?: sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        practiceAccelerometer = sensor
        if (sensor == null) {
            EventLog.log(this, "ERROR", "no_accelerometer_sensor_found_practice")
            onTimeout()
            return
        }

        // איפוס המונים לתחילת ניסיון-האזנה חדש (כולל "נסה שוב")
        practiceSampleCount = 0
        practiceMaxMagnitude = 0.0
        practiceListenStartMs = System.currentTimeMillis()
        practiceDiagnosticTextRef?.text = "החלפות-כיוון: 0 · שיא: —"

        val detector = ShakeDetector(
            onLog = { tag, detail -> EventLog.log(this, tag, detail) },
            onShakeDetected = { reversals, peak ->
                stopPracticeListening()
                onDetected(reversals, peak)
            }
        )

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val x = event.values[0]; val y = event.values[1]; val z = event.values[2]
                val magnitude = Math.sqrt((x * x + y * y + z * z).toDouble())
                val now = System.currentTimeMillis()

                practiceSampleCount++
                if (magnitude > practiceMaxMagnitude) practiceMaxMagnitude = magnitude

                detector.onSample(x, y, z, now)

                // משוב חי על מה שבאמת קובע — מספר החלפות-הכיוון מול הדרוש.
                // מאפשר למשתמש לראות שהוא "מתקרב" במקום לנחש.
                practiceDiagnosticTextRef?.text =
                    "החלפות-כיוון: ${detector.currentReversals()}/${DebugConfig.SHAKE_MIN_REVERSALS} · " +
                        "שיא: ${"%.0f".format(practiceMaxMagnitude)}/${"%.0f".format(DebugConfig.SHAKE_MIN_PEAK)}"
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) { /* not used */ }
        }
        practiceListener = listener
        // SENSOR_DELAY_FASTEST — אותה סיבה כמו ב-TapDetectorService, וגם
        // כדי שהתרגול ידגום באותו קצב בדיוק כמו הזיהוי האמיתי ברקע.
        val registered = sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_FASTEST)
        EventLog.log(
            this, if (registered) "INFO" else "ERROR",
            "practice_sensor_registered;success=$registered"
        )

        val timeoutRunnable = Runnable {
            stopPracticeListening()
            onTimeout()
        }
        practiceTimeoutRunnable = timeoutRunnable
        practiceHandler.postDelayed(timeoutRunnable, DebugConfig.TAP_PRACTICE_TIMEOUT_MS)
    }

    private fun stopPracticeListening() {
        practiceTimeoutRunnable?.let { practiceHandler.removeCallbacks(it) }
        practiceTimeoutRunnable = null
        if (practiceListener != null) {
            // שורת-סיכום אחת לכל ניסיון-האזנה שהסתיים בפועל (הצלחה או
            // timeout) — לא ל-skip/onDestroy שקוראים לפונקציה הזו כשאין
            // ניסיון פעיל, כדי לא לרשום סיכום-ריק כפול.
            val elapsedMs = System.currentTimeMillis() - practiceListenStartMs
            val hzActual = if (elapsedMs > 0) practiceSampleCount / (elapsedMs / 1000.0) else 0.0
            EventLog.log(
                this, "INFO",
                "shake_practice_summary;samples=$practiceSampleCount;" +
                    "max_magnitude=${"%.2f".format(practiceMaxMagnitude)};" +
                    "elapsed_ms=$elapsedMs;hz_actual=${"%.1f".format(hzActual)}"
            )
        }
        practiceListener?.let { practiceSensorManager?.unregisterListener(it) }
        practiceListener = null
    }

    override fun onDestroy() {
        stopPracticeListening()
        // ליתר-ביטחון: אם יצאנו בדרך שלא עברה דרך stopRecording (למשל
        // hardware-back באמצע הקלטה) — recorder עלול עדיין להיות במצב
        // "מקליט", ו-release() ישיר על מצב כזה לא תקין. stop() קודם, גם
        // אם הוא עצמו נכשל (למשל הקלטה קצרה מדי).
        try {
            recorder?.stop()
        } catch (e: Exception) {
            // ראו ההערה ב-stopRecording — אותו מצב, לא קריטי
        }
        recorder?.release()
        player?.release()
        super.onDestroy()
    }
}
