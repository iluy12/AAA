package com.iluy.imutest

import android.content.Context
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * תוצאות מסך הכיול, בקובץ עצמאי.
 *
 * ⚠️ **לא ב-`EventLog`.** הוא מוגבל ל-500 שורות ונדרס — ואלה בדיוק
 * התוויות שהמדידה כולה נבנתה כדי להשיג. *"מה למדוד אחרי שבוע: כמה
 * שאלות יצאו, כמה נענו, ומה ההתפלגות"* דורש שהן ישרדו יותר משעה.
 */
object CheckInLog {

    private const val FILE_NAME = "iluy_checkins.csv"
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    const val CSV_HEADER = "time,bpm,baseline_median,deviation,answer"

    /**
     * @param answer אחד מ: `habit:<שם>`, `stress`, `positive`, `overcome`,
     *        `nothing`, `no_answer`.
     *
     * ⚠️ **`no_answer` נבדל מ-`nothing` בכוונה.** הראשון אומר "המסך הופיע
     * ולא היה מענה" — אולי הפריע. השני אומר "ענה: לא קרה כלום" — תווית
     * שלילית תקפה. לערבב ביניהם היה מוחק בדיוק את ההבחנה שנדרשה.
     */
    fun record(context: Context, bpm: Int, medianBpm: Double, deviation: Int, answer: String) {
        val line = listOf(fmt.format(Date()), bpm, "%.1f".format(medianBpm), deviation, answer)
            .joinToString(",")
        runCatching {
            FileWriter(File(context.filesDir, FILE_NAME), true).use { it.write(line + "\n") }
        }
        EventLog.log(context, "CHECKIN", "answer=$answer;bpm=$bpm;dev=$deviation")
    }

    fun exportAll(context: Context): String {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return CSV_HEADER + "\n"
        return runCatching { CSV_HEADER + "\n" + file.readText() }.getOrDefault(CSV_HEADER + "\n")
    }
}
