package systems.dnull.ueswidget

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build

/** Local "+2 h" reminder for an inbox item turned into a task: scheduled by the notification action, fires here. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra(InboxNotifier.EXTRA_ID, -1)
        if (id < 0) return
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val title = intent.getStringExtra(InboxNotifier.EXTRA_RAW_TITLE) ?: ""
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Нагадування", NotificationManager.IMPORTANCE_HIGH))
        val tap = PendingIntent.getActivity(
            context,
            id,
            Intent(Intent.ACTION_VIEW, Uri.parse(Settings.dashboardUrl(context))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        nm.notify(
            ID_BASE + id,
            Notification.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setContentTitle("🔔 $title")
                .setContentText("Нагадування з вхідних")
                .setContentIntent(tap)
                .setAutoCancel(true)
                .build(),
        )
    }

    companion object {
        const val CHANNEL = "reminders"
        const val ID_BASE = 5000

        // shortcut: alarms don't survive a reboot; re-arm from BootReceiver if that bites.
        /** Inexact (no exact-alarm permission needed) wake-up at [atMillis]. */
        fun schedule(context: Context, id: Int, title: String, atMillis: Long) {
            val intent = Intent(context, ReminderReceiver::class.java)
                .putExtra(InboxNotifier.EXTRA_ID, id)
                .putExtra(InboxNotifier.EXTRA_RAW_TITLE, title)
            val pending = PendingIntent.getBroadcast(
                context, id, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            context.getSystemService(AlarmManager::class.java)
                .setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pending)
        }
    }
}
