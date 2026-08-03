package com.iluy.imutest

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.VideoView
import java.io.File

/**
 * סרטון חיזוק — **אחד בכל פעם, לא מאגר**.
 *
 * ## ⚠️ למה אין רשימה
 *
 * נבו שאל איך לשלב סרטונים בלי להכביד. התשובה היא שהעומס אינו במשקל
 * הקבצים אלא **בבחירה**: רשימה לגלול בה דורשת להשוות, להחליט, ולהתחרט —
 * וזה בדיוק החיכוך שגורם לסגור את המסך ברגע קשה.
 *
 * לכן מגיע **סרטון אחד**, נבחר מראש, וכפתור אחד "עוד אחד" למי שזה לא
 * התחבר לו. אותה לוגיקה בדיוק שהובילה לכפתור "אישור" גדול אחד במסך
 * דיווח הנפילה.
 *
 * ## מאיפה הקבצים
 *
 * מתיקייה מקומית בלבד — `filesDir/videos`. אין הורדה ואין רשת: המכשיר
 * הזה נמצא בידיים של מי שהמידע עליו הכי רגיש, וסרטון שנמשך מהאינטרנט
 * מייצר תעבורה שאפשר לצפות בה מבחוץ.
 *
 * ⚠️ **התיקייה ריקה עד שיוכנסו לתוכה קבצים.** מסך ריק שמתחזה לתקין הוא
 * הדבר הגרוע ביותר כאן — משתמש שלוחץ "כן" ומקבל מסך שחור מפסיק לבטוח
 * במערכת. לכן המצב הריק אומר את האמת במפורש.
 */
class VideoActivity : Activity() {

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        KeyLog.record(this, "video", event)
        return super.dispatchKeyEvent(event)
    }

    companion object {
        private const val EXTRA_SOURCE = "source"
        private const val DIR = "videos"

        fun intentFor(context: Context, source: String): Intent =
            Intent(context, VideoActivity::class.java)
                .putExtra(EXTRA_SOURCE, source)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        fun launch(context: Context, source: String) =
            context.startActivity(intentFor(context, source))

        fun dir(context: Context): File =
            File(context.filesDir, DIR).apply { if (!exists()) mkdirs() }

        fun all(context: Context): List<File> =
            dir(context).listFiles()
                ?.filter { it.isFile && it.length() > 0 }
                ?.sortedBy { it.name }
                ?: emptyList()
    }

    private var source = "לא ידוע"
    private var shown = 0
    private lateinit var root: FrameLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AlertHelper.wakeScreen(this)
        Immersive.apply(this)
        source = intent.getStringExtra(EXTRA_SOURCE) ?: "לא ידוע"

        val files = all(this)
        EventLog.log(this, "VIDEO", "opened;source=$source;available=${files.size}")

        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        setContentView(root)

        if (files.isEmpty()) showEmpty() else showVideo(files, 0)
    }

    /**
     * ⚠️ אומר את האמת ולא מתחזה. ראו הערת-המחלקה.
     */
    private fun showEmpty() {
        root.removeAllViews()
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(28, 28, 28, 28)
            addView(TextView(this@VideoActivity).apply {
                text = "עוד אין כאן סרטונים"
                textSize = 17f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                setPadding(0, 0, 0, 20)
            })
            addView(button("סגור", "#3A3A3A") { finish() })
        })
    }

    private fun showVideo(files: List<File>, index: Int) {
        shown = index
        val file = files[index % files.size]
        EventLog.log(this, "VIDEO", "playing;name=${file.name};index=$index")

        root.removeAllViews()

        val view = VideoView(this).apply {
            setVideoPath(file.absolutePath)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            ).apply { gravity = Gravity.CENTER }
            setOnCompletionListener {
                EventLog.log(this@VideoActivity, "VIDEO", "finished;name=${file.name}")
                showAfter(files, index)
            }
            setOnErrorListener { _, what, _ ->
                EventLog.log(this@VideoActivity, "VIDEO", "error;what=$what;name=${file.name}")
                showEmpty()
                true
            }
        }
        root.addView(view)
        view.start()

        // ✕ קטן לסגירה בכל רגע. סרטון שאי-אפשר לעצור הוא כפייה.
        root.addView(TextView(this).apply {
            text = "✕"
            textSize = 20f
            setTextColor(Color.parseColor("#CCCCCC"))
            setPadding(22, 16, 22, 16)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.TOP or Gravity.START }
            setOnClickListener { finish() }
        })
    }

    /** אחרי שהסרטון נגמר: **עוד אחד**, או סוגרים. שתי אפשרויות, לא רשימה. */
    private fun showAfter(files: List<File>, index: Int) {
        root.removeAllViews()
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(28, 28, 28, 28)
            if (files.size > 1) {
                addView(button("עוד אחד", "#2E5E7D") { showVideo(files, index + 1) })
            }
            addView(button("סיימתי", "#2E7D5B") { finish() })
        })
    }

    private fun button(label: String, colour: String, onClick: () -> Unit): View =
        Button(this).apply {
            text = label
            textSize = 17f
            setPadding(0, 26, 0, 26)
            setBackgroundColor(Color.parseColor(colour))
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 10, 0, 10) }
            setOnClickListener { onClick() }
        }
}
