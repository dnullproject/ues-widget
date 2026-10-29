package systems.dnull.ueswidget

import org.json.JSONObject

data class TodayItem(val title: String, val status: String, val startTime: String?)

data class TodayResult(val items: List<TodayItem>, val error: String? = null) {
    val done get() = items.count { it.status == "done" }
}

private val SHOWN = setOf("planned", "running", "done")

fun TodayItem.line(): String {
    val mark = when (status) { "done" -> "✓ "; "running" -> "▶ "; else -> "• " }
    return mark + (startTime?.let { "$it " } ?: "") + title
}

/** Parses the /api/today response; keeps only planned/running/done items. */
fun parseToday(json: String): TodayResult {
    val root = JSONObject(json)
    val error = if (root.isNull("error")) null else root.getString("error").ifBlank { null }
    if (error != null) return TodayResult(emptyList(), error)
    val arr = root.optJSONObject("plan")?.optJSONArray("items") ?: return TodayResult(emptyList())
    val items = (0 until arr.length()).map { arr.getJSONObject(it) }.mapNotNull { o ->
        val status = o.optString("status")
        if (status !in SHOWN) return@mapNotNull null
        TodayItem(o.optString("title"), status, if (o.isNull("start_time")) null else o.getString("start_time"))
    }
    return TodayResult(items)
}
