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
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.background
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class UesWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = UesWidget()
}

class RefreshCallback : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        UesWidget().update(context, glanceId)
    }
}

class UesWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val key = Settings.apiKey(context)
        val url = Settings.baseUrl(context)
        val dashboard = Settings.dashboardUrl(context)
        val result: TodayResult? = if (key.isBlank()) null else withContext(Dispatchers.IO) {
            try {
                fetchToday(url, key)
            } catch (e: Exception) {
                TodayResult(emptyList(), (e.message ?: e.javaClass.simpleName).take(40))
            }
        }
        val open = actionStartActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(dashboard)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        provideContent {
            val white = ColorProvider(Color.White)
            Column(
                GlanceModifier.fillMaxSize().background(ColorProvider(Color(0xCC202020)))
                    .padding(8.dp).clickable(open)
            ) {
                val header = if (result != null && result.error == null)
                    "Сьогодні  ${result.done}/${result.items.size}" else "Сьогодні"
                Row(GlanceModifier.fillMaxWidth()) {
                    Text(
                        header,
                        GlanceModifier.defaultWeight(),
                        style = TextStyle(color = white, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    )
                    Text(
                        "⟳",
                        GlanceModifier.clickable(actionRunCallback<RefreshCallback>()).padding(horizontal = 8.dp),
                        style = TextStyle(color = white, fontSize = 16.sp)
                    )
                }
                val msg = when {
                    result == null -> "Налаштуй у застосунку"
                    result.error != null -> "Помилка: ${result.error}"
                    result.items.isEmpty() -> "Немає задач"
                    else -> null
                }
                if (msg != null) {
                    Text(msg, style = TextStyle(color = white, fontSize = 13.sp))
                } else {
                    LazyColumn {
                        items(result!!.items) { Text(it.line(), style = TextStyle(color = white, fontSize = 13.sp)) }
                    }
                }
            }
        }
    }
}
