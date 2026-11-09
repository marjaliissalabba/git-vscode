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
