package com.iluy.imutest

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * משפט אחד, על כל המסך.
 *
 * ## ⚠️ למה זה נדרש
 *
 * נבו, 2.8: *"ההתראות לא מוצגות טוב. שומעים צליל אבל לא מוצג כלום —
 * רק בשורת ההתראות רואים שורה שנחתכת."*
 *
 * `BigTextStyle` לא פתר את זה. שורת ההתראות של השעון הזה צרה, והמשפטים
 * בבנקים ארוכים ממנה. כלומר **כל העבודה על הטקסטים התבזבזה על משהו שלא
 * נקרא** — וזו לא בעיה אסתטית: הודעה שנחתכת באמצע היא הודעה שלא נאמרה.
 *
 * הפתרון הוא `setFullScreenIntent`, שפותח מסך במקום לצייר שורה. אותו
 * מנגנון שמשמש שיחות נכנסות, ומאותה סיבה: יש דברים שחייבים להיראות.
 *
 * ## נסגר לבד
 *
 * ⚠️ **הודעה שמחכה ללחיצה הופכת למטלה.** נבו העיר שחלק מהמסכים כדאי
 * שייסגרו לבד, וזה נכון במיוחד כאן: המשפטים האלה נאמרים ברגע קשה, והם
 * לא מבקשים כלום. מסך שנשאר תלוי עד שנוגעים בו הוא עוד דבר שצריך לטפל
 * בו בדיוק כשאין כוח.
 *
 * שאלות הן מקרה אחר לגמרי ולכן הן ב-[AskActivity] — שאלה **כן** מבקשת
 * משהו, ואסור שתיעלם לפני שנענתה.
 */
class MessageActivity : Activity() {

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        KeyLog.record(this, "message", event)
        return super.dispatchKeyEvent(event)
    }

    companion object {
        private const val EXTRA_TEXT = "text"
        private const val EXTRA_SOURCE = "source"
        private const val EXTRA_AUTO_CLOSE_MS = "auto_close_ms"

        /** ברירת מחדל: מספיק כדי לקרוא משפט ארוך בנחת, ולא יותר. */
        const val DEFAULT_AUTO_CLOSE_MS = 12_000L

        fun intentFor(
            context: Context,
            text: String,
            source: String,
            autoCloseMs: Long = DEFAULT_AUTO_CLOSE_MS
        ): Intent =
            Intent(context, MessageActivity::class.java)
                .putExtra(EXTRA_TEXT, text)
                .putExtra(EXTRA_SOURCE, source)
                .putExtra(EXTRA_AUTO_CLOSE_MS, autoCloseMs)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AlertHelper.wakeScreen(this)
        Immersive.apply(this)

        val text = intent.getStringExtra(EXTRA_TEXT) ?: return finish()
        val source = intent.getStringExtra(EXTRA_SOURCE) ?: "לא ידוע"
        val autoClose = intent.getLongExtra(EXTRA_AUTO_CLOSE_MS, DEFAULT_AUTO_CLOSE_MS)
        EventLog.log(this, "MESSAGE_SHOWN", "source=$source;close_ms=$autoClose")

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(30, 30, 30, 30)
            setBackgroundColor(Color.parseColor("#141414"))
        }

        root.addView(TextView(this).apply {
            this.text = text
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setLineSpacing(6f, 1.1f)
        })

        // ⚠️ קיים אבל קטן. אין מה לאשר כאן — זו לא בקשה — ולכן הוא לא
        // צריך להתחרות על תשומת הלב עם המשפט עצמו.
        root.addView(Button(this).apply {
            this.text = "סגור"
            textSize = 13f
            setBackgroundColor(Color.TRANSPARENT)
            setTextColor(Color.parseColor("#9A9A9A"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 26, 0, 0) }
            setOnClickListener { finish() }
        })

        setContentView(root)
        if (autoClose > 0) root.postDelayed({ finish() }, autoClose)
    }

    /** נפתח מהודעה — אין לאן לחזור. */
    override fun onBackPressed() { finish() }
}
