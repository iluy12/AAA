package com.iluy.imutest

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri

/**
 * מפת-המערכת: מה מותקן על השעון, מה מהם ניתן להפעלה מבחוץ, ואיפה יושבות
 * ההגדרות של היצרן.
 *
 * ## למה זה נחוץ
 *
 * שתי שאלות פתוחות שאי-אפשר לענות עליהן מהקוד שלנו:
 *
 * 1. **כפתור הצד** — לאיזה קוד-מקש הוא מתורגם, ואם בכלל מגיע לאפליקציה
 *    שלנו או שהמערכת בולעת אותו קודם. הרישום עצמו יושב ב-WatchFaceActivity;
 *    כאן רק ממפים מי עוד יכול לתפוס אותו.
 * 2. **מספר ה-SOS** — מוגדר באפליקציה של היצרן, ואנחנו לא יודעים אם הוא
 *    יושב ב-Settings, בקובץ פרטי של אותה אפליקציה, או מאחורי רכיב שאפשר
 *    להפעיל ב-Intent. שלוש התשובות מובילות למימוש שונה לגמרי.
 *
 * ניחוש כאן עולה סבב בנייה-התקנה-בדיקה שלם, ולכן קודם סורקים.
 *
 * ## אנדרואיד 8.1 הוא חלון-הזדמנות
 *
 * מ-API 30 אנדרואיד מסתיר את רשימת החבילות מאפליקציות אחרות. targetSdk
 * כאן הוא 28, ולכן הסריקה הזו רואה הכל בלי הרשאה ובלי `<queries>`.
 *
 * ## ⚠️ פרטיות
 *
 * הפלט נכנס ללוג, והלוג עולה לשירות **ציבורי**. ערכי-הגדרות יכולים להכיל
 * מספר טלפון — בדיוק מה שמחפשים כאן. לכן [mask] מחליף כל רצף ספרות ארוך
 * בשלוש האחרונות בלבד: מספיק כדי לזהות שמצאנו את השדה הנכון, לא מספיק
 * כדי לפרסם את המספר.
 */
object SystemScan {

    /**
     * מה נחשב "מעניין" בשמות הגדרות ורכיבים. רחב בכוונה — עדיף כמה שורות
     * מיותרות מאשר לפספס את השדה בגלל ניחוש שגוי של שם.
     */
    private val INTERESTING = Regex(
        "sos|emergency|urgent|panic|help|contact|dial|call|phone|guardian|family|watch|key|button",
        RegexOption.IGNORE_CASE
    )

    /** חבילות של אנדרואיד עצמו — רועשות ולא רלוונטיות לחיפוש אחרי היצרן. */
    private val AOSP_PREFIXES = listOf(
        "com.android.", "com.google.android.", "android.", "com.qualcomm.", "com.mediatek."
    )

    /**
     * ערך שנראה כמו מספר טלפון — 9 ספרות ומעלה, עם קידומת בינלאומית
     * אופציונלית. רחב בכוונה: המטרה למצוא את השדה, לא לאמת את המספר.
     */
    private val PHONE_LIKE = Regex("\\+?\\d{9,15}")

    /**
     * רצף של 5 ספרות ומעלה מוחלף ב-`***` ובשלוש האחרונות. 5 ולא 4 כדי
     * שערכי-הגדרה רגילים (timeouts, מזהים קצרים) יישארו קריאים.
     */
    private fun mask(value: String?): String {
        if (value == null) return "—"
        return Regex("\\d{5,}").replace(value.take(60)) { "***" + it.value.takeLast(3) }
    }

    fun run(context: Context) {
        val log = { detail: String -> EventLog.log(context, "SCAN", detail) }
        log("scan_start")
        // ⚠️ **הסדר כאן הוא סדר-שרידות, לא סדר-חשיבות.** ההעלאה שומרת את
        // 500 השורות האחרונות, והסריקה מייצרת יותר מזה — בסבב הראשון
        // בדיקת המיקום רצה ראשונה ולכן נדחקה החוצה, וחזרנו בלי התשובה
        // היחידה שבשבילה הוספתי אותה. לכן: הרועש (חבילות) קודם, והקצר
        // והמבוקש (מיקום) בסוף, במקום שמובטח לשרוד.
        runCatching { scanPackages(context, log) }.onFailure { log("scan_packages_failed;${it.javaClass.simpleName}") }
        runCatching { scanSettings(context, log) }.onFailure { log("scan_settings_failed;${it.javaClass.simpleName}") }
        runCatching { scanCallHandlers(context, log) }.onFailure { log("scan_call_failed;${it.javaClass.simpleName}") }
        runCatching { scanProviders(context, log) }.onFailure { log("scan_providers_failed;${it.javaClass.simpleName}") }
        runCatching { scanLocation(context, log) }.onFailure { log("scan_location_failed;${it.javaClass.simpleName}") }
        log("scan_done")
    }

    /**
     * האם יש GPS.
     *
     * ⚠️ **זו תיקון לבדיקה קודמת שהסתכלה במקום הלא נכון.** אינוונטר
     * החיישנים עובר על `SensorManager`, ו-GPS **לעולם אינו מופיע שם** —
     * מיקום הוא שירות נפרד (`LocationManager`) ולא חיישן. המסקנה
     * "אין GPS במכשיר" נגזרה מרשימה שמעולם לא יכלה להכיל אותו.
     *
     * `getAllProviders` אינו דורש הרשאה — הוא מדווח מה **קיים**, לא
     * היכן המכשיר נמצא. לשימוש אמיתי במיקום תידרש הרשאת ACCESS_FINE_LOCATION,
     * וזו החלטה נפרדת שתתקבל אחרי שנדע מה יש כאן.
     */
    private fun scanLocation(context: Context, log: (String) -> Unit) {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (lm == null) {
            log("location;service=null")
            return
        }
        val all = lm.allProviders
        log("location;providers=${all.joinToString("|")}")
        for (p in all) {
            val enabled = runCatching { lm.isProviderEnabled(p) }.getOrNull()
            log("  provider;name=$p;enabled=$enabled")
        }
        val pm = context.packageManager
        log(
            "location_features;gps=${pm.hasSystemFeature(PackageManager.FEATURE_LOCATION_GPS)};" +
                "network=${pm.hasSystemFeature(PackageManager.FEATURE_LOCATION_NETWORK)};" +
                "any=${pm.hasSystemFeature(PackageManager.FEATURE_LOCATION)}"
        )
    }

    /**
     * חבילה אחת לשורה, ורק רכיבים **exported** מפורטים — רכיב שאינו
     * exported לא ניתן להפעלה מאפליקציה אחרת, ולכן הוא חסר-ערך כאן גם
     * אם שמו מבטיח.
     */
    private fun scanPackages(context: Context, log: (String) -> Unit) {
        val pm = context.packageManager
        val flags = PackageManager.GET_ACTIVITIES or
            PackageManager.GET_SERVICES or
            PackageManager.GET_RECEIVERS
        val packages = pm.getInstalledPackages(flags)
        log("packages;total=${packages.size}")

        for (pi in packages) {
            val name = pi.packageName ?: continue
            val isAosp = AOSP_PREFIXES.any { name.startsWith(it) }
            // חבילת אנדרואיד מדולגת אלא אם שמה עצמו רומז על הנושא
            if (isAosp && !INTERESTING.containsMatchIn(name)) continue
            if (name == context.packageName) continue

            log("pkg;name=$name;version=${pi.versionName};system=${isSystem(pi)}")
            logExported(pi, log)
        }
    }

    private fun isSystem(pi: PackageInfo): Boolean {
        val flags = pi.applicationInfo?.flags ?: return false
        return (flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
    }

    private fun logExported(pi: PackageInfo, log: (String) -> Unit) {
        pi.activities?.forEach {
            if (it.exported) log("  comp;kind=activity;name=${it.name}")
        }
        pi.services?.forEach {
            if (it.exported) log("  comp;kind=service;name=${it.name}")
        }
        pi.receivers?.forEach {
            if (it.exported) log("  comp;kind=receiver;name=${it.name}")
        }
    }

    /**
     * שלוש טבלאות ההגדרות של אנדרואיד. אין דרך לדעת מראש באיזו מהן היצרן
     * שם את מספר ה-SOS — או אם בכלל — ולכן סורקים את שלושתן.
     */
    private fun scanSettings(context: Context, log: (String) -> Unit) {
        for (table in listOf("system", "secure", "global")) {
            val uri = Uri.parse("content://settings/$table")
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val nameIdx = c.getColumnIndex("name")
                val valueIdx = c.getColumnIndex("value")
                if (nameIdx < 0) return@use
                var matched = 0
                while (c.moveToNext()) {
                    val key = c.getString(nameIdx) ?: continue
                    val value = if (valueIdx >= 0) c.getString(valueIdx) else null

                    // ⚠️ **שתי דרכי התאמה, והשנייה חשובה יותר.** התאמה לפי
                    // שם-שדה מפספסת שדה שנקרא למשל `local_number_1` — אין
                    // בו אף מילה שניחשנו. התאמה לפי **ערך** שנראה כמו מספר
                    // טלפון מוצאת אותו בלי לנחש את השם, וגם בלי לקבע את
                    // המספר עצמו בקוד.
                    val byKey = INTERESTING.containsMatchIn(key)
                    val byValue = value != null && PHONE_LIKE.containsMatchIn(value)
                    if (!byKey && !byValue) continue

                    matched++
                    log(
                        "setting;table=$table;key=$key;value=${mask(value)};" +
                            "hit=${if (byValue) "value" else "key"}"
                    )
                }
                log("settings_scanned;table=$table;total=${c.count};matched=$matched")
            }
        }
    }

    /**
     * ספקי-תוכן של החבילות. `com.sgtc.provider` הוא המועמד הסביר למקום
     * שבו יושבים נתוני היצרן — היסטוריית דופק, ואם לא ב-Settings אז גם
     * מספר ה-SOS.
     *
     * כאן רק **ממפים authorities**. שאילתה בפועל דורשת לדעת את שם הטבלה,
     * ואי-אפשר לנחש אותה — קודם רואים מה קיים.
     */
    private fun scanProviders(context: Context, log: (String) -> Unit) {
        val pm = context.packageManager
        val providers = pm.queryContentProviders(null, 0, 0) ?: return
        log("providers;total=${providers.size}")
        for (p in providers) {
            val auth = p.authority ?: continue
            val pkg = p.packageName ?: ""
            // רק של היצרן ושל אנשי-קשר — השאר רועש ולא רלוונטי
            if (!pkg.contains("sgtc") && !pkg.contains("com.sg") && !auth.contains("contact")) continue
            log("provider;auth=$auth;pkg=$pkg;exported=${p.exported};read=${p.readPermission}")
        }
    }

    /**
     * מי מטפל בחיוג בפועל. אם ל-`ACTION_CALL` יש מטפל של היצרן ולא החייגן
     * הרגיל, ייתכן שהמסלול ל-SOS עובר דרכו.
     */
    private fun scanCallHandlers(context: Context, log: (String) -> Unit) {
        val pm = context.packageManager
        for (action in listOf(Intent.ACTION_CALL, Intent.ACTION_DIAL)) {
            val intent = Intent(action, Uri.parse("tel:000"))
            val handlers = pm.queryIntentActivities(intent, 0)
            log("call_handlers;action=$action;count=${handlers.size}")
            handlers.forEach {
                val comp = ComponentName(it.activityInfo.packageName, it.activityInfo.name)
                log("  handler;comp=${comp.flattenToShortString()}")
            }
        }
    }
}
