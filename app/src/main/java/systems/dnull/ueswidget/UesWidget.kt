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

// Status colours stay Dracula (dashboard theme.css); bg/text/muted/accent come from WidgetStyle.
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
        val st = WidgetStyle.load(context)
        val (result, vitals, system) = if (key.isBlank()) Triple(null, Vitals(), null) else withContext(Dispatchers.IO) {
            val today = async {
                try {
                    fetchToday(url, key)
                } catch (e: Exception) {
                    TodayResult(emptyList(), (e.message ?: e.javaClass.simpleName).take(40))
                }
            }
            val extras = async { fetchVitals(url, key) }
            val sys = async { if (st.showSystem || st.showAgents) fetchSystem(dashboard, key) else null }
            Triple(today.await(), extras.await(), sys.await())
        }
        val actionError = lastError.also { lastError = null }
        val open = actionStartActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(dashboard)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        provideContent {
            Column(GlanceModifier.fillMaxSize().background(st.bg).cornerRadius(st.corner.dp).padding(12.dp)) {
                Header(st, result, vitals.bb ?: result?.bbLevel, open)
                if (actionError != null) Text(actionError, style = TextStyle(color = RED, fontSize = st.sp(12)))
                if (st.showStatus) Status(st, result)
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
                        style = TextStyle(color = if (result?.error != null) RED else st.muted, fontSize = st.sp(13))
                    )
                } else {
                    LazyColumn(GlanceModifier.defaultWeight()) {
                        items(result!!.items) { ItemRow(st, it) }
                    }
                }
                if (system != null && st.showSystem && system.hasSystem) SystemRow(st, system)
                if (system != null && st.showAgents && system.hasAgents) AgentsRow(st, system)
                if (st.showFooter) Footer(st, vitals, open)
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
private fun Header(st: WidgetStyle, result: TodayResult?, bb: Int?, open: Action) {
    val counts = if (result != null && result.error == null && result.items.isNotEmpty())
        "  ${result.done}/${result.items.size}" else ""
    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "Сьогодні$counts",
            GlanceModifier.defaultWeight().clickable(open),
            style = TextStyle(color = st.accent, fontWeight = FontWeight.Bold, fontSize = st.sp(16))
        )
        if (bb != null) {
            Text("⚡$bb", style = TextStyle(color = bbColor(bb), fontWeight = FontWeight.Bold, fontSize = st.sp(15)))
        }
        Text(
            "⟳",
            GlanceModifier.clickable(actionRunCallback<RefreshCallback>()).padding(start = 10.dp, end = 2.dp),
            style = TextStyle(color = st.muted, fontSize = st.sp(17))
        )
    }
}

/** One line under the header: the day is won, or what is next on the calendar. */
@Composable
private fun Status(st: WidgetStyle, result: TodayResult?) {
    val text = when {
        result == null || result.error != null -> return
        result.won -> "🏆 День виграно"
        result.nextEvent != null -> "📅 ${result.nextEvent.time}  ${result.nextEvent.title}"
        else -> return
    }
    Text(
        text,
        GlanceModifier.padding(top = 2.dp, bottom = 4.dp),
        style = TextStyle(color = if (result.won) YELLOW else CYAN, fontSize = st.sp(13)),
        maxLines = 1
    )
}

@Composable
private fun Footer(st: WidgetStyle, v: Vitals, open: Action) {
    val parts = buildList {
        if (v.sleepScore != null) {
            val dur = v.sleepHours?.let { " · ${hoursLabel(it)}" } ?: ""
            add("😴 ${v.sleepScore}$dur" to if (v.sleepScore < 60) ORANGE else st.fg)
        }
        if (v.steps != null) add("👣 ${stepsLabel(v.steps)}" to st.fg)
        if (v.habitsTotal != null) add("✅ ${v.habitsDone ?: 0}/${v.habitsTotal}" to st.fg)
        if (v.inbox != null && v.inbox > 0) add("📥 ${v.inbox}" to PINK)
    }
    if (parts.isEmpty()) return
    Row(GlanceModifier.fillMaxWidth().padding(top = 6.dp).clickable(open)) {
        parts.forEachIndexed { i, (text, color) ->
            if (i > 0) Text("   ", style = TextStyle(fontSize = st.sp(12)))
            Text(text, style = TextStyle(color = color, fontSize = st.sp(12)), maxLines = 1)
        }
    }
}

@Composable
private fun ItemRow(st: WidgetStyle, item: TodayItem) {
    val done = item.status == "done"
    val (mark, markColor) = when (item.status) {
        "done" -> "✓" to GREEN
        "running" -> "▶" to CYAN
        else -> "○" to st.muted
    }
    Row(
        GlanceModifier.fillMaxWidth().padding(vertical = 5.dp).clickable(
            actionRunCallback<ToggleDoneCallback>(actionParametersOf(PAGE_ID to item.pageId, IS_DONE to done))
        ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("$mark ", style = TextStyle(color = markColor, fontSize = st.sp(15), fontWeight = FontWeight.Bold))
        if (item.startTime != null) Text("${item.startTime} ", style = TextStyle(color = CYAN, fontSize = st.sp(13)))
        Text(
            item.title,
            GlanceModifier.defaultWeight(),
            style = TextStyle(
                color = if (done) st.muted else st.fg,
                fontSize = st.sp(14),
                textDecoration = if (done) TextDecoration.LineThrough else TextDecoration.None
            ),
            maxLines = 2
        )
        if (st.showSp && item.sp > 0) Text(" ${item.sp}sp", style = TextStyle(color = st.muted, fontSize = st.sp(11)))
    }
}

private fun stateColor(state: String) = when (state) {
    "crit" -> RED
    "warn" -> ORANGE
    else -> GREEN
}

/** Glanceable health: alerts, uptime, unresolved errors, the Windows gaming VM, home attention. */
@Composable
private fun SystemRow(st: WidgetStyle, s: SystemSnapshot) {
    val parts = buildList {
        if (s.wellUpdatedAt != null) {
            val age = System.currentTimeMillis() - s.wellUpdatedAt
            val level = s.wellLevel?.let { "%.2f%s".format(java.util.Locale.ROOT, it, if (s.wellUnit == "m") "м" else s.wellUnit) } ?: "?"
            if (age > WELL_STALE_MS) add("💧 $level ⚠ ${ageLabel(age)}" to RED)
            else add("💧 $level · ${ageLabel(age)}" to CYAN)
        }
        val page = s.triagePage ?: 0
        val attention = s.triageAttention ?: 0
        when {
            page > 0 -> add("🔴 $page${s.triageTop?.let { " $it" } ?: ""}" to RED)
            !s.pipelineHealthy -> add("⚠ алерти не працюють" to ORANGE)
            attention > 0 -> add("🟠 $attention${s.triageTop?.let { " $it" } ?: ""}" to ORANGE)
            s.triagePage != null -> add("🟢 ок" to GREEN)
        }
        if (s.uptimeUp != null && s.uptimeTotal != null) {
            add("↑${s.uptimeUp}/${s.uptimeTotal}" to if (s.uptimeUp < s.uptimeTotal) ORANGE else st.muted)
        }
        if ((s.errors ?: 0) > 0) add("🐞${s.errors}" to PINK)
        when (s.gamingState) {
            "running" -> add("🎮 Win${s.game?.let { " · $it" } ?: ""}" to GREEN)
            "paused" -> add("⏸ Win" to YELLOW)
            "hibernated" -> add("💤 Win" to st.muted)
            "off" -> add("⏻ Win" to st.muted)
        }
        s.homeAttention.firstOrNull()?.let { add("🏠 $it" to ORANGE) }
    }
    if (parts.isEmpty()) return
    Row(GlanceModifier.fillMaxWidth().padding(top = 6.dp)) {
        parts.forEachIndexed { i, (text, color) ->
            if (i > 0) Text("  ", style = TextStyle(fontSize = st.sp(12)))
            Text(text, style = TextStyle(color = color, fontSize = st.sp(12)), maxLines = 1)
        }
    }
}

/** "C 33·7  G 24·84  ⚙ widget-api 12m": quota used % per window, coloured by state, plus running workers. */
@Composable
private fun AgentsRow(st: WidgetStyle, s: SystemSnapshot) {
    Row(GlanceModifier.fillMaxWidth().padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        quotaGroups(s.quota).forEach { (label, windows) ->
            Text("$label ", style = TextStyle(color = st.muted, fontSize = st.sp(12), fontWeight = FontWeight.Bold))
            windows.forEachIndexed { i, q ->
                if (i > 0) Text("·", style = TextStyle(color = st.muted, fontSize = st.sp(12)))
                Text("${q.usedPct}", style = TextStyle(color = stateColor(q.state), fontSize = st.sp(12)))
            }
            Text("  ", style = TextStyle(fontSize = st.sp(12)))
        }
        val w = s.workers.firstOrNull()
        if (w != null) {
            val more = if (s.workers.size > 1) " +${s.workers.size - 1}" else ""
            Text(
                "⚙ ${w.task} ${w.minutes}m$more",
                GlanceModifier.defaultWeight(),
                style = TextStyle(color = CYAN, fontSize = st.sp(12)),
                maxLines = 1
            )
        }
    }
}
