package systems.dnull.ueswidget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlarmsTest {
    @Test
    fun parseAlarmsReadsValidEntriesAndSkipsInvalid() {
        val json = """
            {
              "generated_at": 1763800000,
              "lead": "both",
              "alarms": [
                {
                  "id": "t1:1763880300",
                  "task_id": "t1",
                  "title": "Автосервіс",
                  "date": "2026-11-23",
                  "start": "09:00",
                  "kind": "pre",
                  "fire_at": 1763880300
                },
                {
                  "id": "",
                  "task_id": "t2",
                  "title": "Bad",
                  "fire_at": 1763880300
                },
                {
                  "id": "t3:0",
                  "task_id": "t3",
                  "title": "Zero",
                  "fire_at": 0
                }
              ]
            }
        """.trimIndent()
        val parsed = parseAlarms(json)
        assertEquals(1, parsed.size)
        assertEquals(
            Alarm(
                id = "t1:1763880300",
                taskId = "t1",
                title = "Автосервіс",
                date = "2026-11-23",
                start = "09:00",
                kind = "pre",
                fireAt = 1763880300L,
            ),
            parsed[0],
        )
    }

    @Test
    fun parseAlarmsEmptyOrMissingArray() {
        assertTrue(parseAlarms("{}").isEmpty())
        assertTrue(parseAlarms("""{"alarms":[]}""").isEmpty())
    }

    @Test
    fun serializeAndParseRoundTrip() {
        val alarms = listOf(
            Alarm("a:100", "a", "Call", "2026-11-23", "10:00", "at", 100L),
            Alarm("b:200", "b", "Prep", "2026-11-23", "10:15", "pre", 200L),
        )
        assertEquals(alarms, parseAlarms(serializeAlarms(alarms)))
    }

    @Test
    fun diffAlarmsSchedulesNewCancelsRemovedAndHandlesGraceWindow() {
        val nowS = 1000L
        val fresh = listOf(
            Alarm("kept:1200", "k", "Kept", "2026-11-23", "10:00", "at", 1200L),
            Alarm("future:1500", "f", "Future", "2026-11-23", "10:05", "at", 1500L),
            Alarm("grace:900", "g", "Just passed", "2026-11-23", "09:58", "at", 900L),
            Alarm("stale:800", "s", "Too old", "2026-11-23", "09:55", "at", 800L),
        )
        val diff = diffAlarms(
            scheduled = setOf("kept:1200", "removed:1100"),
            fresh = fresh,
            nowS = nowS,
        )
        assertEquals(setOf("removed:1100"), diff.toCancel)
        assertEquals(listOf("future:1500", "grace:900"), diff.toSchedule.map { it.id })
    }

    @Test
    fun notificationTitles() {
        assertEquals("⏰ 09:00 — Автосервіс", alarmNotificationTitle("09:00", "Автосервіс", "at"))
        assertEquals("⏰ за 15 хв: 09:00 — Автосервіс", alarmNotificationTitle("09:00", "Автосервіс", "pre"))
    }
}
