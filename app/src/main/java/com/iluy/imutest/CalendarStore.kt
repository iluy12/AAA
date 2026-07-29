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

    fun recordFall(context: Context, category: String) {
        val key = todayKey()
        prefs(context).edit().putString(fallKey(key), category).apply()
    }

    // ---------- קריאה ----------

    fun overcomingsToday(context: Context): Int = overcomingsOn(context, todayKey())

    fun overcomingsOn(context: Context, dateKey: String): Int =
        prefs(context).getInt(overcomingKey(dateKey), 0)

    fun fallOn(context: Context, dateKey: String): String? =
        prefs(context).getString(fallKey(dateKey), null)

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
