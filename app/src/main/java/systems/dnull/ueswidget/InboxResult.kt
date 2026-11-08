package systems.dnull.ueswidget

import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/** One /api/inbox item; `suggestion` is null until the server's AI triage attaches one. */
data class InboxItem(
    val id: Int,
    val rawText: String,
    val source: String,
    val status: String,
    val createdAt: String?,
    val suggestion: Suggestion? = null,
)

/** The AI triage attached to an inbox item. Every field is optional: the server adds them incrementally. */
data class Suggestion(
    val title: String,
    val kind: String,
    val sp: Int = 0,
    val severity: String? = null,
    val context: String? = null,
    val scheduledDate: String? = null,
    val description: String? = null,
    val steps: List<String> = emptyList(),
    val note: String? = null,
)

data class InboxResult(val items: List<InboxItem>, val count: Int = 0)

private fun JSONObject.str(key: String): String? = if (isNull(key)) null else optString(key).ifBlank { null }
private fun JSONObject.int(key: String): Int? = if (isNull(key) || !has(key)) null else optDouble(key).toInt()

fun parseInbox(json: String): InboxResult {
    val root = JSONObject(json)
    val arr = root.optJSONArray("items")
    val items = (0 until (arr?.length() ?: 0)).map { arr!!.getJSONObject(it) }.mapNotNull { o ->
        InboxItem(
            id = o.int("id") ?: return@mapNotNull null,
            rawText = o.optString("raw_text"),
            source = o.optString("source"),
            status = o.optString("status"),
            createdAt = o.str("created_at"),
            suggestion = o.optJSONObject("suggestion")?.let { s ->
                Suggestion(
                    title = s.optString("title"),
                    kind = s.optString("kind"),
                    sp = s.int("sp") ?: 0,
                    severity = s.str("severity"),
                    context = s.str("context"),
                    scheduledDate = s.str("scheduled_date"),
                    description = s.str("description"),
                    steps = s.optJSONArray("steps")?.let { steps ->
                        (0 until steps.length()).map { steps.optString(it) }
                    } ?: emptyList(),
                    note = s.str("note"),
                )
            },
        )
    }
    return InboxResult(items, root.int("count") ?: items.size)
}

/** Items to notify about: new, with a suggestion, created within the last 24 h and not notified yet. */
fun freshSuggestions(items: List<InboxItem>, notified: Set<Int>, now: Instant = Instant.now()): List<InboxItem> {
    val cutoff = now.minus(24, ChronoUnit.HOURS)
    return items.filter { item ->
        item.status == "new" && item.suggestion != null && item.id !in notified &&
            item.createdAt?.let { created ->
                runCatching { OffsetDateTime.parse(created).toInstant() }.getOrNull()
                    ?.let { !it.isBefore(cutoff) && !it.isAfter(now) } ?: false
            } == true
    }
}

/** "2026-11-09" -> "09.11", "2026-11-09T09:00" -> "09.11 09:00"; anything unparseable passes through unchanged. */
fun isoDateLabel(date: String): String =
    try {
        if ('T' in date) LocalDateTime.parse(date).format(DateTimeFormatter.ofPattern("dd.MM HH:mm"))
        else LocalDate.parse(date).format(DateTimeFormatter.ofPattern("dd.MM"))
    } catch (e: Exception) {
        date
    }

/** "Задача: X", or "Нотатка? X" for reference material. */
fun suggestionTitle(item: InboxItem): String {
    val s = item.suggestion ?: return item.rawText
    val t = s.title.ifBlank { item.rawText }
    return if (s.kind == "reference") "Нотатка? $t" else "Задача: $t"
}

/** First line of the notification: date · SP · severity · context. */
fun suggestionMeta(item: InboxItem): String {
    val s = item.suggestion ?: return ""
    return buildList {
        add(s.scheduledDate?.let(::isoDateLabel) ?: "без дати")
        add("${s.sp} SP")
        s.severity?.let { add(it) }
        s.context?.let { add(it) }
    }.joinToString(" · ")
}

/** The notification body: meta line, then the description (or the raw note), then the AI note. */
fun suggestionText(item: InboxItem): String {
    val s = item.suggestion ?: return item.rawText
    val body = s.description ?: item.rawText.ifBlank { null }
    return buildList {
        add(suggestionMeta(item))
        body?.let { add(it) }
        s.note?.let { add("AI: $it") }
    }.joinToString("\n")
}
