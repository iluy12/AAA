package com.iluy.imutest

import android.app.Application

/**
 * רישום קריסות ללוג המקומי.
 *
 * נבנה אחרי תצפית שלפעמים לחיצה על הכפתור הצדדי מציגה את הלאנצ'ר של
 * היצרן במקום את מסך-השעון שלנו. ההסבר הסביר: כשאפליקציית מסך-הבית
 * נכשלת לעלות, אנדרואיד נופל חזרה למועמד-בית אחר.
 *
 * בלי זה אין שום דרך לדעת אם זה קורה — logcat לא נגיש על המכשיר הזה,
 * וקריסה לא משאירה עקבות בלוג שלנו. עכשיו היא תשאיר.
 *
 * המטפל הקודם נשמר ומופעל בסוף, כדי שהמערכת עדיין תסיים את התהליך
 * כרגיל. לא מנסים "להציל" קריסה — רק לתעד אותה.
 */
class IluyApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val where = error.stackTrace.firstOrNull()
                    ?.let { "${it.className}.${it.methodName}:${it.lineNumber}" }
                    ?: "unknown"
                EventLog.log(
                    this, "ERROR",
                    "crash;thread=${thread.name};type=${error.javaClass.simpleName};" +
                        "message=${error.message?.take(120)};at=$where"
                )
            } catch (ignored: Throwable) {
                // רישום הקריסה לא יכול להיות מה שמפיל אותנו
            }
            previous?.uncaughtException(thread, error)
        }
    }
}
