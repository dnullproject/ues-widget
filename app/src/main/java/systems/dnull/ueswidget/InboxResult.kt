package systems.dnull.ueswidget

import org.json.JSONArray
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

/** One sub-task in a "multi" inbox suggestion. */
data class SuggestionItem(
    val title: String,
    val sp: Int = 0,
    val severity: String? = null,
    val context: String? = null,
    val scheduledDate: String? = null,
    val startTime: String? = null,
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
    val endTime: String? = null,
    val tag: String? = null,
    val items: List<SuggestionItem> = emptyList(),
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
                    endTime = s.str("end_time"),
                    description = s.str("description"),
                    steps = s.optJSONArray("steps")?.let { steps ->
                        (0 until steps.length()).map { steps.optString(it) }
                    } ?: emptyList(),
                    note = s.str("note"),
                    tag = s.str("tag"),
                    items = s.optJSONArray("items")?.let { subArr ->
                        (0 until subArr.length()).mapNotNull { idx ->
                            subArr.optJSONObject(idx)?.let { sub ->
                                SuggestionItem(
                                    title = sub.optString("title"),
                                    sp = sub.int("sp") ?: 0,
                                    severity = sub.str("severity"),
                                    context = sub.str("context"),
                                    scheduledDate = sub.str("scheduled_date"),
                                    startTime = sub.str("start_time"),
                                )
                            }
                        }
                    } ?: emptyList(),
                )
            },
        )
    }
    return InboxResult(items, root.int("count") ?: items.size)
}

/** Items to notify about: new, with a non-reference suggestion, created within the last 24 h and not notified yet. */
fun freshSuggestions(items: List<InboxItem>, notified: Set<Int>, now: Instant = Instant.now()): List<InboxItem> {
    val cutoff = now.minus(24, ChronoUnit.HOURS)
    return items.filter { item ->
        item.status == "new" && item.suggestion != null && item.suggestion.kind != "reference" && item.id !in notified &&
            item.createdAt?.let { created ->
                runCatching { OffsetDateTime.parse(created).toInstant() }.getOrNull()
                    ?.let { !it.isBefore(cutoff) && !it.isAfter(now) } ?: false
            } == true
    }
}

/**
 * "2026-11-09" -> "09.11"; a timed date gets its slot, end "HH:MM" or the server's 1 h default:
 * ("2026-11-09T14:00", "16:00") -> "09.11 14:00–16:00". Anything unparseable passes through unchanged.
 */
fun isoDateLabel(date: String, endTime: String? = null): String =
    try {
        if ('T' in date) {
            val start = LocalDateTime.parse(date)
            val end = endTime ?: start.plusHours(1).format(DateTimeFormatter.ofPattern("HH:mm"))
            start.format(DateTimeFormatter.ofPattern("dd.MM HH:mm")) + "–" + end
        } else LocalDate.parse(date).format(DateTimeFormatter.ofPattern("dd.MM"))
    } catch (e: Exception) {
        date
    }

/** "Задача: X", "Нотатка? [tag] X" (or "Нотатка? X") for reference material, or "Задачі (N): X" for multi-task captures. */
fun suggestionTitle(item: InboxItem): String {
    val s = item.suggestion ?: return item.rawText
    val t = s.title.ifBlank { item.rawText }
    return when {
        s.kind == "reference" -> if (!s.tag.isNullOrBlank()) "Нотатка? [${s.tag}] $t" else "Нотатка? $t"
        s.kind == "multi" && s.items.size >= 2 -> "Задачі (${s.items.size}): $t"
        else -> "Задача: $t"
    }
}

/** First line of the notification: date · SP · severity · context. */
fun suggestionMeta(item: InboxItem): String {
    val s = item.suggestion ?: return ""
    return buildList {
        add(s.scheduledDate?.let { isoDateLabel(it, s.endTime) } ?: "без дати")
        add("${s.sp} SP")
        s.severity?.let { add(it) }
        s.context?.let { add(it) }
    }.joinToString(" · ")
}

/** The notification body: multi-item bullet list, or meta line + description/raw + AI note. */
fun suggestionText(item: InboxItem): String {
    val s = item.suggestion ?: return item.rawText
    if (s.kind == "multi" && s.items.size >= 2) {
        return s.items.joinToString("\n") { sub ->
            val whenLabel = sub.scheduledDate?.let { d ->
                val base = isoDateLabel(d)
                if (sub.startTime != null) "$base ${sub.startTime}" else base
            }
            if (whenLabel != null) "• ${sub.title} — $whenLabel" else "• ${sub.title}"
        }
    }
    val body = s.description ?: item.rawText.ifBlank { null }
    return buildList {
        add(suggestionMeta(item))
        body?.let { add(it) }
        s.note?.let { add("AI: $it") }
    }.joinToString("\n")
}

/** Notification action buttons (label to action key) for an inbox item. */
fun suggestionActions(item: InboxItem): List<Pair<String, String>> {
    val s = item.suggestion
    return when {
        s?.kind == "reference" -> listOf(
            "📌 Зберегти" to "reference",
            "✓ Таска" to "convert",
            "🗑" to "trash",
        )
        s?.kind == "two_minute" -> listOf(
            "✓ Зроблено" to "done",
            "✓ Створити" to "convert",
            "🗑" to "trash",
        )
        s?.kind == "multi" && s.items.size >= 2 -> listOf(
            "✓ Створити ${s.items.size}" to "split",
            "🗑" to "trash",
        )
        else -> listOf(
            "⏰ +2 год" to "today",
            "✓ Створити" to "convert",
            "🗑" to "trash",
        )
    }
}

/** Builds the JSON body for POST /api/inbox/{id}/split. */
fun splitBodyJson(items: List<SuggestionItem>): String {
    val arr = JSONArray()
    for (it in items) {
        arr.put(
            JSONObject().apply {
                put("title", it.title)
                if (it.sp > 0) put("sp", it.sp) // absent: the server defaults to 1
                put("severity", it.severity ?: JSONObject.NULL)
                put("context", it.context ?: JSONObject.NULL)
                put("scheduled_date", it.scheduledDate ?: JSONObject.NULL)
                // the server rejects a start_time without a date, which would fail the whole split
                put("start_time", it.startTime?.takeIf { _ -> it.scheduledDate != null } ?: JSONObject.NULL)
            },
        )
    }
    return JSONObject().put("items", arr).toString()
}

/**
 * The "+2 h" reminder: now + 2 h rounded up to the next 15 min. Returns the scheduled_date to send (a timed
 * "YYYY-MM-DDTHH:MM", or just today's date when the slot crosses midnight) and the local alarm time.
 */
fun todayReminderSlot(now: LocalDateTime): Pair<String, LocalDateTime> {
    val base = now.plusHours(2)
    val minute = base.truncatedTo(ChronoUnit.MINUTES)
    val up = if (minute == base) minute else minute.plusMinutes(1)
    val at = up.plusMinutes(((15 - up.minute % 15) % 15).toLong())
    val date = if (at.toLocalDate() == now.toLocalDate()) at.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"))
    else now.toLocalDate().toString()
    return date to at
}
