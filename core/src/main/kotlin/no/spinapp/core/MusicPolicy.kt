package no.spinapp.core

/**
 * Når skal musikken bytte sang? Hoveddragene prioriteres: ny sang inn i et hardt drag,
 * men ikke inn i pauser, og ikke for ofte.
 */
object MusicPolicy {
    /** Fra denne intensiteten (midten av målet, % av FTP) regnes et intervall som et hoveddrag. */
    const val HARD_PCT = 85.0

    /** Korte stykker (sprinter) får ikke egen sang. */
    const val MIN_SEGMENT_SEC = 60

    /** Minste tid mellom to bytter. */
    const val MIN_GAP_SEC = 45

    /** Hardt rett over i hardt: bytt bare hvis forrige bytte er så lenge siden. */
    const val HARD_TO_HARD_GAP_SEC = 180

    fun isHard(s: Segment) = s.midPct >= HARD_PCT || s.isFtpTest

    /**
     * @param prev intervallet vi kommer fra (null ved start)
     * @param next intervallet som starter nå
     * @param secondsSinceLastSkip sekunder siden forrige sangbytte (null hvis aldri)
     */
    fun shouldSkip(prev: Segment?, next: Segment, secondsSinceLastSkip: Int?): Boolean {
        if (!isHard(next)) return false
        if (next.durationSec < MIN_SEGMENT_SEC) return false
        val since = secondsSinceLastSkip ?: Int.MAX_VALUE
        if (since < MIN_GAP_SEC) return false
        return if (prev != null && isHard(prev)) since >= HARD_TO_HARD_GAP_SEC else true
    }

    /** Hvor mange bytter en økt gir (for testing og forklaring). */
    fun countSkips(workout: Workout): Int {
        var last: Int? = null
        var count = 0
        var t = 0
        workout.segments.forEachIndexed { i, s ->
            val prev = workout.segments.getOrNull(i - 1)
            if (i > 0 && shouldSkip(prev, s, last?.let { t - it })) {
                count++
                last = t
            }
            t += s.durationSec
        }
        return count
    }
}

/** Tre tempo-lister i Spotify, valgt ut fra målkadensen. */
enum class CadenceBand(val label: String, val defaultName: String, val rpm: String) {
    HEAVY("Tung", "Spin Tung", "under 78 rpm"),
    MEDIUM("Middels", "Spin Middels", "78–97 rpm"),
    FAST("Rask", "Spin Rask", "98 rpm og over");

    companion object {
        fun forCadence(rpm: Int): CadenceBand = when {
            rpm < 78 -> HEAVY
            rpm < 98 -> MEDIUM
            else -> FAST
        }

        fun forSegment(s: Segment) = forCadence(s.midCadence)
    }
}

sealed interface MusicAction {
    data object None : MusicAction
    data object NextSong : MusicAction
    data class SwitchList(val band: CadenceBand) : MusicAction
}

object TempoListPolicy {
    /**
     * Hva skal skje med musikken når [next] starter?
     * - Pauser, oppvarming og korte sprinter: ingenting (sangen spiller videre).
     * - Hoveddrag med annen kadens enn lista som spiller: bytt liste.
     * - Hoveddrag med samme kadens: ny sang etter vanlig regel (MusicPolicy).
     */
    fun decide(prev: Segment?, next: Segment, secondsSinceLastChange: Int?, playing: CadenceBand?): MusicAction {
        if (!MusicPolicy.isHard(next) || next.durationSec < MusicPolicy.MIN_SEGMENT_SEC) return MusicAction.None
        val since = secondsSinceLastChange ?: Int.MAX_VALUE
        if (since < MusicPolicy.MIN_GAP_SEC) return MusicAction.None
        val wanted = CadenceBand.forSegment(next)
        if (wanted != playing) return MusicAction.SwitchList(wanted)
        return if (MusicPolicy.shouldSkip(prev, next, secondsSinceLastChange)) MusicAction.NextSong else MusicAction.None
    }

    /** Lista økta starter med (ut fra første intervall). */
    fun startBand(workout: Workout): CadenceBand =
        workout.segments.firstOrNull()?.let { CadenceBand.forSegment(it) } ?: CadenceBand.MEDIUM
}
