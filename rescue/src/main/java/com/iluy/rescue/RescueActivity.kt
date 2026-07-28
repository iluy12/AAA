package com.iluy.rescue

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * אפליקציית-חילוץ. קיימת רק כדי לצאת ממצב שבו עילוי הוגדרה כמסך-הבית
 * ואין דרך להגיע להגדרות או להסיר אותה.
 *
 * שלוש עובדות שהופכות אותה לדרך היחידה שנשארה:
 *  1. שם-חבילה שונה — נמנעת התנגשות-חתימה שחוסמת התקנה-מעל של עילוי
 *  2. מתקין-החבילות מציג "פתח" מיד אחרי התקנה — הפעלה בלי מסך-בית
 *  3. ACTION_DELETE מבקש הסרה של חבילה אחרת, בלי צורך בהרשאה מיוחדת
 *
 * אין כאן פעולה אוטומטית בכוונה: קפיצה מיידית למקום אחר עלולה להשאיר
 * אותך תקוע שם בלי דרך חזרה. הכל בלחיצה מפורשת.
 */
class RescueActivity : Activity() {

    companion object {
        private const val ILUY_PACKAGE = "com.iluy.imutest"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(24, 32, 24, 24)
        }

        container.addView(TextView(this).apply {
            text = "חילוץ"
            textSize = 20f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 16)
        })

        container.addView(bigButton("הסר את עילוי") {
            try {
                startActivity(
                    Intent(Intent.ACTION_DELETE, Uri.parse("package:$ILUY_PACKAGE"))
                )
            } catch (e: Exception) {
                Toast.makeText(this, "לא הצלחתי לפתוח הסרה", Toast.LENGTH_LONG).show()
            }
        })

        container.addView(bigButton("בחר מסך בית") {
            openFirstAvailable(
                Intent(Settings.ACTION_HOME_SETTINGS),
                Intent(Settings.ACTION_SETTINGS)
            )
        })

        container.addView(bigButton("הגדרות עילוי") {
            openFirstAvailable(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:$ILUY_PACKAGE")
                ),
                Intent(Settings.ACTION_SETTINGS)
            )
        })

        container.addView(TextView(this).apply {
            text = "אחרי שהסרת את עילוי, מסך הבית המקורי חוזר מעצמו"
            textSize = 11f
            gravity = Gravity.CENTER
            setPadding(0, 16, 0, 0)
        })

        setContentView(container)
    }

    private fun openFirstAvailable(vararg candidates: Intent) {
        for (intent in candidates) {
            try {
                startActivity(intent)
                return
            } catch (e: Exception) {
                // ננסה את הבא בתור
            }
        }
        Toast.makeText(this, "לא הצלחתי לפתוח הגדרות", Toast.LENGTH_LONG).show()
    }

    private fun bigButton(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            textSize = 15f
            minHeight = 120
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = 12
            layoutParams = lp
            setOnClickListener { onClick() }
        }
}
