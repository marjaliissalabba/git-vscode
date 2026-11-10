package no.spinapp.core

import kotlin.test.Test
import kotlin.test.assertEquals

class MascotTest {
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

    private fun sim(r: MascotRunner, sec: Double, mode: SquirrelMode, rpm: Int, w: Double = 9.0, h: Double = 4.0, each: (MascotRunner) -> Unit = {}) {
        var t = 0.0
        while (t < sec * 1000) { r.update(16.0, mode, rpm, w, h); each(r); t += 16.0 }
    }

    @Test
    fun mascotStaysInsideBox() {
        val r = MascotRunner(kotlin.random.Random(1))
        for (m in listOf(SquirrelMode.RUN, SquirrelMode.SPRINT, SquirrelMode.CLIMB, SquirrelMode.IDLE, SquirrelMode.CLIMB, SquirrelMode.RUN)) {
            sim(r, 15.0, m, 95) {
                kotlin.test.assertTrue(it.x in 0.0..8.0 && it.y in 0.0..3.0, "$m: ${it.x}, ${it.y}")
            }
        }
    }

    @Test
    fun mascotRunsBothWaysAndStandsStill() {
        val r = MascotRunner(kotlin.random.Random(2))
        val dirs = mutableSetOf<Boolean>()
        sim(r, 12.0, SquirrelMode.RUN, 90) { dirs += it.facingRight; assertEquals(MascotAnim.RUN, it.anim) }
        assertEquals(setOf(true, false), dirs)
        val x = r.x
        sim(r, 3.0, SquirrelMode.IDLE, 0) { assertEquals(x, it.x, 1e-9) }
        assertEquals(MascotAnim.IDLE, r.anim)
    }

    @Test
    fun mascotJumpsWhenSprintingAndClimbsWalls() {
        val r = MascotRunner(kotlin.random.Random(3))
        var maxY = 0.0
        sim(r, 10.0, SquirrelMode.SPRINT, 110) { maxY = maxOf(maxY, it.y) }
        kotlin.test.assertTrue(maxY > 0.8, "hopper: $maxY")
        val anims = mutableSetOf<MascotAnim>()
        var wallTop = 0.0
        sim(r, 20.0, SquirrelMode.CLIMB, 65) { anims += it.anim; if (it.onWall) wallTop = maxOf(wallTop, it.y) }
        kotlin.test.assertTrue(MascotAnim.WALL in anims)
        kotlin.test.assertTrue(wallTop > 2.5, "klatrer til toppen: $wallTop")
        kotlin.test.assertTrue(MascotAnim.DOUBLE_JUMP in anims) // hopper ut over kanten
        // Ut av klatring: slipper veggen og lander
        sim(r, 4.0, SquirrelMode.RUN, 85)
        assertEquals(0.0, r.y, 1e-9)
        assertEquals(MascotAnim.RUN, r.anim)
    }

    @Test
    fun runFramesFollowCadence() {
        val r = MascotRunner()
        assertEquals(20.0, r.fps(MascotAnim.RUN, 90), 0.01)
        kotlin.test.assertTrue(r.fps(MascotAnim.RUN, 116) > 25.0)
        assertEquals(20.0, r.fps(MascotAnim.IDLE, 0), 0.01)
    }
}
