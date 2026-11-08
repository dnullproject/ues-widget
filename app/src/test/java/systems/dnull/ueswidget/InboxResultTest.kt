package systems.dnull.ueswidget

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
}
