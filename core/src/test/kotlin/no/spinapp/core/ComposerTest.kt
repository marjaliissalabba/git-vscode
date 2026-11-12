package no.spinapp.core

import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ComposerTest {
    @Test
    fun warmupLengths() {
        assertTrue(Warmup.build(0).isEmpty())
        for (m in Warmup.OPTIONS_MIN.filter { it > 0 }) {
            val w = Warmup.build(m)
            assertEquals(m * 60, w.sumOf { it.durationSec })
            assertTrue(w.all { it.isWarmup })
        }
    }

    @Test
    fun composeAddsWarmupInFront() {
        val c = WorkoutComposer.compose(WorkoutLibrary.threshold, 8)
        assertTrue(c.segments.first().isWarmup)
        assertEquals(WorkoutLibrary.threshold.durationSec + 8 * 60, c.durationSec)
    }

    @Test
    fun scaleTo80Percent() {
        val w = WorkoutLibrary.climbSprint // 45 min
        assertEquals(45, WorkoutComposer.mainMinutes(w))
        val s = WorkoutComposer.scale(w, 36) // 80 %
        val total = s.durationSec
        assertTrue(total in 35 * 60..37 * 60, "varer $total s")
        // Hvert intervall ca. 20 % kortere, og ståperiodene følger med
        val climb = s.segments.first()
        assertEquals(190, climb.durationSec) // 240 s * 0.8 = 192 → 190
        assertEquals(StandWindow(120, 168), climb.stand.single())
        // Sprint på 30 s blir 25 s, ikke under minstegrensen
        assertTrue(s.segments.all { it.durationSec >= WorkoutComposer.MIN_SEGMENT_SEC })
    }

    @Test
    fun scaleWithWarmupKeepsWarmupLength() {
        val c = WorkoutComposer.compose(WorkoutLibrary.threshold, 10, targetMinutes = 30)
        assertEquals(600, c.segments.filter { it.isWarmup }.sumOf { it.durationSec })
        assertTrue(c.segments.filter { !it.isWarmup }.sumOf { it.durationSec } in 29 * 60..31 * 60)
    }

    @Test
    fun lengthOptionsIncludeOriginal() {
        val o = WorkoutComposer.lengthOptions(WorkoutLibrary.climbSprint)
        assertTrue(45 in o && 25 in o && 70 in o && 40 in o, "$o")
    }

    @Test
    fun standCues() {
        val seg = WorkoutLibrary.climbSprint.segments.first() // stå 150–210
        assertEquals(StandCue(false, null, null), StandCues.at(seg, 100))
        assertEquals(StandCue(false, null, 5), StandCues.at(seg, 145))
        assertEquals(StandCue(true, 60, null), StandCues.at(seg, 150))
        assertEquals(StandCue(true, 1, null), StandCues.at(seg, 209))
        assertFalse(StandCues.at(seg, 210).standing)
    }

    @Test
    fun skipToNext() {
        val s = RideSession(WorkoutLibrary.threshold, 200)
        s.tick(null)
        assertTrue(s.skipToNext())
        assertEquals("Pause", s.state(null).segment?.name)
        while (s.skipToNext()) { /* til siste */ }
        assertEquals("Nedtrapping", s.state(null).segment?.name)
        assertFalse(s.skipToNext())
    }

    @Test
    fun skipToPrevious() {
        val s = RideSession(WorkoutLibrary.threshold, 200)
        s.skipToNext() // til Pause
        repeat(10) { s.tick(null) }
        assertTrue(s.skipToPrevious()) // > 3 s inn: start av Pause
        assertEquals("Pause", s.state(null).segment?.name)
        assertEquals(0, s.state(null).segmentRemainingSec - s.state(null).segment!!.durationSec)
        assertTrue(s.skipToPrevious()) // < 3 s inn: forrige intervall
        assertEquals("Terskel 1/3", s.state(null).segment?.name)
    }

    @Test
    fun jsonRoundTrip() {
        val w = WorkoutLibrary.climbSprint.copy(id = "custom-1", name = "Min klatring")
        val back = WorkoutJson.fromJson(JSONObject(WorkoutJson.toJson(w).toString()))
        assertEquals(w, back)
        assertEquals(listOf(w), WorkoutJson.listFromJson(WorkoutJson.listToJson(listOf(w))))
        assertNull(WorkoutJson.fromJson(JSONObject("{}")))
        assertTrue(WorkoutJson.listFromJson("søppel").isEmpty())
    }
}
