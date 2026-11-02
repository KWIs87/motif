package com.kirkisham.motif.ui.alarm

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * The four things Android 13+ needs before a Motif alarm can be heard and seen (0.12):
 * notifications (the foreground-service banner and the lock-screen full-screen intent are dropped
 * without it), exact alarms, the full-screen intent itself (Android 14+), and the battery exemption.
 */
data class AlarmPermissionStatus(
    val notifications: Boolean,
    val exactAlarms: Boolean,
    val fullScreen: Boolean,
    val battery: Boolean,
) {
    val allOk: Boolean get() = notifications && exactAlarms && fullScreen && battery

    companion object {
        fun read(context: Context): AlarmPermissionStatus {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            val notif = (Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
                nm.areNotificationsEnabled()
            return AlarmPermissionStatus(
                notifications = notif,
                exactAlarms = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms(),
                fullScreen = Build.VERSION.SDK_INT < 34 || nm.canUseFullScreenIntent(),
                battery = pm.isIgnoringBatteryOptimizations(context.packageName),
            )
        }

        /** True when the runtime POST_NOTIFICATIONS prompt still needs asking (Android 13+). */
        fun needsNotificationRequest(context: Context): Boolean =
            Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED

        fun openNotificationSettings(context: Context) {
            val i = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            runCatching { context.startActivity(i) }
        }

        fun openExactAlarmSettings(context: Context) {
            if (Build.VERSION.SDK_INT >= 31) runCatching { context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))) }
        }

        fun openFullScreenSettings(context: Context) {
            if (Build.VERSION.SDK_INT >= 34) runCatching { context.startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${context.packageName}"))) }
        }

        fun requestBatteryExemption(context: Context) {
            runCatching { context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))) }
                .onFailure { runCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } }
        }
    }
}
