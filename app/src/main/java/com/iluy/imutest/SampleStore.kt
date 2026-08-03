package com.iluy.imutest

import android.content.Context
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * הזיכרון של המערכת. רשומה אחת לכל פרץ-דופק, בקובץ שנשאר.
 *
 * ## למה זה נבנה, וזה החלק שהיה חסר
 *
 * מד הדופק כבר עובד ואמין — אבל עד עכשיו כל מה שהוא מדד נכנס **רק**
 * ל-`EventLog`, שהוא לוג-דיבאג המוגבל ל-500 שורות בהעלאה ונדרס תוך שעות.
 * כלומר המערכת מדדה מצוין ולא **זכרה** כלום, ובלי זיכרון אין בסיס אישי
 * ואין ממה ללמוד. זה הקובץ שסוגר את הפער.
 *
 * ## למה רשומה לפרץ ולא לדקה
 *
 * התכנון המקורי דיבר על "סיכומי-דקה, 86 קילובייט ליום" — והוא הניח דגימה
 * רצופה. בפועל דוגמים בפרצים, כל 2 דקות בפעילות ו-10 בשקט, ולכן יש
 * 150-700 רשומות ביום ולא 1440. **כ-50 קילובייט ליום במקרה הגרוע**, פחות
 * ממה שתוכנן.
 *
 * ## אחסון מקומי בלבד
 *
 * הוכרע: אין שרת, אין רשת, שום דבר לא יוצא מהמכשיר. ⚠️ ואנדרואיד **אינו**
 * מוחק נתוני אפליקציה בעדכון, רק בהסרה — כלומר הבסיס שורד עדכונים לבד.
 */
object SampleStore {

    private const val FILE_NAME = "iluy_samples.csv"
    private const val PREFS_NAME = "iluy_samples_meta"
    private const val KEY_COUNT = "count"

    /**
     * שלושים יום. הבסיס עצמו נשען על שבוע, והשאר נשמר כדי שאפשר יהיה
     * לחשב אותו מחדש אם ההגדרות ישתנו — בלי לאבד את ההיסטוריה.
     */
    private const val MAX_RECORDS = 30 * 700

    /** גריסה רק כשעברנו בהרבה, כדי לא לשכתב קובץ שלם בכל פרץ. */
    private const val PRUNE_SLACK = 500

    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    /**
     * רשומה אחת. `bpm = -1` פירושו שהפרץ חזר בלי דגימה תקינה — ⚠️ וזה
     * נשמר במפורש ולא מושתק: פרץ ריק הוא מידע על אמינות החיישן, וכבר
     * פעם אחת "ערך שנראה תקין" הסתיר תקלה של שמונה שעות.
     */
    data class Record(
        val timestampMs: Long,
        val hourOfDay: Int,
        val bpm: Int,
        val samples: Int,
        val firstSampleMs: Long,
        val steps: Int,
        val stillMs: Long,
        val battery: Int,
        /**
         * כמה דגימות בפרץ הגיעו **בלי מגע עם העור**.
         *
         * ⚠️ **בלי השדה הזה הבסיס מורעל, וזה נמדד.** ב-2026-07-30 בשעה
         * 21:51 השעון היה מחוץ ליד ו-104 מתוך 125 הדגימות היו ללא מגע.
         * "מנוחה" מוגדרת כדופק תקין + אפס צעדים + חוסר תנועה — **ושעון על
         * השולחן עונה על שלושת התנאים.** ההגנה היחידה הייתה שהדופק מתיישן
         * אחרי שתי דקות, אבל בחלון שלפני כן ערך ישן נחשב תקין ונכנס לבסיס.
         */
        val noContact: Int = 0,
        /**
         * כיוון כוח הכובד בפרק היד, ב-m/s² כפול 10, ועוצמת התנועה.
         *
         * ⚠️ **אלה מספרים גולמיים בכוונה, ולא "שוכב/יושב".** השעון על פרק
         * היד ולא על הגוף — אדם שוכב יכול להחזיק יד באוויר, ואדם עומד
         * יכול להניח יד אופקית. סיווג תנוחה מראש היה סף אוכלוסייה
         * בתחפושת, בדיוק כמו "+15 פעימות".
         *
         * לכן נרשם הווקטור, והתבנית האישית תתגלה מהנתונים — אותה שיטה
         * שעבדה בדופק. `motion` הוא פיזור עוצמת התאוצה, והוא מודד חוסר-
         * תנועה **עדין יותר ממונה הצעדים**: מי שיושב ומקליד אינו צובר
         * צעדים, אבל היד שלו זזה.
         */
        val gravityX: Int = 0,
        val gravityY: Int = 0,
        val gravityZ: Int = 0,
        val motion: Int = -1,
        /**
         * הצורה **בתוך** הפרץ.
         *
         * ⚠️ **הנתון הזה כבר היה בידינו ונזרק.** כל פרץ מכיל כ-65 דגימות
         * ב-3Hz, ושמרנו מהן חציון אחד — כלומר 21 שניות של עקומה הצטמצמו
         * למספר. `trend` הוא ההפרש בין מחצית שנייה לראשונה, ולכן פרץ
         * שהדופק עולה בתוכו נראה עכשיו שונה מפרץ יציב באותו חציון.
         *
         * זה לא מספיק בשביל "עליה-רוגע-עליה" — לזה צריך דגימה רציפה —
         * אבל זה ההבדל בין נקודה לבין קטע.
         */
        val bpmMin: Int = -1,
        val bpmMax: Int = -1,
        val bpmTrend: Int = 0,
        /**
         * מה שהמערכת **חשבה** ברגע הזה.
         *
         * ⚠️ **בלי זה מצב-הצל חסר טעם.** הציונים נרשמו רק ללוג-הדיבאג,
         * שמוגבל ל-500 שורות ונדרס תוך שעות — כלומר אי-אפשר היה לשאול
         * "מה היה הציון ביום שלישי ב-21:00", והצטברות ציונים היא כל מה
         * שמצב-הצל אמור לייצר.
         *
         * ⚠️ ו-`score` לבדו חסר משמעות בלי `available`: בשבוע הראשון רוב
         * האותות אינם ניתנים לחישוב, ולכן ציון 20 מתוך 30 אפשריים אינו
         * אותו דבר כמו 20 מתוך 60.
         *
         * `blocked` מציין איזה שער עצר, או ריק אם כולם עברו. בלעדיו
         * "לא הוצע" הוא תיבה שחורה.
         */
        val score: Int = -1,
        val available: Int = -1,
        val blocked: String = "",
        /**
         * ⚠️ **מרחק מהמקום הרגיל, והתאמה למקום שנפל בו.** שני אלה נמדדים
         * ברגע ואינם ניתנים לשחזור מהרשומה — אם לא נשמרו כאן, הם אבודים
         * לצמיתות. כל שאר האותות ניתנים לחישוב מחדש בדיעבד.
         */
        val placeMeters: Int = -1,
        val knownPlace: Int = -1,
        /**
         * האם המשתמש דיווח משהו בסמוך לרשומה הזו — ✕, נפילה או מצב-רוח.
         *
         * ⚠️ **זה מה שהופך את הנתונים לניתנים לניתוח.** בלעדיו, השאלה
         * "איך נראה הציון לפני דיווח" דורשת להצליב שני מקורות לפי חותמות
         * זמן; איתו היא שאילתה על עמודה אחת.
         */
        val nearReport: String = ""
    )

    fun append(context: Context, r: Record) {
        val line = listOf(
            fmt.format(Date(r.timestampMs)),
            r.hourOfDay,
            r.bpm,
            r.samples,
            r.firstSampleMs,
            r.steps,
            r.stillMs,
            r.battery,
            r.noContact,
            r.gravityX,
            r.gravityY,
            r.gravityZ,
            r.motion,
            r.bpmMin,
            r.bpmMax,
            r.bpmTrend,
            r.score,
            r.available,
            r.blocked,
            r.placeMeters,
            r.knownPlace,
            r.nearReport
        ).joinToString(",")

        val file = File(context.filesDir, FILE_NAME)
        try {
            FileWriter(file, true).use { it.write(line + "\n") }
            bumpCount(context, 1)
        } catch (e: Exception) {
            // כתיבה בודדת שנכשלת אינה קריטית — הרשומה הבאה תגיע בעוד דקות.
            EventLog.log(context, "INFO", "sample_store_write_failed;${e.javaClass.simpleName}")
        }
        pruneIfNeeded(context, file)
    }

    /**
     * מספר הרשומות, מוחזק במונה ולא נספר מהקובץ.
     *
     * ⚠️ **הסיבה חשובה:** ספירת שורות מהקובץ קוראת אותו כולו לזיכרון, וזה
     * קרה בגרסה הראשונה **בכל פרץ, על החוט הראשי** — כלומר כל שתי דקות,
     * ועל קובץ שיגיע ל-20,000 שורות. המונה הופך את זה לקריאה אחת של מספר.
     */
    fun count(context: Context): Int =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getInt(KEY_COUNT, 0)

    private fun bumpCount(context: Context, delta: Int) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putInt(KEY_COUNT, (prefs.getInt(KEY_COUNT, 0) + delta).coerceAtLeast(0)).apply()
    }

    private fun setCount(context: Context, value: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putInt(KEY_COUNT, value).apply()
    }

    /**
     * ⚠️ הגריסה כן קוראת את הקובץ כולו, ולכן היא נשלטת במונה: היא רצה רק
     * כשחרגנו מעל [MAX_RECORDS] בשולי [PRUNE_SLACK]. בקצב של 700 רשומות
     * ביום זה בערך פעם ביומיים, ולא בכל פרץ.
     */
    private fun pruneIfNeeded(context: Context, file: File) {
        if (count(context) <= MAX_RECORDS + PRUNE_SLACK) return
        try {
            if (!file.exists()) return
            val lines = file.readLines()
            val kept = lines.takeLast(MAX_RECORDS)
            file.writeText(kept.joinToString("\n") + "\n")
            setCount(context, kept.size)
            EventLog.log(
                context, "INFO",
                "sample_store_pruned;was=${lines.size};now=${kept.size}"
            )
        } catch (e: Exception) {
            EventLog.log(context, "INFO", "sample_store_prune_failed;${e.javaClass.simpleName}")
        }
    }

    /**
     * הרשומות האחרונות, הישנה קודם.
     *
     * ⚠️ **זה גם החוצץ המתגלגל.** התכנון דיבר על חוצץ נפרד בזיכרון של
     * 30-60 דקות, אבל אחרי שהרשומות נשמרות בקובץ, "חצי השעה שקדמה
     * לדיווח" היא פשוט הזנב של הקובץ. חוצץ נפרד היה מצב כפול שיכול
     * להיפרד מהאמת, ובלי שום יתרון.
     */
    fun recent(context: Context, count: Int): List<Record> {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return emptyList()
        return try {
            file.readLines().takeLast(count).mapNotNull { parse(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** רשומות מתוך חלון-זמן אחורה, לניתוח בדיעבד אחרי דיווח נפילה. */
    fun since(context: Context, sinceMs: Long): List<Record> {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return emptyList()
        return try {
            file.readLines().mapNotNull { parse(it) }.filter { it.timestampMs >= sinceMs }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * ⚠️ **סובלני לרשומות ישנות בנות 8 שדות.** `noContact` נוסף אחרי
     * שכבר נאספו רשומות, ופענוח נוקשה היה מוחק את כל ההיסטוריה שנאספה עד
     * כה. רשומה ישנה מקבלת 0 — כלומר תיחשב "עם מגע", וזו ההנחה שהייתה
     * בתוקף כשהיא נכתבה ממילא.
     */
    /**
     * שורת כותרות, כדי שהקובץ ייפתח כטבלה קריאה ולא כמספרים ערומים.
     *
     * ⚠️ **חייבת להישאר מסונכרנת עם [append].** עמודה שנוספת שם ולא כאן
     * מזיזה את כל הכותרות שאחריה, וניתוח שנעשה על טבלה מוזזת ייראה תקין
     * לחלוטין ויהיה שגוי לגמרי.
     */
    const val CSV_HEADER =
        "time,hour,bpm,samples,first_ms,steps,still_ms,battery,no_contact," +
            "grav_x,grav_y,grav_z,motion,bpm_min,bpm_max,bpm_trend," +
            "score,available,blocked,place_m,known_place,near_report"

    /**
     * כל הרשומות כטקסט אחד, עם כותרות.
     *
     * ⚠️ **זו הדרך היחידה להוציא את מלוא הדאטא מהשעון.** העלאת הלוג
     * מוגבלת ל-500 שורות ול-60KB, כלומר היא מראה שעות בודדות — ואילו
     * הניתוח שמצב-הצל קיים בשבילו דורש שבועות. הקובץ הזה הוא הדאטא
     * המלא, וגם התחליף להעלאה הציבורית כשהיא תוסר.
     */
    fun exportAll(context: Context): String {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return CSV_HEADER + "\n"
        return try {
            // ⚠️ **הממצאים בראש הקובץ, לפני הכותרות.** קובץ של אלפי שורות
            // נפתח בהתחלה, ומה ששבור צריך להיות הדבר הראשון שנקרא. שורות
            // `#` אינן מפריעות לשום כלי ניתוח.
            DataSanity.exportHeader(since(context, 0L)) + CSV_HEADER + "\n" + file.readText()
        } catch (e: Exception) {
            CSV_HEADER + "\n"
        }
    }

    /** מוחק את כל הרשומות ומאפס את המונה. ראו [Baseline.reset]. */
    fun clear(context: Context) {
        runCatching { File(context.filesDir, FILE_NAME).delete() }
        setCount(context, 0)
        EventLog.log(context, "INFO", "sample_store_cleared")
    }

    private fun parse(line: String): Record? {
        val p = line.split(",")
        if (p.size < 8) return null
        return try {
            Record(
                timestampMs = fmt.parse(p[0])?.time ?: return null,
                hourOfDay = p[1].toInt(),
                bpm = p[2].toInt(),
                samples = p[3].toInt(),
                firstSampleMs = p[4].toLong(),
                steps = p[5].toInt(),
                stillMs = p[6].toLong(),
                battery = p[7].toInt(),
                noContact = if (p.size > 8) (p[8].toIntOrNull() ?: 0) else 0,
                gravityX = if (p.size > 9) (p[9].toIntOrNull() ?: 0) else 0,
                gravityY = if (p.size > 10) (p[10].toIntOrNull() ?: 0) else 0,
                gravityZ = if (p.size > 11) (p[11].toIntOrNull() ?: 0) else 0,
                motion = if (p.size > 12) (p[12].toIntOrNull() ?: -1) else -1,
                bpmMin = if (p.size > 13) (p[13].toIntOrNull() ?: -1) else -1,
                bpmMax = if (p.size > 14) (p[14].toIntOrNull() ?: -1) else -1,
                bpmTrend = if (p.size > 15) (p[15].toIntOrNull() ?: 0) else 0,
                score = if (p.size > 16) (p[16].toIntOrNull() ?: -1) else -1,
                available = if (p.size > 17) (p[17].toIntOrNull() ?: -1) else -1,
                blocked = if (p.size > 18) p[18] else "",
                placeMeters = if (p.size > 19) (p[19].toIntOrNull() ?: -1) else -1,
                knownPlace = if (p.size > 20) (p[20].toIntOrNull() ?: -1) else -1,
                nearReport = if (p.size > 21) p[21] else ""
            )
        } catch (e: Exception) {
            null
        }
    }
}
