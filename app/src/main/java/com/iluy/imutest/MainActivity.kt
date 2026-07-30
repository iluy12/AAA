package com.iluy.imutest

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat

/**
 * מסך הבית. "שום דבר על מסך השעון הראשי לא חושף כלום" (סעיף 10) — זה
 * מתייחס ל-watch face של המכשיר עצמו, לא למסך הזה שבתוך האפליקציה; שני
 * הכפתורים (מצב-רוח, נפלתי) מותרים כאן כי הם *בתוך* האפליקציה, לא גלויים
 * כברירת מחדל על השעון.
 */
class MainActivity : Activity() {

    /** רישום מקשים בלבד. לא צורך את המקש — ראו KeyLog. */
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        KeyLog.record(this, "main", event)
        return super.dispatchKeyEvent(event)
    }

    companion object {
        private const val LOG_DISPLAY_MAX_LINES = 150
        private const val REQUEST_BODY_SENSORS = 701
        private const val REQUEST_LOCATION = 702

        fun start(context: Context) {
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            context.startActivity(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!LocalStore.isQuestionnaireDone(this)) {
            startActivity(Intent(this, QuestionnaireActivity::class.java))
            finish()
            return
        }

        // worn-gating: אם יש חיישן דופק אבל אין הרשאה עדיין — מבקשים.
        // אם המשתמש ידחה, TapDetectorService פשוט לא יחסום כלום לפי זה
        // (נופל בחזרה ל"תמיד נחשב לבוש") — לא קריטי לתפקוד הבסיסי.
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.BODY_SENSORS)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            androidx.core.app.ActivityCompat.requestPermissions(
                this, arrayOf(android.Manifest.permission.BODY_SENSORS), REQUEST_BODY_SENSORS
            )
        }

        // מיקום: רק "בבית או לא". נקרא המיקום האחרון הידוע ולא מודלק
        // מקלט, אז העלות אפסית — אבל ההרשאה עדיין נדרשת. סירוב מחזיר
        // אות אחד פחות ולא שובר כלום.
        if (!PlaceTracker.hasPermission(this)) {
            androidx.core.app.ActivityCompat.requestPermissions(
                this, arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION), REQUEST_LOCATION
            )
        }

        // השאלון כבר הושלם — מפעילים את שירות זיהוי ההקשה ברקע באופן קבוע
        TapDetectorService.start(this)

        renderHome()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_BODY_SENSORS &&
            grantResults.isNotEmpty() && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            // setupWornGating() ב-TapDetectorService רץ פעם אחת ב-onCreate ולא
            // בודק שוב אחר-כך — מפעילים מחדש כדי שהשירות יבדוק את ההרשאה
            // שעכשיו קיימת ויפעיל worn-gating מבוסס-דופק אם צריך.
            TapDetectorService.stop(this)
            TapDetectorService.start(this)
        }
    }

    private var logDisplay: TextView? = null

    private fun renderHome() {
        val scroll = ScrollView(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 32, 28, 28)
        }
        scroll.addView(container)

        container.addView(TextView(this).apply {
            text = "עילוי"
            textSize = 20f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 6)
        })
        container.addView(TextView(this).apply {
            text = "פעיל ברקע"
            textSize = 12f
            setTextColor(ContextCompat.getColor(context, R.color.text_tertiary))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 24)
        })

        container.addView(bigButton("מצב-רוח") {
            startActivity(Intent(this, MoodPickerActivity::class.java))
        })

        container.addView(bigButton("נפלתי", primary = true) {
            EventLog.log(this, "TRIGGER", "fell_button;note=no_physio_buffer_v1")
            RiskFlowActivity.launch(this, source = "כפתור 'נפלתי'")
        })

        container.addView(bigButton("כל האפליקציות") {
            AppDrawerActivity.launch(this)
        })

        val updateStatus = TextView(this).apply {
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(ContextCompat.getColor(context, R.color.text_tertiary))
            setPadding(0, 6, 0, 0)
            visibility = android.view.View.GONE
        }

        container.addView(secondaryButton("מלא שאלון מחדש") {
            LocalStore.setQuestionnaireDone(this, false)
            EventLog.log(this, "INFO", "questionnaire_restart_requested")
            startActivity(Intent(this, QuestionnaireActivity::class.java))
            finish()
        })

        container.addView(secondaryButton("אפס הכל והתחל שאלון") {
            // מוחק גם את הלוח וגם את התשובות. לבדיקות: מאפשר להתחיל
            // מאפס בלי להסיר את האפליקציה — מה שממילא לא תמיד אפשרי
            // כשעילוי היא מסך-הבית.
            LocalStore.resetAll(this)
            startActivity(Intent(this, QuestionnaireActivity::class.java))
            finish()
        })

        container.addView(secondaryButton("בדוק עדכון") {
            updateStatus.visibility = android.view.View.VISIBLE
            updateStatus.text = "בודק…"
            UpdateChecker.check(this) { result ->
                when (result) {
                    is UpdateChecker.Result.UpToDate ->
                        updateStatus.text = "הגרסה עדכנית (build ${BuildConfig.VERSION_CODE})"
                    is UpdateChecker.Result.Failed ->
                        updateStatus.text = result.reason
                    is UpdateChecker.Result.Found -> {
                        updateStatus.text = "נמצא build ${result.update.buildNumber} — מוריד"
                        UpdateChecker.downloadAndInstall(this, result.update) { status ->
                            updateStatus.text = status
                        }
                    }
                }
            }
        })
        container.addView(updateStatus)

        addHomeAppSection(container)

        if (DebugConfig.DEBUG_TAG_ENABLED) {
            container.addView(TextView(this).apply {
                text = "מצב בדיקה (v1)"
                textSize = 12f
                setTextColor(ContextCompat.getColor(context, R.color.text_tertiary))
                setPadding(0, 32, 0, 6)
            })
            container.addView(secondaryButton("הצג לוג אירועים") {
                toggleLogDisplay()
            })
            container.addView(secondaryButton("העתק לוג ללוח") {
                copyLogToClipboard()
            })
            container.addView(secondaryButton("נקה לוג") {
                clearLog()
            })
            container.addView(secondaryButton("שלח לוג ← קבל כתובת") {
                uploadLog()
            })
            // ⚠️ בלחיצה בלבד, ולא בהפעלה. הסריקה מוסיפה עשרות שורות
            // בבת-אחת, ואילו רצה מעצמה היא הייתה דוחקת החוצה את סיכומי
            // הדופק — כלומר הורסת בדיוק את בדיקת-הלילה שבשבילה הכל נבנה.
            // הסדר הנכון: קודם לשלוח את לוג-הלילה, אחר-כך לסרוק, ולשלוח שוב.
            // מחיקת הבסיס והרשומות. נחוץ אחרי תיקון שמשנה מה נחשב מנוחה —
            // בסיס מזוהם אינו ניתן לתיקון בדיעבד, ראו Baseline.reset.
            container.addView(secondaryButton("אפס בסיס דופק") {
                Baseline.reset(this)
                SampleStore.clear(this)
                logDisplay?.apply {
                    text = "הבסיס והרשומות נמחקו. האיסוף מתחיל מחדש."
                    visibility = android.view.View.VISIBLE
                }
            })
            container.addView(secondaryButton("סרוק את השעון") {
                // ברקע: getInstalledPackages עם כל הרכיבים לוקח שניות על
                // המכשיר הזה, וכל שורה גם נכתבת לקובץ. על החוט הראשי זה
                // מקפיא את המסך ועלול להגיע ל-ANR.
                Thread {
                    SystemScan.run(this)
                    runOnUiThread { uploadLog() }
                }.start()
            })

            val display = TextView(this).apply {
                textSize = 10f
                setTextColor(ContextCompat.getColor(context, R.color.text_tertiary))
                setPadding(0, 16, 0, 0)
                visibility = android.view.View.GONE
            }
            logDisplay = display
            container.addView(display)
        }

        // דרך-חזרה גלויה. כשעילוי היא מסך-הבית זה חוזר למסך-השעון;
        // אחרת זה סוגר את האפליקציה — בשני המקרים יציאה צפויה.
        container.addView(secondaryButton("חזרה") { finish() })

        container.addView(TextView(this).apply {
            text = "גרסה ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · נבנה ${BuildConfig.BUILD_TIMESTAMP}"
            textSize = 9f
            setTextColor(ContextCompat.getColor(context, R.color.text_tertiary))
            gravity = Gravity.CENTER
            setPadding(0, 32, 0, 0)
        })

        setContentView(scroll)
    }

    /**
     * הגדרת עילוי כמסך-הבית.
     *
     * במכשירים זולים היצרן לעיתים עורך את ההגדרות ומשמיט את הפריט
     * "מסך בית", אבל אנדרואיד עדיין מחזיק את המסך עצמו — רק בלי קיצור
     * אליו. ACTION_HOME_SETTINGS קופץ ישירות לשם.
     *
     * מוצג גם מי מוגדר כרגע, כדי שיהיה אפשר לוודא שההגדרה נתפסה בלי
     * לנחש לפי התנהגות הכפתור הצדדי.
     */
    private fun addHomeAppSection(container: LinearLayout) {
        val currentHome = resolveCurrentHomePackage()
        val isUs = currentHome == packageName

        container.addView(TextView(this).apply {
            text = if (isUs) {
                "מסך הבית: עילוי ✓"
            } else {
                "מסך הבית כרגע: ${currentHome ?: "לא ידוע"}"
            }
            textSize = 11f
            setTextColor(ContextCompat.getColor(context, R.color.text_tertiary))
            gravity = Gravity.CENTER
            setPadding(0, 24, 0, 0)
        })

        // מוצג בשני המצבים. כשעילוי היא מסך-הבית זו דרך-החזרה היחידה —
        // הלאנצ'ר של היצרן הוחלף, ואיתו כל דרכי-הניווט שלו.
        container.addView(
            secondaryButton(
                if (isUs) "החזר את מסך הבית המקורי" else "הגדר את עילוי כמסך הבית"
            ) { openHomeSettings() }
        )

        if (isUs) {
            container.addView(TextView(this).apply {
                text = "לביטול: בחר את מסך הבית המקורי ברשימה שתיפתח"
                textSize = 10f
                setTextColor(ContextCompat.getColor(context, R.color.text_tertiary))
                gravity = Gravity.CENTER
                setPadding(0, 4, 0, 0)
            })
        }
    }

    private fun resolveCurrentHomePackage(): String? {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolved = packageManager.resolveActivity(
            intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY
        )
        return resolved?.activityInfo?.packageName
    }

    private fun openHomeSettings() {
        // נפילה-חזרה מדורגת: המסך הייעודי, ואם היצרן הסיר אותו — ההגדרות
        // הכלליות, שתמיד קיימות.
        val candidates = listOf(
            Intent(android.provider.Settings.ACTION_HOME_SETTINGS),
            Intent(android.provider.Settings.ACTION_SETTINGS)
        )
        for (intent in candidates) {
            try {
                startActivity(intent)
                EventLog.log(this, "INFO", "home_settings_opened;action=${intent.action}")
                return
            } catch (e: Exception) {
                // ננסה את הבא בתור
            }
        }
        EventLog.log(this, "ERROR", "home_settings_unavailable")
        Toast.makeText(this, "לא הצלחתי לפתוח את ההגדרות", Toast.LENGTH_LONG).show()
    }

    private fun toggleLogDisplay() {
        val display = logDisplay ?: return
        if (display.visibility == android.view.View.VISIBLE) {
            display.visibility = android.view.View.GONE
            return
        }
        val lines = EventLog.readLastN(this, LOG_DISPLAY_MAX_LINES)
        val total = EventLog.readAll(this).size
        val header = if (total > lines.size) {
            "מציג $LOG_DISPLAY_MAX_LINES שורות אחרונות מתוך $total\n\n"
        } else ""
        display.text = header + lines.joinToString("\n")
        display.visibility = android.view.View.VISIBLE
    }

    private fun copyLogToClipboard() {
        val lines = EventLog.readAll(this)
        val full = lines.joinToString("\n")
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText("iluy_events", full))
        Toast.makeText(this, "כל הלוג הועתק ללוח (${lines.size} שורות)", Toast.LENGTH_LONG).show()
    }

    /**
     * מעלה את הלוג ומציג את הכתובת הקצרה על המסך, גדול וברור — היא
     * נועדה להיקרא ולהיאמר, לא להיות מועתקת (העתקה על השעון הזה היא
     * בדיוק מה שלא עובד).
     */
    private fun uploadLog() {
        val display = logDisplay ?: return
        display.text = "מעלה…"
        display.textSize = 14f
        display.visibility = android.view.View.VISIBLE

        LogUploader.upload(this) { result ->
            // הכתובת מוצגת גדולה ובמונחה — היא נועדה להיקרא ולהיאמר,
            // ותו אחד שגוי הופך אותה לחסרת-ערך. אות אחת שהועתקה לא נכון
            // כבר עלתה לנו סבב.
            val slug = result.substringAfterLast('/')
            display.text = if (result.startsWith("http")) "$slug\n\n$result" else result
            display.textSize = if (result.startsWith("http")) 22f else 14f
            display.setTextColor(android.graphics.Color.BLACK)
            EventLog.log(this, "INFO", "log_uploaded;result=$result")
        }
    }

    private fun clearLog() {
        EventLog.clear(this)
        logDisplay?.apply {
            text = ""
            visibility = android.view.View.GONE
        }
        Toast.makeText(this, "הלוג נוקה", Toast.LENGTH_SHORT).show()
    }

    private fun bigButton(label: String, primary: Boolean = false, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            setTextColor(android.graphics.Color.WHITE)
            setBackgroundColor(
                ContextCompat.getColor(
                    context,
                    if (primary) R.color.emerald_primary else R.color.emerald_primary_hover
                )
            )
            textSize = 15f
            minHeight = 130
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = 12
            layoutParams = lp
            setOnClickListener { onClick() }
        }

    private fun secondaryButton(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            textSize = 13f
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = 8
            layoutParams = lp
            setOnClickListener { onClick() }
        }
}
