package com.iluy.imutest

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * מסך-השעון של עילוי — גם רקע-השעון וגם משטח-הדיווח.
 *
 * ## למה זה החליף את זיהוי-התנועה
 *
 * חמישה סבבים של זיהוי מבוסס-תאוצה נכשלו, ולא בגלל כיוונון: ב-25Hz
 * (תקרת-החומרה) הקשה טבעית (15-17) והליכה (12-20) חופפות בנתונים עצמם,
 * וניעור מתנגש עם ניעור-מים אחרי נטילת ידיים. מגע במסך הוא נתון מדויק
 * ולא אות רועש — אין ספים לכייל, אין רעש להפריד ממנו, ואין מה למדוד
 * בשטח. ✕ או שצויר או שלא.
 *
 * ## למה דווקא כמסך-בית
 *
 * המשתמש לוחץ על הכפתור הצדדי (שכבר מחזיר למסך-הבית — נמצא בבדיקת
 * "קליקס"), המסך נדלק, וזה מה שמוצג. אפס צעדים של פתיחת אפליקציה.
 *
 * זה גם עונה על סעיף 10 במסמך ההקשר — "שום דבר על מסך השעון הראשי לא
 * חושף כלום" — טוב יותר מאפליקציה שצריך לפתוח: מי שמסתכל רואה שעון.
 *
 * ## אמינות
 *
 * מסך-בית הוא הדבר הראשון שנטען תמיד; אם הוא קורס, המכשיר קשה לשימוש.
 * לכן אין כאן חיישנים, אין שירותים, ואין תלות בשום דבר שעלול ליפול —
 * רק שעון ומאזין-מגע.
 */
class WatchFaceActivity : Activity() {

    private lateinit var timeText: TextView
    private lateinit var dateText: TextView
    private lateinit var feedbackText: TextView

    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val dateFormat = SimpleDateFormat("EEEE, d בMMMM", Locale("he"))

    private val uiHandler = Handler(Looper.getMainLooper())

    // --- מצב מחוות ה-✕ ---
    private var downX = 0f
    private var downY = 0f
    private var lastStrokeDirection = 0 // 1 = "\", -1 = "/", 0 = אין עדיין
    private var lastStrokeMs = 0L

    /** ACTION_TIME_TICK נורה פעם בדקה — זול בהרבה מטיימר משלנו. */
    private val timeTickReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = updateClock()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        blendStatusBar()
        setContentView(buildLayout())
        updateClock()
    }

    /**
     * צובע את שורת-הסטטוס בשחור במקום להסתיר אותה.
     *
     * ⚠️ היה כאן FLAG_FULLSCREEN, וזו הייתה טעות חמורה: הסתרת השורה חוסמת
     * גם את משיכת-ההתראות מלמעלה. יחד עם העובדה שמסך-בית מחליף את
     * הלאנצ'ר של היצרן (ואיתו החלקה-שמאלה לתפריט), המשתמש נשאר נעול
     * במסך אחד בלי גישה להגדרות ובלי יכולת להסיר את האפליקציה.
     *
     * צביעה בשחור פותרת את הפס האפור בלי לקחת שום יכולת מהמערכת.
     */
    private fun blendStatusBar() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return

        // statusBarColor נכנס לתוקף רק כשהדגל הזה מוגדר ו-TRANSLUCENT
        // כבוי. בלעדיו הצבע נקבע והמערכת פשוט מתעלמת ממנו — בדיוק מה
        // שקרה, ולכן הפס נשאר אפור.
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        @Suppress("DEPRECATION")
        window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)

        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK

        // ערכת-הנושא בהירה, ולכן המערכת עלולה לצייר אייקונים כהים —
        // בלתי-נראים על שחור. מכבים את דגל-הבהיר כדי שיישארו לבנים.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                window.decorView.systemUiVisibility and
                    View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
        }
    }

    private fun buildLayout(): View {
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            isClickable = true
        }

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        timeText = TextView(this).apply {
            textSize = 44f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        dateText = TextView(this).apply {
            textSize = 13f
            setTextColor(ContextCompat.getColor(context, R.color.text_tertiary))
            gravity = Gravity.CENTER
            setPadding(0, 6, 0, 0)
        }
        feedbackText = TextView(this).apply {
            textSize = 14f
            setTextColor(ContextCompat.getColor(context, R.color.emerald_accent))
            gravity = Gravity.CENTER
            setPadding(0, 18, 0, 0)
            visibility = View.INVISIBLE
        }

        column.addView(timeText)
        column.addView(dateText)
        column.addView(feedbackText)
        root.addView(column)

        // דרך-מוצא לתפריט. חובה, לא נוחות: מסך-בית מחליף את הלאנצ'ר של
        // היצרן ואיתו כל דרכי-הניווט שלו, אז בלי זה אין גישה להגדרות,
        // אין דרך להסיר את האפליקציה, והמכשיר בפועל נעול על מסך אחד.
        // דיסקרטי בכוונה — מי שמסתכל רואה שעון.
        val menuDot = TextView(this).apply {
            text = "⋯"
            textSize = 18f
            setTextColor(ContextCompat.getColor(context, R.color.text_tertiary))
            gravity = Gravity.CENTER
            setPadding(24, 8, 24, 8)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            )
            setOnClickListener { openMenu() }
        }
        root.addView(menuDot)

        // אין כאן מאזין-מגע על השורש בכוונה — הוא היה צורך את האירועים
        // ומבטל את מחוות המערכת. ה-✕ מזוהה ב-dispatchTouchEvent, שרק
        // מסתכל ומעביר הלאה.
        return root
    }

    private fun openMenu() {
        EventLog.log(this, "INFO", "watch_face_menu_opened")
        startActivity(Intent(this, MainActivity::class.java))
    }

    // ---------- מחוות ✕ ----------

    /**
     * ✕ = שני קווים אלכסוניים בכיוונים מנוגדים, בתוך חלון-זמן קצר.
     * לא נדרש שיצטלבו בפועל — צירוף כזה לא קורה בטעות.
     *
     * ## למה dispatchTouchEvent ולא OnTouchListener
     *
     * כאן רק **מסתכלים** על האירועים ומעבירים אותם הלאה כרגיל. מאזין-מגע
     * רגיל היה צורך אותם, וכל מה שאנדרואיד עושה עם החלקות היה נעלם —
     * וזו בדיוק הטעות שכבר עשינו פעם אחת כשהחלפנו את הלאנצ'ר בלי
     * להחליף את מה שהוא סיפק. מחוות המערכת נשארות שלה; אנחנו רק
     * מזהים ✕ שנצייר מעליהן.
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        trackXGesture(ev)
        return super.dispatchTouchEvent(ev)
    }

    private fun trackXGesture(event: MotionEvent) {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                downY = event.rawY
            }
            MotionEvent.ACTION_UP -> {
                val dx = event.rawX - downX
                val dy = event.rawY - downY
                val length = Math.hypot(dx.toDouble(), dy.toDouble())

                val root = window.decorView
                val minSide = Math.min(root.width, root.height)
                if (minSide <= 0) return
                if (length < minSide * DebugConfig.X_GESTURE_MIN_STROKE_FRACTION) return

                val shorterAxis = Math.min(Math.abs(dx), Math.abs(dy))
                if (shorterAxis < length * DebugConfig.X_GESTURE_MIN_DIAGONAL_RATIO) {
                    handleSwipe(dx, dy, root.width, root.height)
                    return
                }

                val direction = if (dx * dy > 0) 1 else -1
                val now = System.currentTimeMillis()

                val isSecondStroke = lastStrokeDirection != 0 &&
                    direction != lastStrokeDirection &&
                    now - lastStrokeMs <= DebugConfig.X_GESTURE_MAX_INTERVAL_MS

                if (isSecondStroke) {
                    lastStrokeDirection = 0
                    lastStrokeMs = 0L
                    onXDrawn()
                } else {
                    lastStrokeDirection = direction
                    lastStrokeMs = now
                }
            }
        }
    }

    /**
     * מפת-המחוות של מסך-הבית, כפי שהוגדרה מול ההתנהגות שהייתה בלאנצ'ר
     * המקורי:
     *
     *   ימין→שמאל      כל האפליקציות
     *   שמאל→ימין      תפריט עילוי
     *   מלמעלה-ימין ↓  התראות
     *   מלמעלה-שמאל ↓  לוח הגדרות מהיר
     *   מלמטה-ימין ↑   אפליקציות פתוחות
     *   מלמטה-שמאל ↑   לוח-שנה ומידע
     *
     * חשוב: אלה לא "ברירות מחדל של אנדרואיד" שנעלמו — לאנדרואיד אין
     * התנהגות להחלקה אופקית על מסך-הבית. כולן היו פיצ'רים של הלאנצ'ר
     * של היצרן, ולכן רק מי שמחליף אותו יכול לספק אותן.
     */
    private fun handleSwipe(dx: Float, dy: Float, width: Int, height: Int) {
        val fromRight = downX > width / 2f
        val horizontal = Math.abs(dx) > Math.abs(dy)

        if (horizontal) {
            if (dx < 0) AppDrawerActivity.launch(this) else openMenu()
            return
        }

        if (dy > 0 && downY < height * 0.3f) {
            if (fromRight) systemPanel("expandNotificationsPanel")
            else systemPanel("expandSettingsPanel")
            return
        }

        if (dy < 0 && downY > height * 0.7f) {
            if (fromRight) toggleRecentApps() else InfoActivity.launch(this)
        }
    }

    /**
     * פתיחת פאנלים של המערכת. אין ל-SDK דרך רשמית, ולכן רפלקציה על
     * StatusBarManager עם הרשאת EXPAND_STATUS_BAR (הרשאה רגילה).
     * באנדרואיד 8.1 גישה ל-API מוסתר עדיין לא חסומה, ולכן זה אמור
     * לעבוד כאן — אבל זו הסתמכות על פנימיות, ולכן כישלון נרשם ולא קורס.
     */
    private fun systemPanel(method: String) {
        try {
            val service = getSystemService("statusbar")
            Class.forName("android.app.StatusBarManager")
                .getMethod(method)
                .invoke(service)
        } catch (e: Exception) {
            EventLog.log(this, "DEBUG", "system_panel_unavailable;method=$method")
        }
    }

    /**
     * מסך האפליקציות-הפתוחות. אין לזה API ציבורי כלל, גם לא מוסתר
     * ב-StatusBarManager — צריך לפנות ישירות ל-IStatusBarService.
     * זו המחווה היחידה מהשש שאני לא יכול להבטיח שתעבוד.
     */
    private fun toggleRecentApps() {
        try {
            val serviceManager = Class.forName("android.os.ServiceManager")
            val binder = serviceManager
                .getMethod("getService", String::class.java)
                .invoke(null, "statusbar")
            val stub = Class.forName("com.android.internal.statusbar.IStatusBarService\$Stub")
            val service = stub
                .getMethod("asInterface", Class.forName("android.os.IBinder"))
                .invoke(null, binder)
            service?.javaClass?.getMethod("toggleRecentApps")?.invoke(service)
        } catch (e: Exception) {
            EventLog.log(this, "DEBUG", "recent_apps_unavailable")
        }
    }

    private fun onXDrawn() {
        val outcome = OvercomingReporter.record(
            this, source = "✕ על מסך השעון", launchUi = false
        )

        when (outcome) {
            OvercomingReporter.Outcome.ESCALATED -> {
                // דיווח שני באותה שעה — כאן כן פותחים מסך, זו כל המטרה
                showFeedback("נשמר")
                RiskFlowActivity.launch(
                    this,
                    source = "✕ על מסך השעון",
                    variant = RiskFlowActivity.VARIANT_SECOND_TAP_IN_HOUR
                )
            }
            OvercomingReporter.Outcome.ACKNOWLEDGED -> showFeedback("נשמר")
            OvercomingReporter.Outcome.IGNORED_COOLDOWN -> showFeedback("נשמר")
        }
    }

    /**
     * משוב על המסך עצמו, בלי להשיק מסך נוסף ובלי צליל — המשתמש כבר
     * מסתכל, ושקט הוא חלק מהדיסקרטיות.
     */
    private fun showFeedback(text: String) {
        feedbackText.text = text
        feedbackText.visibility = View.VISIBLE
        uiHandler.postDelayed({ feedbackText.visibility = View.INVISIBLE }, 2_000L)
    }

    // ---------- שעון ----------

    private fun updateClock() {
        val now = Date()
        timeText.text = timeFormat.format(now)
        dateText.text = dateFormat.format(now)
    }

    override fun onResume() {
        super.onResume()
        registerReceiver(timeTickReceiver, IntentFilter(Intent.ACTION_TIME_TICK))
        updateClock()
        // איפוס מצב-המחווה: קו בודד שנשאר מפעם קודמת לא יצטרף לקו חדש
        lastStrokeDirection = 0
        lastStrokeMs = 0L
    }

    override fun onPause() {
        try {
            unregisterReceiver(timeTickReceiver)
        } catch (e: IllegalArgumentException) {
            // לא היה רשום — לא קריטי
        }
        super.onPause()
    }

    override fun onDestroy() {
        uiHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    /** מסך-בית לא אמור לצאת בחזרה לשום מקום. */
    override fun onBackPressed() { /* בכוונה ריק */ }
}
