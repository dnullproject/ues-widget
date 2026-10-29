package systems.dnull.ueswidget

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextDecoration
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import androidx.compose.runtime.Composable
import androidx.glance.action.Action
import androidx.glance.layout.Alignment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext

// Dracula, same tokens as the dashboard (services/dashboard/src/lib/styles/theme.css).
private val BG = ColorProvider(Color(0xF0282A36))
private val FG = ColorProvider(Color(0xFFF8F8F2))
private val MUTED = ColorProvider(Color(0xFF6272A4))
private val PURPLE = ColorProvider(Color(0xFFBD93F9))
private val GREEN = ColorProvider(Color(0xFF50FA7B))
private val CYAN = ColorProvider(Color(0xFF8BE9FD))
private val RED = ColorProvider(Color(0xFFFF5555))
private val ORANGE = ColorProvider(Color(0xFFFFB86C))
private val YELLOW = ColorProvider(Color(0xFFF1FA8C))
private val PINK = ColorProvider(Color(0xFFFF79C6))

private val PAGE_ID = ActionParameters.Key<String>("page_id")
private val IS_DONE = ActionParameters.Key<Boolean>("is_done")

class UesWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = UesWidget()
}

class RefreshCallback : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        UesWidget().update(context, glanceId)
    }
}

/** Tap on a task: done <-> reopen, then redraw from the server's state. */
class ToggleDoneCallback : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val id = parameters[PAGE_ID] ?: return
        val err = withContext(Dispatchers.IO) {
            try {
                toggleDone(Settings.baseUrl(context), Settings.apiKey(context), id, parameters[IS_DONE] ?: false)
                null
            } catch (e: Exception) {
                e.message ?: e.javaClass.simpleName
            }
        }
        if (err != null) UesWidget.lastError = "Не відмітилось: $err".take(48)
        UesWidget().update(context, glanceId)
    }
}

class UesWidget : GlanceAppWidget() {
    companion object {
        // shortcut: in-memory only, lost if the process dies; fine for a one-shot hint.
        @Volatile var lastError: String? = null
    }

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val key = Settings.apiKey(context)
        val url = Settings.baseUrl(context)
        val dashboard = Settings.dashboardUrl(context)
        val (result, vitals) = if (key.isBlank()) null to Vitals() else withContext(Dispatchers.IO) {
            val today = async {
                try {
                    fetchToday(url, key)
                } catch (e: Exception) {
                    TodayResult(emptyList(), (e.message ?: e.javaClass.simpleName).take(40))
                }
            }
            val extras = async { fetchVitals(url, key) }
            today.await() to extras.await()
        }
        val actionError = lastError.also { lastError = null }
        val open = actionStartActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(dashboard)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        provideContent {
            Column(GlanceModifier.fillMaxSize().background(BG).cornerRadius(16.dp).padding(12.dp)) {
                Header(result, vitals.bb ?: result?.bbLevel, open)
                if (actionError != null) Text(actionError, style = TextStyle(color = RED, fontSize = 12.sp))
                Status(result)
                val msg = when {
                    result == null -> "Налаштуй у застосунку"
                    result.error != null -> "Помилка: ${result.error}"
                    result.items.isEmpty() -> "Немає задач"
                    else -> null
                }
                if (msg != null) {
                    Text(
                        msg,
                        GlanceModifier.defaultWeight().padding(vertical = 4.dp),
                        style = TextStyle(color = if (result?.error != null) RED else MUTED, fontSize = 13.sp)
                    )
                } else {
                    LazyColumn(GlanceModifier.defaultWeight()) {
                        items(result!!.items) { ItemRow(it) }
                    }
                }
                Footer(vitals, open)
            }
        }
    }
}

private fun bbColor(level: Int) = when {
    level >= 50 -> GREEN
    level >= 25 -> ORANGE
    else -> RED
}

@Composable
private fun Header(result: TodayResult?, bb: Int?, open: Action) {
    val counts = if (result != null && result.error == null && result.items.isNotEmpty())
        "  ${result.done}/${result.items.size}" else ""
    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "Сьогодні$counts",
            GlanceModifier.defaultWeight().clickable(open),
            style = TextStyle(color = PURPLE, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        )
        if (bb != null) {
            Text("⚡$bb", style = TextStyle(color = bbColor(bb), fontWeight = FontWeight.Bold, fontSize = 15.sp))
        }
        Text(
            "⟳",
            GlanceModifier.clickable(actionRunCallback<RefreshCallback>()).padding(start = 10.dp, end = 2.dp),
            style = TextStyle(color = MUTED, fontSize = 17.sp)
        )
    }
}

/** One line under the header: the day is won, or what is next on the calendar. */
@Composable
private fun Status(result: TodayResult?) {
    val text = when {
        result == null || result.error != null -> return
        result.won -> "🏆 День виграно"
        result.nextEvent != null -> "📅 ${result.nextEvent.time}  ${result.nextEvent.title}"
        else -> return
    }
    Text(
        text,
        GlanceModifier.padding(top = 2.dp, bottom = 4.dp),
        style = TextStyle(color = if (result.won) YELLOW else CYAN, fontSize = 13.sp),
        maxLines = 1
    )
}

@Composable
private fun Footer(v: Vitals, open: Action) {
    val parts = buildList {
        if (v.sleepScore != null) {
            val dur = v.sleepHours?.let { " · ${hoursLabel(it)}" } ?: ""
            add("😴 ${v.sleepScore}$dur" to if (v.sleepScore < 60) ORANGE else FG)
        }
        if (v.steps != null) add("👣 ${stepsLabel(v.steps)}" to FG)
        if (v.habitsTotal != null) add("✅ ${v.habitsDone ?: 0}/${v.habitsTotal}" to FG)
        if (v.inbox != null && v.inbox > 0) add("📥 ${v.inbox}" to PINK)
    }
    if (parts.isEmpty()) return
    Row(GlanceModifier.fillMaxWidth().padding(top = 6.dp).clickable(open)) {
        parts.forEachIndexed { i, (text, color) ->
            if (i > 0) Text("   ", style = TextStyle(fontSize = 12.sp))
            Text(text, style = TextStyle(color = color, fontSize = 12.sp), maxLines = 1)
        }
    }
}

@Composable
private fun ItemRow(item: TodayItem) {
    val done = item.status == "done"
    val (mark, markColor) = when (item.status) {
        "done" -> "✓" to GREEN
        "running" -> "▶" to CYAN
        else -> "○" to MUTED
    }
    Row(
        GlanceModifier.fillMaxWidth().padding(vertical = 5.dp).clickable(
            actionRunCallback<ToggleDoneCallback>(actionParametersOf(PAGE_ID to item.pageId, IS_DONE to done))
        ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("$mark ", style = TextStyle(color = markColor, fontSize = 15.sp, fontWeight = FontWeight.Bold))
        if (item.startTime != null) Text("${item.startTime} ", style = TextStyle(color = CYAN, fontSize = 13.sp))
        Text(
            item.title,
            GlanceModifier.defaultWeight(),
            style = TextStyle(
                color = if (done) MUTED else FG,
                fontSize = 14.sp,
                textDecoration = if (done) TextDecoration.LineThrough else TextDecoration.None
            ),
            maxLines = 2
        )
        if (item.sp > 0) Text(" ${item.sp}sp", style = TextStyle(color = MUTED, fontSize = 11.sp))
    }
}
