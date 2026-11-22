package systems.dnull.ueswidget

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

sealed class SharePayload {
    data class Text(val text: String) : SharePayload()
    data class Images(val uris: List<String>, val text: String) : SharePayload()
}

/** Combines optional EXTRA_SUBJECT and EXTRA_TEXT without duplicating the subject if already in text. */
fun combineShareText(subject: String?, text: String?): String {
    val s = subject?.trim().orEmpty()
    val t = text?.trim().orEmpty()
    return when {
        s.isNotEmpty() && t.isNotEmpty() && !t.contains(s) -> "$s\n$t"
        t.isNotEmpty() -> t
        else -> s
    }
}

/**
 * Chooses whether an incoming share intent should be sent as text or image(s).
 * Returns null if there is nothing actionable to send.
 */
fun resolveSharePayload(
    action: String?,
    mimeType: String?,
    subject: String?,
    text: String?,
    streamUris: List<String>,
): SharePayload? {
    val isImageMime = mimeType?.startsWith("image/") == true
    val combined = combineShareText(subject, text)
    return when (action) {
        "android.intent.action.SEND", "android.intent.action.SEND_MULTIPLE" -> when {
            streamUris.isNotEmpty() && (isImageMime || mimeType == "*/*" || mimeType == null) ->
                SharePayload.Images(streamUris, combined)
            combined.isNotEmpty() ->
                SharePayload.Text(combined)
            else -> null
        }
        else -> null
    }
}

fun buildInboxJson(text: String, source: String = "share"): String =
    JSONObject().put("text", text).put("source", source).toString()

data class MultipartRequest(val contentType: String, val body: ByteArray)

/** Builds a multipart/form-data body for POST /api/inbox with text, source, and file fields. */
fun buildInboxMultipart(
    text: String,
    source: String = "share",
    imageBytes: ByteArray,
    filename: String = "image.jpg",
    mimeType: String = "image/jpeg",
    boundary: String = "----ues-share-${UUID.randomUUID()}",
): MultipartRequest {
    val out = ByteArrayOutputStream()
    fun writeStr(s: String) = out.write(s.toByteArray(Charsets.UTF_8))
    fun addField(name: String, value: String) {
        writeStr("--$boundary\r\n")
        writeStr("Content-Disposition: form-data; name=\"$name\"\r\n\r\n")
        writeStr(value)
        writeStr("\r\n")
    }
    addField("text", text)
    addField("source", source)
    writeStr("--$boundary\r\n")
    writeStr("Content-Disposition: form-data; name=\"file\"; filename=\"$filename\"\r\n")
    writeStr("Content-Type: $mimeType\r\n\r\n")
    out.write(imageBytes)
    writeStr("\r\n--$boundary--\r\n")
    return MultipartRequest("multipart/form-data; boundary=$boundary", out.toByteArray())
}

/** Computes target (width, height) so the long side is at most [maxLongSide] pixels. */
fun scaledDimensions(width: Int, height: Int, maxLongSide: Int = 2048): Pair<Int, Int> {
    if (width <= 0 || height <= 0) return width to height
    val longSide = maxOf(width, height)
    if (longSide <= maxLongSide) return width to height
    val scale = maxLongSide.toDouble() / longSide.toDouble()
    return maxOf(1, (width * scale).toInt()) to maxOf(1, (height * scale).toInt())
}

/** Blocking; call off the main thread. Posts a text/link item to /api/inbox. */
fun postInboxText(baseUrl: String, apiKey: String, text: String) {
    postRaw(baseUrl, apiKey, "/api/inbox", "application/json", buildInboxJson(text).toByteArray(Charsets.UTF_8))
}

/** Blocking; call off the main thread. Posts an image item to /api/inbox as multipart/form-data. */
fun postInboxImage(baseUrl: String, apiKey: String, text: String, imageBytes: ByteArray) {
    val req = buildInboxMultipart(text = text, imageBytes = imageBytes)
    postRaw(baseUrl, apiKey, "/api/inbox", req.contentType, req.body)
}

private fun postRaw(baseUrl: String, apiKey: String, path: String, contentType: String, body: ByteArray) {
    val conn = URL("${baseUrl.trimEnd('/')}$path").openConnection() as HttpURLConnection
    try {
        conn.requestMethod = "POST"
        conn.connectTimeout = 10_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("X-API-Key", apiKey)
        conn.setRequestProperty("Content-Type", contentType)
        conn.doOutput = true
        conn.outputStream.use { it.write(body) }
        val code = conn.responseCode
        if (code !in 200..299) error("HTTP $code")
    } finally {
        conn.disconnect()
    }
}
