package systems.dnull.ueswidget

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class InboxResultTest {
    private val json = """
        {"items":[
          {"id":1,"raw_text":"milk","source":"watch","status":"new","created_at":"2026-11-08T10:00:00+00:00",
           "suggestion":{"title":"Buy milk","kind":"action","sp":3,"severity":"Med","context":"home",
             "scheduled_date":"2026-11-09","description":"At the store","steps":["go","buy"],"note":"soon"}},
          {"id":2,"raw_text":"raw idea","source":"watch","status":"new","created_at":"2026-11-08T11:00:00+00:00","suggestion":null},
          {"id":3,"raw_text":"done","source":"watch","status":"done","created_at":"2026-11-07T10:00:00+00:00",
           "suggestion":{"title":"Old","kind":"two_minute","sp":1}}
        ],"count":3}""".trimIndent()

    @Test fun parse() {
        val r = parseInbox(json)
        assertEquals(3, r.count)
        assertEquals(3, r.items.size)
        val first = r.items[0]
        assertEquals(1, first.id)
        assertEquals("milk", first.rawText)
        assertEquals("new", first.status)
        assertEquals("2026-11-08T10:00:00+00:00", first.createdAt)
        val s = first.suggestion!!
        assertEquals("Buy milk", s.title)
        assertEquals("action", s.kind)
        assertEquals(3, s.sp)
        assertEquals("Med", s.severity)
        assertEquals("home", s.context)
        assertEquals("2026-11-09", s.scheduledDate)
        assertEquals("At the store", s.description)
        assertEquals(listOf("go", "buy"), s.steps)
        assertEquals("soon", s.note)
        assertNull(r.items[1].suggestion)
        assertEquals(1, r.items[2].suggestion!!.sp)
        assertNull(r.items[2].suggestion!!.severity)
    }

    @Test fun countFallsBackToItems() {
        val r = parseInbox(
            """{"items":[{"id":1,"raw_text":"x","source":"w","status":"new","created_at":"2026-11-08T10:00:00+00:00",
              "suggestion":{"title":"T","kind":"action"}}]}""".trimIndent(),
        )
        assertEquals(1, r.count)
    }

    @Test fun fresh() {
        val now = Instant.parse("2026-11-08T12:00:00Z")
        fun item(id: Int, status: String = "new", created: String = "2026-11-08T11:00:00+00:00", suggested: Boolean = true) =
            InboxItem(id, "r", "watch", status, created, if (suggested) Suggestion("T$id", "action") else null)

        val items = listOf(
            item(1), // fresh -> notify
            item(2, created = "2026-11-07T10:00:00+00:00"), // older than 24 h
            item(3, suggested = false), // no suggestion
            item(4, status = "done"), // handled already
            item(5), // notified before
            item(6, created = "garbage"), // unparseable date
            item(7, created = "2026-11-08T13:00:00+00:00"), // future date
        )
        assertEquals(listOf(1), freshSuggestions(items, setOf(5), now).map { it.id })
    }

    @Test fun titleAndText() {
        val s = Suggestion("Buy milk", "action", 3, "Med", "home", "2026-11-09", "At the store", listOf("go"), "soon")
        val item = InboxItem(1, "milk", "watch", "new", "2026-11-08T10:00:00+00:00", s)
        assertEquals("Задача: Buy milk", suggestionTitle(item))
        assertEquals("09.11 · 3 SP · Med · home\nAt the store\nAI: soon", suggestionText(item))
        assertEquals("Нотатка? Buy milk", suggestionTitle(item.copy(suggestion = s.copy(kind = "reference"))))

        val bare = item.copy(rawText = "raw note", suggestion = Suggestion("X", "two_minute"))
        assertEquals("Задача: X", suggestionTitle(bare))
        assertEquals("без дати · 0 SP\nraw note", suggestionText(bare))
        assertEquals("09.11 · 0 SP", suggestionMeta(bare.copy(suggestion = Suggestion("X", "two_minute", scheduledDate = "2026-11-09"))))

        assertEquals("09.11", isoDateLabel("2026-11-09"))
        assertEquals("09.11 09:00–10:00", isoDateLabel("2026-11-09T09:00"))
        assertEquals("09.11 14:00–16:00", isoDateLabel("2026-11-09T14:00", "16:00"))
        assertEquals("nope", isoDateLabel("nope"))
    }

    @Test fun parseMultiItems() {
        val multiJson = """
            {"items":[
              {"id":10,"raw_text":"buy milk and call mom","source":"watch","status":"new","created_at":"2026-11-08T10:00:00+00:00",
               "suggestion":{"title":"Errands","kind":"multi","items":[
                 {"title":"Buy milk","sp":1,"severity":"Low","context":"home","scheduled_date":"2026-11-09","start_time":"14:00"},
                 {"title":"Call mom","sp":2,"severity":null,"context":null,"scheduled_date":null,"start_time":null}
               ]}}
            ]}""".trimIndent()
        val r = parseInbox(multiJson)
        val s = r.items.single().suggestion!!
        assertEquals("multi", s.kind)
        assertEquals(2, s.items.size)
        assertEquals(SuggestionItem("Buy milk", 1, "Low", "home", "2026-11-09", "14:00"), s.items[0])
        assertEquals(SuggestionItem("Call mom", 2, null, null, null, null), s.items[1])
    }

    @Test fun multiTitleAndText() {
        val items = listOf(
            SuggestionItem("Buy milk", 1, scheduledDate = "2026-11-09", startTime = "14:00"),
            SuggestionItem("Pay bills", 2, scheduledDate = "2026-11-10"),
            SuggestionItem("Call mom", 1),
        )
        val item = InboxItem(1, "raw", "watch", "new", "2026-11-08T10:00:00+00:00", Suggestion("Errands", "multi", items = items))
        assertEquals("Задачі (3): Errands", suggestionTitle(item))
        assertEquals(
            "• Buy milk — 09.11 14:00\n• Pay bills — 10.11\n• Call mom",
            suggestionText(item),
        )
    }

    @Test fun actionsPerKind() {
        val base = InboxItem(1, "raw", "watch", "new", "2026-11-08T10:00:00+00:00", Suggestion("T", "action"))
        assertEquals(
            listOf("⏰ +2 год" to "today", "✓ Створити" to "convert", "🗑" to "trash"),
            suggestionActions(base),
        )
        assertEquals(
            listOf("📌 Зберегти" to "reference", "✓ Таска" to "convert", "🗑" to "trash"),
            suggestionActions(base.copy(suggestion = Suggestion("T", "reference"))),
        )
        val two = listOf(SuggestionItem("A"), SuggestionItem("B"))
        assertEquals(
            listOf("✓ Створити 2" to "split", "🗑" to "trash"),
            suggestionActions(base.copy(suggestion = Suggestion("T", "multi", items = two))),
        )
        assertEquals(
            listOf("⏰ +2 год" to "today", "✓ Створити" to "convert", "🗑" to "trash"),
            suggestionActions(base.copy(suggestion = Suggestion("T", "multi", items = listOf(SuggestionItem("A"))))),
        )
    }

    @Test fun splitBodyJsonFormat() {
        val jsonStr = splitBodyJson(
            listOf(
                SuggestionItem("Buy milk", 1, "Low", "home", "2026-11-09", "14:00"),
                SuggestionItem("Call mom", 2),
            ),
        )
        val root = JSONObject(jsonStr)
        val arr = root.getJSONArray("items")
        assertEquals(2, arr.length())
        val first = arr.getJSONObject(0)
        assertEquals("Buy milk", first.getString("title"))
        assertEquals(1, first.getInt("sp"))
        assertEquals("Low", first.getString("severity"))
        assertEquals("home", first.getString("context"))
        assertEquals("2026-11-09", first.getString("scheduled_date"))
        assertEquals("14:00", first.getString("start_time"))
        val second = arr.getJSONObject(1)
        assertEquals("Call mom", second.getString("title"))
        assertEquals(2, second.getInt("sp"))
        assertEquals(true, second.isNull("severity"))
        assertEquals(true, second.isNull("scheduled_date"))
        assertEquals(true, second.isNull("start_time"))
    }

    @Test fun splitBodyDropsUndatedStartTimeAndZeroSp() {
        val item = JSONObject(splitBodyJson(listOf(SuggestionItem("Call", 0, startTime = "10:00")))).getJSONArray("items").getJSONObject(0)
        assertEquals(false, item.has("sp"))
        assertEquals(true, item.isNull("start_time"))
    }
}

class TodayReminderSlotTest {
    private fun slot(s: String) = todayReminderSlot(java.time.LocalDateTime.parse(s))

    @Test fun roundsUpToNextQuarter() {
        val (date, at) = slot("2026-11-09T10:07:30")
        assertEquals("2026-11-09T12:15", date)
        assertEquals(java.time.LocalDateTime.parse("2026-11-09T12:15:00"), at)
    }

    @Test fun exactQuarterStays() = assertEquals("2026-11-09T12:00", slot("2026-11-09T10:00:00").first)

    @Test fun secondsPushPastExactQuarter() = assertEquals("2026-11-09T12:15", slot("2026-11-09T10:00:01").first)

    @Test fun crossingMidnightSendsDateOnly() {
        val (date, at) = slot("2026-11-09T22:40:00")
        assertEquals("2026-11-09", date)
        assertEquals(java.time.LocalDateTime.parse("2026-11-10T00:45:00"), at)
    }
}
