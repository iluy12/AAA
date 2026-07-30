package com.iluy.imutest

import android.content.Context

/**
 * הבסיס האישי: מה נורמלי **אצל המשתמש הזה**, בפילוח לפי שעה ביום.
 *
 * ## למה אישי ולא סף אחיד — וזו לא קפריזה
 *
 * המחקר על דופק בעוררות מראה שני דברים שמושכים לכיוונים הפוכים. סף אחיד
 * לכל האוכלוסייה **נכשל** — דופק לא מבחין בין גירוי מיני, מעורר-חרדה
 * וניטרלי. אבל לכל אדם יש **"דופק-חתימה" עקבי משלו**, עם מקדם התאמה
 * תוך-אישי של 0.71-0.82.
 *
 * > **סף אוכלוסייה = נכשל. חתימה אישית נלמדת = עובדת.**
 *
 * ⚠️ ומכאן נובע דבר שקל לפספס: **"+15 פעימות מעל הרגיל" הוא סף אוכלוסייה
 * בתחפושת.** זה מספר קבוע לכל המשתמשים, בדיוק מה שהמחקר אומר שנכשל. אצל
 * מי שדופק המנוחה שלו יציב ב-±2 זה אירוע נדיר; אצל מי שנע ±12 באופן טבעי
 * זה יקרה כל שעה.
 *
 * לכן "מוגבר" נמדד ביחידות **של המשתמש עצמו** — ראו [deviation].
 *
 * ## למה חציון ולא ממוצע, ולמה MAD ולא סטיית-תקן
 *
 * דגימה חריגה אחת לא מזיזה חציון. ו-MAD — החציון של המרחקים מהחציון —
 * הוא מדד הפיזור העמיד המקביל, ומאותה סיבה. סטיית-תקן הייתה מתנפחת מכל
 * קריאה שגויה בודדת.
 *
 * ## ⚠️ מה מרעיל את הבסיס
 *
 * הבסיס נבנה **ממנוחה בלבד**. שלוש דרכים לזהם אותו, וכולן נחסמות ב-[isResting]:
 *
 * 1. **דופק אחרי מאמץ.** נבו הציע להגדיר מנוחה כ"מיד אחרי דקה של תנועה",
 *    אבל דופק מיד אחרי תנועה עדיין גבוה מהמאמץ — הבסיס היה יוצא מנופח,
 *    ואז עוררות אמיתית לא הייתה בולטת מעליו. לכן נדרש **שקט מתמשך**.
 * 2. **שעון שלא על היד.** שעון על השידה היה מכניס ערכי-זבל.
 * 3. **פרץ ריק.** `bpm = -1` אינו דופק נמוך, הוא היעדר מדידה.
 */
object Baseline {

    private const val PREFS_NAME = "iluy_baseline"

    /**
     * כמה זמן בלי צעד נדרש כדי שרשומה תיחשב מנוחה.
     *
     * ⚠️ חמש דקות ולא דקה. זו ההבחנה בין שני דברים שנבו ענה עליהם באותה
     * תשובה, ושהם לא אותו דבר: **"התנועה נעצרה"** הוא רגע המעבר, ומשמש
     * את ההדק. **"מנוחה"** הוא מצב מתמשך, ומשמש את הלמידה. אותו כלל
     * לשניהם היה מרעיל את הבסיס.
     */
    const val REST_MIN_STILL_MS = 5 * 60 * 1000L

    /** מעל זה כמות דגימות בפרץ נחשבת מספקת לקריאה אמינה. */
    const val MIN_SAMPLES_IN_BURST = 20

    /**
     * מינימום דגימות-מנוחה בתא שעתי לפני שהוא שמיש.
     *
     * ⚠️ 20 ולא 5. שבוע נותן כ-7 קריאות מנוחה לתא שעתי בלבד, וחציון על 7
     * רועש מדי מכדי להשוות אליו. לכן יש [levelFor] — נפילה-חזרה לתא גס
     * יותר במקום להשוות למשהו שאינו יציב.
     */
    const val MIN_SAMPLES_PER_BUCKET = 20

    /**
     * רצפה ל-MAD. ⚠️ הכרחית: משתמש עם דופק מנוחה יציב מאוד ייתן MAD=1,
     * ואז שלוש יחידות הן 3 פעימות — והמערכת תירה על רעש. 3 פעימות הן
     * הרזולוציה הסבירה של החיישן הזה.
     */
    const val MIN_MAD_BPM = 3.0

    /**
     * תקרה ל-MAD, כדי שמשתמש רועש במיוחד לא יהפוך לבלתי-ניתן-לזיהוי —
     * אחרת כל חריגה הייתה נבלעת בפיזור שלו.
     */
    const val MAX_MAD_BPM = 12.0

    /** כמה קריאות שומרים לכל תא. מספיק לחציון יציב, וזניח בנפח. */
    private const val KEEP_PER_BUCKET = 120

    /**
     * האם הרשומה מתאימה ללמידת הבסיס. ראו הערת-ההרעלה במחלקה.
     */
    fun isResting(r: SampleStore.Record): Boolean =
        r.bpm > 0 &&
            r.samples >= MIN_SAMPLES_IN_BURST &&
            r.steps == 0 &&
            r.stillMs >= REST_MIN_STILL_MS &&
            // ⚠️ **התנאי הזה נולד מבאג שנמדד.** שעון שמונח על השולחן עונה
            // על כל שאר התנאים — דופק תקין (מערך שנשאר בזיכרון), אפס
            // צעדים, וחוסר תנועה מוחלט. ב-2026-07-30 ב-21:51 השעון היה
            // מחוץ ליד עם 104 דגימות ללא מגע, ורק התיישנות הדופק אחרי
            // שתי דקות מנעה את הרעלת הבסיס — כלומר בחלון שלפניה היא כן
            // הייתה קורית.
            //
            // אפס ולא "מעט": פרץ עם מגע חלקי הוא פרץ שבו השעון זז על היד
            // או נענד באמצע, ואי-אפשר לדעת אילו מהדגימות תקינות.
            r.noContact == 0

    fun learn(context: Context, r: SampleStore.Record) {
        if (!isResting(r)) return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val key = "h${r.hourOfDay}"
        val values = (prefs.getString(key, "") ?: "")
            .split(',').mapNotNull { it.trim().toIntOrNull() }
            .plus(r.bpm)
            .takeLast(KEEP_PER_BUCKET)
        prefs.edit().putString(key, values.joinToString(",")).apply()
    }

    /**
     * רמת-הייחוס לשעה נתונה: החציון והפיזור, ומאיזה תא הם הגיעו.
     *
     * ⚠️ **הנפילה-חזרה כאן היא העיקר.** 24 תאים שעתיים מול שבוע נתונים
     * נותנים כ-7 קריאות לתא, והרבה תאים יהיו ריקים לגמרי. לכן: תא שעתי
     * אם יש בו מספיק, אחרת בלוק של 4 שעות, אחרת הכל. מתדרדר בחן במקום
     * להישבר או להשוות לחציון של שלוש דגימות.
     */
    fun levelFor(context: Context, hourOfDay: Int): Level? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val read = { h: Int ->
            (prefs.getString("h$h", "") ?: "").split(',').mapNotNull { it.trim().toIntOrNull() }
        }

        val exact = read(hourOfDay)
        if (exact.size >= MIN_SAMPLES_PER_BUCKET) return level(exact, "hour")

        // בלוק של 4 שעות סביב אותה שעה — קרוב מבחינת מקצב יומי
        val blockStart = (hourOfDay / 4) * 4
        val block = (blockStart until blockStart + 4).flatMap { read(it % 24) }
        if (block.size >= MIN_SAMPLES_PER_BUCKET) return level(block, "block")

        val all = (0 until 24).flatMap { read(it) }
        if (all.size >= MIN_SAMPLES_PER_BUCKET) return level(all, "all")

        // עוד אין בסיס. ⚠️ null ולא ניחוש — בלי בסיס אין מה להשוות, וזה
        // תנאי מוצרי ולא באג: השבוע הראשון שקט מהבחינה הזו.
        return null
    }

    private fun level(values: List<Int>, source: String): Level {
        val sorted = values.sorted()
        val median = sorted[sorted.size / 2].toDouble()
        val deviations = sorted.map { kotlin.math.abs(it - median) }.sorted()
        val mad = deviations[deviations.size / 2]
            .coerceIn(MIN_MAD_BPM, MAX_MAD_BPM)
        return Level(median, mad, values.size, source)
    }

    data class Level(
        val medianBpm: Double,
        val madBpm: Double,
        val sampleCount: Int,
        val source: String
    )

    /**
     * בכמה **יחידות אישיות** הדופק הנוכחי מעל הרגיל של המשתמש בשעה הזו.
     *
     * זו ההמרה שמיישמת את המחקר בפועל: אצל אחד שלוש יחידות יוצאות +9
     * פעימות, אצל אחר +21 — **"כמה פעימות" מתגלה לכל אדם בנפרד במקום
     * להיקבע מראש.**
     *
     * ⚠️ את הסף עצמו (כמה יחידות = "מוגבר") **אין מה לקבוע כאן.** הוא
     * ייקבע ממצב-צל, אחרי שיצטבר פיזור אמיתי של ציונים. כל מספר שהיה
     * נכתב כאן עכשיו היה ניחוש.
     */
    fun deviation(level: Level, currentBpm: Int): Double =
        (currentBpm - level.medianBpm) / level.madBpm

    fun describe(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        var filled = 0
        var total = 0
        for (h in 0 until 24) {
            val n = (prefs.getString("h$h", "") ?: "")
                .split(',')
                .mapNotNull { it.trim().toIntOrNull() }
                .size
            total += n
            if (n >= MIN_SAMPLES_PER_BUCKET) filled++
        }
        return "buckets_ready=$filled/24;resting_samples=$total"
    }
}
