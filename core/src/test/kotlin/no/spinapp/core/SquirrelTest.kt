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
    fun speedFollowsCadence() {
        val p = SquirrelPlayer()
        assertEquals(100.0, p.frameDurationMs(SquirrelClip.SLOW_RUN, 85), 0.01)
        assertEquals(62.5, p.frameDurationMs(SquirrelClip.FAST_RUN, 105), 0.01)
        kotlin.test.assertTrue(p.frameDurationMs(SquirrelClip.FAST_RUN, 116) < 60.0)
        assertEquals(100.0, p.frameDurationMs(SquirrelClip.CLIMB, 65), 0.01)
        assertEquals(1000.0 / 6, p.frameDurationMs(SquirrelClip.IDLE, 0), 0.01)
    }

    @Test
    fun directorIgnoresShortSpikesAndSwitchesAfterDelay() {
        val d = SquirrelDirector() // 3 s
        fun run(sec: Double, w: Int, r: Int, paused: Boolean = false): SquirrelMode {
            var m = d.mode; var t = 0.0
            while (t < sec * 1000) { m = d.update(16.0, w, r, ftp, paused); t += 16.0 }
            return m
        }
        assertEquals(SquirrelMode.RUN, run(8.0, 130, 88))
        // 1 s med høy kadens: ingen bytte
        assertEquals(SquirrelMode.RUN, run(1.0, 130, 110))
        assertEquals(SquirrelMode.RUN, run(3.0, 130, 88))
        // Varig klatring: bytter etter en stund, ikke med en gang
        assertEquals(SquirrelMode.RUN, run(1.0, 180, 62))
        assertEquals(SquirrelMode.CLIMB, run(8.0, 180, 62))
        // Pause: står med en gang
        assertEquals(SquirrelMode.IDLE, run(0.1, 180, 62, paused = true))
        // Farten bruker utjevnet rpm rundet til 5
        run(10.0, 130, 92)
        assertEquals(90, d.animRpm)
    }

    @Test
    fun timingsFromStudio() {
        val p = SquirrelPlayer(mapOf(
            SquirrelClip.SLOW_RUN to doubleArrayOf(50.0, 150.0, 100.0, 100.0, 100.0, 100.0, 100.0, 100.0),
            SquirrelClip.IDLE to doubleArrayOf(300.0, 100.0, 100.0, 100.0, 100.0, 100.0, 100.0, 100.0),
        ))
        // Løp: rytme rundt kadensfarten (100 ms ved 85 rpm, snitt 100)
        assertEquals(50.0, p.frameDurationMs(SquirrelClip.SLOW_RUN, 85, 0), 0.01)
        assertEquals(150.0, p.frameDurationMs(SquirrelClip.SLOW_RUN, 85, 1), 0.01)
        // Høyere kadens: raskere, men samme rytme (bilde 2 tre ganger så langt som bilde 1)
        val a0 = p.frameDurationMs(SquirrelClip.SLOW_RUN, 120, 0)
        val a1 = p.frameDurationMs(SquirrelClip.SLOW_RUN, 120, 1)
        kotlin.test.assertTrue(a0 < 50.0)
        assertEquals(3.0, a1 / a0, 0.001)
        // Hvile: millisekundene brukes direkte
        assertEquals(300.0, p.frameDurationMs(SquirrelClip.IDLE, 0, 0), 0.01)
        // Uten timing for klippet: standard
        assertEquals(100.0, p.frameDurationMs(SquirrelClip.CLIMB, 65, 3), 0.01)
    }

    @Test
    fun switchesLoopsDirectlyWithFade() {
        val p = SquirrelPlayer()
        val d = p.frameDurationMs(SquirrelClip.IDLE, 0)
        p.advance(d * 2.5, SquirrelMode.IDLE, 0)
        assertEquals(2, p.frame)
        assertEquals(0.5, p.progress, 0.01)
        assertEquals(3, p.nextFrame)
        p.advance(1.0, SquirrelMode.CLIMB, 65)
        assertEquals(SquirrelClip.CLIMB, p.clip)
        assertEquals(SquirrelClip.IDLE to 2, p.fadeFrom)
        p.advance(125.0, SquirrelMode.CLIMB, 65)
        assertEquals(0.5, p.fadeAlpha, 0.02)
        p.advance(200.0, SquirrelMode.CLIMB, 65)
        assertEquals(null, p.fadeFrom)
        // Siste bilde blandes mot første
        repeat(20) { p.advance(p.frameDurationMs(p.clip, 65, p.frame), SquirrelMode.CLIMB, 65) }
        assertEquals((p.frame + 1) % 8, p.nextFrame)
    }
}
