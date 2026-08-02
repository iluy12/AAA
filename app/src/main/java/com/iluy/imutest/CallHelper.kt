package com.iluy.imutest

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * RISK B ל-v1: מחייג לקו-הבדיקה שלך (DebugConfig.TEST_LINE_PHONE_NUMBER),
 * לא למרכזייה האמיתית (סעיף 12: "חיוג לקו-בדיקה שלך, לא למרכזייה
 * האמיתית"). כשעוברים לגרסת ייצור — להחליף למספר המרכזייה האמיתי.
 */
object CallHelper {

    private const val REQUEST_CALL_PERMISSION = 501

    fun startCall(activity: Activity, source: String) {
        EventLog.log(activity, "RISK_B_CALL", "source=$source;number=${DebugConfig.TEST_LINE_PHONE_NUMBER}")

        // ⚠️ **השיחה סוגרת את עץ ההסלמה.** זו התגובה החזקה ביותר שיש
        // למערכת, וברגע שהיא קרתה — האירוע הועבר לאדם. בלי האיפוס הזה
        // הרמה הייתה נשארת גבוהה עוד שעות והמערכת הייתה ממשיכה להיות
        // אגרסיבית **אחרי** שכבר טיפלו בו, כלומר בדיוק כשהיא הכי מיותרת.
        Escalation.clear(activity)
        FallReport.cancelPending(activity, "call_placed")

        val hasPermission = ContextCompat.checkSelfPermission(
            activity, Manifest.permission.CALL_PHONE
        ) == PackageManager.PERMISSION_GRANTED

        if (hasPermission) {
            placeCallDirectly(activity)
        } else {
            // אין הרשאה עדיין — פותחים חייגן עם המספר ממולא, המשתמש מאשר בעצמו.
            // גם מבקשים הרשאה לפעם הבאה. אם היא תתקבל — onPermissionResult
            // ישלים את השיחה בפועל, לא רק ישאיר את החייגן פתוח.
            ActivityCompat.requestPermissions(
                activity, arrayOf(Manifest.permission.CALL_PHONE), REQUEST_CALL_PERMISSION
            )
            openDialer(activity)
        }
    }

    /**
     * לקרוא מ-onRequestPermissionsResult של כל Activity שקורא ל-startCall
     * (RiskFlowActivity, HelpMenuActivity) — Android מחזיר את תוצאת ההרשאה
     * ל-Activity המבקש, לא ל-object הזה, אז זו נקודת-הכניסה היחידה האפשרית.
     */
    fun onPermissionResult(activity: Activity, requestCode: Int, grantResults: IntArray) {
        if (requestCode != REQUEST_CALL_PERMISSION) return
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            placeCallDirectly(activity)
        }
    }

    private fun placeCallDirectly(activity: Activity) {
        val intent = Intent(Intent.ACTION_CALL).apply {
            data = Uri.parse("tel:${DebugConfig.TEST_LINE_PHONE_NUMBER}")
        }
        try {
            activity.startActivity(intent)
        } catch (e: SecurityException) {
            openDialer(activity)
        }
    }

    private fun openDialer(activity: Activity) {
        val intent = Intent(Intent.ACTION_DIAL).apply {
            data = Uri.parse("tel:${DebugConfig.TEST_LINE_PHONE_NUMBER}")
        }
        activity.startActivity(intent)
    }
}
