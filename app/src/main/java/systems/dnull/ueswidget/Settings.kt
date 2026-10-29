package systems.dnull.ueswidget

import android.content.Context
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

/** Best-effort extras for the widget; any endpoint that fails is skipped. */
fun fetchVitals(baseUrl: String, apiKey: String): Vitals {
    var v = Vitals()
    fun step(path: String, apply: Vitals.(String) -> Vitals) {
        v = try { v.apply(call(baseUrl, apiKey, "GET", path)) } catch (e: Exception) { v }
    }
    step("/api/body-battery/current", Vitals::withBodyBattery)
    step("/api/stats/daily-summary", Vitals::withDailySummary)
    step("/api/health/current", Vitals::withHealth)
    step("/api/inbox", Vitals::withInbox)
    return v
}

/** Marks a plan item done, or reopens it if it is already done. */
fun toggleDone(baseUrl: String, apiKey: String, pageId: String, done: Boolean) {
    val id = URLEncoder.encode(pageId, "UTF-8")
    call(baseUrl, apiKey, "POST", "/api/today/items/$id/${if (done) "reopen" else "done"}")
}
