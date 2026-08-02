package com.watchprobe

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorManager
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Watch Probe — בדיקת חומרה של שעון מועמד, בלחיצה אחת.
 *
 * ## למה זה קיים
 *
 * ⚠️ **מפרטים שיווקיים של שעונים כאלה משקרים, וזה נמדד ולא הונח.** על
 * הדגם שאנחנו עובדים עליו התברר ש-`Sensor.power` מדווח ערך שגוי, ש"חיישן
 * טמפרטורה" הוא טמפרטורת שבב הברומטר ולא של העור, ש"לחץ דם" מחושב ולא
 * נמדד, ושמדידת הדופק האוטומטית של היצרן כותבת ערך קבוע ולא מודדת בכלל.
 *
 * ⚠️ **והשאלה המכריעה אינה "אילו חיישנים יש" אלא "אילו נחשפים".** באותו
 * שעון קיים חיישן חמצן-בדם שאף אפליקציה חיצונית אינה יכולה לקרוא — הוא
 * קיים בחומרה ולא קיים מבחינתנו. `SensorManager` הוא הבוחן היחיד שקובע.
 *
 * ## למה בעברית — לא
 *
 * הטקסט כאן באנגלית בכוונה: האפליקציה נשלחת לספקים שיריצו אותה על דגם
 * מועמד. ממשק שהם לא מבינים הוא ממשק שלא יופעל.
 */
class ProbeActivity : Activity() {

    private val lines = StringBuilder()
    private lateinit var status: TextView
    private lateinit var codeView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 20, 20, 20)
            gravity = Gravity.CENTER_HORIZONTAL
        }

        root.addView(TextView(this).apply {
            text = "Watch Probe"
            textSize = 18f
            gravity = Gravity.CENTER
        })

        codeView = TextView(this).apply {
            textSize = 30f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#2E7D5B"))
            setPadding(0, 16, 0, 16)
        }
        root.addView(codeView)

        status = TextView(this).apply {
            textSize = 12f
            gravity = Gravity.CENTER
        }
        root.addView(status)

        setContentView(ScrollView(this).apply { addView(root) })

        // ⚠️ ההרשאות נדרשות **לפני** הסריקה: בלי BODY_SENSORS חיישני הגוף
        // לא מופיעים כלל, ובלי מיקום אין תוצאות WiFi. סריקה מוקדמת מדי
        // הייתה מחזירה "אין" על חיישנים שקיימים.
        requestPermissions(
            arrayOf(Manifest.permission.BODY_SENSORS, Manifest.permission.ACCESS_FINE_LOCATION),
            1
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        runProbe()
    }

    private fun runProbe() {
        status.text = "Scanning…"
        Thread {
            collect()
            val result = upload(lines.toString())
            runOnUiThread {
                codeView.text = result
                status.text = "Send this code back"
            }
        }.start()
    }

    private fun add(s: String) = lines.append(s).append("\n")

    private fun collect() {
        add("=== DEVICE ===")
        add("model=${Build.MODEL};brand=${Build.BRAND};device=${Build.DEVICE}")
        add("android=${Build.VERSION.RELEASE};sdk=${Build.VERSION.SDK_INT}")

        // ⚠️ הרשימה המלאה, כולל wakeup ו-fifo. wakeup קובע אם חיישן ממשיך
        // למסור כשהמעבד ישן — הפרט שעלה לנו יומיים לגלות בדגם הנוכחי.
        add("")
        add("=== SENSORS EXPOSED TO ANDROID ===")
        val sm = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val all = sm.getSensorList(Sensor.TYPE_ALL)
        add("total=${all.size}")
        for (s in all) {
            add(
                "sensor;type=${s.type};name=${s.name};vendor=${s.vendor};" +
                    "power_ma=${s.power};min_delay_us=${s.minDelay};" +
                    "max_range=${s.maximumRange};" +
                    "wakeup=${if (Build.VERSION.SDK_INT >= 21) s.isWakeUpSensor else null};" +
                    "fifo=${if (Build.VERSION.SDK_INT >= 19) s.fifoMaxEventCount else -1}"
            )
        }

        // הבדיקה שבאמת מעניינת: האם החיישנים שנמכרו בפרסום קיימים כאן.
        add("")
        add("=== THE ONES THAT MATTER ===")
        val want = mapOf(
            "heart_rate" to Sensor.TYPE_HEART_RATE,
            "accelerometer" to Sensor.TYPE_ACCELEROMETER,
            "gyroscope" to Sensor.TYPE_GYROSCOPE,
            "magnetometer" to Sensor.TYPE_MAGNETIC_FIELD,
            "step_counter" to Sensor.TYPE_STEP_COUNTER,
            "ambient_temperature" to Sensor.TYPE_AMBIENT_TEMPERATURE,
            "relative_humidity" to Sensor.TYPE_RELATIVE_HUMIDITY,
            "light" to Sensor.TYPE_LIGHT,
            "proximity" to Sensor.TYPE_PROXIMITY,
            "pressure" to Sensor.TYPE_PRESSURE
        )
        for ((label, type) in want) {
            add("has;$label=${sm.getDefaultSensor(type) != null}")
        }
        // ⚠️ אלה דורשים API 20+ ולכן נבדקים בנפרד — הם גם המעניינים ביותר.
        if (Build.VERSION.SDK_INT >= 20) {
            add("has;heart_beat=${sm.getDefaultSensor(Sensor.TYPE_HEART_BEAT) != null}")
        }
        if (Build.VERSION.SDK_INT >= 24) {
            add("has;offbody_detect=${sm.getDefaultSensor(Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT) != null}")
        }

        // חיישנים פרטיים של היצרן — טיפוסים מעל 65536. ⚠️ **כאן יושבים
        // בדרך כלל SpO2 ומוליכות-עור אם הם נחשפים בכלל**, ולכן זו השורה
        // החשובה ביותר בכל הבדיקה.
        add("")
        add("=== VENDOR-PRIVATE SENSOR TYPES ===")
        val privates = all.filter { it.type >= 65536 }
        add("count=${privates.size}")
        for (s in privates) add("private;type=${s.type};name=${s.name};vendor=${s.vendor}")

        add("")
        add("=== RADIOS ===")
        val pm = packageManager
        add("wifi=${pm.hasSystemFeature(PackageManager.FEATURE_WIFI)}")
        add("bluetooth_le=${pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)}")
        add("telephony=${pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)}")
        add("gps=${pm.hasSystemFeature(PackageManager.FEATURE_LOCATION_GPS)}")
        runCatching {
            val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            add("location_providers=${lm.allProviders.joinToString("|")}")
        }

        add("")
        add("=== SCREEN ===")
        val dm = resources.displayMetrics
        add("px=${dm.widthPixels}x${dm.heightPixels};dpi=${dm.densityDpi}")

        // האם אפשר להחליף מסך-בית — תנאי מוקדם לכל המוצר.
        add("")
        add("=== LAUNCHER REPLACEABLE ===")
        val home = android.content.Intent(android.content.Intent.ACTION_MAIN)
            .addCategory(android.content.Intent.CATEGORY_HOME)
        add("home_candidates=${pm.queryIntentActivities(home, 0).size}")

        add("")
        add("=== INSTALLED PACKAGES ===")
        val pkgs = runCatching { pm.getInstalledPackages(0) }.getOrNull().orEmpty()
        add("total=${pkgs.size}")
        for (p in pkgs) {
            val name = p.packageName ?: continue
            if (name.startsWith("com.android.") || name.startsWith("com.google.")) continue
            add("pkg=$name;v=${p.versionName}")
        }
    }

    /**
     * שרשרת שירותים ולא אחד — שירות שנופל הופך לעיכוב של שנייה במקום
     * לסבב שלם מול הספק.
     */
    private fun upload(body: String): String {
        runCatching {
            return post(
                "https://dpaste.com/api/v2/",
                "application/x-www-form-urlencoded",
                "content=" + URLEncoder.encode(body, "UTF-8") + "&syntax=text&expiry_days=30"
            ).substringAfterLast('/')
        }
        runCatching {
            return post("https://paste.c-net.org/", "text/plain; charset=utf-8", body)
                .substringAfterLast('/')
        }
        return "UPLOAD FAILED"
    }

    private fun post(endpoint: String, contentType: String, payload: String): String {
        val c = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 30_000
            readTimeout = 90_000
            setRequestProperty("Content-Type", contentType)
            setRequestProperty("User-Agent", "watch-probe/1.0")
        }
        try {
            OutputStreamWriter(c.outputStream, Charsets.UTF_8).use { it.write(payload) }
            if (c.responseCode !in 200..299) throw IllegalStateException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }.trim()
        } finally {
            c.disconnect()
        }
    }
}
