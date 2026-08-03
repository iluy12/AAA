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
 * בחירת סוג נפילה — **ארבעה כפתורים על מסך מלא**.
 *
 * ## ⚠️ למה לא גרירה, וזה נמדד ולא הוחלט
 *
 * המסך הזה היה בורר רדיאלי: מחזיקים במרכז וגוררים לכיוון. הוא נכשל,
 * ולקח שלושה סבבים של תיקונים שגויים עד שהמדידה הכריעה.
 *
 * מהלוג של 2026-08-03 בשעה 22:28, אחרי שהמסך כבר היה מסך-מלא אמיתי:
 *
 * ```
 * picker_down;at=176,246
 * picker_up;moves=6;far_len=0;trace=[176,246 176,246 176,246 ...]
 * ```
 *
 * **176,246 היא מרכז המסך** (368×448), כלומר לא קצה ולא אזור-מערכת. שש
 * הודעות תנועה הגיעו, וכולן על אותה נקודה בדיוק.
 *
 * ובאותו מכשיר, משיכות שכן נרשמו היו באורך **308-384 פיקסלים על מסך
 * ברוחב 368** — כלומר כמעט מקצה לקצה.
 *
 * > **מסך המגע מדווח תנועה רק במשיכות ענק. משיכה מהמרכז לבועה היא
 * > כ-130 פיקסלים, והיא לא תירשם לעולם.**
 *
 * זו מגבלת חומרה, לא באג. שום תיקון בקוד לא יעזור לה.
 *
 * ⚠️ **ושתי ההשערות שלי לפניה היו שגויות, שתיהן:** שהדיגיטייזר לא מוסר
 * MOVE בכלל (הוא כן, 6-16 הודעות), ושרבע מהמסך שייך למערכת (`view`
 * שווה ל-`metrics` — הכל שלנו). כל אחת מהן הובילה לתיקון שלא יכול היה
 * לעבוד.
 *
 * ## ומה שנבו תיאר, שנפתר מאליו
 *
 * *"מזהה לחיצה ארוכה אבל לא משיכה. צריך לעזוב ואז למשוך שוב."*
 *
 * זה נכון ובלתי-פתיר בגרירה: הלחיצה הארוכה יורה **בזמן שהאצבע עוד
 * למטה**, המסך הזה נפתח, ואנדרואיד לא מוסר לו `ACTION_DOWN` על אצבע
 * שכבר הייתה מונחת. עם כפתורים השאלה לא קיימת.
 */
class FallPickerActivity : Activity() {

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        KeyLog.record(this, "fall_picker", event)
        return super.dispatchKeyEvent(event)
    }

    companion object {
        fun launch(context: Context) {
            context.startActivity(
                Intent(context, FallPickerActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }

        /**
         * צבע לכל קטגוריה, מהחמור לקל.
         *
         * ⚠️ **בכוונה לא ארבעה אדומים.** מסך שכולו אדום ברגע של בושה
         * הוא ענישה חזותית. הסולם עובר מאדום-אדמה לחום ולאפור-כחלחל,
         * וקרי-לילה — שלא הייתה בו בחירה — מקבל את הגוון הרגוע ביותר.
         */
        private val COLOURS = listOf("#8C3B34", "#8A5A32", "#4A5A6E", "#3C4450")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AlertHelper.wakeScreen(this)
        Immersive.apply(this)
        EventLog.log(this, "FALL", "picker_shown")
        setContentView(buildLayout())
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) Immersive.apply(this)
    }

    private fun buildLayout(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0A0A0B"))
            setPadding(10, 8, 10, 8)
        }

        root.addView(TextView(this).apply {
            text = "מה קרה?"
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#9A9A98"))
            setPadding(0, 4, 0, 8)
        })

        // ⚠️ **הכפתורים חולקים את הגובה שנשאר בשווה.** גובה קבוע בפיקסלים
        // היה נשבר על מסך אחר, והמסך הזה כבר עומד להתחלף (368×448 היום).
        for ((i, label) in RadialFallButton.CATEGORIES.withIndex()) {
            root.addView(Button(this).apply {
                text = label
                textSize = 19f
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.parseColor(COLOURS[i]))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
                ).apply { setMargins(0, 4, 0, 4) }
                setOnClickListener { report(label) }
            })
        }

        // ✕ קטן. יציאה בלי לדווח חייבת להיות אפשרית, ולא צריכה להתחרות
        // בגודל עם הדיווח עצמו.
        root.addView(TextView(this).apply {
            text = "✕"
            textSize = 17f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#6E6E6C"))
            setPadding(0, 10, 0, 6)
            setOnClickListener {
                EventLog.log(this@FallPickerActivity, "FALL", "picker_cancelled")
                finish()
            }
        })

        return root
    }

    private fun report(category: String) {
        EventLog.log(this, "FALL", "picker_choice;category=$category")
        FallReport.record(this, category)
        showAcknowledge()
    }

    /**
     * הרגע עצמו: משפט אחד, ונסגר לבד.
     *
     * ⚠️ **מיד אחרי נפילה זה לא הזמן לדבר** — זה רגע של חרטה ובושה, ומסך
     * שנשאר פתוח נקרא כתחקור. המשפט המעודד מגיע כעבור 5-15 דקות, מבנק
     * אחר לגמרי.
     */
    private fun showAcknowledge() {
        val text = Encouragements.fallAcknowledge()
        setContentView(TextView(this).apply {
            this.text = text
            textSize = 17f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#0A0A0B"))
            setPadding(26, 26, 26, 26)
            postDelayed({ finish() }, 2_200L)
        })
    }

    override fun onBackPressed() { finish() }
}
