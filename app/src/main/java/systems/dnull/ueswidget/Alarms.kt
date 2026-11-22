package systems.dnull.ueswidget

import org.json.JSONArray
import org.json.JSONObject

data class Alarm(
    val id: String,
    val taskId: String,
    val title: String,
    val date: String,
    val start: String,
    val kind: String,
    val fireAt: Long,
)

data class AlarmDiff(
    val toSchedule: List<Alarm>,
    val toCancel: Set<String>,
)

const val ALARM_GRACE_SECONDS = 120L

/** Parses GET /api/alarms?channel=phone&hours=48 response. */
fun parseAlarms(json: String): List<Alarm> {
    val arr = JSONObject(json).optJSONArray("alarms") ?: return emptyList()
    return (0 until arr.length()).mapNotNull { i ->
        val o = arr.optJSONObject(i) ?: return@mapNotNull null
        val id = o.optString("id").trim()
        val fireAt = o.optLong("fire_at", 0L)
        if (id.isEmpty() || fireAt <= 0L) return@mapNotNull null
        Alarm(
            id = id,
            taskId = o.optString("task_id"),
            title = o.optString("title"),
            date = o.optString("date"),
            start = o.optString("start"),
            kind = o.optString("kind"),
            fireAt = fireAt,
        )
    }
}

/** Serializes alarms to a JSON array string for local persistence across reboots. */
fun serializeAlarms(alarms: List<Alarm>): String {
    val arr = JSONArray()
    for (a in alarms) {
        arr.put(
            JSONObject()
                .put("id", a.id)
                .put("task_id", a.taskId)
                .put("title", a.title)
                .put("date", a.date)
                .put("start", a.start)
                .put("kind", a.kind)
                .put("fire_at", a.fireAt),
        )
    }
    return JSONObject().put("alarms", arr).toString()
}

/**
 * Computes which alarms need to be scheduled and which previously scheduled ids disappeared.
 * Alarms whose [Alarm.fireAt] is older than [ALARM_GRACE_SECONDS] (2 min) before [nowS] are skipped.
 */
fun diffAlarms(scheduled: Set<String>, fresh: List<Alarm>, nowS: Long): AlarmDiff {
    val freshIds = fresh.mapTo(HashSet()) { it.id }
    val toCancel = scheduled.filterTo(HashSet()) { it !in freshIds }
    val toSchedule = fresh.filter { it.id !in scheduled && it.fireAt >= nowS - ALARM_GRACE_SECONDS }
    return AlarmDiff(toSchedule = toSchedule, toCancel = toCancel)
}

fun alarmNotificationTitle(start: String, title: String, kind: String): String =
    if (kind == "pre") "⏰ за 15 хв: $start — $title" else "⏰ $start — $title"
