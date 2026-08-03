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
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * אישור לדיווח נפילה שנעשה בנגיעה רגילה, בלי בחירת סוג.
 *
 * ⚠️ **רק המסלול הזה מקבל אישור, ולא ההפך.** לחיצה ארוכה היא מחווה
 * מכוונת שאי-אפשר לעשות בכיס, והיא פותחת ישר את בחירת הסוג. נגיעה
 * רגילה אפשר לעשות בטעות — ולכן דווקא **המסלול הקצר** מקבל שער נוסף.
 *
 * ## למה כפתור אחד גדול ו-✕ קטן
 *
 * ברגע של בושה, כל החלטה נוספת היא חיכוך. **"אישור" חייב להיות המסלול
 * הקל** — גדול, במרכז, בלי לחשוב. הביטול קיים כי לחיצה בטעות אפשרית,
 * אבל הוא לא צריך להתחרות עליו בגודל.
 */
class FallConfirmActivity : Activity() {

    /** רישום מקשים בלבד. לא צורך את המקש — ראו KeyLog. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        KeyLog.record(this, "fall_confirm", event)
        return super.dispatchKeyEvent(event)
    }

    companion object {
        fun launch(context: Context) {
            context.startActivity(
                Intent(context, FallConfirmActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AlertHelper.wakeScreen(this)
        EventLog.log(this, "FALL", "confirm_shown")
        setContentView(buildLayout())
    }

    private fun buildLayout(): View {
        val root = FrameLayout(this)

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(28, 28, 28, 28)
        }

        column.addView(TextView(this).apply {
            text = "האם לדווח על נפילה?"
            textSize = 17f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 26)
        })

        column.addView(Button(this).apply {
            text = "אישור"
            textSize = 19f
            setPadding(0, 30, 0, 30)
            setBackgroundColor(Color.parseColor("#8C3B34"))
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener {
                // בלי קטגוריה — הנתון היקר הוא הזמן, לא הסיווג.
                FallReport.record(this@FallConfirmActivity, null)
                showAcknowledge(root)
            }
        })

        root.addView(column)

        // ✕ קטן בפינה. קיים, ולא מתחרה על תשומת הלב.
        root.addView(TextView(this).apply {
            text = "✕"
            textSize = 20f
            setTextColor(Color.parseColor("#8A8A8A"))
            setPadding(20, 14, 20, 14)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.TOP or Gravity.START }
            setOnClickListener {
                EventLog.log(this@FallConfirmActivity, "FALL", "confirm_cancelled")
                finish()
            }
        })

        return root
    }

    /**
     * הרגע עצמו: משפט אחד, ונסגר לבד.
     *
     * ⚠️ **אין כאן כפתור ואין שאלה.** זה לא הזמן לדבר איתו — ההודעה
     * המעודדת מגיעה בשקט 5-15 דקות אחר כך, אחרי שהרגע החריף עבר.
     */
    private fun showAcknowledge(root: FrameLayout) {
        root.removeAllViews()
        root.addView(TextView(this).apply {
            text = Encouragements.fallAcknowledge()
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        })
        root.postDelayed({ finish() }, 2_200L)
    }

    override fun onBackPressed() { finish() }
}
