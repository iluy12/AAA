package com.iluy.imutest

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat

/**
 * אישור-קל להקשה ראשונה בשעה (חלק 3.1 בסבב העיצוב-מחדש, הועלה-בעדיפות
 * יחד עם 1.1 — שני מסכי-בחירה כפויים הם שהפכו את השעון ל"בלתי-שמיש"
 * בבדיקת-שטח). ההקשה היא אירוע חיובי — התגברות — לא צריכה מסך-בחירה
 * ולא קטלוג. מדליק מסך + צליל (AlertHelper הקיים, כמו כל טריגר אחר),
 * מציג הודעה קצרה בלי שום כפתור, ונסגר לבד אחרי כמה שניות.
 *
 * מחליף לגמרי את VideoPlaceholderActivity ואת ה-notification "עילוי
 * שלחו לך סרטון" שהיו במסלול הזה — לא מסלול מקביל, תחליף.
 *
 * "ירוק, ממוספר" בלוח-השנה (סעיף 5) עדיין לא מיושם כאן — CalendarStore
 * לא בנוי עדיין. התיעוד היחיד כרגע הוא tap_first_in_hour שכבר נכתב
 * ב-TapDetectorService לפני ההשקה של המסך הזה.
 */
class TapAcknowledgedActivity : Activity() {

    companion object {
        const val EXTRA_SOURCE = "extra_source"
        private const val AUTO_DISMISS_MS = 2_500L

        fun launch(context: Context, source: String) {
            val intent = Intent(context, TapAcknowledgedActivity::class.java).apply {
                putExtra(EXTRA_SOURCE, source)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            context.startActivity(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AlertHelper.wakeScreen(this)
        AlertHelper.playAlertSound(this)

        val source = intent.getStringExtra(EXTRA_SOURCE) ?: "לא ידוע"
        EventLog.log(this, "INFO", "tap_acknowledgment_shown;source=$source")

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(28, 40, 28, 28)
        }
        container.addView(TextView(this).apply {
            text = "ההקשה נשמרה"
            textSize = 17f
            gravity = Gravity.CENTER
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
        })
        setContentView(container)

        Handler(Looper.getMainLooper()).postDelayed({ finish() }, AUTO_DISMISS_MS)
    }
}
