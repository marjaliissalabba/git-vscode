package no.spinapp.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MusicPolicyTest {
    private val easy = Segment("Pause", 180, 50, 55, 80, 90)
    private val hard = Segment("Drag", 240, 95, 100, 85, 95)
    private val sprint = Segment("Sprint", 30, 140, 170, 105, 120)

    @Test
    fun newSongIntoMainEffort() {
        assertTrue(MusicPolicy.shouldSkip(easy, hard, null))
        assertTrue(MusicPolicy.shouldSkip(easy, hard, 300))
    }

    @Test
    fun noSkipIntoRecoveryOrShortSprint() {
        assertFalse(MusicPolicy.shouldSkip(hard, easy, 999))
        assertFalse(MusicPolicy.shouldSkip(easy, sprint, 999))
    }

    @Test
    fun notTooOften() {
        assertFalse(MusicPolicy.shouldSkip(easy, hard, 20))
        assertFalse(MusicPolicy.shouldSkip(hard, hard, 120))
        assertTrue(MusicPolicy.shouldSkip(hard, hard, 200))
    }

    @Test
    fun balancedPerWorkout() {
        // Rolige økter: musikken går i fred
        assertEquals(0, MusicPolicy.countSkips(WorkoutLibrary.endurance))
        assertEquals(0, MusicPolicy.countSkips(WorkoutLibrary.recovery))
        // Ett bytte per hoveddrag
        assertEquals(3, MusicPolicy.countSkips(WorkoutLibrary.threshold))
        assertEquals(5, MusicPolicy.countSkips(WorkoutLibrary.vo2))
        // Ingen økt bytter oftere enn ca. hvert 3. minutt i snitt
        for (w in WorkoutLibrary.all) {
            val n = MusicPolicy.countSkips(w)
            assertTrue(n == 0 || w.durationSec / n >= 180, "${w.name}: $n bytter")
        }
    }
}

class TempoListPolicyTest {
    private val pause = Segment("Pause", 180, 50, 55, 80, 90)
    private val climb = Segment("Stigning", 240, 85, 95, 60, 70)
    private val threshold = Segment("Terskel", 480, 95, 100, 85, 95)
    private val vo2 = Segment("VO2", 180, 110, 118, 100, 110)
    private val sprint = Segment("Sprint", 30, 140, 170, 105, 120)

    @Test
    fun bands() {
        assertEquals(CadenceBand.HEAVY, CadenceBand.forSegment(climb))
        assertEquals(CadenceBand.MEDIUM, CadenceBand.forSegment(threshold))
        assertEquals(CadenceBand.FAST, CadenceBand.forSegment(vo2))
    }

    @Test
    fun switchesListIntoMainEffortWithOtherCadence() {
        assertEquals(MusicAction.SwitchList(CadenceBand.HEAVY), TempoListPolicy.decide(pause, climb, 300, CadenceBand.MEDIUM))
        assertEquals(MusicAction.SwitchList(CadenceBand.FAST), TempoListPolicy.decide(pause, vo2, 300, CadenceBand.MEDIUM))
    }

    @Test
    fun sameListGivesNewSongIntoMainEffort() {
        assertEquals(MusicAction.NextSong, TempoListPolicy.decide(pause, threshold, 300, CadenceBand.MEDIUM))
    }

    @Test
    fun pausesAndSprintsKeepTheMusic() {
        assertEquals(MusicAction.None, TempoListPolicy.decide(climb, pause, 300, CadenceBand.HEAVY))
        assertEquals(MusicAction.None, TempoListPolicy.decide(pause, sprint, 300, CadenceBand.MEDIUM))
        assertEquals(MusicAction.None, TempoListPolicy.decide(pause, vo2, 20, CadenceBand.MEDIUM))
    }

    @Test
    fun startBand() {
        assertEquals(CadenceBand.MEDIUM, TempoListPolicy.startBand(WorkoutLibrary.threshold))
    }
}
