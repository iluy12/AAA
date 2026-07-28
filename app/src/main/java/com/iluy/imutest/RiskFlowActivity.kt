package com.iluy.imutest

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat

/**
 * פופאפ RISK A — "רגע איתך. הכל טוב?" (סעיף 9 במסמך המסירה).
 *
 * עקרון-על שאסור לשבור: המשתמש תמיד בוחר בעצמו. שום הסלמה אוטומטית,
 * גם בגרסת "פעם שנייה בשעה" — רק הניסוח משתנה, לא חופש הבחירה.
 */
class RiskFlowActivity : Activity() {

    companion object {
        const val EXTRA_SOURCE = "extra_source"
        const val EXTRA_VARIANT = "extra_variant"
        const val VARIANT_NORMAL = "normal"
        const val VARIANT_SECOND_TAP_IN_HOUR = "second_tap_in_hour"

        fun launch(context: Context, source: String, variant: String = VARIANT_NORMAL) {
            val intent = Intent(context, RiskFlowActivity::class.java).apply {
                putExtra(EXTRA_SOURCE, source)
                putExtra(EXTRA_VARIANT, variant)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            context.startActivity(intent)
        }
    }

    private lateinit var source: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AlertHelper.wakeScreen(this)
        AlertHelper.playAlertSound(this)
        source = intent.getStringExtra(EXTRA_SOURCE) ?: "לא ידוע"
        val variant = intent.getStringExtra(EXTRA_VARIANT) ?: VARIANT_NORMAL

        EventLog.log(this, "RISK_A_SHOWN", "source=$source;variant=$variant")

        val root = buildLayout(variant)
        setContentView(root)
    }

    private fun buildLayout(variant: String): View {
        val scroll = ScrollView(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 40, 28, 28)
            setBackgroundColor(ContextCompat.getColor(context, R.color.bg_white))
        }
        scroll.addView(container)

        if (DebugConfig.DEBUG_TAG_ENABLED) {
            container.addView(debugTag("הופעל ע\"י: $source"))
        }

        container.addView(TextView(this).apply {
            text = userFacingDetection(source)
            textSize = 13f
            setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 8)
        })

        val title = if (variant == VARIANT_SECOND_TAP_IN_HOUR)
            "זו כבר פעם שנייה בשעה האחרונה. בוא נצא מזה."
        else
            "רגע איתך. הכל טוב?"

        container.addView(TextView(this).apply {
            text = title
            textSize = 17f
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 20)
        })

        container.addView(bigButton("זיהוי לשווא") { onAllGood(falsePositive = true) })
        container.addView(bigButton("זיהוי אמיתי, אני מסתדר") { onAllGood(falsePositive = false) })
        container.addView(bigButton("עייף / עצבני") { onNeedsHelp("עייף/עצבני") })
        container.addView(bigButton("אני צריך עזרה") { onNeedsHelp("אני צריך עזרה") })
        container.addView(bigButton("אני צריך לדבר עכשיו", primary = true) { onNeedsToTalkNow() })

        // כפתור-חזרה גלובלי (חלק 1.1 בסבב העיצוב-מחדש): לעולם לא לסמוך
        // רק על הכפתור הפיזי/חומרה כדרך-יציאה יחידה. מכוון-בעיצוב שונה
        // מ-5 הכפתורים למעלה (טקסט בלבד, בלי רקע) כדי שלא ייראה כאופציה
        // שווה-משקל — זו יציאה, לא בחירה. מפעיל cooldown בדיוק כמו "הכל
        // טוב" כדי שלא ליצור לולאת-הצפה אם השעון תוקע-לתוקע (הבעיה
        // שהתגלתה בבדיקת-שטח: 4 מסכי RISK A רצופים בלי דרך לצאת).
        container.addView(backButton { onDismissedWithoutChoice() })

        return scroll
    }

    /**
     * ניסוח-משתמש למה שזוהה, לפי מקור-הטריגר בפועל (סעיף 6.5). זה נגזר
     * מ-source ולא מציג את תג-הדיבאג הטכני (זה נשאר בdebugTag הנפרד,
     * מוצג רק כש-DEBUG_TAG_ENABLED). התאמה לפי הקידומות שהקוראים בפועל
     * שולחים (MainActivity/MoodPickerActivity/TapDetectorService) — עם
     * נפילה-חזרה כללית אם מקור עתידי לא תואם אף דפוס.
     */
    private fun userFacingDetection(source: String): String = when {
        source.startsWith("כפתור 'נפלתי'") -> "לחצת על 'נפלתי'"
        source.startsWith("כפתור מצב-רוח") -> {
            val mood = Regex("\\((.*)\\)").find(source)?.groupValues?.getOrNull(1)
            if (mood != null) "בחרת שאתה מרגיש $mood" else "בחרת מצב-רוח"
        }
        source.startsWith("הקשה") -> "זיהינו הקשה על השעון"
        else -> "זיהינו: $source"
    }

    private fun onAllGood(falsePositive: Boolean) {
        val tag = if (falsePositive) "false_positive=true" else "true_positive=true"
        EventLog.log(this, "RISK_A_RESULT", "all_good;source=$source;$tag")
        LocalStore.setCooldownUntil(this, System.currentTimeMillis() + DebugConfig.COOLDOWN_MS)
        android.widget.Toast.makeText(this, "שמח לשמוע. אני כאן אם תצטרך.", android.widget.Toast.LENGTH_LONG).show()
        finish()
    }

    private fun onNeedsHelp(moodLabel: String) {
        EventLog.log(this, "RISK_A_RESULT", "needs_help;$moodLabel;source=$source;true_positive=true")
        HelpMenuActivity.launch(this, source = "$source → $moodLabel")
        finish()
    }

    private fun onNeedsToTalkNow() {
        // "הניסוח עצמו = הסכמה, בלי אישור כפול" — ישר ל-RISK B, בלי מסך ביניים
        EventLog.log(this, "RISK_A_RESULT", "needs_to_talk_now;source=$source;true_positive=true")
        CallHelper.startCall(this, source = "אני צריך לדבר עכשיו (מ: $source)")
        finish()
    }

    /**
     * חזרה בלי לבחור אף אפשרות. לא true/false-positive (זו לא טענה על
     * דיוק-הזיהוי, רק "לא רוצה להתעסק עם זה עכשיו") — תג נפרד ב-לוג.
     * כן מפעיל cooldown, מאותה סיבה שהכל-טוב מפעיל: למנוע פתיחה חוזרת
     * מיידית מאותה תנועה/הקשה.
     */
    private fun onDismissedWithoutChoice() {
        EventLog.log(this, "RISK_A_RESULT", "dismissed_without_choice;source=$source")
        LocalStore.setCooldownUntil(this, System.currentTimeMillis() + DebugConfig.COOLDOWN_MS)
        finish()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        CallHelper.onPermissionResult(this, requestCode, grantResults)
    }

    private fun debugTag(text: String): View = TextView(this).apply {
        this.text = text
        textSize = 11f
        setTextColor(Color.WHITE)
        setBackgroundColor(ContextCompat.getColor(context, R.color.danger))
        setPadding(12, 6, 12, 6)
        gravity = Gravity.CENTER
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.bottomMargin = 20
        layoutParams = lp
    }

    private fun backButton(onClick: () -> Unit): Button =
        Button(this).apply {
            text = "חזרה"
            setTextColor(ContextCompat.getColor(context, R.color.text_tertiary))
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            textSize = 13f
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = 24
            layoutParams = lp
            setOnClickListener { onClick() }
        }

    private fun bigButton(label: String, primary: Boolean = false, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            setTextColor(Color.WHITE)
            setBackgroundColor(
                ContextCompat.getColor(
                    context,
                    if (primary) R.color.emerald_primary else R.color.emerald_primary_hover
                )
            )
            textSize = 15f
            minHeight = 130
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = 16
            layoutParams = lp
            setOnClickListener { onClick() }
        }
}
