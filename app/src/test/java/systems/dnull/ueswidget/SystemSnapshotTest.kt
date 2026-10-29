package systems.dnull.ueswidget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemSnapshotTest {
    @Test fun full() {
        val s = parseSystem(
            """{"generated_at":"x",
            "triage":{"page":1,"attention":2,"pipeline_healthy":true,"top":"memory: readiness"},
            "uptime":{"up":23,"total":24},"errors":{"unresolved":3},
            "quota":[{"pool":"gemini","window":"weekly","used_pct":84,"state":"warn","resets_in":"1d"},
                     {"pool":"claude","window":"7d","used_pct":7,"state":"ok"},
                     {"pool":"claude","window":"5h","used_pct":33,"state":"ok"},
                     {"pool":"gemini","window":"5h","used_pct":24,"state":"ok"}],
            "agents":{"running":1,"workers":[{"engine":"claude","task":"widget-api","minutes":12}],"ledgers":[]},
            "gaming":{"state":"running","game":"Hades"},
            "home":{"attention":["Front door open"]}}"""
        )
        assertEquals(1, s.triagePage)
        assertEquals("memory: readiness", s.triageTop)
        assertEquals(23, s.uptimeUp)
        assertEquals(3, s.errors)
        assertEquals("running", s.gamingState)
        assertEquals("Hades", s.game)
        assertEquals(listOf("Front door open"), s.homeAttention)
        assertEquals(listOf(SystemSnapshot.Worker("claude", "widget-api", 12)), s.workers)
        val g = quotaGroups(s.quota)
        assertEquals(listOf("C", "G"), g.map { it.first })
        assertEquals(listOf(33, 7), g[0].second.map { it.usedPct })
        assertEquals(listOf(24, 84), g[1].second.map { it.usedPct })
        assertTrue(s.hasSystem && s.hasAgents)
    }

    @Test fun allSourcesDown() {
        val s = parseSystem("""{"generated_at":"x","triage":null,"uptime":null,"errors":null,"quota":null,"agents":null,"gaming":null,"home":null}""")
        assertNull(s.triagePage)
        assertNull(s.gamingState)
        assertTrue(s.quota.isEmpty())
        assertFalse(s.hasSystem)
        assertFalse(s.hasAgents)
    }
}
