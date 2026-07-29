package com.iluy.imutest

/**
 * פענוח הדופק מהחיישן של S10PRO.
 *
 * ## למה צריך פענוח בכלל
 *
 * `TYPE_HEART_RATE` על המכשיר הזה מחזיר ערכים כמו `2.3e20` או `9.9e20`,
 * שנראים כמו זבל ולכן כמעט פסלנו את החיישן. הם לא זבל: הדרייבר כותב
 * **מבנה בינארי** לתוך השדה שמוצהר כ-`float`, ואנדרואיד מפרש את אותם
 * ביטים כמספר עשרוני ענק. הנתון עצמו שלם — רק הפרשנות שגויה.
 *
 * **הדופק יושב בבייט השני של תבנית-הביטים.**
 *
 * ## איך אומת
 *
 * חמש קריאות מאפליקציית היצרן שימשו כאמת-מידה (70, 76, 85, 86, 117 BPM),
 * והפענוח התאים לכולן בסטייה של 0-2 פעימות — כולל שתי התאמות מדויקות.
 * באימות עיוור על 225 דגימות רצופות: 99.6% נפלו בטווח אנושי, החציון היה
 * 86, והעקומה טיפסה ל-115 בדיוק בזמן שכיבות-שמיכה.
 *
 * ⚠️ זה **הנדסה-לאחור של דרייבר ספציפי**, לא חוזה API. אם מדגם עתידי
 * יחזיר ערכים לא-סבירים, החשד הראשון הוא שהיצרן שינה את המבנה — ולכן
 * [decodeBpm] מסנן לטווח סביר ומחזיר null במקום מספר שקרי.
 */
object HeartRate {

    /**
     * גבולות שמרניים בכוונה. המטרה אינה אבחון רפואי אלא לתפוס מצב שבו
     * המבנה השתנה, ואז עדיף "אין נתון" על פני נתון שגוי שיזין את הזיהוי.
     */
    const val MIN_PLAUSIBLE_BPM = 30
    const val MAX_PLAUSIBLE_BPM = 220

    /**
     * @return הדופק ב-BPM, או null אם אין מגע עם העור או שהערך לא סביר.
     *
     * ערך גולמי 0 הוא **לא** דופק אפס אלא "החיישן לא במגע עם העור" —
     * וזה שימושי בפני עצמו, כי הוא נותן זיהוי-לבישה בחינם.
     */
    fun decodeBpm(raw: Float): Int? {
        if (raw == 0f) return null
        val bpm = (java.lang.Float.floatToRawIntBits(raw) ushr 16) and 0xFF
        return if (bpm in MIN_PLAUSIBLE_BPM..MAX_PLAUSIBLE_BPM) bpm else null
    }

    /** האם הערך הגולמי מעיד שהשעון על היד. */
    fun isWorn(raw: Float): Boolean = decodeBpm(raw) != null

    /**
     * חציון נע על חלון קצר.
     *
     * דגימות בודדות קופצות (73, 75, 70, 74, 76 בתוך דקה אחת) — זו תנודה
     * אמיתית של פעימה-לפעימה, לא רעש, אבל היא מקשה על השוואה לסף. חציון
     * עדיף על ממוצע כי דגימה חריגה בודדת לא מזיזה אותו.
     *
     * החלון קטן בכוונה: בקצב של ~3 דגימות בשנייה, 9 דגימות הן כשלוש
     * שניות — מספיק להחליק רעש בלי לטשטש עלייה אמיתית.
     */
    class Smoother(private val windowSize: Int = 9) {
        private val window = IntArray(windowSize)
        private var count = 0
        private var next = 0

        fun add(bpm: Int): Int {
            window[next] = bpm
            next = (next + 1) % windowSize
            if (count < windowSize) count++
            return median()
        }

        /** null עד שנצברה דגימה אחת לפחות. */
        fun current(): Int? = if (count == 0) null else median()

        fun reset() {
            count = 0
            next = 0
        }

        private fun median(): Int {
            val sorted = window.copyOfRange(0, count).sortedArray()
            return sorted[count / 2]
        }
    }
}
