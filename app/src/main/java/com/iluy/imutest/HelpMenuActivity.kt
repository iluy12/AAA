package com.iluy.imutest

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat

/**
 * תפריט סיוע — זהה בכל הכניסות (סעיף 9): חיזקי, תקשיב לעצמך, סרטון חיזוק,
 * עצות, חיוג מיידי.
 */
class HelpMenuActivity : Activity() {

    /** רישום מקשים בלבד. לא צורך את המקש — ראו KeyLog. */
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        KeyLog.record(this, "help_menu", event)
        return super.dispatchKeyEvent(event)
    }

    companion object {
        const val EXTRA_SOURCE = "extra_source"

        fun launch(context: Context, source: String) {
            val intent = Intent(context, HelpMenuActivity::class.java).apply {
                putExtra(EXTRA_SOURCE, source)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            context.startActivity(intent)
        }
    }

    private lateinit var source: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        source = intent.getStringExtra(EXTRA_SOURCE) ?: "לא ידוע"
        EventLog.log(this, "HELP_MENU_SHOWN", "source=$source")
        setContentView(buildLayout())
    }

    private fun buildLayout(): View {
        val scroll = ScrollView(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 40, 28, 28)
        }
        scroll.addView(container)

        if (DebugConfig.DEBUG_TAG_ENABLED) {
            container.addView(TextView(this).apply {
                text = "הופעל ע\"י: $source"
                textSize = 11f
                setTextColor(Color.WHITE)
                setBackgroundColor(ContextCompat.getColor(context, R.color.danger))
                setPadding(12, 6, 12, 6)
                gravity = Gravity.CENTER
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.bottomMargin = 20
                layoutParams = lp
            })
        }

        container.addView(TextView(this).apply {
            text = "איך אפשר לעזור עכשיו?"
            textSize = 17f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 20)
        })

        container.addView(menuButton("חיזקי") {
            EventLog.log(this, "HELP_TOOL", "chizki;source=$source")
            PlaceholderActivity.launch(
                this, title = "חיזקי",
                message = "כאן תהיה התכתבות עם בוט (placeholder ל-v1)."
            )
        })

        // ⚠️ שני אלה היו placeholder עד היום, והם הכלים שנבו ביקש שהמערכת
        // תשתמש בהם הרבה יותר. עכשיו הם מסכים אמיתיים — ואותם מסכים בדיוק
        // שהתשובה "כן" בהודעות מובילה אליהם, כדי שלא ייווצרו שני מסלולים
        // שיכולים להיפרד.
        container.addView(menuButton("תקשיב לעצמך") {
            EventLog.log(this, "HELP_TOOL", "listen_to_yourself;source=$source")
            RecordingActivity.launch(this, source)
        })

        container.addView(menuButton("סרטון חיזוק") {
            EventLog.log(this, "HELP_TOOL", "encouragement_video;source=$source")
            VideoActivity.launch(this, source)
        })

        container.addView(menuButton("עצות") {
            EventLog.log(this, "HELP_TOOL", "tips;source=$source")
            showTips()
        })

        container.addView(menuButton("חיוג מיידי", primary = true) {
            EventLog.log(this, "HELP_TOOL", "immediate_call;source=$source")
            CallHelper.startCall(this, source = "חיוג מיידי (מ: $source)")
        })

        // כל מסך חייב דרך-חזרה גלויה. מכוון-בעיצוב שונה משאר הכפתורים
        // (טקסט בלבד) כדי שלא ייראה ככלי-סיוע נוסף.
        container.addView(Button(this).apply {
            text = "חזרה"
            setTextColor(ContextCompat.getColor(context, R.color.text_tertiary))
            setBackgroundColor(Color.TRANSPARENT)
            textSize = 13f
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = 20
            layoutParams = lp
            setOnClickListener { finish() }
        })

        return scroll
    }

    // ⚠️ **`playPersonalRecording` נמחק ולא הושאר "ליתר ביטחון".** הוא
    // ניגן את אותה הקלטה בקוד משלו, עם הודעת-שגיאה משלו ומצב-ריק משלו.
    // שני מסלולים לאותו דבר נפרדים בשקט ברגע שאחד מהם משתנה — וזה בדיוק
    // מה שכבר קרה כאן פעם, כששכבת-הגנה נעלמה בלי שאיש שם לב.
    // RecordingActivity הוא הבעלים היחיד.

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        CallHelper.onPermissionResult(this, requestCode, grantResults)
    }

    private fun showTips() {
        val userTips = LocalStore.getMultiChoice(this, LocalStore.KEY_Q5_HELPS)
        val genericTips = listOf(
            "צא להליכה קצרה, גם רק סביב הבית",
            "שתה כוס מים לאט",
            "תן ל-5 דקות לעבור לפני שתחליט משהו",
            "התקשר לחבר או חברותא"
        )
        val allTips = (userTips + genericTips).distinct()
        val text = if (allTips.isEmpty()) "אין עדיין עצות שמורות." else allTips.joinToString("\n• ", prefix = "• ")
        PlaceholderActivity.launch(this, title = "עצות", message = text)
    }

    private fun menuButton(label: String, primary: Boolean = false, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            setTextColor(Color.WHITE)
            setBackgroundColor(
                ContextCompat.getColor(
                    context,
                    if (primary) R.color.emerald_primary else R.color.emerald_primary_hover
                )
            )
            textSize = 15f
            minHeight = 110
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = 14
            layoutParams = lp
            setOnClickListener { onClick() }
        }
}
