package systems.dnull.ueswidget

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val CHANNEL = "refresh"
private const val INTERVAL_MS = 60_000L

/**
 * Keeps the widget within a minute of the server: refreshes on screen-on and every minute while the screen is on,
 * nothing while it is off. Being a foreground service also keeps Android from deferring Glance's redraw job.
 */
class RefreshService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loop: Job? = null

    private val screen = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF) stopLoop() else startLoop()
        }
    }

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Оновлення віджета", NotificationManager.IMPORTANCE_MIN))
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentTitle("UES Widget")
            .setContentText("Оновлюю щохвилини, поки екран увімкнено")
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(1, n)
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        // System broadcasts still arrive with NOT_EXPORTED; the flag is mandatory from API 34.
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(screen, filter, RECEIVER_NOT_EXPORTED)
        else registerReceiver(screen, filter)
        if (getSystemService(PowerManager::class.java).isInteractive) startLoop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        unregisterReceiver(screen)
        scope.cancel()
        super.onDestroy()
    }

    private fun startLoop() {
        if (loop?.isActive == true) return
        loop = scope.launch {
            while (isActive) {
                try {
                    refreshAll(applicationContext)
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    Log.w(TAG, "refresh failed", e)
                }
                delay(INTERVAL_MS)
            }
        }
    }

    private fun stopLoop() {
        loop?.cancel()
        loop = null
    }

    companion object {
        private const val TAG = "UesRefresh"

        /** Start if any widget is placed; a background start Android refuses is retried by the next app open or widget tap. */
        fun start(context: Context) {
            val ids = AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, UesWidgetReceiver::class.java))
            if (ids.isEmpty()) return
            try {
                context.startForegroundService(Intent(context, RefreshService::class.java))
            } catch (e: IllegalStateException) {
                Log.w(TAG, "foreground start refused", e)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RefreshService::class.java))
        }
    }
}

/** Restarts the service after a reboot or an app update, both of which allow a foreground start. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = RefreshService.start(context)
}
