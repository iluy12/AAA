package com.iluy.imutest

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * אחסון ייעודי ללוח ההתגברויות והנפילות (חלק 5 במסמך העיצוב-מחדש).
 *
 * **למה לא לקרוא את EventLog:** הלוג הוא קובץ טקסט שגדל לאלפי שורות.
 * פירוק שלו בכל פתיחת מסך הוא איטי ושביר על חומרה חלשה. כאן מבנה
 * מסודר: תאריך → מונה התגברויות + קטגוריית נפילה.
 *
 * **גבול היום: חצות מקומית** (לא חלון מתגלגל) — תואם למה שהלוח מציג
 * ויזואלית וקל להסביר למשתמש.
 *
 * זהו גם המקור לספירת "כמה התגברויות היום", שעליה תתבסס לוגיקת
 * ההסלמה מבוססת-הסף-האישי.
 */
object CalendarStore {

    private const val PREFS_NAME = "iluy_calendar"
    private val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    /** שעת הדיווח, כדי שאפשר יהיה להבחין בין שתי נפילות באותו יום. */
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.US)

    /** יום בלוח. אותו יום יכול להכיל גם התגברויות וגם נפילה. */
    data class Day(
        val date: String,
        val overcomings: Int,
        val fallCategory: String?
    ) {
        val hasFall: Boolean get() = fallCategory != null
        val isEmpty: Boolean get() = overcomings == 0 && fallCategory == null
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun todayKey(): String = dayFormat.format(Date())

    // ---------- כתיבה ----------

    /** מחזיר את המונה המעודכן של היום. */
    fun recordOvercoming(context: Context): Int {
        val key = todayKey()
        val next = overcomingsOn(context, key) + 1
        prefs(context).edit().putInt(overcomingKey(key), next).apply()
        return next
    }

    /**
     * ⚠️ **הלוח שמר קטגוריה אחת ליום, וזה מחק דיווחים.**
     *
     * ב-3.8 נבו דיווח "מחשבה" ב-16:15 ו"ברית" ב-16:27, ובלוח ראה **רק
     * ברית** — כי הדיווח השני דרס את הראשון. הוא הסיק מזה שהמחווה שמרה
     * ברית בטעות, וזו הסקה סבירה לגמרי מהמסך שהוא ראה.
     *
     * שני אירועים ביום הם **הנתון החשוב ביותר** שיש ליום כזה, ולא משהו
     * שאפשר לקפל למחרוזת אחת.
     *
     * ⚠️ הפורמט שומר גם `cancelled` — נבו ביקש שיהיה אפשר לבטל דיווח
     * שגוי, ומחיקה הייתה מסתירה שהיה כאן דיווח בכלל.
     */
    fun recordFall(context: Context, category: String) {
        val key = todayKey()
        val entries = fallsOn(context, key) + Entry(timeFormat.format(Date()), category, false)
        writeEntries(context, key, entries)
    }

    data class Entry(val time: String, val category: String, val cancelled: Boolean)

    fun fallsOn(context: Context, dateKey: String): List<Entry> {
        val raw = prefs(context).getString(fallKey(dateKey), null) ?: return emptyList()
        // ⚠️ תאימות לאחור: פורמט ישן היה קטגוריה בודדת בלי מפרידים.
        if (!raw.contains('|')) return listOf(Entry("", raw, false))
        return raw.split(";").mapNotNull {
            val p = it.split("|")
            if (p.size < 3) null else Entry(p[0], p[1], p[2] == "1")
        }
    }

    private fun writeEntries(context: Context, dateKey: String, entries: List<Entry>) {
        prefs(context).edit().putString(
            fallKey(dateKey),
            entries.joinToString(";") { "${it.time}|${it.category}|${if (it.cancelled) 1 else 0}" }
        ).apply()
    }

    /**
     * מבטל או מחזיר דיווח נפילה.
     *
     * ⚠️ **מסומן ולא נמחק.** דיווח מבוטל אינו נספר בשום חישוב, אבל הוא
     * נשאר גלוי — כדי שנבו יוכל לראות בעצמו כמה פעמים המחווה טעתה, וכדי
     * שביטול בטעות יהיה הפיך.
     */
    fun setFallCancelled(context: Context, dateKey: String, index: Int, cancelled: Boolean) {
        val entries = fallsOn(context, dateKey).toMutableList()
        if (index !in entries.indices) return
        entries[index] = entries[index].copy(cancelled = cancelled)
        writeEntries(context, dateKey, entries)
        EventLog.log(
            context, "FALL",
            "cancel_toggled;date=$dateKey;i=$index;cat=${entries[index].category};on=$cancelled"
        )
    }

    // ---------- קריאה ----------

    fun overcomingsToday(context: Context): Int = overcomingsOn(context, todayKey())

    fun overcomingsOn(context: Context, dateKey: String): Int =
        prefs(context).getInt(overcomingKey(dateKey), 0)

    /**
     * סיכום היום לצורך חישובים: **החמורה מבין הנפילות שלא בוטלו**, או
     * null אם אין כאלה.
     *
     * ⚠️ החמורה ולא האחרונה. יום שהיו בו מחשבה וברית הוא יום של ברית.
     */
    fun fallOn(context: Context, dateKey: String): String? =
        fallsOn(context, dateKey)
            .filter { !it.cancelled }
            .maxByOrNull { FallSeverity.fromLabel(it.category).weight }
            ?.category

    /**
     * הימים האחרונים, מהיום אחורה. נקרא לפי חשבון-תאריכים ולא לפי
     * אינדקס שמור — פחות מצבים שאפשר לקלקל, והעלות זניחה (קריאה אחת
     * לכל יום).
     */
    fun recentDays(context: Context, count: Int): List<Day> {
        val calendar = Calendar.getInstance()
        val days = mutableListOf<Day>()
        for (i in 0 until count) {
            val key = dayFormat.format(calendar.time)
            days.add(
                Day(
                    date = key,
                    overcomings = overcomingsOn(context, key),
                    fallCategory = fallOn(context, key)
                )
            )
            calendar.add(Calendar.DAY_OF_YEAR, -1)
        }
        return days
    }

    /**
     * ממוצע התגברויות ליום, על ימים שהיה בהם *משהו* בלבד. ימים ריקים
     * הם כמעט תמיד ימים שהשעון לא נלבש, וספירתם הייתה מדללת את הממוצע
     * ומעוותת אותו כלפי מטה.
     */
    fun averageActiveDayOvercomings(context: Context, windowDays: Int = 30): Double {
        val active = recentDays(context, windowDays).filter { !it.isEmpty }
        if (active.isEmpty()) return 0.0
        return active.sumOf { it.overcomings }.toDouble() / active.size
    }

    private fun overcomingKey(dateKey: String) = "day_${dateKey}_overcomings"
    private fun fallKey(dateKey: String) = "day_${dateKey}_fall"
}
