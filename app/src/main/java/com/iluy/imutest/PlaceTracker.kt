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
     * המיקום האחרון הידוע, בלי לבקש קריאה חדשה.
     *
     * ⚠️ **`getLastKnownLocation` ולא בקשת עדכון.** בקשה פעילה מדליקה את
     * המקלט ועולה סוללה; המיקום האחרון הוא בחינם לגמרי. במכשיר הזה
     * המדידה של היצרן רצה ממילא כל שעה, כלומר תמיד יש ערך טרי מספיק
     * לשאלה "אותו מקום או לא".
     */
    private fun lastKnown(context: Context): Location? {
        if (!hasPermission(context)) return null
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        // רשת לפני לוויינים — עובד בתוך מבנים ובחינם. ראו הערת-המחלקה.
        for (p in listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)) {
            val loc = runCatching { lm.getLastKnownLocation(p) }.getOrNull()
            if (loc != null) return loc
        }
        return null
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
