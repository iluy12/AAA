package com.iluy.imutest

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View

/**
 * בחירת סוג נפילה — **מסך מלא**.
 *
 * ## ⚠️ למה זה מסך ולא כפתור בתוך רשימה
 *
 * הגרסה הראשונה הייתה תיבה בגובה 300 פיקסל בתוך רשימה נגללת, ונכשלה
 * בשלוש דרכים שכולן נובעות מאותה החלטה:
 *
 * 1. **הגרירה לא עבדה בכלל.** ה-ScrollView שמסביב יירט את התנועה
 *    האנכית לפני שהיא הגיעה לכפתור — כלומר "ברית" ו"מחשבה", שנמצאות
 *    למעלה ולמטה, היו בלתי-נגישות לחלוטין.
 * 2. **הטקסט היה זעיר.** גדלים נכתבו בפיקסלים גולמיים; על מסך 480×480
 *    בצפיפות גבוהה זה מיקרוסקופי. הכל כאן מוכפל ב-`density`.
 * 3. **צפוף.** ארבע אפשרויות בתיבה נמוכה נדחסות זו לזו.
 *
 * מסך מלא פותר את שלושתן בבת אחת: אין מה שיירט, יש מקום, והמסך כולו
 * הוא אזור המגע.
 *
 * ## המחווה
 *
 * גרירה לכיוון = בחירה ודיווח מיידי. הרמת אצבע בלי גרירה = מסך אישור.
 * ⚠️ האסימטריה מכוונת: גרירה אי-אפשר לעשות בכיס, לחיצה כן — ולכן דווקא
 * המסלול הקצר מקבל את השער הנוסף.
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
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AlertHelper.wakeScreen(this)
        setContentView(PickerView(this))
    }

    override fun onBackPressed() { finish() }

    private inner class PickerView(context: Context) : View(context) {

        private val d = resources.displayMetrics.density

        /** כל הגדלים ביחידות מסך אמיתיות. ראו הערת-המחלקה. */
        private val bubbleR = 46f * d
        private val labelSize = 15f * d
        private val titleSize = 17f * d
        private val hintSize = 12f * d
        /**
         * ⚠️ 14 ולא 24. על מסך של 480×480 עם אצבע, גרירה של 24dp היא כמעט
         * חמישית מהמסך — הרבה יותר ממה שנדרש כדי להביע כיוון. הסף הגבוה
         * הוא חלק מהסיבה שהמחווה נכשלה בפועל.
         */
        private val dragThreshold = 14f * d

        private var downX = 0f
        private var downY = 0f
        private var curX = 0f
        private var curY = 0f
        private var touching = false

        private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            textSize = labelSize
            isFakeBoldText = true
        }
        private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            textSize = titleSize
            color = Color.WHITE
        }
        private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            textSize = hintSize
            color = Color.parseColor("#9A9A9A")
        }
        private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#F2C14E")
            strokeWidth = 3f * d
        }

        private fun positions(): List<Pair<Float, Float>> {
            val pad = bubbleR + 6f * d
            return listOf(
                width / 2f to pad,                 // ברית
                width - pad to height / 2f,        // עיניים
                width / 2f to height - pad,        // מחשבה
                pad to height / 2f                 // קרי לילה
            )
        }

        private fun highlighted(): Int {
            if (!touching) return -1
            val dx = curX - downX
            val dy = curY - downY
            if (kotlin.math.sqrt(dx * dx + dy * dy) < dragThreshold) return -1
            return if (kotlin.math.abs(dy) > kotlin.math.abs(dx)) {
                if (dy < 0) 0 else 2
            } else {
                if (dx > 0) 1 else 3
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x; downY = event.y
                    curX = downX; curY = downY
                    touching = true
                    // ⚠️ חגורה ושלייקס: גם במסך מלא, אם מישהו יעטוף את זה
                    // בעתיד במיכל גליל — הגרירה תישבר בשקט בדיוק כמו קודם.
                    parent?.requestDisallowInterceptTouchEvent(true)
                    invalidate()
                }
                MotionEvent.ACTION_MOVE -> {
                    curX = event.x; curY = event.y
                    invalidate()
                }
                MotionEvent.ACTION_UP -> {
                    // ⚠️ **הקואורדינטות נלקחות מ-UP עצמו ולא מה-MOVE האחרון.**
                    //
                    // זו הסיבה שנבו לא הצליח לגרור. הבחירה נקבעה מ-`curX/curY`
                    // שמתעדכנים **רק ב-ACTION_MOVE** — ואם הדיגיטייזר של השעון
                    // הזה לא מוסר MOVE (או מוסר מעט מדי), הערכים נשארים שווים
                    // לנקודת הלחיצה, המרחק יוצא אפס, והמחווה תמיד נקראה
                    // כ"לחיצה בלי גרירה".
                    //
                    // בלוג של 3.8: שלושה `confirm_shown` מול הצלחה אחת. כלומר
                    // המחווה נכשלה ברוב הניסיונות, ובכל כישלון הדיווח נשמר
                    // כ**ברית** — ברירת המחדל החמורה. המחווה השבורה ייצרה
                    // נתונים שגויים, לא רק תסכול.
                    //
                    // נקודת ה-UP קיימת תמיד, ולכן היא המקור הנכון בכל מקרה.
                    curX = event.x; curY = event.y
                    val picked = highlighted()
                    EventLog.log(
                        context, "FALL",
                        "picker_up;dx=${(curX - downX).toInt()};dy=${(curY - downY).toInt()};" +
                            "picked=${if (picked >= 0) RadialFallButton.CATEGORIES[picked] else "none"}"
                    )
                    touching = false
                    invalidate()
                    if (picked >= 0) {
                        FallReport.record(this@FallPickerActivity, RadialFallButton.CATEGORIES[picked])
                        showAcknowledge()
                    } else {
                        FallConfirmActivity.launch(this@FallPickerActivity)
                        finish()
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    touching = false
                    invalidate()
                }
            }
            return true
        }

        override fun onDraw(canvas: Canvas) {
            canvas.drawColor(Color.parseColor("#141414"))
            val cx = width / 2f
            val cy = height / 2f
            val hi = highlighted()

            // קו מהאצבע לכיוון הנבחר — משוב מיידי שהמחווה נקלטה
            if (touching && hi >= 0) {
                val (bx, by) = positions()[hi]
                canvas.drawLine(downX, downY, bx, by, linePaint)
            }

            for (i in RadialFallButton.CATEGORIES.indices) {
                val (x, y) = positions()[i]
                bubblePaint.color =
                    if (i == hi) Color.parseColor("#F2C14E") else Color.parseColor("#3C3C3C")
                canvas.drawCircle(x, y, bubbleR, bubblePaint)
                labelPaint.color = if (i == hi) Color.BLACK else Color.WHITE
                canvas.drawText(
                    RadialFallButton.CATEGORIES[i], x,
                    y + labelSize / 3f, labelPaint
                )
            }

            if (!touching) {
                canvas.drawText("נפלתי", cx, cy - 6f * d, titlePaint)
                canvas.drawText("גרור לכיוון, או הרם לאישור", cx, cy + 16f * d, hintPaint)
            }
        }

        private fun showAcknowledge() {
            val text = Encouragements.fallAcknowledge()
            android.widget.Toast.makeText(context, text, android.widget.Toast.LENGTH_LONG).show()
            postDelayed({ this@FallPickerActivity.finish() }, 900L)
        }
    }
}
