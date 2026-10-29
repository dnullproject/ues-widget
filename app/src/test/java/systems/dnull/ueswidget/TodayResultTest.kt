package systems.dnull.ueswidget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class TodayResultTest {
    private val kyiv = ZoneId.of("Europe/Kyiv")

    @Test fun normal() {
        val r = parseToday(
            """{"plan":{"day":"2026-10-29","status":"active","items":[
              {"page_id":"1","title":"A","status":"done","start_time":"09:30","sp":3},
              {"page_id":"2","title":"B","status":"running","start_time":null},
              {"page_id":"3","title":"C","status":"planned"},
              {"page_id":"4","title":"D","status":"skipped","start_time":null}]},
              "next_event":{"title":"Standup","start":"2026-10-29T12:00:00+00:00","end":"x"},
              "bb":{"level":25,"label":"Low"}}""",
            kyiv,
        )
        assertNull(r.error)
        assertEquals(3, r.items.size)
        assertEquals(1, r.done)
        assertEquals(listOf("09:30", null, null), r.items.map { it.startTime })
        assertEquals(listOf("1", "2", "3"), r.items.map { it.pageId })
        assertEquals(listOf(3, 0, 0), r.items.map { it.sp })
        assertEquals(NextEvent("14:00", "Standup"), r.nextEvent)
        assertEquals(25, r.bbLevel)
        assertFalse(r.won)
    }

    @Test fun wonAndEmpty() {
        val r = parseToday("""{"plan":{"day":"2026-10-29","status":"won","items":[]},"next_event":null,"bb":null}""")
        assertNull(r.error)
        assertEquals(0, r.items.size)
        assertTrue(r.won)
        assertNull(r.nextEvent)
        assertNull(r.bbLevel)
    }

    @Test fun errorField() {
        val r = parseToday("""{"plan":null,"error":"boom"}""")
        assertEquals("boom", r.error)
        assertEquals(0, r.items.size)
    }

    @Test fun vitals() {
        val v = Vitals()
            .withBodyBattery("""{"available":true,"level":17,"charged_today":56,"drained_today":63}""")
            .withDailySummary("""{"body":{"sleep_score":47,"sleep_duration_h":3.13},"habits":{"done":2,"skipped":1,"missed":5}}""")
            .withHealth("""{"steps":5089,"resting_hr":null}""")
            .withInbox("""{"items":[{},{}],"count":12}""")
        assertEquals(Vitals(17, 47, 3.13, 5089, 2, 8, 12), v)
        assertEquals("5.1k", stepsLabel(5089))
        assertEquals("840", stepsLabel(840))
        assertEquals("3г08", hoursLabel(3.13))
    }

    @Test fun vitalsUnavailable() {
        val v = Vitals()
            .withBodyBattery("""{"available":false,"level":null}""")
            .withDailySummary("""{"body":{"sleep_score":null,"sleep_duration_h":null},"habits":{"done":0,"skipped":0,"missed":0}}""")
        assertEquals(Vitals(), v)
    }
}
