package systems.dnull.ueswidget

import android.content.Context
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.TimeZone

object Settings {
    const val DEFAULT_BASE_URL = "https://memory-ues.kudu-skate.ts.net"
    const val DEFAULT_DASHBOARD_URL = "https://dashboard.libra.dnull.systems/today"

    private fun prefs(c: Context) = c.getSharedPreferences("ues_widget", Context.MODE_PRIVATE)
    fun baseUrl(c: Context) = prefs(c).getString("base_url", null) ?: DEFAULT_BASE_URL
    fun apiKey(c: Context) = prefs(c).getString("api_key", null) ?: ""
    fun dashboardUrl(c: Context) = prefs(c).getString("dashboard_url", null) ?: DEFAULT_DASHBOARD_URL

    fun save(c: Context, base: String, key: String, dash: String) {
        prefs(c).edit().putString("base_url", base).putString("api_key", key)
            .putString("dashboard_url", dash).apply()
    }
}

/** Blocking; call off the main thread. Throws on network/HTTP/parse errors. */
fun fetchToday(baseUrl: String, apiKey: String): TodayResult {
    val tz = URLEncoder.encode(TimeZone.getDefault().id, "UTF-8")
    val conn = URL("${baseUrl.trimEnd('/')}/api/today?tz=$tz").openConnection() as HttpURLConnection
    try {
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        conn.setRequestProperty("X-API-Key", apiKey)
        val code = conn.responseCode
        if (code != 200) error("HTTP $code")
        return parseToday(conn.inputStream.bufferedReader().use { it.readText() })
    } finally {
        conn.disconnect()
    }
}
