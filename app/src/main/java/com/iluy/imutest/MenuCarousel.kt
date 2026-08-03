package com.iluy.imutest

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * תפריט של **מסך אחד לכל פעולה**, עם החלקה בין המסכים.
 *
 * ## ⚠️ למה לא רשימה
 *
 * זה נקבע במפורש: *"כל פעם להציג כפתור אחד עם כותרת, וכל פעם שגוללים
 * לצדדים מגיעים לבא אחריו."*
 *
 * וההיגיון מאחוריו הוא אותו היגיון של כל המוצר: **התפריט הזה נלחץ
 * ברגעים קשים.** רשימה של שישה כפתורים קטנים דורשת קריאה, כיוון ודיוק
 * — שלושה דברים שאין ברגע כזה. מטרה אחת שתופסת מסך שלם דורשת רק לגעת.
 *
 * ⚠️ **וזו בדיוק ההפרדה מהגדרות:** מסך הפיתוח **כן** רשימה צפופה, כי
 * הוא נלחץ בשקט ובמכוון. ממשק גדול ואיטי היכן שהרגע קשה, צפוף ומהיר
 * היכן שהמשתמש רגוע.
 *
 * ## למה מימוש עצמאי ולא ViewPager
 *
 * ViewPager2 היה מוסיף תלות שלמה בשביל ארבעה מסכים סטטיים. הזיהוי כאן
 * הוא החלקה אופקית פשוטה, וזה כל מה שנדרש.
 */
class MenuCarousel(
    private val activity: Activity,
    private val pages: List<Page>
) {

    /**
     * @param onOpen נגיעה רגילה.
     * @param onHold לחיצה ארוכה. `null` פירושו שאין לדף מסלול ארוך,
     *        ואז לחיצה ארוכה מתנהגת כמו נגיעה.
     *
     * ⚠️ **שני מסלולים, וזו החלטה של נבו על "נפלתי":** נגיעה רגילה
     * מובילה למסך אישור ("האם לדווח על נפילה?"), ולחיצה ארוכה פותחת
     * ישר את בחירת הסוג. **המסלול הקצר מקבל את השער הנוסף** — נגיעה
     * אפשר לעשות בטעות בכיס, לחיצה ארוכה לא.
     */
    data class Page(
        val title: String,
        val subtitle: String,
        val colour: String,
        val onOpen: (Context) -> Unit,
        val onHold: ((Context) -> Unit)? = null
    )

    private var index = 0
    private val root = FrameLayout(activity)
    private val density = activity.resources.displayMetrics.density

    /**
     * ⚠️ הסף גבוה יחסית (60dp) בכוונה. על מסך קטן אצבע זזה מעט בכל
     * לחיצה, וסף נמוך היה הופך נגיעה מכוונת להחלקה — כלומר המשתמש היה
     * מחמיץ את הכפתור שהתכוון ללחוץ עליו בדיוק ברגע שהוא צריך אותו.
     */
    private val swipeThreshold get() = 60f * density

    private val detector = GestureDetector(activity, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        override fun onFling(
            e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float
        ): Boolean {
            val dx = e2.x - (e1?.x ?: return false)
            // ⚠️ אופקי בלבד: החלקה אלכסונית שנקראת כאופקית מרגישה שבורה.
            if (kotlin.math.abs(dx) < swipeThreshold) return false
            if (kotlin.math.abs(dx) < kotlin.math.abs(e2.y - e1.y)) return false
            index = if (dx < 0) (index + 1) % pages.size
            else (index - 1 + pages.size) % pages.size
            render()
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            pages[index].onOpen(activity)
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            val page = pages[index]
            EventLog.log(activity, "INFO", "menu_long_press;page=${page.title}")
            (page.onHold ?: page.onOpen)(activity)
        }
    })

    fun view(): View {
        render()
        root.setOnTouchListener { _, ev -> detector.onTouchEvent(ev) }
        return root
    }

    private fun render() {
        root.removeAllViews()
        val page = pages[index]

        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor(page.colour))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        column.addView(TextView(activity).apply {
            text = page.title
            textSize = 26f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
        })

        column.addView(TextView(activity).apply {
            text = page.subtitle
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#D8D8D8"))
            setPadding(0, (8 * density).toInt(), 0, 0)
        })

        root.addView(column)

        // נקודות מיקום — בלעדיהן המשתמש לא יודע שיש עוד מסכים בכלל.
        root.addView(TextView(activity).apply {
            text = pages.indices.joinToString(" ") { if (it == index) "●" else "○" }
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#BFBFBF"))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM
                bottomMargin = (10 * density).toInt()
            }
        })
    }
}
