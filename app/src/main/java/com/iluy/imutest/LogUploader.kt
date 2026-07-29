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
     * 300 שורות **מסוננות** מכילות יותר מידע שימושי מ-1000 גולמיות.
     */
    private const val MAX_LINES = 300

    /**
     * רק תנועות-מגע גולמיות מסוננות — הן רבות ומעניינות רק על המכשיר.
     *
     * ⚠️ hr_sample **לא** מסונן יותר. סיננתי אותו קודם כדי לחסוך נפח,
     * ובכך מחקתי בדיוק את השדות שהוספתי לאבחון (accuracy ו-all_slots)
     * — הלוג הגיע בלי המידע שבשבילו נשלח. הוא ממילא מוגבל לדגימה אחת
     * מכל 30, אז הנפח שולי.
     */
    private val noisyMarkers = listOf("stroke;")

    fun upload(activity: Activity, onResult: (String) -> Unit) {
        Thread {
            val result = try {
                val lines = EventLog.readAll(activity)
                    .filter { line -> noisyMarkers.none { line.contains(it) } }
                    .takeLast(MAX_LINES)
                if (lines.isEmpty()) "הלוג ריק" else postWithFallbacks(lines.joinToString("\n"))
            } catch (e: Exception) {
                "שגיאה: ${e.javaClass.simpleName}"
            }
            activity.runOnUiThread { onResult(result) }
        }.start()
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
