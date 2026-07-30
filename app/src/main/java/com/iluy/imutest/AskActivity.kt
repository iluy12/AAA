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
import android.widget.TextView

/**
 * שאלת כן/לא, ותשובה שמובילה לפעולה.
 *
 * ## ⚠️ הכלל שקבע נבו
 *
 * > **בכל מקום שיש שאלה — כן ולא. "כן" מרימה שיחה למלווה. "לא" עונה
 * > "אם צריך אני כאן".**
 *
 * הסיבה שזה חשוב: הודעה ששואלת *"רוצה נתחזק ביחד?"* בלי דרך לענות היא
 * רק עוד טקסט שנעלם. **וזו בדיוק ההצעה שלא נענית** — לא כי היא לא נכונה,
 * אלא כי אין מה לעשות איתה.
 *
 * ## ולמה "לא" לא מנדנד
 *
 * התשובה ל"לא" נאמרת פעם אחת ונסגרת. **מי שאמר לא, אמר לא.** מערכת
 * שממשיכה להציע אחרי סירוב מלמדת את המשתמש להתעלם ממנה — ואז גם ההצעה
 * הבאה, שאולי הייתה נחוצה, לא תיענה.
 */
class AskActivity : Activity() {

    /** רישום מקשים בלבד. לא צורך את המקש — ראו KeyLog. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        KeyLog.record(this, "ask", event)
        return super.dispatchKeyEvent(event)
    }

    companion object {
        private const val EXTRA_QUESTION = "question"
        private const val EXTRA_SOURCE = "source"

        fun intentFor(context: Context, question: String, source: String): Intent =
            Intent(context, AskActivity::class.java)
                .putExtra(EXTRA_QUESTION, question)
                .putExtra(EXTRA_SOURCE, source)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }

    private var source: String = "לא ידוע"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AlertHelper.wakeScreen(this)
        val question = intent.getStringExtra(EXTRA_QUESTION) ?: "רוצה שנדבר?"
        source = intent.getStringExtra(EXTRA_SOURCE) ?: "לא ידוע"
        EventLog.log(this, "ASK_SHOWN", "source=$source;q=$question")
        setContentView(buildLayout(question))
    }

    private fun buildLayout(question: String): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(24, 24, 24, 24)
        }

        root.addView(TextView(this).apply {
            text = question
            textSize = 17f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 28)
        })

        root.addView(answerButton("כן", primary = true) {
            EventLog.log(this, "ASK_RESULT", "answer=yes;source=$source")
            CallHelper.startCall(this, source = "$source ← כן")
            finish()
        })

        root.addView(answerButton("לא", primary = false) {
            EventLog.log(this, "ASK_RESULT", "answer=no;source=$source")
            showDeclineReply(root)
        })

        return root
    }

    /**
     * "לא" — משפט אחד, ונסגר לבד. בלי כפתור נוסף ובלי הצעה חוזרת.
     */
    private fun showDeclineReply(root: LinearLayout) {
        root.removeAllViews()
        root.addView(TextView(this).apply {
            text = Encouragements.DECLINE_REPLY
            textSize = 17f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
        })
        root.postDelayed({ finish() }, 2_500L)
    }

    private fun answerButton(label: String, primary: Boolean, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            textSize = 16f
            setPadding(0, 18, 0, 18)
            setBackgroundColor(if (primary) Color.parseColor("#2E7D5B") else Color.parseColor("#3A3A3A"))
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 8, 0, 8) }
            setOnClickListener { onClick() }
        }

    /** מסך שנפתח מהודעה לא אמור לחזור לשום מקום. */
    override fun onBackPressed() { finish() }
}
