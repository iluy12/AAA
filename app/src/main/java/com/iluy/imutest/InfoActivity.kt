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
    private fun renderCalendar(container: LinearLayout) {
        val days = CalendarStore.recentDays(this, 14)

        container.addView(TextView(this).apply {
            text = "14 הימים האחרונים"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            setPadding(0, 0, 0, 10)
        })

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
            if (day.isEmpty) continue

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
            if (day.fallCategory != null) {
                row.addView(chip(day.fallCategory, R.color.danger))
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
