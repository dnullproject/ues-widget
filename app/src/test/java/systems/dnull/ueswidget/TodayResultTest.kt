package systems.dnull.ueswidget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TodayResultTest {
    @Test fun normal() {
        val r = parseToday(
            """{"plan":{"day":"2026-10-29","items":[
              {"page_id":"1","title":"A","status":"done","start_time":"09:30"},
              {"page_id":"2","title":"B","status":"running","start_time":null},
              {"page_id":"3","title":"C","status":"planned"},
              {"page_id":"4","title":"D","status":"skipped","start_time":null}]}}"""
        )
        assertNull(r.error)
        assertEquals(3, r.items.size)
        assertEquals(1, r.done)
        assertEquals(listOf("09:30", null, null), r.items.map { it.startTime })
        assertEquals(listOf("1", "2", "3"), r.items.map { it.pageId })
    }

    @Test fun emptyItems() {
        val r = parseToday("""{"plan":{"day":"2026-10-29","items":[]}}""")
        assertNull(r.error)
        assertEquals(0, r.items.size)
    }

    @Test fun errorField() {
        val r = parseToday("""{"plan":null,"error":"boom"}""")
        assertEquals("boom", r.error)
        assertEquals(0, r.items.size)
    }
}
