package com.iluy.imutest

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * "מה קורה עכשיו?" — מסך הכיול.
 *
 * ## ⚠️ שאלה, לא אבחנה
 *
 * הכותרת היא "מה קורה עכשיו?", לא "זיהיתי משהו". היא לא מרמזת שהמערכת
 * יודעת דבר — בדיוק כמו הכלל בבנק 6. ההבדל היחיד ממנו: זו לא הצעה
 * שקטה, זו שאלה שמצפה למענה, כי כל הערך שלה הוא בתשובה.
 *
 * ## חמשת סוגי התשובה, וזה לא מקרי שיש חמישה
 *
 * | תשובה | מזיזה משקל? | מסלימה? |
 * |---|---|---|
 * | הרגל (קפה/עישון/תרופה) | לא | לא |
 * | במתח / כועס | לא | **כן** |
 * | ניסיון | לא (עוד) | **כן — RISK A** |
 * | ✕ התגברתי | — | דרך המסלול הקיים |
 * | כלום מיוחד | לא | לא |
 * | (לא נענה) | לא | לא |
 *
 * ⚠️ **"במתח/כעס" לא משתיק.** אם לחיצה עליו הייתה מקטינה תשומת לב,
 * זה היה נראה למשתמש כבגידה — לחץ כדי שיניחו לו, וקיבל יותר תשומת
 * לב. הטקסט מתחתיו אומר את זה במפורש כדי שלא יגלה בדיעבד.
 */
class CheckInActivity : Activity() {

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        KeyLog.record(this, "checkin", event)
        return super.dispatchKeyEvent(event)
    }

    companion object {
        private const val EXTRA_BPM = "bpm"
        private const val EXTRA_MEDIAN = "median"
        private const val EXTRA_DEV = "dev"

        /** נסגר לבד אם לא נענה — ראו ההבחנה מ-"כלום" בהערת-המחלקה. */
        private const val TIMEOUT_MS = 50_000L

        fun launch(context: Context, bpm: Int, median: Double, deviation: Int) {
            context.startActivity(
                Intent(context, CheckInActivity::class.java)
                    .putExtra(EXTRA_BPM, bpm)
                    .putExtra(EXTRA_MEDIAN, median)
                    .putExtra(EXTRA_DEV, deviation)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    private var answered = false
    private var bpm = 0
    private var median = 0.0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AlertHelper.wakeScreen(this)
        AlertHelper.playAlertSound(this)
        Immersive.apply(this)

        bpm = intent.getIntExtra(EXTRA_BPM, 0)
        median = intent.getDoubleExtra(EXTRA_MEDIAN, 0.0)
        val dev = intent.getIntExtra(EXTRA_DEV, 0)

        setContentView(buildLayout(dev))

        // ⚠️ `no_answer` נבדל מ"כלום מיוחד" — ראו CheckInLog. המסך נסגר
        // מעצמו כדי לא להישאר תלוי; זה עצמו מידע.
        window.decorView.postDelayed({
            if (!answered) answer("no_answer", finishAfter = true)
        }, TIMEOUT_MS)
    }

    private fun buildLayout(dev: Int): View {
        val scroll = ScrollView(this)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            setBackgroundColor(Color.parseColor("#0A0A0B"))
        }
        scroll.addView(col)

        col.addView(TextView(this).apply {
            text = "מה קורה עכשיו?"
            textSize = 19f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 6)
        })
        col.addView(TextView(this).apply {
            text = "הדופק שלך גבוה ב-$dev מהרגיל שלך עכשיו"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#7A7A78"))
            setPadding(0, 0, 0, 20)
        })

        // --- קבוצה א: הסברים, רק מה שסימן בשאלון ---
        val habits = LocalStore.getMultiChoice(this, LocalStore.KEY_Q6_HABITS)
        val habitLabels = buildList {
            if (habits.any { it.contains("קפה") }) add("קפה" to "coffee")
            if (habits.any { it.contains("מעשן") }) add("סיגריה" to "smoking")
            if (habits.any { it.contains("תרופה") }) add("תרופה" to "medication")
        }
        for ((label, key) in habitLabels) {
            col.addView(plainButton(label) { answer("habit:$key") })
        }

        // --- קבוצה ב: מופרדת בבירור, עם הסבר מה קורה בלחיצה ---
        if (habitLabels.isNotEmpty()) col.addView(divider())
        col.addView(plainButton("במתח / כועס") { onStress() })
        col.addView(TextView(this).apply {
            text = "לא משתיק את השעון — עוזר לו להכיר אותך יותר טוב"
            textSize = 10.5f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#5C5C5A"))
            setPadding(0, 0, 0, 6)
        })
        col.addView(divider())

        // --- קבוצה ג: מסלולי הדיווח, במרכז תשומת הלב ---
        col.addView(primaryButton("✕  התגברתי", "#2E7D5B") { onOvercome() })
        col.addView(primaryButton("ניסיון", "#8C3B34") { onArousal() })
        col.addView(secondaryButton("כלום מיוחד") { answer("nothing") })

        return scroll
    }

    private fun onStress() {
        // ⚠️ "mood_" ולא קטגוריה חדשה — מתח/כעס מסלים בדיוק כמו דיווח
        // מצב-רוח, ואין סיבה לבנות מסלול הסלמה שני לאותו דבר.
        OfferBudget.recordUserReport(this, "mood_stress")
        answer("stress")
    }

    private fun onArousal() {
        // ⚠️ אותה סיבה: "ניסיון" מתייג עוררות פעילה עכשיו, וזה בדיוק
        // מה שדיווח מצב-רוח כבר יודע להסלים אליו (RISK A).
        OfferBudget.recordUserReport(this, "mood_ניסיון")
        RiskFlowActivity.launch(this, source = "בדיקת כיול (ניסיון)")
        // RiskFlowActivity כבר בדרך מעליו; answer() סוגר את המסך הזה.
        answer("positive")
    }

    private fun onOvercome() {
        // ⚠️ launchUi=false: לא לפתוח עוד מסך אישור מעל זה. המשוב מוצג כאן.
        val report = OvercomingReporter.record(this, source = "בדיקת כיול", launchUi = false)
        answer("overcome", finishAfter = false)
        setContentView(TextView(this).apply {
            text = report.message
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#0A0A0B"))
            setPadding(26, 26, 26, 26)
            postDelayed({ finish() }, 2_200L)
        })
    }

    private fun answer(kind: String, finishAfter: Boolean = true) {
        if (answered) return
        answered = true
        val dev = intent.getIntExtra(EXTRA_DEV, 0)
        CheckInLog.record(this, bpm, median, dev, kind)
        if (finishAfter) finish()
    }

    private fun divider(): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 1
        ).apply { topMargin = 10; bottomMargin = 14 }
        setBackgroundColor(Color.parseColor("#1E1E20"))
    }

    private fun plainButton(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            textSize = 15f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#2A2A2E"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 6 }
            setOnClickListener { onClick() }
        }

    private fun primaryButton(label: String, colour: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            textSize = 17f
            setPadding(0, 22, 0, 22)
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor(colour))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 8 }
            setOnClickListener { onClick() }
        }

    private fun secondaryButton(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            textSize = 13f
            setTextColor(Color.parseColor("#9A9A98"))
            setBackgroundColor(Color.TRANSPARENT)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 14 }
            setOnClickListener { onClick() }
        }

    /** נפתח מפיזיולוגיה, לא מלחיצה — חזרה אינה פעולה משמעית. */
    override fun onBackPressed() { answer("no_answer") }
}
