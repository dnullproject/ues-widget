package systems.dnull.ueswidget

import android.content.Context
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object Settings {
    const val DEFAULT_BASE_URL = "https://memory-ues.kudu-skate.ts.net"
    const val DEFAULT_DASHBOARD_URL = "https://dashboard.libra.dnull.systems/"

    private fun prefs(c: Context) = c.getSharedPreferences("ues_widget", Context.MODE_PRIVATE)
    fun baseUrl(c: Context) = prefs(c).getString("base_url", null) ?: DEFAULT_BASE_URL
    fun apiKey(c: Context) = prefs(c).getString("api_key", null) ?: ""
    fun dashboardUrl(c: Context) = prefs(c).getString("dashboard_url", null) ?: DEFAULT_DASHBOARD_URL

    fun save(c: Context, base: String, key: String, dash: String) {
        prefs(c).edit().putString("base_url", base).putString("api_key", key)
            .putString("dashboard_url", dash).apply()
    }
}

/** Blocking; call off the main thread. Throws on network/HTTP errors. Returns the body. */
private fun call(baseUrl: String, apiKey: String, method: String, path: String): String {
    val conn = URL("${baseUrl.trimEnd('/')}$path").openConnection() as HttpURLConnection
    try {
        conn.requestMethod = method
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        conn.setRequestProperty("X-API-Key", apiKey)
        val code = conn.responseCode
        if (code != 200) error("HTTP $code")
        return conn.inputStream.bufferedReader().use { it.readText() }
    } finally {
        conn.disconnect()
    }
}

// No tz: Android reports legacy ids (Europe/Kiev) the server's tzdata lacks; it defaults to Europe/Kyiv.
fun fetchToday(baseUrl: String, apiKey: String): TodayResult =
    parseToday(call(baseUrl, apiKey, "GET", "/api/today"))

/** Dashboard /api/widget (same API key); null when the dashboard is unreachable. */
fun fetchSystem(dashboardUrl: String, apiKey: String): SystemSnapshot? =
    try { parseSystem(call(dashboardUrl, apiKey, "GET", "/api/widget")) } catch (e: Exception) { null }

/** Vitals plus the inbox items captured in the same fetch, so the notifications need no second request. */
data class VitalsFetch(val vitals: Vitals, val inbox: InboxResult? = null)

/** Best-effort extras for the widget; any endpoint that fails is skipped. */
fun fetchVitals(baseUrl: String, apiKey: String): VitalsFetch {
    var v = Vitals()
    fun step(path: String, apply: Vitals.(String) -> Vitals) {
        v = try { v.apply(call(baseUrl, apiKey, "GET", path)) } catch (e: Exception) { v }
    }
    step("/api/body-battery/current", Vitals::withBodyBattery)
    step("/api/stats/daily-summary", Vitals::withDailySummary)
    step("/api/health/current", Vitals::withHealth)
    val inbox = try { parseInbox(call(baseUrl, apiKey, "GET", "/api/inbox")) } catch (e: Exception) { null }
    return VitalsFetch(v.copy(inbox = inbox?.count), inbox)
}

/** Marks a plan item done, or reopens it if it is already done. */
fun toggleDone(baseUrl: String, apiKey: String, pageId: String, done: Boolean) {
    val id = URLEncoder.encode(pageId, "UTF-8")
    call(baseUrl, apiKey, "POST", "/api/today/items/$id/${if (done) "reopen" else "done"}")
}

/** POSTs an inbox action (empty JSON body by default); 2xx and 409 (already handled) count as ok. */
fun inboxAction(baseUrl: String, apiKey: String, id: Int, action: String, body: String = "{}") {
    val code = postCode(baseUrl, apiKey, "/api/inbox/$id/$action", body).first
    if (code !in 200..299 && code != 409) error("HTTP $code")
}

private const val CHUNK_BYTES = 48 * 1024

/**
 * Uploads a recorded OGG/Opus note in 48 KiB base64 chunks to /api/voice-notes/chunk; the server assembles it
 * (201 on the last chunk), transcribes it and files it into the inbox. A 409 lists missing chunks: resend once.
 */
fun uploadVoiceNote(baseUrl: String, apiKey: String, file: File, durationS: Int) {
    val bytes = file.readBytes()
    if (bytes.isEmpty()) error("порожній запис")
    val noteId = "phone-" + System.currentTimeMillis()
    val recordedAt = (System.currentTimeMillis() / 1000).toInt() - durationS
    val n = (bytes.size + CHUNK_BYTES - 1) / CHUNK_BYTES
    fun send(seq: Int): Pair<Int, String> {
        val part = bytes.copyOfRange(seq * CHUNK_BYTES, minOf(bytes.size, (seq + 1) * CHUNK_BYTES))
        val o = JSONObject().put("note_id", noteId).put("seq", seq).put("last", seq == n - 1)
            .put("data", Base64.encodeToString(part, Base64.NO_WRAP))
        if (seq == 0) o.put("format", "ogg_opus").put("recorded_at", recordedAt)
        if (seq == n - 1) o.put("duration_s", durationS).put("total_bytes", bytes.size).put("chunks", n)
        return postCode(baseUrl, apiKey, "/api/voice-notes/chunk", o.toString())
    }
    var res = Pair(200, "")
    for (seq in 0 until n) {
        res = send(seq)
        if (res.first == 409) break
        if (res.first !in 200..299) error("HTTP ${res.first}")
    }
    if (res.first == 409) {
        val missing = JSONObject(res.second).optJSONArray("missing")
        for (k in 0 until (missing?.length() ?: 0)) {
            val seq = missing!!.getInt(k)
            if (seq !in 0 until n) error("HTTP 409")
            val r = send(seq)
            if (r.first !in 200..299) error("HTTP ${r.first}")
        }
        res = send(n - 1)
    }
    if (res.first != 201) error("HTTP ${res.first}")
}

/** POST with a JSON body; returns the HTTP status code and the response body (the error body on 4xx/5xx). */
private fun postCode(baseUrl: String, apiKey: String, path: String, body: String): Pair<Int, String> {
    val conn = URL("${baseUrl.trimEnd('/')}$path").openConnection() as HttpURLConnection
    try {
        conn.requestMethod = "POST"
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        conn.setRequestProperty("X-API-Key", apiKey)
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput = true
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        val text = (if (code >= 400) conn.errorStream else conn.inputStream)?.bufferedReader()?.use { it.readText() } ?: ""
        return code to text
    } finally {
        conn.disconnect()
    }
}
