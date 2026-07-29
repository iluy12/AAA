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
     * כמה זמן דגימה נחשבת עדיין רלוונטית. מעבר לזה [Smoother.current]
     * מחזיר null במקום את הערך האחרון שנצבר.
     *
     * ⚠️ הסף הזה נולד ממדידה, לא מהערכה. בלוג של 2026-07-29 זרם הדופק
     * נפסק לגמרי ב-~18:32 ולא חזר במשך שעתיים — ובכל 11 שורות הסיכום
     * שאחריו `now_bpm` המשיך לדווח 82 בביטחון מלא, כי החציון לא ידע
     * מה גיל הנתון שבידיו. מנוע-ציון שהיה קורא אותו היה משווה לבסיס
     * ערך בן שעתיים ולא היה לו שום סימן שמשהו לא בסדר.
     *
     * שתי דקות הן גם הרבה מעל המרווח הגרוע ביותר שנמדד בין דגימות
     * (1079ms), כלומר הן לא יפסלו נתון תקין גם כשהקצב יורד ל-1Hz.
     */
    const val MAX_SAMPLE_AGE_MS = 120_000L

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
    class Smoother(
        private val windowSize: Int = 9,
        private val maxAgeMs: Long = MAX_SAMPLE_AGE_MS
    ) {
        private val window = IntArray(windowSize)
        private var count = 0
        private var next = 0
        private var lastAddedMs = 0L

        fun add(bpm: Int): Int {
            window[next] = bpm
            next = (next + 1) % windowSize
            if (count < windowSize) count++
            lastAddedMs = android.os.SystemClock.elapsedRealtime()
            return median()
        }

        /**
         * null עד שנצברה דגימה אחת, **וגם** אחרי שהאחרונה התיישנה.
         *
         * ⚠️ המדידה היא ב-`elapsedRealtime` ולא ב-`uptimeMillis` דווקא
         * מפני ש-`uptimeMillis` קופא בשינה עמוקה — ובדיוק בשינה עמוקה
         * הנתון מתיישן. עם השעון הלא-נכון, דגימה בת שעתיים הייתה
         * נראית בת כמה שניות, וה-TTL היה חסר-ערך בדיוק במקרה שבשבילו
         * נבנה.
         */
        fun current(): Int? {
            if (count == 0) return null
            if (ageMs() > maxAgeMs) return null
            return median()
        }

        /** גיל הדגימה האחרונה במילישניות, או -1 אם אין דגימה כלל. */
        fun ageMs(): Long =
            if (count == 0) -1L else android.os.SystemClock.elapsedRealtime() - lastAddedMs

        fun reset() {
            count = 0
            next = 0
            lastAddedMs = 0L
        }

        private fun median(): Int {
            val sorted = window.copyOfRange(0, count).sortedArray()
            return sorted[count / 2]
        }
    }
}
