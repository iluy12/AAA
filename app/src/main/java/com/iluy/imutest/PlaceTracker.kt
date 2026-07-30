package com.iluy.imutest

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat

/**
 * "בבית או לא" — ולא "איפה הוא נמצא".
 *
 * ## מה נמדד וכמה גס
 *
 * ⚠️ **חדר אינו ניתן לחישה בשום צורה.** מיקום לפי אנטנות מדויק למאות
 * מטרים, ובית שלם נכנס בתוך טעות המדידה. גם GPS לוויינים עובד גרוע בתוך
 * מבנים — ודווקא שם קורים רוב האירועים.
 *
 * מה שכן ניתן, ומספיק: **האם הוא במקום שבו הוא נמצא בדרך כלל.** לזה
 * מספיק דיוק של מאות מטרים.
 *
 * ## ⚠️ לא שואלים אותו איפה הבית
 *
 * "הבית" נלמד: המקום שבו הוא נמצא הכי הרבה שעות. שאלה בשאלון על מיקום
 * הייתה חיכוך מיותר על משהו שנמדד ממילא — והעיקרון כאן הוא **שואלים רק
 * מה שאי-אפשר לחוש.**
 *
 * ## למה `network` לפני `gps`
 *
 * ספק הרשת עובד בתוך מבנים, צורך הרבה פחות, ומספיק לרזולוציה שאנחנו
 * צריכים. GPS לוויינים הוא נפילה-חזרה בלבד.
 */
object PlaceTracker {

    private const val PREFS_NAME = "iluy_place"

    /**
     * מתחת למרחק הזה נחשב "אותו מקום". 300 מטר בנדיבות — מיקום רשת
     * בתוך מבנה סוטה בקלות במאות מטרים, וסף צר היה מסמן "יצא מהבית"
     * בכל פעם שהאות משתנה.
     */
    const val SAME_PLACE_METERS = 300f

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

    /**
     * ⚠️ **"בחינם" התברר כ"ריק".** הגרסה הראשונה קראה רק את המיקום האחרון
     * הידוע, בהנחה שמדידת המיקום השעתית של היצרן משאירה ערך במטמון של
     * אנדרואיד. בלוג של 2026-07-31 חזר `place_m=-1` בכל פרץ למרות שההרשאה
     * ניתנה — כלומר אין מטמון כזה, והיצרן כותב למסד שלו בלבד.
     *
     * לכן נדרשת בקשה אמיתית אחת. היא לא רצה בכל פרץ אלא לכל היותר
     * [REFRESH_INTERVAL_MS], כי מיקום כמעט לא משתנה בין פרץ לפרץ ואין
     * סיבה להדליק מקלט כל שתי דקות.
     */
    private const val REFRESH_INTERVAL_MS = 30 * 60 * 1000L
    private var lastRequestElapsedMs = 0L

    private fun lastKnown(context: Context): Location? {
        if (!hasPermission(context)) return null
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null

        // רשת לפני לוויינים — עובד בתוך מבנים וזול בהרבה. ראו הערת-המחלקה.
        val providers = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)

        val cached = providers.firstNotNullOfOrNull {
            runCatching { lm.getLastKnownLocation(it) }.getOrNull()
        }

        val now = android.os.SystemClock.elapsedRealtime()
        val due = lastRequestElapsedMs == 0L || now - lastRequestElapsedMs > REFRESH_INTERVAL_MS
        if (due) {
            lastRequestElapsedMs = now
            requestOneFix(context, lm, providers)
        }

        // הערך שחוזר עכשיו הוא עדיין הישן — הבקשה א-סינכרונית והתוצאה
        // תיכנס למטמון לפרץ הבא. זה מקובל: השאלה היא "אותו מקום או לא",
        // ופרץ אחד של פיגור אינו משנה אותה.
        return cached
    }

    /**
     * בקשה בודדת, ומיד מתנתקים.
     *
     * ⚠️ `requestSingleUpdate` ולא `requestLocationUpdates` מתמשך: מאזין
     * שנשאר רשום מחזיק את המקלט דלוק ושורף סוללה בשקט — וזו בדיוק סוג
     * התקלה שלא רואים עד שמסתכלים על צריכה של יממה.
     */
    private fun requestOneFix(context: Context, lm: LocationManager, providers: List<String>) {
        val provider = providers.firstOrNull { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
            ?: return
        runCatching {
            lm.requestSingleUpdate(
                provider,
                object : android.location.LocationListener {
                    override fun onLocationChanged(location: Location) {
                        EventLog.log(context, "INFO", "location_fix;provider=$provider;acc=${location.accuracy.toInt()}")
                    }
                    override fun onStatusChanged(p: String?, s: Int, e: android.os.Bundle?) {}
                    override fun onProviderEnabled(p: String) {}
                    override fun onProviderDisabled(p: String) {}
                },
                android.os.Looper.getMainLooper()
            )
        }.onFailure {
            EventLog.log(context, "INFO", "location_request_failed;${it.javaClass.simpleName}")
        }
    }

    /**
     * מעדכן את "המקום הרגיל" ומחזיר את המרחק ממנו במטרים, או -1 אם אין
     * נתון.
     *
     * המקום הרגיל נלמד בממוצע נע איטי: כל קריאה מזיזה אותו קצת. כך הוא
     * מתכנס למקום שבו הוא נמצא הכי הרבה, בלי לקפוץ בכל יציאה מהבית.
     */
    fun distanceFromUsual(context: Context): Int {
        val loc = lastKnown(context) ?: return -1
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        val haveUsual = prefs.contains("lat")
        if (!haveUsual) {
            prefs.edit()
                .putFloat("lat", loc.latitude.toFloat())
                .putFloat("lon", loc.longitude.toFloat())
                .apply()
            return 0
        }

        val usualLat = prefs.getFloat("lat", 0f).toDouble()
        val usualLon = prefs.getFloat("lon", 0f).toDouble()
        val out = FloatArray(1)
        Location.distanceBetween(usualLat, usualLon, loc.latitude, loc.longitude, out)
        val meters = out[0]

        // ⚠️ נמשך למקום הרגיל רק כשהוא **שם**. אחרת כל נסיעה ארוכה הייתה
        // גוררת את "הבית" אחריה, ואחרי שבוע "הבית" היה ממוצע חסר-משמעות
        // בין כל המקומות שביקר בהם.
        if (meters < SAME_PLACE_METERS) {
            val a = 0.05
            prefs.edit()
                .putFloat("lat", (usualLat * (1 - a) + loc.latitude * a).toFloat())
                .putFloat("lon", (usualLon * (1 - a) + loc.longitude * a).toFloat())
                .apply()
        }
        return meters.toInt()
    }

    /** `true` כשהוא במקום הרגיל, `null` כשאין נתון מיקום כלל. */
    fun atUsualPlace(context: Context): Boolean? {
        val d = distanceFromUsual(context)
        return if (d < 0) null else d < SAME_PLACE_METERS
    }
}
