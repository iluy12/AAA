package com.iluy.imutest

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * "מה המערכת רואה עכשיו" — מסך אימות לתקופת הכיול.
 *
 * ## ⚠️ מציג את הקלט, לא את הפסק
 *
 * מצב-צל קיים כדי שהמשתמש לא ייחשף להתראות שווא. **אבל נבו הוא הבודק,
 * לא המשתמש** — ובלי לראות מה המערכת קולטת, אין דרך לדעת אם היא קוראת
 * אותו נכון או שהיא מודדת זבל בשקט.
 *
 * ההבחנה שנשמרת כאן: המסך מציג **עובדות** — דומם כמה דקות, דופק כמה,
 * כמה מעל הרגיל שלו — ולא **פסק דין**. עובדה אינה מלמדת אותו שהמערכת
 * עומדת להציע משהו, ולכן היא כמעט לא משנה את התנהגותו. ציון בולט על
 * המסך היה הופך את הכיול למשחק, ואת הנתונים למזוהמים.
 *
 * הציון עצמו מוצג בקטן ובתחתית, כי הוא כן נחוץ לאימות — אבל הוא לא
 * העיקר.
 *
 * ⚠️ **המסך הזה זמני.** הוא נועד לתקופת הכיול ואמור לרדת לפני שהמוצר
 * מגיע למישהו אחר.
 */
class StatusActivity : Activity() {

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        KeyLog.record(this, "status", event)
        return super.dispatchKeyEvent(event)
    }

    companion object {
        fun launch(context: Context) {
            context.startActivity(Intent(context, StatusActivity::class.java))
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var body: TextView

    private val refresh = object : Runnable {
        override fun run() {
            body.text = render()
            // ⚠️ רענון כל 5 שניות ולא בזמן אמת: הנתונים מתעדכנים רק בפרץ,
            // שקורה כל 2-10 דקות. רענון צפוף יותר היה מציג את אותו מספר
            // שוב ושוב ורק שורף סוללה.
            handler.postDelayed(this, 5_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        body = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.WHITE)
            setPadding(20, 20, 20, 20)
        }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#141414"))
            addView(LinearLayout(this@StatusActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.START
                addView(body)
            })
        })
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresh)
    }

    override fun onPause() {
        handler.removeCallbacks(refresh)
        super.onPause()
    }

    /**
     * ⚠️ הכל נקרא מהרשומה האחרונה שנשמרה, ולא נמדד מחדש. מדידה כאן הייתה
     * מדליקה חיישנים מחוץ לפרץ — וכבר נמדד במכשיר הזה שהדלקת מקלט באמצע
     * שוברת חיישן אחר.
     */
    private fun render(): String {
        val last = SampleStore.recent(this, 1).lastOrNull()
            ?: return "עוד אין נתונים.\nלבש את השעון וחכה כמה דקות."

        val sb = StringBuilder()
        val ageMin = (System.currentTimeMillis() - last.timestampMs) / 60000

        sb.append("── מה נמדד ──\n")
        sb.append("לפני $ageMin דק'\n\n")

        sb.append(if (last.noContact > 0 || last.bpm <= 0) "על היד:  ✗ לא\n" else "על היד:  ✓ כן\n")
        sb.append("דופק:  ${if (last.bpm > 0) last.bpm else "—"}")
        if (last.bpmMin > 0) sb.append("   (${last.bpmMin}-${last.bpmMax})")
        sb.append("\n")
        sb.append("מגמה בפרץ:  ${if (last.bpmTrend > 0) "+" else ""}${last.bpmTrend}\n")

        val stillMin = if (last.stillMs >= 0) last.stillMs / 60000 else -1
        sb.append("דומם:  ${if (stillMin >= 0) "$stillMin דק'" else "—"}\n")
        sb.append("צעדים בפרץ:  ${last.steps}\n")
        sb.append("תנועה עדינה:  ${if (last.motion >= 0) last.motion else "—"}\n")
        sb.append("כיוון יד:  ${last.gravityX},${last.gravityY},${last.gravityZ}\n")
        sb.append("מיקום:  ${if (last.placeMeters >= 0) "${last.placeMeters} מ'" else "—"}\n")
        sb.append("סוללה:  ${last.battery}%\n\n")

        sb.append("── הבסיס שלך ──\n")
        val level = Baseline.levelFor(this, last.hourOfDay)
        if (level == null) {
            sb.append("עוד לא נבנה\n")
        } else {
            sb.append("רגיל בשעה זו:  ${"%.0f".format(level.medianBpm)}\n")
            sb.append("פיזור:  ±${"%.0f".format(level.madBpm)}\n")
            if (last.bpm > 0) {
                sb.append("עכשיו:  ${"%+.1f".format(Baseline.deviation(level, last.bpm))} יחידות\n")
            }
            sb.append("מקור:  ${level.source}\n")
        }
        sb.append("${Baseline.describe(this)}\n")
        sb.append("רשומות:  ${SampleStore.count(this)}\n\n")

        sb.append("── תקציב ──\n")
        sb.append("${OfferBudget.describe(this)}\n")
        // ⚠️ מוצג כאן ולא בתחתית: זה **מה שיקרה** בדיווח הבא, וזה בדיוק
        // מה שנבו צריך לראות כדי לבדוק שהעץ מצטבר נכון.
        sb.append("הסלמה:  ${Escalation.levelNow(this)} (${Escalation.total(this)})\n\n")

        // ⚠️ בתחתית ובקטן בכוונה — ראו הערת-המחלקה.
        sb.append("── ציון ──\n")
        if (last.blocked.isNotBlank()) {
            sb.append("נחסם:  ${last.blocked}\n")
        } else {
            sb.append("${last.score} מתוך ${last.available}\n")
        }
        if (last.nearReport.isNotBlank()) sb.append("סמוך לדיווח:  ${last.nearReport}\n")

        return sb.toString()
    }
}
