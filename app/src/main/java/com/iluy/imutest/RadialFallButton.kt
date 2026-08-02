package com.iluy.imutest

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View

/**
 * כפתור "נפלתי" עם מחווה רדיאלית.
 *
 * ## איך זה עובד, ולמה כך
 *
 * לחיצה ארוכה פותחת ארבע אפשרויות סביב האצבע. גרירה לכיוון אחת מהן
 * בוחרת אותה ומדווחת מיד. **שחרור בלי גרירה** מדווח בלי קטגוריה — אבל
 * דרך מסך אישור.
 *
 * ⚠️ **ההבחנה הזו היא הלב, והיא של נבו:** מי שגרר לכיוון עשה מחווה
 * מכוונת שאי-אפשר לעשות בטעות, ולכן הוא **לא צריך מסך אישור**. מי
 * שרק לחץ — יכול היה לגעת בכיס. לכן דווקא **המסלול הקצר יותר הוא זה
 * שמקבל אישור**, ולא ההפך.
 *
 * ## למה בכלל לאפשר דיווח בלי קטגוריה
 *
 * ארבע קטגוריות דורשות מהמשתמש **לסווג את עצמו** ברגע של בושה, וזה
 * בדיוק החיכוך שרצינו לאפס. **הנתון היקר הוא הזמן, לא הקטגוריה** —
 * החלון של חצי השעה שקדמה הוא מה שהמערכת לומדת ממנו, והוא זהה בשני
 * המקרים. לכן הקטגוריה אופציונלית לגמרי.
 */
class RadialFallButton(
    context: Context,
    private val onReport: (category: String?) -> Unit,
    private val onNeedsConfirm: () -> Unit
) : View(context) {

    companion object {
        /**
         * למעלה, ימין, למטה, שמאל — בסדר הזה.
         *
         * ⚠️ **"אחר" הוסר והוחלף ב"קרי לילה".** "אחר" לא היה קיים בסולם
         * החומרה ולכן היה נרשם בשקט כברית — כלומר מי שבחר אותו במפורש היה
         * מסווג כנפילה החמורה בלי שיידע.
         *
         * הסדר מכוון: גרירה כלפי מעלה היא התנועה הטבעית ביותר והיא שמורה
         * לחמורה. קרי לילה בשמאל — הרחוקה ביותר — כי היא גם הנדירה וגם
         * זו שנבחרת בבוקר בשקט ולא ברגע של מצוקה.
         */
        val CATEGORIES = listOf("ברית", "עיניים", "מחשבה", "קרי לילה")

        /** מרחק מינימלי שנחשב גרירה מכוונת ולא רעד של אצבע. */
        private const val DRAG_THRESHOLD_PX = 60f

        /** כמה זמן החזקה פותחת את האפשרויות. */
        private const val HOLD_MS = 320L
    }

    private var expanded = false
    private var downX = 0f
    private var downY = 0f
    private var curX = 0f
    private var curY = 0f
    private var highlighted = -1

    private val expandRunnable = Runnable {
        expanded = true
        invalidate()
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 30f
        textAlign = Paint.Align.CENTER
    }
    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#8C3B34")
    }
    private val baseTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 38f
        textAlign = Paint.Align.CENTER
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x; downY = event.y
                curX = downX; curY = downY
                highlighted = -1
                expanded = false
                postDelayed(expandRunnable, HOLD_MS)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                curX = event.x; curY = event.y
                // גרירה משמעותית פותחת את האפשרויות מיד, בלי לחכות
                // להחזקה — מי שכבר יודע לאן הוא הולך לא צריך להמתין.
                if (!expanded && distanceFromDown() > DRAG_THRESHOLD_PX) {
                    removeCallbacks(expandRunnable)
                    expanded = true
                }
                highlighted = if (expanded) directionIndex() else -1
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(expandRunnable)
                val picked = if (expanded) directionIndex() else -1
                expanded = false
                highlighted = -1
                invalidate()

                if (picked >= 0) {
                    // גרירה מכוונת — מדווח מיד, בלי אישור. ראו הערת-המחלקה.
                    onReport(CATEGORIES[picked])
                } else {
                    // לחיצה בלבד — עוברת דרך אישור, כי היא יכולה לקרות בטעות.
                    onNeedsConfirm()
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(expandRunnable)
                expanded = false
                highlighted = -1
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun distanceFromDown(): Float {
        val dx = curX - downX
        val dy = curY - downY
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    /**
     * לאיזו מארבע האפשרויות האצבע מצביעה, או -1 אם לא זזה מספיק.
     *
     * מחולק לרבעים לפי הזווית, כך שאין "אזורים מתים" בין האפשרויות —
     * כל כיוון שייך תמיד למישהו. ברגע של מצוקה אין מקום לדיוק.
     */
    private fun directionIndex(): Int {
        if (distanceFromDown() < DRAG_THRESHOLD_PX) return -1
        val dx = curX - downX
        val dy = curY - downY
        // מעלה=0 (ברית) · ימין=1 (עיניים) · מטה=2 (מחשבה) · שמאל=3 (קרי לילה)
        return if (kotlin.math.abs(dy) > kotlin.math.abs(dx)) {
            if (dy < 0) 0 else 2
        } else {
            if (dx > 0) 1 else 3
        }
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f

        if (!expanded) {
            canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), 18f, 18f, basePaint)
            canvas.drawText("נפלתי", cx, cy + 14f, baseTextPaint)
            return
        }

        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), 18f, 18f, basePaint)

        // ⚠️ **הבועות נצמדות לקצוות ולא מסודרות סביב האצבע.** בגרסה הראשונה
        // הן ישבו במרחק קבוע מנקודת הלחיצה, ועל מסך של שני אינץ' זה דחס
        // אותן למרכז — האצבע כיסתה אותן והטקסט לא נקרא. עכשיו כל אחת
        // נצמדת לקצה שלה, כך שגודל המסך קובע את המרחק ולא מקום הלחיצה.
        val bubbleR = 52f
        val padX = bubbleR + 6f
        val padY = bubbleR + 6f
        val positions = listOf(
            cx to padY,                        // למעלה — ברית
            width - padX to cy,                // ימין  — עיניים
            cx to height - padY,               // למטה  — מחשבה
            padX to cy                         // שמאל  — קרי לילה
        )
        for (i in CATEGORIES.indices) {
            val (x, y) = positions[i]
            bubblePaint.color = if (i == highlighted) Color.parseColor("#F2C14E")
            else Color.parseColor("#4A4A4A")
            canvas.drawCircle(x, y, bubbleR, bubblePaint)
            labelPaint.color = if (i == highlighted) Color.BLACK else Color.WHITE
            canvas.drawText(CATEGORIES[i], x, y + 11f, labelPaint)
        }
    }
}
