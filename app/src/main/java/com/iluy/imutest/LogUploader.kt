package com.iluy.imutest

import android.app.Activity
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * העלאת הלוג לשירות-הדבקה ציבורי, שמחזיר כתובת קצרה.
 *
 * הסיבה: העתקת הלוג ידנית מהשעון לא מעשית — המכשיר לא עומד בכמות
 * הטקסט. עם כתובת קצרה אפשר פשוט להקריא אותה.
 *
 * ⚠️ **פרטיות — קריטי.** הלוג מכיל התגברויות, נפילות וקטגוריות, כלומר
 * מידע אישי ורגיש ביותר. הוא עולה לשירות ציבורי שכל מי שיודע את הכתובת
 * יכול לקרוא. זה מקובל רק כי מדובר בבדיקות של נבו על המכשיר שלו.
 *
 * **חובה להסיר לפני שהאפליקציה יוצאת למשתמשים אחרים.** ההגנה כרגע:
 * הכפתור מוצג רק כש-DEBUG_TAG_ENABLED, שממילא חייב לרדת ל-false לפני
 * הפצה. זו הגנה יחידה, ולכן היא נכתבת כאן במפורש ולא רק בהערה בקוד.
 */
object LogUploader {

    /**
     * 0x0.st מקבל multipart, שזה חוזה ברור יותר מ-POST גולמי. paste.rs
     * החזיר 500 בפועל — כנראה בגלל הגודל או סוג-התוכן — ובלי גוף-שגיאה
     * שאפשר ללמוד ממנו.
     */
    private const val ENDPOINT = "https://0x0.st"
    private const val BOUNDARY = "----iluyLogBoundary"

    /** תקרה כדי לא לנסות להעלות מגה-בייטים דרך חיבור של שעון. */
    private const val MAX_LINES = 1_000

    fun upload(activity: Activity, onResult: (String) -> Unit) {
        Thread {
            val result = try {
                val lines = EventLog.readLastN(activity, MAX_LINES)
                if (lines.isEmpty()) "הלוג ריק" else post(lines.joinToString("\n"))
            } catch (e: Exception) {
                "שגיאה: ${e.javaClass.simpleName}"
            }
            activity.runOnUiThread { onResult(result) }
        }.start()
    }

    private fun post(body: String): String {
        val connection = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$BOUNDARY")
            // חלק מהשירותים דוחים בקשות בלי User-Agent מזוהה
            setRequestProperty("User-Agent", "iluy-watch-log/1.0")
        }
        try {
            OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { writer ->
                writer.write("--$BOUNDARY\r\n")
                writer.write(
                    "Content-Disposition: form-data; name=\"file\"; filename=\"iluy.log\"\r\n"
                )
                writer.write("Content-Type: text/plain; charset=utf-8\r\n\r\n")
                writer.write(body)
                writer.write("\r\n--$BOUNDARY--\r\n")
            }

            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }?.trim()

            return if (code in 200..299 && !response.isNullOrBlank()) {
                response
            } else {
                // גוף-השגיאה נחתך ומוצג: בלעדיו "נכשל 500" הוא מבוי סתום
                "נכשל $code: ${response?.take(60) ?: "אין פירוט"}"
            }
        } finally {
            connection.disconnect()
        }
    }
}
