package com.iluy.imutest

import android.app.Activity
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.net.URLEncoder

/**
 * העלאת הלוג לשירות-הדבקה, שמחזיר כתובת קצרה.
 *
 * הסיבה: העתקת הלוג ידנית מהשעון לא מעשית — המכשיר לא עומד בכמות
 * הטקסט. עם כתובת קצרה אפשר פשוט להקריא אותה.
 *
 * ⚠️ **פרטיות — קריטי.** הלוג מכיל התגברויות, נפילות וקטגוריות, כלומר
 * מידע אישי ורגיש ביותר. הוא עולה לשירות ציבורי שכל מי שיודע את הכתובת
 * יכול לקרוא. זה מקובל רק כי מדובר בבדיקות של נבו על המכשיר שלו.
 *
 * **חובה להסיר לפני שהאפליקציה יוצאת למשתמשים אחרים** — יחד עם הרשאת
 * INTERNET. ההגנה כרגע היא ש-DEBUG_TAG_ENABLED חייב לרדת ל-false לפני
 * הפצה, וזו הגנה יחידה.
 */
object LogUploader {

    /**
     * 1000 שורות היו כ-80KB וגרמו ל-SocketTimeout על חיבור של שעון.
     *
     * 500 שורות **מסוננות** הן כ-85KB במקרה הגרוע (מכשיר ער כל הלילה,
     * שורת סיכום כל 60 שניות), ובפועל הרבה פחות: כשהמכשיר נכנס לשינה
     * ה-Handler מאחר, ולכן פחות שורות מכסות יותר שעות. בלוג של
     * 2026-07-29 קטע של 115 דקות תפס 11 שורות בלבד.
     */
    private const val MAX_LINES = 500

    /**
     * ⚠️ `hr_sample;` חזר להיות מסונן — **וזו אינה חזרה על הטעות הקודמת.**
     *
     * הפעם סיננתי אותו קודם כדי לחסוך נפח, ובכך מחקתי בדיוק את השדות
     * שהוספתי לאבחון (accuracy ו-all_slots) — הלוג הגיע בלי המידע
     * שבשבילו נשלח. אלא שהאבחון ההוא נסגר: הפענוח אומת ונרשם ב-HeartRate,
     * ו-hr_sample כבר לא נושא שאלה פתוחה.
     *
     * מה שהשתנה הוא סדר-הגודל. בבדיקת-לילה hr_sample נכתב כל 30 דגימות,
     * כלומר כ-2,900 שורות ב-12 שעות — הוא היה דוחק החוצה את כל שורות
     * `hr_summary` פרט לשעה האחרונה, ומאבד בדיוק את הרגע שבו הזרם נעצר.
     * הסדרה של `hr_summary` היא כל התוצאה של הבדיקה הזו.
     *
     * אם הפענוח ייראה חשוד שוב — להסיר את המסנן הזה לסבב אחד.
     */
    private val noisyMarkers = listOf("stroke;", "hr_sample;")

    /**
     * ⚠️ **תקרת בייטים, בנוסף לתקרת שורות.** ב-2026-07-30 העלאה נכשלה
     * בשלושת השירותים ב-`SocketTimeoutException`: 500 שורות סריקה הן
     * ארוכות בהרבה משורות דופק, והמשקל חצה את מה שחיבור השעון סובל.
     * מגבלת-שורות לבדה אינה מגנה, כי אורך שורה משתנה פי כמה לפי סוגה.
     *
     * 60KB הם מתחת ל-80KB שנמדדו כנכשלים, עם מרווח.
     */
    private const val MAX_BYTES = 60_000

    fun upload(activity: Activity, onResult: (String) -> Unit) {
        Thread {
            val result = try {
                val lines = EventLog.readAll(activity)
                    .filter { line -> noisyMarkers.none { line.contains(it) } }
                    .takeLast(MAX_LINES)
                if (lines.isEmpty()) "הלוג ריק" else postWithFallbacks(trimToBytes(lines))
            } catch (e: Exception) {
                "שגיאה: ${e.javaClass.simpleName}"
            }
            activity.runOnUiThread { onResult(result) }
        }.start()
    }

    /**
     * מוריד שורות **מהראשונות** עד שהמשקל נכנס לתקרה, כך שהשורות
     * האחרונות — הטריות והרלוונטיות — הן אלה שנשמרות.
     */
    private fun trimToBytes(lines: List<String>): String {
        var start = 0
        var bytes = lines.sumOf { it.toByteArray(Charsets.UTF_8).size + 1 }
        while (start < lines.size - 1 && bytes > MAX_BYTES) {
            bytes -= lines[start].toByteArray(Charsets.UTF_8).size + 1
            start++
        }
        return lines.subList(start, lines.size).joinToString("\n")
    }

    /**
     * שרשרת שירותים, לא אחד.
     *
     * paste.rs החזיר 500, ו-0x0.st השבית העלאות לגמרי. כל כישלון כזה עלה
     * סבב שלם של בנייה-התקנה-בדיקה. מנסים אחד אחרי השני עד שמישהו עונה,
     * כך ששירות שנופל הופך לעיכוב של שנייה במקום ליום עבודה.
     */
    private fun postWithFallbacks(body: String): String {
        val failures = mutableListOf<String>()

        runCatching { return postForm("https://dpaste.com/api/v2/", body) }
            .onFailure { failures.add("dpaste:${it.javaClass.simpleName}") }

        runCatching { return postRaw("https://paste.c-net.org/", body) }
            .onFailure { failures.add("cnet:${it.javaClass.simpleName}") }

        // לא HTTP בכלל אלא socket גולמי, ולכן לא מושפע מהחסימות שהפילו
        // את שני הקודמים
        runCatching { return postSocket(body) }
            .onFailure { failures.add("termbin:${it.javaClass.simpleName}") }

        return "כל השירותים נכשלו: ${failures.joinToString(", ").take(80)}"
    }

    /** dpaste מצפה ל-form encoded עם השדה content. */
    private fun postForm(endpoint: String, body: String): String {
        val payload = "content=" + URLEncoder.encode(body, "UTF-8") +
            "&syntax=text&expiry_days=7"
        return send(endpoint, "application/x-www-form-urlencoded", payload)
    }

    private fun postRaw(endpoint: String, body: String): String =
        send(endpoint, "text/plain; charset=utf-8", body)

    private fun send(endpoint: String, contentType: String, payload: String): String {
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            // נדיב בכוונה: החיבור של השעון איטי, וכישלון כאן עולה סבב שלם.
            connectTimeout = 30_000
            readTimeout = 90_000
            setRequestProperty("Content-Type", contentType)
            setRequestProperty("User-Agent", "iluy-watch-log/1.0")
        }
        try {
            OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { it.write(payload) }

            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }?.trim()

            if (code in 200..299 && !response.isNullOrBlank()) return response
            throw IllegalStateException("HTTP $code")
        } finally {
            connection.disconnect()
        }
    }

    /**
     * termbin: פותחים socket, כותבים, סוגרים את צד-הכתיבה, וקוראים את
     * הכתובת שחוזרת.
     *
     * סגירת צד-הכתיבה קריטית: בלעדיה השרת ממשיך להמתין לעוד נתונים ולא
     * מחזיר כלום עד ל-timeout.
     */
    private fun postSocket(body: String): String {
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress("termbin.com", 9999), 30_000)
            socket.soTimeout = 90_000

            val output = socket.getOutputStream()
            output.write(body.toByteArray(Charsets.UTF_8))
            output.flush()
            socket.shutdownOutput()

            val reply = socket.getInputStream()
                .bufferedReader(Charsets.UTF_8)
                .readText()
                .trim()

            if (reply.isBlank()) throw IllegalStateException("empty reply")
            return reply
        } finally {
            runCatching { socket.close() }
        }
    }
}
