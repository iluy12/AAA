package com.iluy.imutest

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
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
    /**
     * ⚠️ **המספר הזה וה-`when` ב-[renderStep] חייבים להתאים, ואי-התאמה
     * לא מתפוצצת — היא מציגה מסך ריק.**
     *
     * וזה כבר קרה: כשנוספה שאלת השעה העיקרית היא קיבלה את המספר 1,
     * שכבר היה תפוס על ידי שאלת התדירות — **ושאלת התדירות הפכה לבלתי
     * נגישה בשקט.** המשתמש היה עובר עליה בלי לדעת, והמערכת הייתה מקבלת
     * מרווח נפילות ריק.
     *
     * הסדר: שעות ← עיקרית ← תדירות ← מקום ← תנוחה ← שקט לפני ← מה עוזר
     * ← הרגלים ← שתי הסכמות ← סף סירובים ← רצף ארוך.
     */
    private val totalSteps = 12

    companion object {

        private const val REQUEST_RECORD_AUDIO = 601

        /**
         * טווחי שעתיים לאורך כל היממה.
         *
         * ⚠️ **הפורמט הוא `HH-HH` והוא נקרא על ידי המערכת**, לא רק מוצג.
         * [RiskContext.hourMatchesDeclared] מפרסר אותו. תווית בסגנון
         * "אחרי הצהריים" הייתה נראית ידידותית יותר ומחייבת טבלת המרה
         * שנייה — שהיא בדיוק הדבר שנפרד מהמקור ברגע שמישהו מוסיף אפשרות.
         *
         * ⚠️ מתחיל ב-06 בכוונה: היממה נפתחת עם היקיצה, ולא בחצות. מי
         * שהזמן המסוכן שלו הוא 01:00 יסמן את הטווח האחרון, שנמצא בסוף
         * הרשימה ולא בתחילתה.
         */
        val RISK_HOURS = listOf(
            "06-08", "08-10", "10-12", "12-14", "14-16", "16-18",
            "18-20", "20-22", "22-00", "00-02", "02-04", "04-06"
        )
    }

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

    // --- תרגול מחוות ✕ ---

    private var practiceDownX = 0f
    private var practiceDownY = 0f
    private var practiceLastStrokeDirection = 0
    private var practiceLastStrokeMs = 0L


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
            // ⚠️ **השאלה החשובה ביותר במוצר בשבוע הראשון.**
            //
            // ההדק לדגימה צפופה נפתח על "שקט מתמשך + שעה מוצהרת", וביום
            // הראשון אין בסיס דופק ואין היסטוריה — כלומר **התשובה כאן
            // היא ההדק היחיד שקיים.**
            //
            // ⚠️ הטווחים היו 4-7 שעות ("ערב" = ארבע שעות), והתקציב הוא
            // 3-4 חלונות ביום. תשובה כזו הייתה שורפת אותו בחצי שעה.
            //
            // ⚠️ **ובכל זאת לא טווח יחיד של שעתיים.** אדם לא יודע את
            // השעות שלו ברזולוציה כזו, וכפייה לבחור אחד מייצרת **דיוק
            // מזויף** — הוא ימציא, והמערכת תתייחס לזה כאילו נמדד.
            //
            // בחירה מרובה פותרת את שניהם: הוא לא מתחייב, ואנחנו מקבלים
            // כיסוי. הסימון של העיקרי (בשלב הבא) נותן את סדר העדיפויות
            // שנדרש לשריון החלונות.
            0 -> renderMultiChoice(
                container, "באילו שעות זה קורה אצלך? (אפשר לסמן כמה)",
                RISK_HOURS,
                LocalStore.KEY_Q1_TIMES
            )
            // ⚠️ "כל כמה זמן" הוחלף ב"כמה פעמים בחודש האחרון" — שאלה על
            // מספר שקרה בפועל קלה לזכור מהערכת תדירות, והיא גם מה שהאלגוריתם
            // באמת צריך: מרווח ממוצע בימים.
            // ⚠️ **מבין הטווחים שסימן — איזה הכי הרבה.** בלי זה יש לנו
            // כיסוי בלי סדר עדיפויות, וכשהתקציב היומי נגמר אין דרך לדעת
            // איזה חלון לשריין. מוצג רק מה שהוא סימן, כדי שהשאלה תהיה
            // קצרה ולא חזרה על הקודמת.
            1 -> renderSingleChoice(
                container, "ומתוכן — מתי הכי הרבה?",
                LocalStore.getMultiChoice(this, LocalStore.KEY_Q1_TIMES).ifEmpty { RISK_HOURS },
                LocalStore.KEY_Q1_PRIMARY_HOUR
            )
            // ⚠️ **מיפוי ישיר לטביעת החדר.** "אותו חדר / משתנה" לא נצרך
            // על ידי שום דבר במערכת — הוא לא ניתן להצלבה עם מה שהשעון
            // רואה. שם של חדר כן: הוא הופך את הטביעה מ"מקום 3" ל"חדר
            // השינה", וזה גם מה שמאפשר להסביר החלטה למשתמש.
            // ⚠️ "כל כמה זמן" הוחלף ב"כמה פעמים בחודש האחרון" — שאלה על
            // מספר שקרה בפועל קלה לזכור מהערכת תדירות, והיא גם מה
            // שהאלגוריתם באמת צריך: מרווח ממוצע בימים.
            2 -> renderNumber(
                container, "בחודש האחרון, כמה פעמים נפלת?",
                LocalStore.KEY_Q2_FREQUENCY,
                shortcuts = listOf(1, 2, 4, 8, 15),
                suffix = "פעמים"
            )
            3 -> renderMultiChoice(
                container, "איפה זה בד\"כ קורה? (אפשר לסמן כמה)",
                listOf("חדר שינה", "סלון", "שירותים", "מקלחת", "מחוץ לבית"),
                LocalStore.KEY_Q3_PLACE
            )
            4 -> renderMultiChoice(
                container, "באיזו תנוחה בד\"כ? (בחירה מרובה)",
                listOf("יושב", "שוכב", "עומד"),
                LocalStore.KEY_Q3_POSITION
            )
            // ⚠️ **מיפוי ישיר ל-`stillMs`, שהוא חצי מההדק.**
            //
            // ⚠️ ושאלת מצבי-הרוח שהייתה כאן ("שיעמום, עצבנות, לחץ")
            // **הוסרה.** קריטריון הסינון הוא אחד: **רק מה שהשעון יכול
            // לראות.** מתח ובדידות אינם ניתנים לזיהוי בשום צורה, ולכן
            // כשאלה בשאלון הם מייצרים נתון שההדק לא יכול להשתמש בו.
            // כדיווח בזמן אמת הם שווים הרבה — ושם מקומם, במסך הווידוא.
            5 -> renderSingleChoice(
                container, "כמה זמן אתה בד\"כ לבד ובשקט לפני שזה קורה?",
                listOf("מיד", "אחרי כמה דקות", "אחרי חצי שעה ויותר"),
                LocalStore.KEY_Q12_QUIET_BEFORE
            )
            6 -> renderMultiChoice(
                container, "מה עוזר לך לצאת מזה? (בחירה מרובה)",
                listOf("הליכה קצרה", "שתיית מים", "שיחה עם חבר", "תפילה", "לימוד", "מוזיקה", "נשימות עמוקות", "יציאה מהחדר"),
                LocalStore.KEY_Q5_HELPS
            )
            // ⚠️ **שורה אחת, ורק כדי לדעת אילו כפתורים להציג במסך
            // הווידוא.** לא "מה מעלה לך את הדופק" — אנשים לא יודעים
            // לענות על זה באוויר, והתשובה תהיה ניחוש בלי חותמת זמן ובלי
            // הקשר. הדיכוי דורש בדיוק את שני אלה, ולכן הוא נלמד מהרגע
            // עצמו ולא מכאן.
            //
            // מה שכן שווה: לדעת מראש אילו הסברים בכלל רלוונטיים לאדם
            // הזה, כדי **לקצר** את מסך הווידוא במקום להאריך אותו. רשימה
            // של 12 אפשרויות ברגע מתוח = הוא לא עונה.
            7 -> renderMultiChoice(
                container, "מה מהבאים רלוונטי אצלך?",
                listOf("שותה קפה", "מעשן", "לוקח תרופה קבועה"),
                LocalStore.KEY_Q6_HABITS
            )
            8 -> renderConsent(
                container, "בסדר שאתקשר מדי פעם?",
                LocalStore.KEY_Q7_CONSENT_CALL
            )
            9 -> renderConsent(
                container, "בסדר שאשלח הודעה מדי פעם?",
                LocalStore.KEY_Q8_CONSENT_MESSAGE
            )
            // הנתון שקובע מתי המערכת מציעה חיזוק מורחב. מחליף סף שהומצא
            // ("שנייה בשעה") בסף שיש לו משמעות אצל האדם הזה.
            10 -> renderNumber(
                container, "כשהיצר בא אליך, כמה פעמים אתה מצליח להגיד \"לא\" לפני שקורה משהו?",
                LocalStore.KEY_Q10_REFUSALS,
                shortcuts = listOf(1, 2, 3, 5, 8),
                suffix = "פעמים"
            )
            // ⚠️ נקודת הייחוס האישית. בלעדיה המערכת לא יודעת אם רצף של
            // שבועיים הוא שיא או שגרה — וזה משנה גם את הזיהוי וגם, ובעיקר,
            // את מה שהיא אומרת לו.
            11 -> renderNumber(
                container, "מה הכי הרבה ימים שהחזקת נקי?",
                LocalStore.KEY_Q11_LONGEST_STREAK,
                shortcuts = listOf(7, 14, 30, 60, 90),
                suffix = "ימים"
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

    /**
     * שאלה שהתשובה שלה מספר.
     *
     * ⚠️ **הוחלפו כאן שאלות שהיו טקסט גס.** "כמה חודשים" חייב אותי להמיר
     * ל-75 ימים בניחוש, ו"2-3 פעמים" ל-2.5 — כלומר האלגוריתם ניזון
     * ממספרים שהמצאתי, בדיוק הדבר שהמוצר נמנע ממנו בכל מקום אחר. מספר
     * שהמשתמש בחר בעצמו הוא הנתון, לא פרשנות שלו.
     */
    private fun renderNumber(
        container: LinearLayout,
        title: String,
        key: String,
        shortcuts: List<Int>,
        suffix: String
    ) {
        addTitle(container, title)
        val current = LocalStore.getSingleChoice(this, key).toIntOrNull() ?: 0
        var chosen = current
        container.addView(
            NumberPicker.build(this, current, shortcuts, suffix) { picked ->
                chosen = picked
                LocalStore.saveSingleChoice(this, key, picked.toString())
            }
        )
        // ⚠️ **בלי זה השאלון נתקע.** כל שאר סוגי השאלות מוסיפים את כפתור
        // ההמשך בעצמם, ו-renderNumber נכתב בלעדיו — כלומר המשתמש הגיע
        // לשאלה ולא הייתה לו דרך להתקדם ממנה. מסך ללא מוצא הוא שיתוק,
        // לא באג קוסמטי.
        addNextButton(container) {
            LocalStore.saveSingleChoice(this, key, chosen.toString())
            advance()
        }
    }

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
     * הוראת-המחווה: ציור ✕ על מסך-השעון.
     *
     * החליפה את כל הזיהוי מבוסס-התאוצה (הקשה, ואז ניעור) שנכשל בחמישה
     * סבבים — ראו WatchFaceActivity להסבר המלא. מגע הוא נתון מדויק ולא
     * אות רועש, ולכן התרגול כאן לא מכייל דבר: הוא רק מלמד את המחווה.
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
            text = "בכל פעם שהתגברת על ניסיון:\n" +
                "הדלק את המסך וצייר ✕ קטן במרכז המסך — שני קווים " +
                "אלכסוניים קצרים.\n\n" +
                "קטן ובמרכז, לא מקצה לקצה: קו שמתחיל בשולי המסך נחטף " +
                "למחוות-מערכת לפני שהוא מגיע אלינו.\n\n" +
                "אין צורך לפתוח שום דבר. מי שמסתכל רואה שעון רגיל."
            textSize = 14f
            setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            setPadding(0, 0, 0, 20)
        })

        val statusText = TextView(this).apply {
            text = "נסה עכשיו — צייר ✕ קטן במרכז המסגרת שלמטה"
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 12)
        }
        container.addView(statusText)

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

        val practicePad = TextView(this).apply {
            text = "צייר כאן ✕ קטן, במרכז"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(ContextCompat.getColor(context, R.color.text_tertiary))
            setBackgroundColor(ContextCompat.getColor(context, R.color.bg_grey))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 260
            )
            layoutParams = lp
        }
        val skipButton = Button(this).apply {
            text = "המשך בלי תרגול"
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = 12
            layoutParams = lp
            setOnClickListener {
                EventLog.log(this@QuestionnaireActivity, "INFO", "x_gesture_practice_skipped")
                renderRecordingStep()
            }
        }

        practicePad.setOnTouchListener { view, event ->
            handlePracticeTouch(view, event) {
                EventLog.log(this, "INFO", "x_gesture_practice_success")
                statusText.text = "✓ נקלט בהצלחה"
                practicePad.text = "✓"
                // "המשך" מחליף את "המשך בלי תרגול" במקום להצטרף אליו —
                // אחרי שהתרגול הצליח, "בלי תרגול" כבר לא אפשרות רלוונטית
                // וזה רק מבלבל.
                skipButton.visibility = View.GONE
                continueButton.visibility = View.VISIBLE
            }
        }
        container.addView(practicePad)
        container.addView(skipButton)
        container.addView(continueButton)

        setContentView(scroll)
    }

    /**
     * זיהוי ✕ לתרגול — אותם כללים בדיוק כמו ב-WatchFaceActivity: שני
     * קווים אלכסוניים בכיוונים מנוגדים בתוך חלון-זמן. התרגול חייב לשקף
     * את המציאות, אחרת הוא מאשר משהו שלא יעבוד אחר-כך.
     */
    private fun handlePracticeTouch(view: View, event: MotionEvent, onXDrawn: () -> Unit): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                // בלי זה ה-ScrollView שמסביב חוטף כל גרירה אנכית לגלילה,
                // והקו האלכסוני אף פעם לא מגיע לכאן שלם.
                view.parent?.requestDisallowInterceptTouchEvent(true)
                practiceDownX = event.x
                practiceDownY = event.y
            }
            MotionEvent.ACTION_UP -> {
                val dx = event.x - practiceDownX
                val dy = event.y - practiceDownY
                val length = Math.hypot(dx.toDouble(), dy.toDouble())

                val minSide = Math.min(view.width, view.height)
                if (length < minSide * DebugConfig.X_GESTURE_MIN_STROKE_FRACTION) return true

                val shorterAxis = Math.min(Math.abs(dx), Math.abs(dy))
                if (shorterAxis < length * DebugConfig.X_GESTURE_MIN_DIAGONAL_RATIO) return true

                val direction = if (dx * dy > 0) 1 else -1
                val now = System.currentTimeMillis()
                val isSecondStroke = practiceLastStrokeDirection != 0 &&
                    direction != practiceLastStrokeDirection &&
                    now - practiceLastStrokeMs <= DebugConfig.X_GESTURE_MAX_INTERVAL_MS

                if (isSecondStroke) {
                    practiceLastStrokeDirection = 0
                    practiceLastStrokeMs = 0L
                    onXDrawn()
                } else {
                    practiceLastStrokeDirection = direction
                    practiceLastStrokeMs = now
                }
            }
        }
        return true
    }

    override fun onDestroy() {
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
