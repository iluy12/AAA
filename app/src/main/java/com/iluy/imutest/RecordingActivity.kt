package com.iluy.imutest

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.File

/**
 * ההקלטה שבה המשתמש סיפר לעצמו למה הוא לא רוצה את זה.
 *
 * ## למה זה הכלי החזק ביותר שיש כאן
 *
 * נבו ניסח את זה: *"הבאתי לך את ההקלטה בה סיפרת למה אתה לא רוצה את זה,
 * זה זמן טוב להיזכר בה עכשיו?"*
 *
 * ⚠️ **ההבדל מכל טקסט אחר הוא שזה הוא עצמו.** משפט חיזוק שאני כתבתי
 * מגיע מבחוץ, ואפשר לוותר עליו. הקול שלו, מרגע שהיה שקט ובחר, הוא
 * הדבר היחיד במוצר שהמשתמש לא יכול לפטור כ"מישהו לא מבין".
 *
 * ## הקלטה אחת בלבד
 *
 * ⚠️ **בכוונה, ולא מחוסר זמן.** רשימת הקלטות דורשת בחירה, ובחירה ברגע
 * קשה היא בדיוק החיכוך שגורם לסגור את המסך. אחת — מנגנים אותה ונגמר.
 * הקלטה חדשה דורסת את הקודמת, וזה נכון: מה שהוא אמר לעצמו לפני חודש
 * פחות רלוונטי ממה שאמר אתמול.
 */
class RecordingActivity : Activity() {

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        KeyLog.record(this, "recording", event)
        return super.dispatchKeyEvent(event)
    }

    companion object {
        private const val EXTRA_SOURCE = "source"
        private const val REQUEST_MIC = 601
        private const val FILE_NAME = "my_reason.3gp"

        fun intentFor(context: Context, source: String): Intent =
            Intent(context, RecordingActivity::class.java)
                .putExtra(EXTRA_SOURCE, source)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        fun launch(context: Context, source: String) =
            context.startActivity(intentFor(context, source))

        /**
         * ⚠️ **מקור אחד להקלטה, ולא שניים.** השאלון כבר מקליט ושומר את
         * הנתיב ב-[LocalStore], ואם המסך הזה היה כותב לקובץ משלו — היו
         * למשתמש שתי הקלטות שונות, ומה שהוא הקליט בשאלון לא היה מתנגן
         * כאן לעולם. זו בדיוק הצורה שבה שני מסלולים נפרדים ואף אחד לא
         * שם לב.
         */
        fun file(context: Context): File {
            val saved = LocalStore.getRecordingPath(context)
            if (!saved.isNullOrBlank()) {
                val f = File(saved)
                if (f.exists() && f.length() > 0) return f
            }
            return File(context.filesDir, FILE_NAME)
        }

        fun exists(context: Context) = file(context).let { it.exists() && it.length() > 0 }
    }

    private var source = "לא ידוע"
    private var player: MediaPlayer? = null
    private var recorder: MediaRecorder? = null
    private lateinit var root: LinearLayout
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AlertHelper.wakeScreen(this)
        Immersive.apply(this)
        source = intent.getStringExtra(EXTRA_SOURCE) ?: "לא ידוע"
        EventLog.log(this, "RECORDING", "opened;source=$source;exists=${exists(this)}")

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(28, 28, 28, 28)
            setBackgroundColor(Color.parseColor("#141414"))
        }
        status = TextView(this).apply {
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 24)
        }
        root.addView(status)
        setContentView(root)
        render()
    }

    private fun render() {
        while (root.childCount > 1) root.removeViewAt(1)

        if (exists(this)) {
            status.text = "ההקלטה שלך"
            root.addView(bigButton("השמע", "#2E7D5B") { play() })
            // ⚠️ קטן ומשני. הקלטה מחדש דורסת, וברגע קשה זו לא הפעולה
            // שצריכה להיות קלה.
            root.addView(smallButton("הקלט מחדש") { startRecording() })
        } else {
            status.text = "עוד לא הקלטת.\nספר לעצמך למה אתה לא רוצה את זה — " +
                "ותשמע את זה ברגע שתצטרך."
            root.addView(bigButton("הקלט עכשיו", "#2E5E7D") { startRecording() })
        }
        root.addView(smallButton("סגור") { finish() })
    }

    // ---------- ניגון ----------

    private fun play() {
        stopPlayer()
        runCatching {
            player = MediaPlayer().apply {
                setDataSource(file(this@RecordingActivity).absolutePath)
                prepare()
                setOnCompletionListener {
                    EventLog.log(this@RecordingActivity, "RECORDING", "played_to_end")
                    stopPlayer()
                    render()
                }
                start()
            }
        }.onFailure {
            EventLog.log(this, "RECORDING", "play_failed;${it.javaClass.simpleName}")
            status.text = "לא הצלחתי להשמיע"
            return
        }
        status.text = "מנגן…"
        while (root.childCount > 1) root.removeViewAt(1)
        root.addView(bigButton("עצור", "#8C3B34") { stopPlayer(); render() })
    }

    private fun stopPlayer() {
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
    }

    // ---------- הקלטה ----------

    private fun startRecording() {
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MIC
            )
            return
        }

        // ⚠️ **מקליטים לקובץ זמני ולא ישירות על הקיים.** הקלטה שנכשלת
        // באמצע הייתה מוחקת את מה שכבר היה — כלומר המשתמש מאבד את הקול
        // שלו מלפני חודש בגלל תקלה של שתי שניות.
        val temp = File(filesDir, "$FILE_NAME.tmp")
        runCatching {
            recorder = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
                setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
                setOutputFile(temp.absolutePath)
                prepare()
                start()
            }
        }.onFailure {
            EventLog.log(this, "RECORDING", "record_failed;${it.javaClass.simpleName}")
            status.text = "לא הצלחתי להקליט"
            return
        }

        EventLog.log(this, "RECORDING", "recording_started")
        status.text = "מקליט… דבר חופשי"
        while (root.childCount > 1) root.removeViewAt(1)
        root.addView(bigButton("סיים", "#8C3B34") { stopRecording(temp) })
    }

    private fun stopRecording(temp: File) {
        val ok = runCatching {
            recorder?.stop()
            recorder?.release()
        }.isSuccess
        recorder = null

        if (ok && temp.exists() && temp.length() > 0) {
            val target = File(filesDir, FILE_NAME)
            runCatching { target.delete() }
            runCatching { temp.renameTo(target) }
            // ⚠️ מעדכן את אותו מפתח שהשאלון כותב אליו, אחרת ההקלטה החדשה
            // לא הייתה נמצאת על ידי שום מסלול אחר.
            LocalStore.setRecordingPath(this, target.absolutePath)
            EventLog.log(this, "RECORDING", "saved;bytes=${target.length()}")
        } else {
            runCatching { temp.delete() }
            EventLog.log(this, "RECORDING", "record_discarded")
        }
        render()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        if (requestCode == REQUEST_MIC &&
            grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            startRecording()
        }
    }

    override fun onPause() {
        super.onPause()
        stopPlayer()
        runCatching { recorder?.release() }
        recorder = null
    }

    // ---------- כפתורים ----------

    private fun bigButton(label: String, colour: String, onClick: () -> Unit): View =
        Button(this).apply {
            text = label
            textSize = 19f
            setPadding(0, 30, 0, 30)
            setBackgroundColor(Color.parseColor(colour))
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 10, 0, 10) }
            setOnClickListener { onClick() }
        }

    private fun smallButton(label: String, onClick: () -> Unit): View =
        Button(this).apply {
            text = label
            textSize = 13f
            setBackgroundColor(Color.TRANSPARENT)
            setTextColor(Color.parseColor("#9A9A9A"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 6, 0, 6) }
            setOnClickListener { onClick() }
        }
}
