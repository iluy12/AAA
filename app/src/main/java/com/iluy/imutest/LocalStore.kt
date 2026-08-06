package com.iluy.imutest

import android.content.Context
import android.content.SharedPreferences

/**
 * כל האחסון של v1 מקומי בלבד — SharedPreferences על השעון עצמו. אין שרת,
 * אין רשת, אין דאטה שיוצא מהמכשיר (ראו סעיף 12 במסמך המסירה: "לא צריך
 * שרת! רק אחסון מקומי על השעון").
 *
 * ריבוי-בחירה (checkboxes) נשמר כמחרוזת מופרדת בפסיקים — פשוט מספיק
 * למשתמש בודד, בלי צורך בספריית JSON חיצונית.
 */
object LocalStore {

    private const val PREFS_NAME = "iluy_v1_store"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ---------- שאלון ----------

    fun isQuestionnaireDone(context: Context): Boolean =
        prefs(context).getBoolean(KEY_Q_DONE, false)

    fun setQuestionnaireDone(context: Context, done: Boolean) {
        prefs(context).edit().putBoolean(KEY_Q_DONE, done).apply()
    }

    /**
     * מוחק הכל: תשובות השאלון, מצב ההיכון, ההקלטה האישית — ובנפרד גם
     * לוח ההתגברויות. נועד לבדיקות, כדי להתחיל מאפס בלי להסיר ולהתקין
     * מחדש (מה שממילא לא תמיד אפשרי כשעילוי היא מסך-הבית).
     */
    fun resetAll(context: Context) {
        prefs(context).edit().clear().apply()
        context.getSharedPreferences("iluy_calendar", Context.MODE_PRIVATE)
            .edit().clear().apply()
        EventLog.log(context, "INFO", "all_data_reset")
    }

    fun saveMultiChoice(context: Context, key: String, values: List<String>) {
        prefs(context).edit().putString(key, values.joinToString(",")).apply()
    }

    fun getMultiChoice(context: Context, key: String): List<String> {
        val raw = prefs(context).getString(key, "") ?: ""
        return if (raw.isBlank()) emptyList() else raw.split(",")
    }

    fun saveSingleChoice(context: Context, key: String, value: String) {
        prefs(context).edit().putString(key, value).apply()
    }

    fun getSingleChoice(context: Context, key: String): String =
        prefs(context).getString(key, "") ?: ""

    fun saveBoolean(context: Context, key: String, value: Boolean) {
        prefs(context).edit().putBoolean(key, value).apply()
    }

    fun getBoolean(context: Context, key: String, default: Boolean = false): Boolean =
        prefs(context).getBoolean(key, default)

    // ---------- מצב הקשה / "היכון" לשעה ----------

    /** אם עדיין בתוקף (עתיד) — השעון כבר ב"היכון" מהקשה קודמת באותה שעה. */
    fun getTapStandbyUntil(context: Context): Long =
        prefs(context).getLong(KEY_TAP_STANDBY_UNTIL, 0L)

    fun setTapStandbyUntil(context: Context, epochMillis: Long) {
        prefs(context).edit().putLong(KEY_TAP_STANDBY_UNTIL, epochMillis).apply()
    }

    // ---------- קירור אחרי "הכל טוב" ----------

    fun getCooldownUntil(context: Context): Long =
        prefs(context).getLong(KEY_COOLDOWN_UNTIL, 0L)

    fun setCooldownUntil(context: Context, epochMillis: Long) {
        prefs(context).edit().putLong(KEY_COOLDOWN_UNTIL, epochMillis).apply()
    }

    // ---------- סף אישי להסלמה (שאלה 10) ----------

    /**
     * כמה פעמים המשתמש מצליח לסרב לפני שקורה משהו, לפי דיווחו העצמי.
     * זה הופך את ההסלמה ממספר שהומצא ("שנייה בשעה") לסף שיש לו משמעות
     * אצל האדם הזה.
     *
     * **רצפה של 3** גם למי שדיווח פחות: מי שענה "פעם אחת" היה מקבל מסך
     * כבר בהתגברות הראשונה, וזה מציף במקום לעזור.
     */
    /**
     * ⚠️ **נקרא כמספר, וקודם לא.**
     *
     * השאלה הייתה פעם בחירה מטקסט ("פעם אחת", "2-3", "4-5"), והפרסור
     * כאן התאים לזה. מאז היא הפכה לבורר מספרים שכותב `"5"` — והפרסור
     * הישן נשאר:
     *
     * | נענה | נקרא |
     * |---|---|
     * | 4 | 5 |
     * | 5 | 3 |
     * | 8 | 3 |
     *
     * כלומר **המספר שהמשתמש הזין כמעט לא השפיע**, וזה הנתון שקובע מתי
     * המערכת מציעה מלווה. וכמו כל השאר כאן — הכישלון היה שקט: `else`
     * החזיר ערך סביר לגמרי.
     */
    fun getPersonalThreshold(context: Context): Int {
        val reported = getSingleChoice(context, KEY_Q10_REFUSALS).trim().toIntOrNull() ?: 0
        return maxOf(reported, MIN_PERSONAL_THRESHOLD)
    }

    const val MIN_PERSONAL_THRESHOLD = 3

    // ---------- כיול-אישי לסף-ההקשה (תרגול בשאלון) ----------

    /** null = אין כיול-אישי עדיין, המערכת נופלת חזרה ל-DebugConfig.TAP_MAGNITUDE_THRESHOLD. */
    fun getPersonalTapThreshold(context: Context): Double? {
        if (!prefs(context).contains(KEY_PERSONAL_TAP_THRESHOLD)) return null
        return prefs(context).getFloat(KEY_PERSONAL_TAP_THRESHOLD, 0f).toDouble()
    }

    fun setPersonalTapThreshold(context: Context, value: Double) {
        prefs(context).edit().putFloat(KEY_PERSONAL_TAP_THRESHOLD, value.toFloat()).apply()
    }

    /**
     * וקטור-כובד-ייחוס אישי לשער-התנוחה: התנוחה שבה המשתמש מחזיק את
     * פרק היד כשהוא מדווח. null = לא כויל עדיין, ואז השער כבוי לגמרי
     * וזיהוי-ההקשה מתנהג כמו קודם (נפילה-חזרה בטוחה, לא שבורה).
     *
     * נשמר כשלושה floats נפרדים — SharedPreferences לא מחזיק מערכים,
     * ואין טעם לגרור ספריית JSON בשביל שלושה מספרים.
     */
    fun getReferenceGravity(context: Context): FloatArray? {
        val p = prefs(context)
        if (!p.contains(KEY_REFERENCE_GRAVITY_X)) return null
        return floatArrayOf(
            p.getFloat(KEY_REFERENCE_GRAVITY_X, 0f),
            p.getFloat(KEY_REFERENCE_GRAVITY_Y, 0f),
            p.getFloat(KEY_REFERENCE_GRAVITY_Z, 0f)
        )
    }

    fun setReferenceGravity(context: Context, gravity: FloatArray) {
        if (gravity.size < 3) return
        prefs(context).edit()
            .putFloat(KEY_REFERENCE_GRAVITY_X, gravity[0])
            .putFloat(KEY_REFERENCE_GRAVITY_Y, gravity[1])
            .putFloat(KEY_REFERENCE_GRAVITY_Z, gravity[2])
            .apply()
    }

    // ---------- הקלטה אישית (שלב 9 בשאלון) ----------

    fun setRecordingPath(context: Context, path: String) {
        prefs(context).edit().putString(KEY_RECORDING_PATH, path).apply()
    }

    fun getRecordingPath(context: Context): String? =
        prefs(context).getString(KEY_RECORDING_PATH, null)

    // ---------- מפתחות שאלון ----------

    const val KEY_Q1_TIMES = "q1_times"
    const val KEY_Q2_FREQUENCY = "q2_frequency"
    const val KEY_Q3_PLACE = "q3_place"
    const val KEY_Q3_POSITION = "q3_position"
    const val KEY_Q5_HELPS = "q5_helps"
    const val KEY_Q7_CONSENT_CALL = "q7_consent_call"
    const val KEY_Q8_CONSENT_MESSAGE = "q8_consent_message"
    const val KEY_Q10_REFUSALS = "q10_refusals_before_fall"

    /**
     * הרצף הנקי הארוך ביותר שהמשתמש זוכר, בימים.
     *
     * ⚠️ **זה לא עוד נתון — זו נקודת הייחוס האישית שלו.** 14 יום נקיים הם
     * ניצחון למי שהשיא שלו 10, ושבוע רגיל למי שהשיא שלו 60. בלי המספר
     * הזה המערכת לא יודעת אם הוא בשיא או בשגרה, ולכן גם המסרים יוצאים
     * גנריים — ומשפט גנרי נשחק תוך שבוע.
     */
    const val KEY_Q11_LONGEST_STREAK = "q11_longest_streak_days"

    /**
     * הטווח שסימן כעיקרי, מתוך אלה שסימן ב-[KEY_Q1_TIMES].
     *
     * ⚠️ **בלעדיו יש כיסוי בלי סדר עדיפויות.** תקציב החלונות החמים הוא
     * 3-4 ביום, ומי שסימן חמישה טווחים ישרוף אותו לפני שהגיע לטווח
     * שבאמת מסוכן אצלו. זה הנתון שקובע מה משוריין.
     */
    const val KEY_Q1_PRIMARY_HOUR = "q1_primary_hour"

    /** כמה זמן שקט קודם לאירוע. ממופה ישירות ל-`stillMs`. */
    const val KEY_Q12_QUIET_BEFORE = "q12_quiet_before"

    /**
     * הרגלים שמעלים דופק ורלוונטיים לאדם הזה.
     *
     * ⚠️ **לא "מה מעלה לך את הדופק".** המטרה היחידה היא לדעת אילו
     * כפתורים להציג במסך הווידוא — כלומר **לקצר** אותו. רשימה של 12
     * אפשרויות ברגע מתוח פירושה שהוא לא עונה, ומספר התוויות הוא
     * הצוואר האמיתי.
     */
    const val KEY_Q6_HABITS = "q6_habits"

    private const val KEY_Q_DONE = "questionnaire_done"
    private const val KEY_TAP_STANDBY_UNTIL = "tap_standby_until"
    private const val KEY_COOLDOWN_UNTIL = "cooldown_until"
    private const val KEY_RECORDING_PATH = "recording_path"
    private const val KEY_PERSONAL_TAP_THRESHOLD = "personal_tap_threshold"
    private const val KEY_REFERENCE_GRAVITY_X = "reference_gravity_x"
    private const val KEY_REFERENCE_GRAVITY_Y = "reference_gravity_y"
    private const val KEY_REFERENCE_GRAVITY_Z = "reference_gravity_z"
}
