package com.iluy.imutest

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * בדיקה והתקנה של גרסה חדשה מתוך GitHub Releases.
 *
 * נבנה אחרי לילה שבו כל עדכון דרש דפדפן, הורדה ידנית והתקנה — ובשלב
 * מסוים לא היה בכלל דפדפן נגיש. אם אנחנו לוקחים אחריות על המכשיר,
 * אנחנו לוקחים אחריות גם על העדכונים.
 *
 * **למה ההשוואה פשוטה:** מאז ש-versionCode נגזר ממספר ריצת ה-CI, הוא
 * זהה למספר בתגית (build-52 ↔ versionCode 52). אין כאן פרסור-גרסאות,
 * רק השוואת שני מספרים שלמים.
 *
 * **למה לא /releases/latest:** כל הגרסאות מסומנות prerelease, ואותו
 * נתיב מדלג על prereleases ומחזיר ריק. לכן מושכים את הרשימה ולוקחים
 * את הראשונה.
 */
object UpdateChecker {

    private const val RELEASES_URL =
        "https://api.github.com/repos/iluy12/AAA/releases?per_page=5"
    private const val ASSET_NAME = "app-debug.apk"

    data class Available(val buildNumber: Int, val downloadUrl: String)

    sealed class Result {
        object UpToDate : Result()
        data class Found(val update: Available) : Result()
        data class Failed(val reason: String) : Result()
    }

    fun check(activity: Activity, onResult: (Result) -> Unit) {
        Thread {
            val result = try {
                val newest = fetchNewest()
                when {
                    newest == null -> Result.Failed("לא נמצאה גרסה")
                    newest.buildNumber <= BuildConfig.VERSION_CODE -> Result.UpToDate
                    else -> Result.Found(newest)
                }
            } catch (e: Exception) {
                Result.Failed("שגיאה: ${e.javaClass.simpleName}")
            }
            activity.runOnUiThread { onResult(result) }
        }.start()
    }

    private fun fetchNewest(): Available? {
        val connection = (URL(RELEASES_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/vnd.github+json")
        }
        val body = try {
            if (connection.responseCode !in 200..299) return null
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }

        val releases = JSONArray(body)
        var best: Available? = null
        for (i in 0 until releases.length()) {
            val release = releases.optJSONObject(i) ?: continue
            val tag = release.optString("tag_name")
            val number = tag.removePrefix("build-").toIntOrNull() ?: continue

            val assets = release.optJSONArray("assets") ?: continue
            for (j in 0 until assets.length()) {
                val asset = assets.optJSONObject(j) ?: continue
                if (asset.optString("name") != ASSET_NAME) continue
                val url = asset.optString("browser_download_url")
                if (url.isBlank()) continue
                if (best == null || number > best!!.buildNumber) {
                    best = Available(number, url)
                }
            }
        }
        return best
    }

    /**
     * מוריד ומפעיל את מתקין החבילות. ההתקנה עצמה תמיד עוברת דרך מסך
     * האישור של אנדרואיד — אנחנו לא מתקינים בשקט, וזה נכון: זו פעולה
     * שהמשתמש צריך לראות.
     */
    fun downloadAndInstall(activity: Activity, update: Available, onStatus: (String) -> Unit) {
        if (!canInstall(activity)) {
            onStatus("צריך לאשר התקנה ממקור לא ידוע")
            openInstallPermission(activity)
            return
        }

        onStatus("מוריד…")
        Thread {
            val outcome = try {
                val file = download(activity, update)
                activity.runOnUiThread { launchInstaller(activity, file) }
                "פותח מתקין…"
            } catch (e: Exception) {
                "הורדה נכשלה: ${e.javaClass.simpleName}"
            }
            activity.runOnUiThread { onStatus(outcome) }
        }.start()
    }

    private fun download(activity: Activity, update: Available): File {
        val dir = File(activity.cacheDir, "updates").apply { mkdirs() }
        val file = File(dir, "iluy-${update.buildNumber}.apk")

        val connection = (URL(update.downloadUrl).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 20_000
            readTimeout = 60_000
        }
        try {
            connection.inputStream.use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }
        } finally {
            connection.disconnect()
        }
        return file
    }

    private fun launchInstaller(activity: Activity, file: File) {
        // מ-API 24 אסור למסור file:// לאפליקציה אחרת — צריך content://
        // דרך FileProvider, אחרת המתקין מקבל URI שהוא לא מורשה לקרוא.
        val uri = FileProvider.getUriForFile(
            activity, "${activity.packageName}.updates", file
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        activity.startActivity(intent)
    }

    private fun canInstall(activity: Activity): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            activity.packageManager.canRequestPackageInstalls()
        } else {
            true
        }

    private fun openInstallPermission(activity: Activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        try {
            activity.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${activity.packageName}")
                )
            )
        } catch (e: Exception) {
            try {
                activity.startActivity(Intent(Settings.ACTION_SETTINGS))
            } catch (ignored: Exception) {
                // אין מה לעשות — נשאר ההסבר על המסך
            }
        }
    }
}
