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
        assertEquals(SquirrelMode.RUN, m.update(120, 80, ftp, false))            // 60 %, 80 rpm
        assertEquals(SquirrelMode.SPRINT, m.update(120, 95, ftp, false))         // høy rpm
        assertEquals(SquirrelMode.SPRINT, m.update(120, 80, ftp, false))         // hysterese: blir i sprint
        assertEquals(SquirrelMode.RUN, m.update(120, 75, ftp, false))            // under exit
        assertEquals(SquirrelMode.CLIMB, m.update(180, 60, ftp, false))          // 90 %, 60 rpm
        assertEquals(SquirrelMode.CLIMB, m.update(160, 68, ftp, false))          // 80 %, 68 rpm: fortsatt klatring
        assertEquals(SquirrelMode.RUN, m.update(150, 75, ftp, false))            // rpm > 72: slutt
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
    fun runSpeedFollowsCadence() {
        val p = SquirrelPlayer()
        assertEquals(160.0, p.frameDurationMs(SquirrelClip.RUN, 30))
        assertEquals(80.0, p.frameDurationMs(SquirrelClip.RUN, 110))
        assertEquals(120.0, p.frameDurationMs(SquirrelClip.RUN, 70))
        assertEquals(80.0, p.frameDurationMs(SquirrelClip.RUN, 140))
    }
}
