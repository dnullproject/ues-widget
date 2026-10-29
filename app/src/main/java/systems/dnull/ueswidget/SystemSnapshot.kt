package systems.dnull.ueswidget

import org.json.JSONArray
import org.json.JSONObject

/** Parsed dashboard /api/widget; every section is optional (null when its source was down). */
data class SystemSnapshot(
    val triagePage: Int? = null,
    val triageAttention: Int? = null,
    val pipelineHealthy: Boolean = true,
    val triageTop: String? = null,
    val uptimeUp: Int? = null,
    val uptimeTotal: Int? = null,
    val errors: Int? = null,
    val quota: List<Quota> = emptyList(),
    val workers: List<Worker> = emptyList(),
    val gamingState: String? = null,
    val game: String? = null,
    val homeAttention: List<String> = emptyList(),
) {
    data class Quota(val pool: String, val window: String, val usedPct: Int, val state: String)
    data class Worker(val engine: String, val task: String, val minutes: Int)

    val hasSystem get() = triagePage != null || uptimeTotal != null || errors != null || gamingState != null
    val hasAgents get() = quota.isNotEmpty() || workers.isNotEmpty()
}

private fun JSONObject.intOrNull(k: String): Int? = if (!has(k) || isNull(k)) null else optDouble(k).toInt()
private fun JSONObject.strOrNull(k: String): String? = if (!has(k) || isNull(k)) null else optString(k).ifBlank { null }
private inline fun <T> JSONArray?.mapObjects(f: (JSONObject) -> T?): List<T> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it)?.let(f) }

fun parseSystem(json: String): SystemSnapshot {
    val o = JSONObject(json)
    val triage = o.optJSONObject("triage")
    val uptime = o.optJSONObject("uptime")
    val gaming = o.optJSONObject("gaming")
    val home = o.optJSONObject("home")?.optJSONArray("attention")
    return SystemSnapshot(
        triagePage = triage?.intOrNull("page"),
        triageAttention = triage?.intOrNull("attention"),
        pipelineHealthy = triage?.optBoolean("pipeline_healthy", true) ?: true,
        triageTop = triage?.strOrNull("top"),
        uptimeUp = uptime?.intOrNull("up"),
        uptimeTotal = uptime?.intOrNull("total"),
        errors = o.optJSONObject("errors")?.intOrNull("unresolved"),
        quota = o.optJSONArray("quota").mapObjects { q ->
            val pct = q.intOrNull("used_pct") ?: return@mapObjects null
            SystemSnapshot.Quota(q.optString("pool"), q.optString("window"), pct, q.optString("state", "ok"))
        },
        workers = o.optJSONObject("agents")?.optJSONArray("workers").mapObjects { w ->
            SystemSnapshot.Worker(w.optString("engine"), w.optString("task"), w.intOrNull("minutes") ?: 0)
        },
        gamingState = gaming?.strOrNull("state"),
        game = gaming?.strOrNull("game"),
        homeAttention = if (home == null) emptyList() else (0 until home.length()).mapNotNull { home.optString(it).ifBlank { null } },
    )
}

/** "claude" -> "C", "gemini" -> "G"; windows in order 5h then the long one. */
fun quotaGroups(q: List<SystemSnapshot.Quota>): List<Pair<String, List<SystemSnapshot.Quota>>> =
    q.groupBy { it.pool }.map { (pool, list) ->
        pool.take(1).uppercase() to list.sortedBy { if (it.window == "5h") 0 else 1 }
    }.sortedBy { it.first }
