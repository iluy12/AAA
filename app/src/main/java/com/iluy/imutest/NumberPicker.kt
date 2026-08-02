package com.iluy.imutest

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * בחירת מספר על מסך של שני אינץ'.
 *
 * ## למה גם קיצורים וגם +/−
 *
 * ⚠️ הזנת מספר במקלדת על מסך כזה כמעט בלתי אפשרית, אבל **תשובות טקסט
 * גסות** — "כמה חודשים" — אילצו אותי להמיר אותן למספר בניחוש, וזה בדיוק
 * סוג ההמצאה שהמוצר הזה נמנע ממנה בכל מקום אחר.
 *
 * הפתרון: **קיצורים למספרים השכיחים** מכסים את רוב המקרים בנגיעה אחת,
 * ו-+/− מאפשרים לדייק כשצריך. מי שהשיא שלו 7 ימים לוחץ פעם אחת; מי
 * שהשיא שלו 23 מתחיל מ-30 ויורד.
 *
 * ⚠️ **הקפיצה של +/− גדלה עם הערך.** מ-0 עד 30 קופצים ב-1, ומעל 100
 * ב-10 — אחרת מי שרוצה 200 צריך מאתיים לחיצות. הדיוק שנדרש יורד ככל
 * שהמספר גדל, וזה נכון גם למה שנמדד: ההבדל בין 3 ל-4 ימים משמעותי,
 * ההבדל בין 200 ל-201 אינו.
 */
object NumberPicker {

    /**
     * @param shortcuts מספרים שכיחים, בנגיעה אחת.
     * @param onPick נקרא בכל שינוי — הקורא אחראי לשמור.
     */
    fun build(
        context: Context,
        initial: Int,
        shortcuts: List<Int>,
        suffix: String,
        onPick: (Int) -> Unit
    ): View {
        var value = initial

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }

        val display = TextView(context).apply {
            textSize = 26f
            gravity = Gravity.CENTER
            setPadding(0, 8, 0, 8)
        }

        fun refresh() {
            display.text = if (value <= 0) "—" else "$value $suffix"
            onPick(value)
        }

        // --- שורת +/− ---
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        row.addView(stepButton(context, "−") {
            value = (value - stepFor(value, down = true)).coerceAtLeast(0)
            refresh()
        })
        row.addView(display, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(stepButton(context, "+") {
            value += stepFor(value, down = false)
            refresh()
        })
        root.addView(row)

        // --- קיצורים ---
        val chips = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 6, 0, 0)
        }
        for (n in shortcuts) {
            chips.addView(Button(context).apply {
                text = n.toString()
                textSize = 13f
                setPadding(4, 6, 4, 6)
                setBackgroundColor(Color.parseColor("#3A3A3A"))
                setTextColor(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { setMargins(3, 0, 3, 0) }
                setOnClickListener {
                    value = n
                    refresh()
                }
            })
        }
        root.addView(chips)

        refresh()
        return root
    }

    /**
     * גודל הקפיצה. ⚠️ עולה עם הערך — ראו הערת-המחלקה.
     *
     * `down` נבדק בנפרד כדי שירידה מ-100 תיתן 90 ולא 90 בקפיצה של 10
     * שמדלגת על 99: כשיורדים, הקפיצה נקבעת לפי הערך **שאליו מגיעים**.
     */
    private fun stepFor(value: Int, down: Boolean): Int {
        val ref = if (down) value - 1 else value
        return when {
            ref < 30 -> 1
            ref < 100 -> 5
            else -> 10
        }
    }

    private fun stepButton(context: Context, label: String, onClick: () -> Unit): Button =
        Button(context).apply {
            text = label
            textSize = 22f
            setBackgroundColor(Color.parseColor("#2E7D5B"))
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(96, LinearLayout.LayoutParams.WRAP_CONTENT)
            setOnClickListener { onClick() }
        }
}
