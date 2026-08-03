package com.iluy.imutest

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * מסך תאריך ומידע — יעד ההחלקה מלמטה-שמאל.
 *
 * מחליף פאנל שהיה בלאנצ'ר של היצרן ("לוח שנה, תאריך ושעה, מידע על
 * פעילות"). זה לא היה פיצ'ר של אנדרואיד אלא שלהם, ולכן אי-אפשר לקרוא
 * לו — רק לבנות מקביל.
 *
 * ⚠️ זהו שלד. לוח ההתגברויות והנפילות (חלק 5) עדיין לא נבנה, ולכן אין
 * כאן עדיין ספירות — CalendarStore לא קיים. המסך הזה הוא המקום שאליו
 * הוא ייכנס.
 */
class InfoActivity : Activity() {

    companion object {
        fun launch(context: Context) {
            context.startActivity(Intent(context, InfoActivity::class.java))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scroll = ScrollView(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(24, 32, 24, 24)
        }
        scroll.addView(container)

        val now = Date()

        container.addView(TextView(this).apply {
            text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(now)
            textSize = 34f
            gravity = Gravity.CENTER
        })
        container.addView(TextView(this).apply {
            text = SimpleDateFormat("EEEE, d בMMMM yyyy", Locale("he")).format(now)
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            setPadding(0, 8, 0, 24)
        })

        renderCalendar(container)

        container.addView(Button(this).apply {
            text = "חזרה"
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = 24
            layoutParams = lp
            setOnClickListener { finish() }
        })

        setContentView(scroll)
    }

    /**
     * לוח 14 הימים האחרונים.
     *
     * יום מעורב מציג **את שניהם** — ירוק ואדום זה לצד זה — ולא "אדום
     * מנצח". זו הייתה החלטה מפורשת: להתגברויות של יום שהיה בו גם נופל
     * יש ערך בפני עצמן, והעלמתן הייתה מוחקת בדיוק את המאמץ שכן נעשה.
     */
    /**
     * שורות הימים בלבד, בלי הכותרת.
     *
     * ⚠️ מוחזק בנפרד כדי שביטול נפילה יוכל לצייר מחדש **רק אותן**. בלי
     * זה הייתי צריך לבנות את כל המסך מחדש, ואז המיקום בגלילה נאבד בדיוק
     * כשהמשתמש מתקן משהו.
     */
    private var daysContainer: LinearLayout? = null

    private fun renderCalendar(container: LinearLayout) {
        container.addView(TextView(this).apply {
            text = "14 הימים האחרונים"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            setPadding(0, 0, 0, 10)
        })

        val days = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        daysContainer = days
        container.addView(days)
        renderDays()
    }

    private fun renderDays() {
        val container = daysContainer ?: return
        container.removeAllViews()
        val days = CalendarStore.recentDays(this, 14)

        if (days.all { it.isEmpty }) {
            container.addView(TextView(this).apply {
                text = "עוד אין מה להציג"
                textSize = 12f
                gravity = Gravity.CENTER
                setTextColor(ContextCompat.getColor(context, R.color.text_tertiary))
            })
            return
        }

        val labelFormat = SimpleDateFormat("d/M", Locale.US)
        val parseFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

        for (day in days) {
            // ⚠️ **לא `day.isEmpty`.** הסיכום היומי סופר רק נפילות שלא
            // בוטלו, ולכן יום שכל נפילותיו בוטלו היה נעלם מהמסך — יחד עם
            // האפשרות לבטל את הביטול. שורה שהמשתמש נגע בה חייבת להישאר.
            val entries = CalendarStore.fallsOn(this, day.date)
            if (day.overcomings == 0 && entries.isEmpty()) continue

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.topMargin = 6
                layoutParams = lp
            }

            val label = try {
                labelFormat.format(parseFormat.parse(day.date) ?: Date())
            } catch (e: Exception) {
                day.date
            }

            row.addView(TextView(this).apply {
                text = label
                textSize = 12f
                setTextColor(ContextCompat.getColor(context, R.color.text_tertiary))
                layoutParams = LinearLayout.LayoutParams(70, LinearLayout.LayoutParams.WRAP_CONTENT)
            })

            if (day.overcomings > 0) {
                row.addView(chip("${day.overcomings} התגברויות", R.color.emerald_primary))
            }
            // ⚠️ **כל נפילה בנפרד, ולא סיכום אחד ליום.** קודם הוצגה קטגוריה
            // אחת, ולכן דיווח שני באותו יום נראה כאילו הוא **החליף** את
            // הראשון — וזה מה שגרם לנבו להסיק שהמחווה שומרת ברית בטעות.
            //
            // ולחיצה על נפילה מבטלת אותה. הוא ביקש את זה במפורש: דיווח
            // שנרשם בטעות חייב להיות ניתן לתיקון, אחרת כל טעות במחווה
            // הופכת לנתון קבוע שהמערכת תלמד ממנו.
            for ((i, entry) in entries.withIndex()) {
                val chipText = listOfNotNull(
                    entry.time.takeIf { it.isNotBlank() },
                    entry.category
                ).joinToString(" ")
                row.addView(
                    chip(
                        if (entry.cancelled) "$chipText ✗בוטל" else chipText,
                        if (entry.cancelled) R.color.text_tertiary else R.color.danger
                    ).apply {
                        setOnClickListener {
                            CalendarStore.setFallCancelled(
                                this@InfoActivity, day.date, i, !entry.cancelled
                            )
                            renderDays()
                        }
                    }
                )
            }

            container.addView(row)
        }
    }

    private fun chip(label: String, colorRes: Int): TextView = TextView(this).apply {
        text = label
        textSize = 12f
        setTextColor(android.graphics.Color.WHITE)
        setBackgroundColor(ContextCompat.getColor(context, colorRes))
        setPadding(10, 4, 10, 4)
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.marginEnd = 6
        layoutParams = lp
    }
}
