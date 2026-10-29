package systems.dnull.ueswidget

import org.json.JSONObject
import java.time.OffsetDateTime
import java.time.ZoneId

data class TodayItem(
    val pageId: String,
    val title: String,
    val status: String,
    val startTime: String?,
    val sp: Int = 0,
)

data class NextEvent(val time: String, val title: String)

data class TodayResult(
    val items: List<TodayItem>,
    val error: String? = null,
    val won: Boolean = false,
    val nextEvent: NextEvent? = null,
    val bbLevel: Int? = null,
) {
    val done get() = items.count { it.status == "done" }
}

private val SHOWN = setOf("planned", "running", "done")

private fun JSONObject.str(key: String): String? = if (isNull(key)) null else optString(key).ifBlank { null }
private fun JSONObject.int(key: String): Int? = if (isNull(key) || !has(key)) null else optDouble(key).toInt()

/** "2026-10-29T14:00:00+02:00" -> "14:00" in the device zone; falls back to the raw HH:MM. */
internal fun localHm(iso: String, zone: ZoneId = ZoneId.systemDefault()): String =
    try {
        OffsetDateTime.parse(iso).atZoneSameInstant(zone).toLocalTime().toString().take(5)
    } catch (e: Exception) {
        iso.substringAfter('T', iso).take(5)
    }

/** Parses the /api/today response; keeps only planned/running/done items. */
fun parseToday(json: String, zone: ZoneId = ZoneId.systemDefault()): TodayResult {
    val root = JSONObject(json)
    root.str("error")?.let { return TodayResult(emptyList(), it) }
    val plan = root.optJSONObject("plan") ?: return TodayResult(emptyList())
    val arr = plan.optJSONArray("items")
    val items = (0 until (arr?.length() ?: 0)).map { arr!!.getJSONObject(it) }.mapNotNull { o ->
        val status = o.optString("status")
        if (status !in SHOWN) return@mapNotNull null
        TodayItem(o.optString("page_id"), o.optString("title"), status, o.str("start_time"), o.int("sp") ?: 0)
    }
    val next = root.optJSONObject("next_event")?.let { e ->
        e.str("start")?.let { NextEvent(localHm(it, zone), e.optString("title")) }
    }
    return TodayResult(
        items = items,
        won = plan.optString("status") == "won",
        nextEvent = next,
        bbLevel = root.optJSONObject("bb")?.int("level"),
    )
}

/** Everything around the task list. Each field is optional: a failing endpoint just hides its bit. */
data class Vitals(
    val bb: Int? = null,
    val sleepScore: Int? = null,
    val sleepHours: Double? = null,
    val steps: Int? = null,
    val habitsDone: Int? = null,
    val habitsTotal: Int? = null,
    val inbox: Int? = null,
)

fun Vitals.withBodyBattery(json: String): Vitals {
    val o = JSONObject(json)
    if (!o.optBoolean("available", false)) return this
    return copy(bb = o.int("level"))
}

fun Vitals.withDailySummary(json: String): Vitals {
    val o = JSONObject(json)
    val body = o.optJSONObject("body")
    val h = o.optJSONObject("habits")
    val total = h?.let { (it.int("done") ?: 0) + (it.int("skipped") ?: 0) + (it.int("missed") ?: 0) }
    return copy(
        sleepScore = body?.int("sleep_score"),
        sleepHours = body?.takeUnless { it.isNull("sleep_duration_h") }?.optDouble("sleep_duration_h"),
        habitsDone = h?.int("done")?.takeIf { (total ?: 0) > 0 },
        habitsTotal = total?.takeIf { it > 0 },
    )
}

fun Vitals.withHealth(json: String): Vitals = copy(steps = JSONObject(json).int("steps"))

fun Vitals.withInbox(json: String): Vitals {
    val o = JSONObject(json)
    return copy(inbox = o.int("count") ?: o.optJSONArray("items")?.length())
}

/** 3.13 -> "3г08", the way the dashboard writes durations. */
fun hoursLabel(h: Double): String {
    val min = Math.round(h * 60).toInt()
    return "%dг%02d".format(java.util.Locale.ROOT, min / 60, min % 60)
}

fun stepsLabel(n: Int): String = if (n < 1000) "$n" else "%.1fk".format(java.util.Locale.ROOT, n / 1000.0)
