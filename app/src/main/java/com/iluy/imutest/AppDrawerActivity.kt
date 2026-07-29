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
import android.widget.Toast
import androidx.core.content.ContextCompat

/**
 * מגירת-אפליקציות — רשימת כל מה שמותקן במכשיר.
 *
 * ## למה זה חובה ולא נוחות
 *
 * ברגע שעילוי מוגדרת כמסך-הבית היא מחליפה את הלאנצ'ר של היצרן, ואיתו
 * את הדרך היחידה להגיע לכל שאר האפליקציות — דפדפן, הגדרות, טלפון.
 * בלי מגירה, המכשיר נעול על מסך אחד: אי-אפשר להתקין, אי-אפשר להגדיר,
 * ואי-אפשר להסיר את עילוי עצמה. זה קרה בפועל.
 *
 * לאנצ'ר שלא נותן גישה לאפליקציות הוא לא לאנצ'ר. זה מה שהופך את
 * מסך-הבית שלנו לתחליף לגיטימי ולא למלכודת.
 */
class AppDrawerActivity : Activity() {

    companion object {
        fun launch(context: Context) {
            context.startActivity(Intent(context, AppDrawerActivity::class.java))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scroll = ScrollView(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 28, 20, 24)
        }
        scroll.addView(container)

        container.addView(TextView(this).apply {
            text = "כל האפליקציות"
            textSize = 17f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 12)
        })

        val apps = loadLaunchableApps()
        if (apps.isEmpty()) {
            container.addView(TextView(this).apply {
                text = "לא נמצאו אפליקציות"
                textSize = 13f
                gravity = Gravity.CENTER
                setTextColor(ContextCompat.getColor(context, R.color.text_tertiary))
            })
        } else {
            for (app in apps) {
                container.addView(appButton(app))
            }
        }

        container.addView(Button(this).apply {
            text = "חזרה"
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = 20
            layoutParams = lp
            setOnClickListener { finish() }
        })

        setContentView(scroll)
    }

    private data class LaunchableApp(val label: String, val packageName: String)

    /**
     * עוברים על **כל החבילות המותקנות** ובודקים למי מהן יש כוונת-הפעלה,
     * במקום לשאול מי מכריז על CATEGORY_LAUNCHER.
     *
     * הסיבה: אפליקציות של יצרני שעונים לרוב לא מכריזות על הקטגוריה הזו
     * — הלאנצ'ר שלהם מפעיל אותן ישירות — ולכן חיפוש לפי הכרזה החזיר
     * רשימה כמעט ריקה, ואיתה נחסמה הגישה לאפליקציית הדופק ולהגדרות.
     *
     * targetSdk 28, ולכן אין מגבלות נראות-חבילות של אנדרואיד 11+ ואין
     * צורך בהרשאה מיוחדת.
     */
    private fun loadLaunchableApps(): List<LaunchableApp> = try {
        packageManager.getInstalledApplications(0)
            .mapNotNull { app ->
                // עילוי **כן** מופיעה כאן. בלאנצ'ר רגיל היה הגיוני להסתיר
                // את עצמנו, אבל כאן מסך-השעון הוא הבית והמגירה היא הדרך
                // להגיע למסך הראשי — הסתרתה חסמה בדיוק את מה שצריך.
                packageManager.getLaunchIntentForPackage(app.packageName)
                    ?: return@mapNotNull null
                LaunchableApp(
                    label = packageManager.getApplicationLabel(app)?.toString()
                        ?: app.packageName,
                    packageName = app.packageName
                )
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label }
    } catch (e: Exception) {
        EventLog.log(this, "ERROR", "app_drawer_query_failed")
        emptyList()
    }

    private fun appButton(app: LaunchableApp): Button = Button(this).apply {
        text = app.label
        textSize = 14f
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.topMargin = 8
        layoutParams = lp
        setOnClickListener {
            val launch = packageManager.getLaunchIntentForPackage(app.packageName)
            if (launch == null) {
                Toast.makeText(
                    this@AppDrawerActivity, "לא הצלחתי לפתוח", Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }
            startActivity(launch)
        }
    }
}
