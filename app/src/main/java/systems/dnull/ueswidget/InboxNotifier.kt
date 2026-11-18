package systems.dnull.ueswidget

import android.Manifest
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Heads-up notifications for freshly suggested inbox items: the owner dictates a note on his watch,
 * the server attaches a suggestion within ~2 min, and the next refresh pops the notification.
 */
object InboxNotifier {
    const val CHANNEL = "inbox"
    const val ID_BASE = 1000
    const val EXTRA_ID = "item_id"
    const val EXTRA_ACTION = "item_action"
    const val EXTRA_TITLE = "item_title"
    const val EXTRA_RAW_TITLE = "item_raw_title"
    const val EXTRA_SPLIT_BODY = "item_split_body"
    const val EXTRA_ACTIONS = "item_actions"

    // Reuses the Settings prefs file, under its own keys.
    private const val PREFS = "ues_widget"
    private const val KEY_NOTIFIED = "inbox_notified"

    val DEFAULT_ACTIONS = listOf(
        "⏰ +2 год" to "today",
        "✓ Створити" to "convert",
        "🗑" to "trash",
    )

    /** Posts a notification for every fresh suggested item and remembers ids so each is notified once. */
    fun notifyNew(context: Context, inbox: InboxResult?) {
        if (inbox == null) return
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val notified = (prefs.getStringSet(KEY_NOTIFIED, emptySet()) ?: emptySet())
            .mapNotNull { it.toIntOrNull() }
            .toMutableSet()
        for (item in freshSuggestions(inbox.items, notified)) {
            post(context, item, null)
            notified += item.id
        }
        // Prune ids no longer in the inbox: a swipe-dismissed item (which stays in the inbox) is never
        // re-notified, while an id that actually left can be reused later.
        val live = inbox.items.mapTo(HashSet()) { it.id }
        prefs.edit()
            .putStringSet(KEY_NOTIFIED, notified.filterTo(HashSet()) { it in live }.mapTo(HashSet()) { it.toString() })
            .apply()
    }

    /** Dismisses the notification after its action succeeded. */
    fun cancel(context: Context, id: Int) {
        context.getSystemService(NotificationManager::class.java).cancel(ID_BASE + id)
    }

    /** Re-posts a notification whose action failed, keeping the buttons. */
    fun postError(context: Context, id: Int, title: String, rawTitle: String, error: String, actions: List<Pair<String, String>>, splitBody: String?) {
        post(context, id, title, "Помилка: $error, спробуй ще", rawTitle, actions, splitBody)
    }

    private fun post(context: Context, item: InboxItem, error: String?) {
        val text = error?.let { "Помилка: $it, спробуй ще" } ?: suggestionText(item)
        val splitBody = item.suggestion?.takeIf { it.kind == "multi" && it.items.size >= 2 }
            ?.let { splitBodyJson(it.items) }
        post(
            context,
            item.id,
            suggestionTitle(item),
            text,
            item.suggestion?.title?.ifBlank { null } ?: item.rawText,
            suggestionActions(item),
            splitBody,
        )
    }

    private fun post(
        context: Context,
        id: Int,
        title: String,
        text: String,
        rawTitle: String,
        actions: List<Pair<String, String>>,
        splitBody: String? = null,
    ) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Вхідні: AI-розбір", NotificationManager.IMPORTANCE_HIGH))
        nm.notify(ID_BASE + id, build(context, id, title, text, rawTitle, actions, splitBody))
    }

    private fun build(
        context: Context,
        id: Int,
        title: String,
        text: String,
        rawTitle: String,
        actions: List<Pair<String, String>>,
        splitBody: String?,
    ): Notification {
        val tap = PendingIntent.getActivity(
            context,
            id,
            Intent(Intent.ACTION_VIEW, Uri.parse(Settings.dashboardUrl(context))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(tap)
        val encoded = ArrayList(actions.map { (label, action) -> "$action\t$label" })
        actions.forEachIndexed { i, (label, action) ->
            builder.addAction(
                Notification.Action.Builder(
                    null,
                    label,
                    actionPending(context, id, title, rawTitle, action, i, splitBody, encoded),
                ).build(),
            )
        }
        return builder.build()
    }

    private fun actionPending(
        context: Context,
        id: Int,
        title: String,
        rawTitle: String,
        action: String,
        index: Int,
        splitBody: String?,
        actions: ArrayList<String>,
    ): PendingIntent {
        val intent = Intent(context, InboxActionReceiver::class.java)
        intent.action = "systems.dnull.ueswidget.INBOX_ACTION"
        intent.putExtra(EXTRA_ID, id)
        intent.putExtra(EXTRA_ACTION, action)
        intent.putExtra(EXTRA_TITLE, title)
        intent.putExtra(EXTRA_RAW_TITLE, rawTitle)
        if (splitBody != null) intent.putExtra(EXTRA_SPLIT_BODY, splitBody)
        intent.putStringArrayListExtra(EXTRA_ACTIONS, actions) // so a failed action re-posts the same buttons
        return PendingIntent.getBroadcast(
            context,
            id * 10 + index,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}

/** Runs the tapped inbox action off the main thread, then cancels the notification and refreshes the widget. */
class InboxActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra(InboxNotifier.EXTRA_ID, -1)
        val action = intent.getStringExtra(InboxNotifier.EXTRA_ACTION) ?: return
        val title = intent.getStringExtra(InboxNotifier.EXTRA_TITLE) ?: ""
        val rawTitle = intent.getStringExtra(InboxNotifier.EXTRA_RAW_TITLE) ?: title
        val splitBody = intent.getStringExtra(InboxNotifier.EXTRA_SPLIT_BODY)
        val actions = intent.getStringArrayListExtra(InboxNotifier.EXTRA_ACTIONS)
            ?.map { it.substringAfter('\t') to it.substringBefore('\t') } ?: InboxNotifier.DEFAULT_ACTIONS
        if (id < 0) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val err = try {
                    when (action) {
                        "today" -> {
                            val (date, at) = todayReminderSlot(LocalDateTime.now())
                            inboxAction(Settings.baseUrl(context), Settings.apiKey(context), id, "convert", """{"scheduled_date":"$date"}""")
                            ReminderReceiver.schedule(context, id, rawTitle, at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
                        }
                        "split" -> inboxAction(
                            Settings.baseUrl(context),
                            Settings.apiKey(context),
                            id,
                            "split",
                            splitBody ?: """{"items":[]}""",
                        )
                        else -> inboxAction(Settings.baseUrl(context), Settings.apiKey(context), id, action)
                    }
                    null
                } catch (e: Exception) {
                    e.message ?: e.javaClass.simpleName
                }
                if (err == null) {
                    InboxNotifier.cancel(context, id)
                    refreshAll(context) // the inbox count changed
                } else {
                    InboxNotifier.postError(context, id, title, rawTitle, err, actions, splitBody)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
