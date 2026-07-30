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
        val motion: Int = -1
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
            r.motion
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
                motion = if (p.size > 12) (p[12].toIntOrNull() ?: -1) else -1
            )
        } catch (e: Exception) {
            null
        }
    }
}
