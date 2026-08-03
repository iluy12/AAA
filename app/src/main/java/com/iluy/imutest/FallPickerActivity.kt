package com.iluy.imutest

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.TextView

/**
 * בחירת סוג נפילה — **משיכה מקצה לקצה**.
 *
 * ## ⚠️ למה מקצה לקצה ולא מהמרכז החוצה
 *
 * הגרסה הרדיאלית נכשלה, ולקח שלושה סבבים של תיקונים שגויים עד שהמדידה
 * הכריעה. מהלוג של 2026-08-03:
 *
 * ```
 * picker_down;at=176,246        ← מרכז המסך (368×448)
 * picker_up;moves=6;far_len=0   ← שש הודעות תנועה, אפס תזוזה
 * ```
 *
 * ובאותה דקה, על אותו מכשיר, תשע משיכות אחרות נרשמו **מושלם** באורך
 * 308-384 פיקסלים.
 *
 * > **מסך המגע מדווח תנועה רק במשיכות ענק.** משיכה מהמרכז לבועה היא
 * > כ-130 פיקסלים ולא תירשם לעולם; משיכה על פני כל המסך היא 368 ותירשם.
 *
 * זו מגבלת חומרה. המחווה עוצבה מחדש **סביבה** ולא נגדה.
 *
 * ## ⚠️ מה שעדיין לא הוכח
 *
 * המשיכות שנמדדו היו **אופקיות**. על אנכיות נבו דיווח שהמערכת חוטפת
 * אותן — ייתכן שמסך-מלא פתר את זה, וייתכן שלא. לכן:
 *
 * 1. `far_len` ו-`dir` נרשמים בכל משיכה, כדי שנדע מה באמת עובר
 * 2. **יש דרך חלופית מיידית** — נגיעה על שם קטגוריה בוחרת אותה. אם
 *    המשיכה נכשלת המשתמש לא נתקע, וזה חייב להיות כך: מסך דיווח שלא
 *    מגיב ברגע הזה הוא הכישלון החמור ביותר שיש למוצר.
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
         * הכיוונים: ימינה, שמאלה, מטה, מעלה.
         *
         * ⚠️ **נגזר מ-[FallSeverity] ולא נכתב כמחרוזות.** הייתה כאן
         * רשימת שמות משלי, ו-[RadialFallButton.CATEGORIES] מחזיקה את
         * אותם שמות **בסדר אחר** — שתי רשימות זהות בתוכן ושונות בסדר
         * הן בדיוק הצורה שבה שינוי במקום אחד לא מגיע לשני.
         *
         * וכאן זה לא היה נראה כבאג: שם שגוי גורם ל-`fromLabel` ליפול
         * בשקט לברירת המחדל, וכל בחירה הייתה נרשמת כנפילה בברית.
         */
        private val DIRS = listOf(
            FallSeverity.SEED.label,      // ימינה
            FallSeverity.EYES.label,      // שמאלה
            FallSeverity.THOUGHT.label,   // מטה
            FallSeverity.NOCTURNAL.label  // מעלה
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AlertHelper.wakeScreen(this)
        Immersive.apply(this)
        EventLog.log(this, "FALL", "picker_shown")
        setContentView(SwipeView(this))
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            Immersive.apply(this)
            Immersive.logGeometry(this, "fall_picker")
        }
    }

    private fun report(category: String, how: String) {
        EventLog.log(this, "FALL", "picker_choice;category=$category;how=$how")
        FallReport.record(this, category)
        showAcknowledge()
    }

    /**
     * הרגע עצמו: משפט אחד, ונסגר לבד.
     *
     * ⚠️ **מיד אחרי נפילה זה לא הזמן לדבר** — זה רגע של חרטה, ומסך
     * שנשאר פתוח נקרא כתחקור. המשפט המעודד מגיע כעבור 5-15 דקות,
     * מבנק אחר לגמרי.
     */
    private fun showAcknowledge() {
        setContentView(TextView(this).apply {
            text = Encouragements.fallAcknowledge()
            textSize = 19f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#0A0A0B"))
            setPadding(26, 26, 26, 26)
            postDelayed({ finish() }, 2_200L)
        })
    }

    override fun onBackPressed() { finish() }

    private inner class SwipeView(context: Context) : View(context) {

        /**
         * ⚠️ **נמוך בכוונה — 90 פיקסלים.** לא בגלל שרוצים לזהות משיכות
         * קטנות, אלא כי החומרה **ממילא** לא מדווחת אותן: כל תזוזה שכן
         * תגיע לכאן היא כבר גדולה. סף גבוה היה רק מוסיף כישלון שני על
         * גבי הראשון.
         */
        private val minSwipe = 90f

        private var downX = 0f
        private var downY = 0f
        private var farX = 0f
        private var farY = 0f
        private var farDist = 0f
        private var moves = 0

        private val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER; textSize = 20f; color = Color.parseColor("#9A9A98")
        }
        private val hint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER; textSize = 13f; color = Color.parseColor("#5C5C5A")
        }
        private val word = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER; textSize = 22f
            color = Color.parseColor("#C9A961"); isFakeBoldText = true
        }
        private val live = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#F0D89B"); strokeWidth = 4f; strokeCap = Paint.Cap.ROUND
        }

        private var hot = -1

        /** מלבן הנגיעה של כל מילה, לחלופה בלי משיכה. */
        private fun spots(): List<FloatArray> {
            val w = width.toFloat(); val h = height.toFloat()
            val pad = 34f
            return listOf(
                floatArrayOf(w - pad * 2.4f, h / 2f),  // ימין  → ברית
                floatArrayOf(pad * 2.4f, h / 2f),      // שמאל  → עיניים
                floatArrayOf(w / 2f, h - pad),         // מטה   → מחשבה
                floatArrayOf(w / 2f, pad)              // מעלה  → קרי לילה
            )
        }

        private fun direction(dx: Float, dy: Float): Int {
            if (kotlin.math.abs(dx) < minSwipe && kotlin.math.abs(dy) < minSwipe) return -1
            return if (kotlin.math.abs(dx) >= kotlin.math.abs(dy)) {
                if (dx > 0) 0 else 1
            } else {
                if (dy > 0) 2 else 3
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x; downY = event.y
                    farX = downX; farY = downY; farDist = 0f; moves = 0
                    hot = -1; invalidate()
                }
                MotionEvent.ACTION_MOVE -> {
                    moves++
                    val d = kotlin.math.hypot(event.x - downX, event.y - downY)
                    if (d > farDist) { farDist = d; farX = event.x; farY = event.y }
                    hot = direction(farX - downX, farY - downY)
                    invalidate()
                }
                MotionEvent.ACTION_UP -> {
                    // הרחוקה ביותר ולא נקודת ההרמה — אצבע שחוזרת לאמצע
                    // לפני שהיא עוזבת היא תנועה טבעית לגמרי.
                    val up = kotlin.math.hypot(event.x - downX, event.y - downY)
                    if (up >= farDist) { farX = event.x; farY = event.y; farDist = up }
                    val dx = farX - downX; val dy = farY - downY
                    val picked = direction(dx, dy)

                    EventLog.log(
                        context, "FALL",
                        "picker_up;moves=$moves;len=${farDist.toInt()};" +
                            "dx=${dx.toInt()};dy=${dy.toInt()};" +
                            "dir=${if (picked >= 0) DIRS[picked] else "none"}"
                    )

                    if (picked >= 0) {
                        report(DIRS[picked], "swipe")
                        return true
                    }

                    // ⚠️ נפילה-חזרה: אולי הוא פשוט נגע במילה. **מסך דיווח
                    // שלא מגיב ברגע הזה הוא הכישלון הגרוע ביותר שיש.**
                    val tapped = spots().indexOfFirst {
                        kotlin.math.hypot(event.x - it[0], event.y - it[1]) < 70f
                    }
                    if (tapped >= 0) report(DIRS[tapped], "tap")
                    hot = -1; invalidate()
                }
                MotionEvent.ACTION_CANCEL -> {
                    EventLog.log(context, "FALL", "picker_cancel;moves=$moves;len=${farDist.toInt()}")
                    hot = -1; invalidate()
                }
            }
            return true
        }

        override fun onDraw(canvas: Canvas) {
            canvas.drawColor(Color.parseColor("#0A0A0B"))
            val w = width.toFloat(); val h = height.toFloat()

            if (hot < 0) {
                canvas.drawText("מה קרה?", w / 2f, h / 2f - 6f, title)
                canvas.drawText("משוך לכיוון, או גע במילה", w / 2f, h / 2f + 20f, hint)
            } else {
                canvas.drawLine(downX, downY, farX, farY, live)
            }

            for ((i, p) in spots().withIndex()) {
                word.color = if (i == hot) Color.parseColor("#F0D89B") else Color.parseColor("#C9A961")
                word.textSize = if (i == hot) 26f else 22f
                canvas.drawText(DIRS[i], p[0], p[1] + 8f, word)
            }
        }
    }
}
