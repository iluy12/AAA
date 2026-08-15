package com.iluy.imutest

import android.content.Context

/**
 * מסך הכיול: "מה קורה עכשיו?" — כשהדופק חורג מהרגיל בישיבה.
 *
 * ## ⚠️ זה לא ההצעה העדינה, וזה בכוונה
 *
 * `RiskScore.SILENT_MODE` נשאר דלוק. זה חוצה גבול אחר — הפעם הראשונה
 * שהשעון פונה על סמך פיזיולוגיה בלבד, לא בתגובה לדיווח. הצדקה מלאה:
 *
 * > **זו הפעולה היחידה שהערך שלה לא תלוי בכך שהסף נכון.** אם +20 שגוי,
 * > התשובות יהיו "כלום" — וזו בדיוק המדידה שחסרה. הסף לא צריך להיות
 * > מוכח כדי שהמסך הזה ישתלם; הוא צריך להיות מוכח כדי **להתריע**,
 * > וזה לא מה שנבנה כאן.
 *
 * ## הסף
 *
 * ⚠️ **+20 מעל הבסיס, לא +26 ולא +15.** נמדד על הבסיס-לפי-מצב החדש
 * (חציון 76, ראו Baseline): +20 יורה כ-3.2 פעמים ביום, בתוך התקציב
 * שנקבע. זה **לא** אותו סף כמו בטבלת ה"התראה" המוקדמת יותר של אותו
 * יום — זו הייתה שאלה אחרת (מתי להתריע), וזו שאלה על מתי לשאול.
 *
 * ## מה זה לא בודק
 *
 * לא הליכה-אחרי-עלייה, לא שום צירוף. הצירוף הזה נבנה מ-16:18 בלבד —
 * מקרה יחיד שגם יצר את ההשערה וגם "אישר" אותה, אישור מעגלי. הסף כאן
 * פיזיולוגי גרידא; דפוסי תנועה נאספים כתיוג ב-[CheckInLog], לא כתנאי.
 */
object CheckIn {

    /** מעל חציון-הערות ב-20 ומעלה. ראו הסבר-הסף במחלקה. */
    private const val THRESHOLD_ABOVE_BASELINE_BPM = 20

    /**
     * חריגה פיזיולוגית טהורה — בלי תקציב, בלי קירור, בלי שינה.
     *
     * ⚠️ **קריאה נפרדת מ-[evaluate], כי לה יש שימוש שני.** `TapDetectorService`
     * קורא לה **לפני** שהוא מלמד את הרשומה לבסיס — רשומה שחוצה את הסף
     * לא נכנסת ללמידה, בלי קשר לתשובה שתגיע עליה מאוחר יותר. אילו
     * ההדרה הזו הייתה תלויה בתקציב/קירור, יום שהתקציב בו נגמר היה
     * חוזר להרעיל את הבסיס בדיוק כמו לפני כל התיקונים של היום.
     */
    fun exceedsBaseline(context: Context, r: SampleStore.Record): Boolean {
        if (r.bpm <= 0 || r.steps != 0 || r.noContact != 0) return false
        if (r.samples < Baseline.MIN_SAMPLES_IN_BURST) return false
        if (Baseline.isProlongedStill(r)) return false
        val level = Baseline.levelFor(context) ?: return false
        return r.bpm >= level.medianBpm + THRESHOLD_ABOVE_BASELINE_BPM
    }

    /**
     * הבדיקה המלאה: פותח מסך רק אם כל התנאים מתקיימים.
     *
     * ⚠️ **סדר הבדיקות מכוון: הזול והוודאי קודם**, כמו ב-`RiskScore.gateBlock`.
     * אין טעם לבדוק תקציב אם הפיזיולוגיה בכלל לא חרגה.
     */
    fun evaluate(context: Context, r: SampleStore.Record) {
        if (!exceedsBaseline(context, r)) return

        // ⚠️ **אף פעם לא בשינה.** REM נותן זינוקים של 20-40 פעימות בלי
        // תנועה, 4-6 פעמים בלילה — בלי החסימה הזו המסך יעיר אותו כל
        // לילה, והוא יוריד את השעון תוך יומיים. גם כבר נבדק בתוך
        // exceedsBaseline, אבל נבדק שוב כאן במפורש כי זה התנאי שאסור
        // שייכשל בשקט אם exceedsBaseline ישתנה בעתיד.
        if (Baseline.isProlongedStill(r)) return

        // אל תשאל מיד אחרי שהוא כבר דיווח משהו — יש כבר תגובה בדרך.
        if (r.nearReport.isNotBlank()) return

        if (!CheckInBudget.hasRoom(context)) {
            EventLog.log(context, "CHECKIN", "skipped;reason=budget;bpm=${r.bpm}")
            return
        }

        val level = Baseline.levelFor(context) ?: return
        val deviation = r.bpm - level.medianBpm.toInt()

        CheckInBudget.record(context)
        EventLog.log(
            context, "CHECKIN",
            "triggered;bpm=${r.bpm};median=${"%.1f".format(level.medianBpm)};dev=$deviation;" +
                "${CheckInBudget.describe(context)}"
        )
        CheckInActivity.launch(context, r.bpm, level.medianBpm, deviation)
    }
}
