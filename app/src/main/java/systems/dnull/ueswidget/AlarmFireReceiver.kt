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
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.util.Log

/** Fires a local alarm notification for a task with phone alarm enabled. */
class AlarmFireReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(EXTRA_ID) ?: return
        val title = intent.getStringExtra(EXTRA_TITLE) ?: ""
        val start = intent.getStringExtra(EXTRA_START) ?: ""
        val kind = intent.getStringExtra(EXTRA_KIND) ?: "at"
        notify(context, id, start, title, kind)
    }

    companion object {
        const val CHANNEL = "alarms"
        const val EXTRA_ID = "alarm_id"
        const val EXTRA_TITLE = "alarm_title"
        const val EXTRA_START = "alarm_start"
        const val EXTRA_KIND = "alarm_kind"

        fun notify(context: Context, id: String, start: String, title: String, kind: String) {
            if (Build.VERSION.SDK_INT >= 33 &&
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) return
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                val alarmSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                val audioAttrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                val channel = NotificationChannel(CHANNEL, "UES alarms", NotificationManager.IMPORTANCE_HIGH).apply {
                    setSound(alarmSound, audioAttrs)
                    enableVibration(true)
                }
                nm.createNotificationChannel(channel)
            }
            val tap = PendingIntent.getActivity(
                context,
                id.hashCode(),
                Intent(Intent.ACTION_VIEW, Uri.parse(Settings.dashboardUrl(context)))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            nm.notify(
                id.hashCode(),
                Notification.Builder(context, CHANNEL)
                    .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                    .setCategory(Notification.CATEGORY_ALARM)
                    .setContentTitle(alarmNotificationTitle(start, title, kind))
                    .setContentIntent(tap)
                    .setAutoCancel(true)
                    .build(),
            )
        }
    }
}

object AlarmScheduler {
    private const val TAG = "UesAlarms"
    private const val PREFS = "ues_widget"
    private const val KEY_SCHEDULED = "alarms_scheduled"
    private const val KEY_STORED_JSON = "alarms_stored_json"

    fun sync(context: Context, fresh: List<Alarm>, nowS: Long = System.currentTimeMillis() / 1000L) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val scheduled = (prefs.getStringSet(KEY_SCHEDULED, emptySet()) ?: emptySet()).toMutableSet()
        val diff = diffAlarms(scheduled, fresh, nowS)

        for (id in diff.toCancel) {
            cancelAlarm(context, id)
            scheduled.remove(id)
        }

        for (alarm in diff.toSchedule) {
            if (alarm.fireAt <= nowS) {
                AlarmFireReceiver.notify(context, alarm.id, alarm.start, alarm.title, alarm.kind)
            } else {
                scheduleAlarm(context, alarm)
            }
            scheduled.add(alarm.id)
        }

        prefs.edit()
            .putStringSet(KEY_SCHEDULED, scheduled)
            .putString(KEY_STORED_JSON, serializeAlarms(fresh))
            .apply()
    }

    fun rescheduleStored(context: Context, nowS: Long = System.currentTimeMillis() / 1000L) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_STORED_JSON, null) ?: return
        val stored = try {
            parseAlarms(json)
        } catch (e: Exception) {
            Log.w(TAG, "failed to parse stored alarms", e)
            return
        }
        val active = stored.filter { it.fireAt > nowS }
        for (alarm in active) {
            scheduleAlarm(context, alarm)
        }
        prefs.edit()
            .putStringSet(KEY_SCHEDULED, active.mapTo(HashSet()) { it.id })
            .apply()
    }

    private fun scheduleAlarm(context: Context, alarm: Alarm) {
        val am = context.getSystemService(AlarmManager::class.java)
        val pending = pendingFor(context, alarm)
        val fireAtMillis = alarm.fireAt * 1000L
        if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
            Log.w(TAG, "canScheduleExactAlarms() is false; falling back to setAndAllowWhileIdle for ${alarm.id}")
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireAtMillis, pending)
            return
        }
        val showIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(Intent.ACTION_VIEW, Uri.parse(Settings.dashboardUrl(context)))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        am.setAlarmClock(AlarmManager.AlarmClockInfo(fireAtMillis, showIntent), pending)
    }

    private fun cancelAlarm(context: Context, id: String) {
        val intent = Intent(context, AlarmFireReceiver::class.java)
        val pending = PendingIntent.getBroadcast(
            context,
            id.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE,
        ) ?: return
        context.getSystemService(AlarmManager::class.java).cancel(pending)
        pending.cancel()
    }

    private fun pendingFor(context: Context, alarm: Alarm): PendingIntent {
        val intent = Intent(context, AlarmFireReceiver::class.java)
            .putExtra(AlarmFireReceiver.EXTRA_ID, alarm.id)
            .putExtra(AlarmFireReceiver.EXTRA_TITLE, alarm.title)
            .putExtra(AlarmFireReceiver.EXTRA_START, alarm.start)
            .putExtra(AlarmFireReceiver.EXTRA_KIND, alarm.kind)
        return PendingIntent.getBroadcast(
            context,
            alarm.id.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
