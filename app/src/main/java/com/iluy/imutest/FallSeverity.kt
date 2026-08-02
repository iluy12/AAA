package com.iluy.imutest

import android.content.Context

/**
 * חומרת הנפילה, ומה שנגזר ממנה.
 *
 * ## הסולם, כפי שנבו הגדיר אותו
 *
 * | קטגוריה | חומרה | למה |
 * |---|---|---|
 * | **ברית** | רצינית | הנפילה המלאה |
 * | **עיניים** | ⚠️ **מסוכנת** | צפויים ניסיונות קשים ביומיים הקרובים |
 * | **מחשבה** | הקלה מכולן | |
 *
 * ⚠️ **"עיניים" אינה אירוע רגעי אלא מצב שנמשך.** זו ההבחנה שמייחדת אותה:
 * היא לא רק מסמנת מה קרה, אלא **חוזה מה יקרה** — ולכן היא מעלה את הסיכון
 * במשך יומיים ולא רק ברגע הדיווח. כל השאר בסולם מתאר עבר; זו מתארת עתיד.
 *
 * ## דיווח בלי סיווג
 *
 * ⚠️ **נכנס כ"ברית" — החמורה ביותר.** נבו קבע כך, וההיגיון נכון: כשלא
 * יודעים, מוטב לטעות לצד המחמיר.
 *
 * **אבל זה מעוות את הנתונים, ולכן נרשם `assumed`.** המסלול חסר-החיכוך —
 * לחיצה בלי גרירה — הוא גם המסלול שברירת המחדל שלו היא החמורה, כך שבלי
 * הסימון הזה כל דיווח עצל היה נראה בניתוח כנפילה חמורה. הסימון מאפשר
 * להפריד בין "אמר ברית" לבין "לא אמר כלום".
 */
enum class FallSeverity(val label: String, val weight: Int) {
    THOUGHT("מחשבה", 1),
    EYES("עיניים", 2),
    SEED("ברית", 3),

    /**
     * קרי לילה.
     *
     * ⚠️ **לא נפילה קלאסית, וכן משבר.** נבו: *"זה לא ברצון, אבל זה כן
     * משבר"* — ולכן היא במקום נפרד בסולם ולא מדורגת מולו.
     *
     * שתי תכונות שאין לאף אחת מהאחרות:
     *
     * 1. **היא רומזת אחורה.** הגוף הגיע לזה כנראה אחרי פגימה בעיניים או
     *    במחשבה, ולכן היא אות על **הימים שקדמו** ולא על הלילה עצמו.
     * 2. ⚠️ **חותמת הדיווח מנותקת מהאירוע לחלוטין.** הוא מדווח בבוקר על
     *    משהו שקרה בשינה — ולכן **ניתוח חצי השעה שקדמה לדיווח חסר
     *    משמעות כאן**, בניגוד לכל השאר. סריקת החלון מדלגת עליה.
     *
     * `weight = 0` כי הסולם מודד **בחירה**, וכאן לא הייתה בחירה. זה לא
     * אומר שהיא לא חשובה — היא פשוט לא נמדדת באותו סרגל.
     */
    NOCTURNAL("קרי לילה", 0);

    /** האם הדיווח מתייחס לרגע שקרוב לזמן הדיווח. */
    val reportIsNearEvent: Boolean get() = this != NOCTURNAL

    companion object {
        /** ברירת המחדל כשלא סווג. ראו הערת-המחלקה. */
        val DEFAULT = SEED

        fun fromLabel(label: String?): FallSeverity =
            values().firstOrNull { it.label == label } ?: DEFAULT
    }
}

/**
 * המצב המתמשך שנוצר אחרי נפילה מסוג "עיניים".
 *
 * ⚠️ נבו: *"בעיניים זו נפילה מסוכנת — צפויים לו ניסיונות קשים ביומיים
 * הקרובים."* כלומר הדיווח אינו רק תיעוד, הוא **תחזית** — והמערכת צריכה
 * להיות ערנית יותר במשך היומיים האלה גם בלי שום אות חדש מהחיישנים.
 */
object FallAftermath {

    private const val PREFS_NAME = "iluy_aftermath"
    private const val WINDOW_MS = 2 * 24 * 60 * 60 * 1000L

    fun record(context: Context, severity: FallSeverity) {
        if (severity != FallSeverity.EYES) return
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putLong("eyes_until", System.currentTimeMillis() + WINDOW_MS).apply()
        EventLog.log(context, "FALL", "aftermath_started;hours=48")
    }

    /** האם אנחנו בתוך היומיים שאחרי נפילת "עיניים". */
    fun inHeightenedWindow(context: Context): Boolean {
        val until = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong("eyes_until", 0L)
        return until > System.currentTimeMillis()
    }
}
