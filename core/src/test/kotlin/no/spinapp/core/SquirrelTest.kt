package no.spinapp.core

import kotlin.test.Test
import kotlin.test.assertEquals

class SquirrelTest {
    private val ftp = 200

    @Test
    fun modesFromMeasurements() {
        val m = SquirrelStateMachine()
        assertEquals(SquirrelMode.IDLE, m.update(null, 0, ftp, paused = false))
        assertEquals(SquirrelMode.IDLE, m.update(150, 90, ftp, paused = true))   // pause vinner
        assertEquals(SquirrelMode.RUN, m.update(120, 85, ftp, false))            // 60 %, 85 rpm: løp
        assertEquals(SquirrelMode.RUN, m.update(195, 90, ftp, false))            // terskel 97 %, 90 rpm: løp
        assertEquals(SquirrelMode.SPRINT, m.update(130, 105, ftp, false))        // rask fot: hoppende
        assertEquals(SquirrelMode.SPRINT, m.update(130, 97, ftp, false))         // hysterese
        assertEquals(SquirrelMode.RUN, m.update(130, 92, ftp, false))
        assertEquals(SquirrelMode.SPRINT, m.update(260, 92, ftp, false))         // 130 % og 92 rpm
        assertEquals(SquirrelMode.RUN, m.update(140, 85, ftp, false))
        // Stigning: 85–95 % på 60–70 rpm
        assertEquals(SquirrelMode.CLIMB, m.update(180, 66, ftp, false))
        assertEquals(SquirrelMode.CLIMB, m.update(175, 72, ftp, false))
        // Toppen: 100–110 % på 70–80 rpm, fortsatt oppover
        assertEquals(SquirrelMode.CLIMB, m.update(210, 78, ftp, false))
        // Pause etter toppen: 50 % → slutt på klatring
        assertEquals(SquirrelMode.RUN, m.update(105, 88, ftp, false))
    }

    @Test
    fun adjustableThresholds() {
        val m = SquirrelStateMachine(SquirrelThresholds(climbPct = 95))
        assertEquals(SquirrelMode.RUN, m.update(180, 60, ftp, false))            // 90 % < 95
        m.thresholds = SquirrelThresholds(climbPct = 80)
        assertEquals(SquirrelMode.CLIMB, m.update(180, 60, ftp, false))
    }

    @Test
    fun transitionsPlayOnceThenLoop() {
        val p = SquirrelPlayer()
        p.advance(10.0, SquirrelMode.RUN, 80)
        assertEquals(SquirrelClip.RUN, p.clip)
        // Inn i klatring: run_to_climb én gang
        p.advance(1.0, SquirrelMode.CLIMB, 60)
        assertEquals(SquirrelClip.RUN_TO_CLIMB, p.clip)
        p.advance(120.0 * 6, SquirrelMode.CLIMB, 60)
        assertEquals(SquirrelClip.CLIMB, p.clip)
        // Klatring looper
        p.advance(130.0 * 12, SquirrelMode.CLIMB, 60)
        assertEquals(SquirrelClip.CLIMB, p.clip)
        // Ut av klatring: over toppen én gang, så løping
        p.advance(1.0, SquirrelMode.RUN, 80)
        assertEquals(SquirrelClip.CLIMB_TO_RUN, p.clip)
        p.advance(120.0 * 6, SquirrelMode.RUN, 80)
        assertEquals(SquirrelClip.RUN, p.clip)
    }

    @Test
    fun transitionIsNotInterrupted() {
        val p = SquirrelPlayer()
        p.advance(1.0, SquirrelMode.RUN, 80)
        p.advance(1.0, SquirrelMode.CLIMB, 60)
        assertEquals(SquirrelClip.RUN_TO_CLIMB, p.clip)
        // Målet endres midt i overgangen: overgangen spilles ferdig, så over toppen
        p.advance(200.0, SquirrelMode.RUN, 80)
        assertEquals(SquirrelClip.RUN_TO_CLIMB, p.clip)
        p.advance(120.0 * 5, SquirrelMode.RUN, 80)
        assertEquals(SquirrelClip.CLIMB_TO_RUN, p.clip)
    }

    @Test
    fun speedFollowsCadence() {
        val p = SquirrelPlayer()
        assertEquals(1000.0 / 6, p.frameDurationMs(SquirrelClip.RUN, 60), 0.01)
        assertEquals(60000.0 / (116 * 6), p.frameDurationMs(SquirrelClip.SPRINT, 116), 0.01) // ca. 86 ms
        assertEquals(1000.0 / 6, p.frameDurationMs(SquirrelClip.CLIMB, 60), 0.01)
        kotlin.test.assertTrue(p.frameDurationMs(SquirrelClip.SPRINT, 116) < p.frameDurationMs(SquirrelClip.RUN, 80))
        assertEquals(250.0, p.frameDurationMs(SquirrelClip.IDLE, 0))
    }
}
