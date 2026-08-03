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
import android.view.KeyEvent
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
    private lateinit var feedbackText: TextView
    private lateinit var bladeView: View
    private lateinit var hebDay: TextView
    private lateinit var hebMonth: TextView
    private lateinit var gregDay: TextView
    private lateinit var gregMonth: TextView

    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    /**
     * ⚠️ **יום וחודש בנפרד, ובלי שנה.** התצוגה היא מספר גדול עם שם החודש
     * קטן מתחתיו, ופירוק מחרוזת מוכנה בחזרה לחלקים הוא בדיוק המקום שבו
     * פורמט נשבר בגרסה הבאה.
     */
    private val gregDayFormat = SimpleDateFormat("d", Locale("he"))
    private val gregMonthFormat = SimpleDateFormat("MMMM", Locale("he"))

    private val uiHandler = Handler(Looper.getMainLooper())

    // --- מצב מחוות ה-✕ ---
    private var downX = 0f
    private var downY = 0f

    /**
     * הנקודה הרחוקה ביותר מנקודת הלחיצה **במהלך** המחווה, וכמה אירועי
     * תנועה בכלל הגיעו.
     *
     * ⚠️ **נולד מ-12 דחיות רצופות עם `len=0` ואפס התגברויות שנרשמו.**
     * אורך הקו נמדד מנקודת הלחיצה לנקודת ההרמה בלבד — ואם מסך המגע של
     * השעון הזה מוסר ב-UP את הקואורדינטות האחרונות **שדווחו**, ולא
     * דיווח אף תנועה באמצע, אז UP שווה ל-DOWN והאורך יוצא אפס תמיד.
     *
     * מדידה לפי הנקודה הרחוקה ביותר עובדת בכל אחד מהמצבים: אם יש אירועי
     * תנועה — נשתמש בהם; אם יש רק UP תקין — הוא עצמו הרחוק ביותר.
     *
     * `moveCount` נרשם ללוג כדי להכריע סופית: **אם הוא אפס, אין מחוות
     * על המכשיר הזה בכלל** — ואז כל ממשק שנשען על גרירה צריך להיזרק
     * ולהיבנות מחדש עם כפתורים.
     */
    private var farX = 0f
    private var farY = 0f
    private var farDist = 0.0
    private var moveCount = 0

    /** הנקודות עצמן. ראו את ההערה המקבילה ב-FallPickerActivity. */
    private val strokeTrace = StringBuilder()
    private var lastStrokeDirection = 0 // 1 = "\", -1 = "/", 0 = אין עדיין
    private var lastStrokeMs = 0L
    // נקודת-האמצע של הקו הקודם, לא נקודת-ההתחלה שלו — ראו ההסבר
    // ב-X_GESTURE_MAX_STROKE_DISTANCE_FRACTION.
    private var lastStrokeMidX = 0f
    private var lastStrokeMidY = 0f

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
    /**
     * הפס העליון מטופל כולו ב-WatchFaceTheme (שקוף, תוכן מצויר מתחתיו).
     * הדגלים בזמן-ריצה שהיו כאן קודם רק התנגשו איתו — הם ניסו לצבוע
     * שחור אטום בזמן שערכת-הנושא מבקשת שקיפות.
     */
    private fun blendStatusBar() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return
        window.addFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)
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

        // ⚠️ **density=1.0 על המכשיר הזה** (נמדד: view=368x448). כלומר
        // `textSize` ב-sp הוא פיקסלים ממש, והמסך צר — 44 היה גדול מדי
        // ביחס לרוחב 368 ברגע שהתאריך העברי מצטרף.
        timeText = TextView(this).apply {
            textSize = 54f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            // כסף מוברש: בהיר למעלה, כהה למטה. נצבע ב-onLayout כי לפני
            // המדידה אין גובה שאפשר למתוח עליו מעבר.
            includeFontPadding = false
        }
        // ⚠️ **שני תאריכים זה לצד זה, בגודל שונה בתוך כל אחד.** נבו:
        // *"היום העברי גדול, מתחתיו החודש בקטן. לידו הלועזי בגדול, רק
        // היום, ומתחתיו החודש."* בלי שנה — היא אף פעם לא השאלה שנשאלת
        // בהצצה על שעון.
        //
        // הזהב לעברי והכסף ללועזי אינם קישוט אלא **היררכיה**: העברי הוא
        // הראשי, הלועזי נספח.
        hebDay = bigDate("#C9A961")
        hebMonth = smallDate("#8A6E32")
        gregDay = bigDate("#C9A961")
        gregMonth = smallDate("#8A6E32")
        feedbackText = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.parseColor("#E4E2DC"))
            gravity = Gravity.CENTER
            setPadding(18, 0, 18, 0)
            visibility = View.INVISIBLE
        }

        column.addView(timeText)

        // קו זהב שנמוג בשני הקצוות. ⚠️ מצויר ולא תמונה — קובץ PNG על
        // מסך 368 היה מטושטש, וזה קו של פיקסל אחד.
        column.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(160, 1).apply { topMargin = 12 }
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(
                    Color.TRANSPARENT, Color.parseColor("#8A6E32"),
                    Color.parseColor("#C9A961"),
                    Color.parseColor("#8A6E32"), Color.TRANSPARENT
                )
            )
        })

        column.addView(dateRow())

        // ⚠️ **מקום שמור להודעות, גם כשאין הודעה.** בלי זה השעה קופצת
        // למעלה ולמטה בכל פעם שנאמר משהו, וזה מרגיש שבור. הגובה קבוע
        // והתוכן הוא שמתחלף — ראו showFeedback.
        column.addView(feedbackText, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 46
        ).apply { topMargin = 22 })

        // גרסה על מסך-השעון עצמו, לא רק במסך הראשי. נוסף אחרי שנתקעת
        // בלי דרך לדעת אם ההתקנה בכלל תפסה — שורת-גרסה שאפשר להגיע
        // אליה רק דרך מסך אחר לא עוזרת כשהמסך ההוא חסום.
        if (DebugConfig.DEBUG_TAG_ENABLED) {
            column.addView(TextView(this).apply {
                text = "${BuildConfig.VERSION_CODE}"
                textSize = 13f
                gravity = Gravity.CENTER
                setTextColor(ContextCompat.getColor(context, R.color.text_tertiary))
                setPadding(0, 14, 0, 0)
            })
        }

        root.addView(column)

        // ⚠️ **הלהב — מד המצב, ועיצוב הגיוני שמסתיר אותו.**
        //
        // פס מתכת דק לאורך הקצה. הוא נראה כמו קישוט של המסגרת, והוא
        // מציין את המצב הנוכחי: כסף כהה (נייח) → כסף (נייד) → זהב
        // (רמה א׳) → ענבר (רמה ב׳).
        //
        // ⚠️ **אין אדום באף מצב, וזה לא אסתטיקה.** טבעת אדומה על היד
        // צועקת "משהו רע קורה" — גם למי שעונד אותה, וגם לכל מי שמסתכל.
        // המוצר הזה עומד או נופל על כך שמי שמסתכל מהצד רואה שעון.
        bladeView = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                3, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.START
            )
        }
        root.addView(bladeView)

        // דרך-מוצא לתפריט. חובה, לא נוחות: מסך-בית מחליף את הלאנצ'ר של
        // היצרן ואיתו כל דרכי-הניווט שלו, אז בלי זה אין גישה להגדרות,
        // אין דרך להסיר את האפליקציה, והמכשיר בפועל נעול על מסך אחד.
        // דיסקרטי בכוונה — מי שמסתכל רואה שעון.
        // ⚠️ **☰ ולא ⋯, וגדול פי שניים.** נבו: *"בקושי רואים את זה.
        // הפונט בטלפון מאוד קטן."* והסיבה נמדדה — `density = 1.0`
        // על המכשיר הזה, כלומר כל גודל שנכתב בקוד הוא פיקסלים ממש.
        // 18 על מסך ברוחב 368 זה כתם.
        val menuDot = TextView(this).apply {
            text = "☰"
            textSize = 30f
            setTextColor(Color.parseColor("#8A6E32"))
            gravity = Gravity.CENTER
            setPadding(30, 10, 30, 10)
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
                farX = downX; farY = downY; farDist = 0.0
                moveCount = 0
                strokeTrace.setLength(0)
            }
            // ⚠️ **מחווה שנחטפה לא הותירה שום עקבות, וזה הסתיר את הבעיה.**
            //
            // כשמחוות-מערכת של השעון תופסת את התנועה — מגירת ההתראות
            // מלמעלה, ההגדרות מלמטה — אנדרואיד שולח ACTION_CANCEL ולא
            // ACTION_UP. הענף היחיד שכתב ללוג היה ACTION_UP, ולכן מחווה
            // חטופה נראתה **בדיוק כמו מסך שלא נגעו בו בכלל**.
            //
            // נבו תיאר את הסימפטום מדויק: "לצדדים זה היה תופס, למעלה
            // ולמטה זה היה גולל". הוא ייחס את זה ל-ScrollView שהיה בעיצוב
            // הישן — הוא כבר לא שם, ומחוות המערכת הן המועמד שנשאר.
            MotionEvent.ACTION_CANCEL -> {
                EventLog.log(
                    this, "DEBUG",
                    "x_cancelled;moves=$moveCount;far_len=${farDist.toInt()};" +
                        "from=${downX.toInt()},${downY.toInt()};" +
                        "trace=[${strokeTrace.toString().trim()}]"
                )
            }
            MotionEvent.ACTION_MOVE -> {
                moveCount++
                if (moveCount <= 10) {
                    strokeTrace.append("${event.rawX.toInt()},${event.rawY.toInt()} ")
                }
                val d = Math.hypot(
                    (event.rawX - downX).toDouble(), (event.rawY - downY).toDouble()
                )
                if (d > farDist) { farDist = d; farX = event.rawX; farY = event.rawY }
            }
            MotionEvent.ACTION_UP -> {
                // ⚠️ הנקודה הרחוקה ביותר, ולא נקודת ההרמה. ראו הערת-השדה.
                val upDist = Math.hypot(
                    (event.rawX - downX).toDouble(), (event.rawY - downY).toDouble()
                )
                val endX = if (upDist >= farDist) event.rawX else farX
                val endY = if (upDist >= farDist) event.rawY else farY

                val dx = endX - downX
                val dy = endY - downY
                val length = Math.hypot(dx.toDouble(), dy.toDouble())

                val root = window.decorView
                val minSide = Math.min(root.width, root.height)
                if (minSide <= 0) return

                // כל תנועה נרשמת, גם כזו שנדחתה. בלי זה אי-אפשר להבדיל
                // בין "המחווה לא זוהתה" לבין "האירוע בכלל לא הגיע אלינו"
                // כי המערכת חטפה אותו — ובדיוק זה ההבדל שצריך עכשיו.
                if (DebugConfig.DEBUG_TAG_ENABLED) {
                    EventLog.log(
                        this, "DEBUG",
                        "stroke;dx=${dx.toInt()};dy=${dy.toInt()};len=${length.toInt()};" +
                            "min_needed=${(minSide * DebugConfig.X_GESTURE_MIN_STROKE_FRACTION).toInt()};" +
                            "from=${downX.toInt()},${downY.toInt()};screen=${root.width}x${root.height}"
                    )
                }

                // כל דחייה נרשמת בשורת x_reject. שורות stroke; מסוננות
                // מהעלאת-הלוג, ולכן "ציירתי ✕ ולא קרה כלום" היה עד היום
                // דיווח שאי-אפשר לאבחן ממנו כלום.
                val minLength = minSide * DebugConfig.X_GESTURE_MIN_STROKE_FRACTION
                if (length < minLength) {
                    // ⚠️ `moves` הוא המספר שמכריע אם יש בכלל מחוות על
                    // המכשיר הזה. אפס = מסך המגע לא מדווח תנועה, וכל
                    // ממשק שנשען על גרירה חייב להיבנות מחדש.
                    // ⚠️ **נגיעה רגילה מגיעה לכאן גם היא, וזה הטעה אותי.**
                    // `dispatchTouchEvent` רואה כל מגע במסך, ולנגיעה אין
                    // אורך — כלומר כל לחיצה תמימה נרשמת בדיוק כמו ניסיון
                    // ✕ שנכשל. הסקתי מ-14 שורות כאלה שהמנגנון שבור, בזמן
                    // שרובן היו כנראה לחיצות רגילות.
                    //
                    // מכאן ואילך נרשם רק מה שהיה בו ניסיון תנועה אמיתי.
                    if (moveCount >= 3) {
                        logXReject(
                            "too_short",
                            "len=${length.toInt()};need=${minLength.toInt()};" +
                                "moves=$moveCount;up_len=${upDist.toInt()};" +
                                "far_len=${farDist.toInt()};" +
                                "from=${downX.toInt()},${downY.toInt()};" +
                                "trace=[${strokeTrace.toString().trim()}]"
                        )
                    }
                    return
                }

                val shorterAxis = Math.min(Math.abs(dx), Math.abs(dy))
                val minShorterAxis = length * DebugConfig.X_GESTURE_MIN_DIAGONAL_RATIO
                if (shorterAxis < minShorterAxis) {
                    // ⚠️ `from=` ו-`view=` נוספו אחרי שהתברר שהם הנתון
                    // המכריע. תשע המשיכות שהצליחו ב-3.8 נרשמו כאן **בלי**
                    // נקודת ההתחלה, ולכן אי-אפשר היה לדעת מהו האזור הבטוח —
                    // רק להסיק אותו מהיעדר כישלונות, וזו הסקה חלשה.
                    logXReject(
                        "not_diagonal",
                        "short=${shorterAxis.toInt()};need=${minShorterAxis.toInt()};" +
                            "len=${length.toInt()};from=${downX.toInt()},${downY.toInt()};" +
                            "view=${root.width}x${root.height}"
                    )
                    handleSwipe(dx, dy, root.width, root.height)
                    return
                }

                val direction = if (dx * dy > 0) 1 else -1
                val now = System.currentTimeMillis()

                // שני הקווים חייבים להיחתך **באותו אזור**. בלי זה, קו
                // אלכסוני בפינה אחת וקו נגדי בפינה אחרת, דקות אחר-כך,
                // נספרו כ-✕ — וזה מקור ההתגברויות שנרשמו בטעות.
                //
                // ⚠️ הבדיקה מודדת את **נקודות האמצע**. קודם נמדדו נקודות
                // ההתחלה, ובזה הייתה הטעות שהפילה איקסים אמיתיים: שני
                // הקווים מתחילים בפינות מנוגדות, כך שהמרחק ביניהן הוא
                // רוחב ה-✕ — וככל שצוירה מחווה גדולה ונקייה יותר, כך
                // גדל הסיכוי שתידחה. האמצעים, לעומת זאת, מצטלבים.
                val midX = (downX + endX) / 2f
                val midY = (downY + endY) / 2f
                val midDistance = Math.hypot(
                    (midX - lastStrokeMidX).toDouble(),
                    (midY - lastStrokeMidY).toDouble()
                )
                val maxDistance = minSide * DebugConfig.X_GESTURE_MAX_STROKE_DISTANCE_FRACTION
                val gapMs = now - lastStrokeMs

                val isSecondStroke = lastStrokeDirection != 0 &&
                    direction != lastStrokeDirection &&
                    gapMs <= DebugConfig.X_GESTURE_MAX_INTERVAL_MS &&
                    midDistance <= maxDistance

                if (isSecondStroke) {
                    lastStrokeDirection = 0
                    lastStrokeMs = 0L
                    onXDrawn()
                } else {
                    // קו ראשון תקין הוא לא דחייה — רק קו שני שלא נסגר.
                    if (lastStrokeDirection != 0) {
                        val reason = when {
                            direction == lastStrokeDirection -> "same_direction"
                            gapMs > DebugConfig.X_GESTURE_MAX_INTERVAL_MS -> "timeout"
                            else -> "too_far"
                        }
                        logXReject(
                            reason,
                            "gap_ms=$gapMs;mid_dist=${midDistance.toInt()};max=${maxDistance.toInt()}"
                        )
                    }
                    lastStrokeDirection = direction
                    lastStrokeMs = now
                    lastStrokeMidX = midX
                    lastStrokeMidY = midY
                }
            }
        }
    }

    /**
     * למה קו לא סגר ✕.
     *
     * שורה קצרה ונפרדת מ-stroke;, כי stroke; מסונן מהעלאת-הלוג בגלל
     * נפח — וכך "ציירתי ✕ ולא קרה כלום" הפך לדיווח בלתי-ניתן לאבחון.
     * כאן יש שורה אחת לכל דחייה, עם המספר שהכשיל אותה.
     */
    private fun logXReject(reason: String, detail: String) {
        if (!DebugConfig.DEBUG_TAG_ENABLED) return
        EventLog.log(this, "DEBUG", "x_reject;reason=$reason;$detail")
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

        val action = when {
            // הוחלף לפי התיקון שלך: החלקה מימין (ימין→שמאל, dx שלילי)
            // פותחת את **המסך הראשי**, לא את מגירת האפליקציות.
            horizontal && dx < 0 -> "menu"
            horizontal -> "apps"
            dy > 0 && downY < height * 0.3f && fromRight -> "notifications"
            dy > 0 && downY < height * 0.3f -> "quick_settings"
            dy < 0 && downY > height * 0.7f && fromRight -> "recents"
            dy < 0 && downY > height * 0.7f -> "info"
            // החלקה אנכית שלא התחילה קרוב מספיק לקצה. נרשמת בשמה כדי
            // שנדע שזה מה שקרה ולא נחפש את הבעיה במקום אחר.
            else -> "vertical_wrong_zone"
        }
        EventLog.log(this, "DEBUG", "swipe;action=$action")

        when (action) {
            "apps" -> AppDrawerActivity.launch(this)
            "menu" -> openMenu()
            "notifications" -> systemPanel("expandNotificationsPanel")
            "quick_settings" -> systemPanel("expandSettingsPanel")
            "recents" -> toggleRecentApps()
            "info" -> InfoActivity.launch(this)
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
        val source = "✕ על מסך השעון"
        val report = OvercomingReporter.record(this, source = source, launchUi = false)

        // החיזוק תמיד מוצג על מסך-השעון עצמו — גם כשמסלימים. מי שהתגבר
        // צריך לראות מילה טובה לפני שנפתח משהו אחר.
        showFeedback(report.message)

        when (report.outcome) {
            OvercomingReporter.Outcome.OFFER_MENTOR ->
                // בהשהיה, לא מיד: קודם הצגתי את החיזוק ופתחתי מסך מעליו
                // באותו רגע, אז הוא הבזיק ונעלם. מי שהתגבר צריך להספיק
                // לקרוא את המילה הטובה לפני שנפתח משהו אחר.
                uiHandler.postDelayed({
                    if (!isFinishing) {
                        RiskFlowActivity.launch(
                            this, source = source,
                            variant = RiskFlowActivity.VARIANT_SECOND_TAP_IN_HOUR
                        )
                    }
                }, DebugConfig.ENCOURAGEMENT_READ_MS)
            OvercomingReporter.Outcome.AT_PERSONAL_THRESHOLD,
            OvercomingReporter.Outcome.ACKNOWLEDGED,
            OvercomingReporter.Outcome.IGNORED_COOLDOWN -> {
                // ההודעה על המסך מספיקה — אין מה לפתוח
            }
        }
    }

    /**
     * משוב על המסך עצמו, בלי להשיק מסך נוסף ובלי צליל — המשתמש כבר
     * מסתכל, ושקט הוא חלק מהדיסקרטיות.
     */
    private fun showFeedback(text: String) {
        feedbackText.text = text
        feedbackText.visibility = View.VISIBLE
        // נשאר על המסך לפחות עד שמסך ההסלמה נפתח, אחרת נוצר רגע ריק
        // שבו החיזוק כבר נעלם ושום דבר עוד לא הופיע.
        uiHandler.postDelayed(
            { feedbackText.visibility = View.INVISIBLE },
            DebugConfig.ENCOURAGEMENT_READ_MS + 400L
        )
    }

    // ---------- שעון ----------

    private fun bigDate(colour: String) = TextView(this).apply {
        textSize = 25f
        setTextColor(Color.parseColor(colour))
        gravity = Gravity.CENTER
        includeFontPadding = false
    }

    private fun smallDate(colour: String) = TextView(this).apply {
        textSize = 11f
        setTextColor(Color.parseColor(colour))
        gravity = Gravity.CENTER
        letterSpacing = 0.05f
        includeFontPadding = false
        setPadding(0, 3, 0, 0)
    }

    /**
     * שתי עמודות תאריך עם קו זהב דק ביניהן.
     *
     * ⚠️ **כיוון RTL נקבע במפורש.** בלעדיו הסדר תלוי בהגדרת השפה של
     * המכשיר — ושעון שהתאריך העברי בו קופץ לצד השני אחרי שינוי הגדרה
     * הוא בדיוק סוג התקלה שאי-אפשר לשחזר.
     */
    private fun dateRow(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        layoutDirection = View.LAYOUT_DIRECTION_RTL
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = 12 }
        layoutParams = lp

        addView(dateColumn(hebDay, hebMonth))

        // מפריד: קו זהב אנכי שנמוג בקצוות
        addView(View(this@WatchFaceActivity).apply {
            layoutParams = LinearLayout.LayoutParams(1, 40).apply {
                leftMargin = 20; rightMargin = 20
            }
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(
                    Color.TRANSPARENT, Color.parseColor("#8A6E32"), Color.TRANSPARENT
                )
            )
        })

        addView(dateColumn(gregDay, gregMonth))
    }

    private fun dateColumn(big: TextView, small: TextView): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            // ⚠️ רוחב אחיד לשתיהן, אחרת "כ״ז" ו-"3" מזיזים את המפריד
            // ממרכז המסך בכל יום מחדש.
            layoutParams = LinearLayout.LayoutParams(74, LinearLayout.LayoutParams.WRAP_CONTENT)
            addView(big)
            addView(small)
        }

    private fun updateClock() {
        val now = Date()
        timeText.text = timeFormat.format(now)

        // ⚠️ **התאריך העברי הוא הראשי, והלועזי נספח אליו.** זה מה שנותן
        // למסך את האופי — בלעדיו זה שעון גנרי עם עוד שורת טקסט.
        val heb = HebrewDate.parts(now)
        hebDay.text = heb?.day ?: ""
        hebMonth.text = heb?.month ?: ""

        gregDay.text = gregDayFormat.format(now)
        gregMonth.text = gregMonthFormat.format(now)

        paintTimeMetal()
        paintBlade()
    }

    /**
     * כסף מוברש על טקסט השעה: בהיר למעלה, כהה למטה.
     *
     * ⚠️ **נצבע בכל עדכון ולא פעם אחת.** ה-shader נמתח על גובה הטקסט,
     * וגובה זה לא ידוע לפני שה-view נמדד. צביעה ב-onCreate הייתה נותנת
     * מעבר על גובה אפס — כלומר טקסט בצבע אחיד, בלי שום סימן לתקלה.
     */
    private fun paintTimeMetal() {
        val h = timeText.textSize
        if (h <= 0f) return
        timeText.paint.shader = android.graphics.LinearGradient(
            0f, 0f, 0f, h,
            intArrayOf(
                Color.parseColor("#FFFFFF"),
                Color.parseColor("#C6C6CC"),
                Color.parseColor("#7E7E86")
            ),
            floatArrayOf(0f, 0.5f, 1f),
            android.graphics.Shader.TileMode.CLAMP
        )
        timeText.invalidate()
    }

    /**
     * נייד או נייח, מהרשומה האחרונה שנאספה.
     *
     * ⚠️ **קורא את הנתון הקיים ולא רושם חיישן משלו.** מסך-השעון מצויר
     * כל דקה; רישום מאזין-תאוצה כאן היה מדליק חיישן שוב ושוב בשביל
     * קישוט, וזו בדיוק ההוצאה שהמוצר לא יכול להרשות לעצמו.
     *
     * אותו סף כמו בשער `moving` שבמנוע הציון — שתי דקות בלי צעד — כדי
     * שמה שהמשתמש רואה יתאר את מה שהמערכת באמת חושבת.
     */
    private fun isMoving(): Boolean {
        val last = SampleStore.recent(this, 1).lastOrNull() ?: return false
        return last.stillMs in 0 until 2 * 60 * 1000L
    }

    /** צובע את הלהב לפי המצב הנוכחי. ראו את ההערה ליד [bladeView]. */
    private fun paintBlade() {
        if (!::bladeView.isInitialized) return
        val level = Escalation.levelNow(this)
        val colours = when {
            level >= Escalation.Level.RISK_B ->
                intArrayOf(0xFFE0913C.toInt(), 0xFFF2C46A.toInt(), 0xFFE0913C.toInt())
            level >= Escalation.Level.RISK_A ->
                intArrayOf(0xFF8A6E32.toInt(), 0xFFF0D89B.toInt(), 0xFF8A6E32.toInt())
            isMoving() ->
                intArrayOf(0xFF6E6E76.toInt(), 0xFFA9A9B2.toInt(), 0xFF6E6E76.toInt())
            else ->
                intArrayOf(0xFF2A2A2E.toInt(), 0xFF4A4A50.toInt(), 0xFF2A2A2E.toInt())
        }
        bladeView.background = android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM, colours
        )
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

    /**
     * ⚠️ **לא צורך אף מקש.** הערך תמיד חוזר מ-`super`, כך שהתנהגות המכשיר
     * לא משתנה בשום צורה. זו אינה קפדנות-יתר: כבר קרה כאן ש-`FLAG_FULLSCREEN`
     * יחד עם החלפת הלאנצ'ר נעלו את נבו מחוץ למכשיר שלו, ובליעת מקש —
     * במיוחד מקש-הפעלה — היא בדיוק אותו סוג תקלה. תמיד להשאיר דרך יציאה.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        KeyLog.record(this, "watchface", event)
        return super.dispatchKeyEvent(event)
    }
}
