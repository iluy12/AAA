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

        container.addView(TextView(this).apply {
            text = "לוח ההתגברויות יופיע כאן"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(ContextCompat.getColor(context, R.color.text_tertiary))
        })

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
}
