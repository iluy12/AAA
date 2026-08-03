package com.iluy.imutest

import android.content.Context

/**
 * בדיקת שפיות על הנתונים שנאספו: מה **לא יכול להיות**.
 *
 * ## למה זה קיים
 *
 * ⚠️ **כל באג שנמצא עד היום נמצא מהנתונים, ולא מקריאת הקוד.** האות
 * שהיה הפוך, החור של ארבע שעות בשעות המוצהרות, הבסיס שבלע את מה שהוא
 * אמור לזהות, ושעון-השקט שהיה אפס יום שלם — כולם התגלו מהסתכלות בטבלה.
 *
 * והם התגלו באותה דרך בדיוק: **שילוב שלא יכול להיות.** "אפס צעדים"
 * ו"זז ברגע זה" באותה שורה. זה לא דרש חוכמה, זה דרש להסתכל.
 *
 * מכאן המסקנה: אין טעם לחפש באגים אחד-אחד. עדיף לרשום מה בלתי-אפשרי
 * ולתת למכשיר לצעוק בעצמו.
 *
 * ⚠️ **וזה גם התנאי לתת את השעון למישהו אחר.** על משתמש אחר אין לוגים
 * שאפשר לקרוא ואין מי שישים לב — בלי הבדיקה הזו נגלה בעוד חודש שהוא
 * אסף זבל, ואת החודש הזה אי-אפשר להחזיר.
 *
 * ## הכלל: לדווח מספרים, לא דעות
 *
 * כל ממצא נושא את המספר שהוליד אותו. "still_ms אפס ב-49 מתוך 49" הוא
 * ממצא; "נראה שיש בעיה בחיישן" הוא ניחוש, והוא חסר ערך אחרי שבועיים.
 */
object DataSanity {

    enum class Level { ERROR, WARN, OK }

    /**
     * @param code מזהה קצר באנגלית, כדי שאפשר יהיה לחפש אותו בייצוא.
     * @param text ההסבר בעברית, **עם המספרים בתוכו**.
     */
    data class Finding(val level: Level, val code: String, val text: String)

    /** מתחת לזה אין מספיק נתונים כדי לומר משהו. */
    private const val MIN_RECORDS = 20

    /**
     * הבדיקה על כל ההיסטוריה **ועל היממה האחרונה בנפרד**.
     *
     * ⚠️ **בלי החלון הקצר, תקלה חדשה נבלעת בהיסטוריה הישנה.** ככל שנצבור
     * נתונים, יום שבור אחד יהיה אחוז קטן מהכל — כלומר **בדיוק כשהאיסוף
     * מתארך והבדיקה נעשית חשובה יותר, היא נעשית עיוורת יותר.** תיקון
     * שיישבר בעוד חודש לא ייצור אף אחוז מורגש.
     *
     * ולכן ממצא שקיים רק ביממה האחרונה מדווח בנפרד ובמפורש.
     */
    fun checkAll(records: List<SampleStore.Record>): List<Finding> {
        val whole = check(records)
        val dayAgo = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
        val recent = records.filter { it.timestampMs >= dayAgo }
        if (recent.size < MIN_RECORDS) return whole

        val seen = whole.map { it.code }.toSet()
        val fresh = check(recent)
            .filter { it.level != Level.OK && it.code !in seen }
            .map { it.copy(text = "ביממה האחרונה בלבד — " + it.text) }
        // ⚠️ אם היממה האחרונה מצאה משהו, שורת ה"נקי" של ההיסטוריה יורדת —
        // אחרת המסך היה אומר "לא נמצא שילוב בלתי אפשרי" ומיד אחריה ממצא.
        return if (fresh.isEmpty()) whole else whole.filter { it.level != Level.OK } + fresh
    }

    fun check(records: List<SampleStore.Record>): List<Finding> {
        if (records.size < MIN_RECORDS) {
            return listOf(
                Finding(
                    Level.OK, "too_few",
                    "רק ${records.size} רשומות. צריך לפחות $MIN_RECORDS כדי לבדוק."
                )
            )
        }

        val out = mutableListOf<Finding>()
        val n = records.size

        // --- 1. אפס צעדים אבל "זז ברגע זה" ---
        //
        // ⚠️ **הבדיקה שהייתה מגלה את הבאג הגדול ביותר עד היום.** ב-49
        // רשומות של 2026-08-03 הופיעו יחד steps=0 ו-still_ms=0, כלומר
        // "לא נעשה אף צעד" ו"זזת בשנייה האחרונה". זו סתירה, והיא הייתה
        // צועקת מהטבלה — אבל אף אחד לא הסתכל במשך יום.
        val contradiction = records.count { it.steps == 0 && it.stillMs in 0L until 60_000L }
        // ⚠️ 10% ולא 20%. **זו סתירה, לא סטטיסטיקה** — היא לא אמורה
        // להופיע אף פעם, ולכן אין סיבה לדרוש שהיא תהיה נפוצה. בסף של 20%
        // הבדיקה הזו הייתה **שותקת בדיוק על הנתונים שהולידו אותה**: יום
        // שלם של עיוורון מתוך שלושה ימי איסוף יוצא 19.5%.
        if (contradiction * 100 / n >= 10) {
            out.add(
                Finding(
                    Level.ERROR, "steps_zero_but_moving",
                    "ב-$contradiction מתוך $n רשומות אין אף צעד ובכל זאת השעון " +
                        "חושב שזזת ברגע זה. זה בלתי אפשרי — מונה הצעדים כנראה " +
                        "משדר בלי הפסקה, וזה מכבה את כל הבדיקות."
                )
            )
        }

        // --- 2. שעון-השקט תקוע על אפס ---
        val stillZero = records.count { it.stillMs == 0L }
        if (stillZero * 100 / n >= 20) {
            out.add(
                Finding(
                    Level.ERROR, "still_always_zero",
                    "שעון-השקט הוא אפס ב-$stillZero מתוך $n רשומות. כל עוד זה " +
                        "המצב, המערכת לא בודקת כלום והבסיס לא לומד כלום."
                )
            )
        }

        // --- 3. אותו מספר דגימות שוב ושוב ---
        //
        // ⚠️ מדידה אמיתית משתנה. מספר שחוזר על עצמו בדיוק הוא תקרה,
        // לולאה שנעצרת, או ערך שנכתב מראש — ולא משהו שנמדד. ב-2026-08-03
        // הופיעו **בדיוק 67 דגימות ב-47 מתוך 49 המדידות**.
        val withSamples = records.filter { it.samples > 0 }
        if (withSamples.size >= MIN_RECORDS) {
            val mode = withSamples.groupingBy { it.samples }.eachCount().maxByOrNull { it.value }
            if (mode != null && mode.value * 100 / withSamples.size >= 70) {
                out.add(
                    Finding(
                        Level.WARN, "samples_constant",
                        "בדיוק ${mode.key} דגימות דופק ב-${mode.value} מתוך " +
                            "${withSamples.size} מדידות. מדידה אמיתית משתנה — " +
                            "מספר קבוע מרמז על תקרה ולא על מדידה."
                    )
                )
            }
        }

        // --- 4. הדופק לא זז ---
        //
        // ⚠️ נולד ממדידה: אפליקציית היצרן כתבה 57 קבוע, ולכן לא הייתה
        // שמישה כמקור-אמת. אותה תקלה יכולה לקרות גם לנו.
        val bpms = records.filter { it.bpm > 0 }.map { it.bpm }
        if (bpms.size >= 30 && bpms.distinct().size <= 4) {
            out.add(
                Finding(
                    Level.ERROR, "bpm_constant",
                    "רק ${bpms.distinct().size} ערכי דופק שונים ב-${bpms.size} " +
                        "מדידות (${bpms.distinct().sorted().joinToString(",")}). " +
                        "החיישן לא באמת מודד."
                )
            )
        }

        // --- 5. מד התאוצה לא מוסר כלום ---
        //
        // ⚠️ קרה בפועל: רישום ב-SENSOR_DELAY_UI הצליח ולא החזיר אף אירוע,
        // ובפעם אחרת בקשת מיקום בתוך הפרץ הרגה אותו לגמרי.
        val noMotion = records.count { it.motion < 0 }
        if (noMotion * 100 / n >= 50) {
            out.add(
                Finding(
                    Level.ERROR, "motion_missing",
                    "אין נתוני תנועה ב-$noMotion מתוך $n רשומות. מד התאוצה שותק."
                )
            )
        }

        // --- 6. שער אחד חוסם כמעט הכל ---
        //
        // ⚠️ **זה הממצא שהיה חוסך לנו יום.** כשכל רשומה נחסמת באותו שער,
        // המערכת עיוורת — ובלוג זה נראה בדיוק כמו "לא היה מה להציע".
        val judged = records.filter { it.score >= 0 || it.blocked.isNotBlank() }
        if (judged.size >= MIN_RECORDS) {
            val blocked = judged.filter { it.blocked.isNotBlank() }
            val worst = blocked.groupingBy { it.blocked }.eachCount().maxByOrNull { it.value }
            if (worst != null && worst.value * 100 / judged.size >= 80) {
                out.add(
                    Finding(
                        Level.ERROR, "one_gate_blocks_all",
                        "השער \"${worst.key}\" חסם ${worst.value} מתוך ${judged.size} " +
                            "בדיקות. המערכת כמעט לא הספיקה להחליט כלום."
                    )
                )
            }
        }

        // --- 7. חלון הדופק נאכל על ידי חימום החיישן ---
        //
        // ⚠️ **הבדיקה שהייתה חוסכת לנו חצי מהנתונים.** 45 שניות נקצבו
        // לפרץ, והדגימה הראשונה הגיעה אחרי 23.7 — כלומר נשארו 21. הקצב
        // עצמו היה תקין לגמרי, ולכן שום בדיקה על הדופק לא הייתה תופסת
        // את זה. רק היחס בין השניים.
        val warmups = records.filter { it.firstSampleMs >= 0 }.map { it.firstSampleMs }
        if (warmups.size >= MIN_RECORDS) {
            val median = warmups.sorted()[warmups.size / 2]
            if (median > 10_000L) {
                out.add(
                    Finding(
                        Level.ERROR, "burst_eaten_by_warmup",
                        "החיישן האופטי לוקח ${median / 1000} שניות עד הדגימה " +
                            "הראשונה. מתוך 45 שניות נשארות רק " +
                            "${(45_000L - median) / 1000} של נתונים אמיתיים."
                    )
                )
            }
        }

        // --- 8. פיזור התנועה מחושב ממעט מדי דגימות ---
        val accelCounts = records.filter { it.accelCount >= 0 }.map { it.accelCount }
        if (accelCounts.size >= MIN_RECORDS) {
            val median = accelCounts.sorted()[accelCounts.size / 2]
            if (median < 30) {
                out.add(
                    Finding(
                        Level.WARN, "motion_from_few_samples",
                        "ערך התנועה מחושב מ-$median דגימות תאוצה בלבד (חציון). " +
                            "פיזור שמחושב ממעט דגימות קופץ בין ערכים ואינו אמין."
                    )
                )
            }
        }

        // --- 9. השעון לא על היד ---
        val notWorn = records.count { it.bpm <= 0 || it.noContact > 0 }
        if (notWorn * 100 / n >= 40) {
            out.add(
                Finding(
                    Level.WARN, "often_not_worn",
                    "ב-$notWorn מתוך $n רשומות אין מגע עם העור. השעון לא נענד " +
                        "רוב הזמן, וכל מה שנאסף מהן חסר ערך."
                )
            )
        }

        // --- 10. חורים באיסוף ---
        //
        // מדידה כל ~10 דקות. פער גדול פירושו שהשירות נפל, שהסוללה נגמרה,
        // או שהשעון היה כבוי — ובכל אחד מהם דיווח שיגיע אחר-כך יתויג לחלון
        // הזמן הלא נכון.
        val sorted = records.sortedBy { it.timestampMs }
        var gaps = 0
        var biggest = 0L
        for (i in 1 until sorted.size) {
            val gap = sorted[i].timestampMs - sorted[i - 1].timestampMs
            if (gap > 45 * 60 * 1000L) gaps++
            if (gap > biggest) biggest = gap
        }
        if (gaps > 0) {
            out.add(
                Finding(
                    Level.WARN, "collection_gaps",
                    "$gaps הפסקות של יותר מ-45 דקות באיסוף. הארוכה ביותר: " +
                        "${biggest / 3600000} שעות ו-${(biggest / 60000) % 60} דקות."
                )
            )
        }

        // --- 11. הסוללה לא זזה ---
        val batteries = records.filter { it.battery in 0..100 }.map { it.battery }
        if (batteries.size >= 50 && batteries.distinct().size <= 1) {
            out.add(
                Finding(
                    Level.WARN, "battery_stuck",
                    "אחוז הסוללה תקוע על ${batteries.first()} לאורך " +
                        "${batteries.size} רשומות. הקריאה כנראה לא אמיתית."
                )
            )
        }

        if (out.isEmpty()) {
            out.add(Finding(Level.OK, "clean", "$n רשומות נבדקו. לא נמצא שילוב בלתי אפשרי."))
        }
        return out
    }

    /** גרסה קצרה למסך. */
    fun describe(context: Context): String {
        val findings = checkAll(SampleStore.since(context, 0L))
        return findings.joinToString("\n\n") { f ->
            val mark = when (f.level) {
                Level.ERROR -> "✖"
                Level.WARN -> "▲"
                Level.OK -> "✓"
            }
            "$mark ${f.text}"
        }
    }

    /**
     * שורות `#` שנוספות בראש הייצוא.
     *
     * ⚠️ **בראש ולא בסוף.** קובץ של אלפי שורות נפתח בהתחלה, והממצא צריך
     * להיות הדבר הראשון שנראה — לא משהו שצריך לגלול אליו. תו `#` מאפשר
     * לכל כלי ניתוח לדלג עליהן.
     */
    fun exportHeader(records: List<SampleStore.Record>): String =
        checkAll(records).joinToString("\n") { "# [${it.level}] ${it.code}: ${it.text}" } + "\n"
}
