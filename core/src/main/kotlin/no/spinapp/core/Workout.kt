package no.spinapp.core

import kotlin.math.roundToInt

/**
 * Treningssoner i prosent av FTP (FTP = 100 %). Fargene går fra lett (grå) til
 * hardest (rød, mørkerød, lilla). 100 % ligger i den oransje terskelsonen.
 */
enum class Zone(val label: String, val short: String, val lowPct: Int, val highPct: Int, val argb: Long) {
    Z1("Z1 Restitusjon", "Restitusjon", 0, 55, 0xFF8B90A3),
    Z2("Z2 Utholdenhet", "Utholdenhet", 56, 75, 0xFF4F8FE0),
    Z3("Z3 Tempo", "Tempo", 76, 87, 0xFF3DB877),
    Z4("Z4 Sweet spot", "Sweet spot", 88, 94, 0xFFE6C13A),
    Z5("Z5 Terskel", "Terskel", 95, 105, 0xFFEC9238),
    Z6("Z6 VO2maks", "VO2maks", 106, 120, 0xFFE2533F),
    Z7("Z7 Anaerob", "Anaerob", 121, 150, 0xFFA8382C),
    Z8("Z8 Sprint", "Sprint", 151, 999, 0xFF9B5AD6);

    companion object {
        fun forPct(pct: Double): Zone = entries.firstOrNull { pct <= it.highPct + 0.5 } ?: Z8
        fun forPower(watts: Int, ftp: Int): Zone = forPct(watts * 100.0 / ftp)
    }
}

/** Stå opp fra [fromSec] til [toSec] (sekunder inn i intervallet). */
data class StandWindow(val fromSec: Int, val toSec: Int) {
    val lengthSec get() = toSec - fromSec
}

data class Segment(
    val name: String,
    val durationSec: Int,
    val powerLowPct: Int,
    val powerHighPct: Int,
    val cadenceLow: Int,
    val cadenceHigh: Int,
    /** Brukes for FTP-test: snittet i dette segmentet gir ny FTP. */
    val isFtpTest: Boolean = false,
    /** Oppvarming kan hoppes over. */
    val isWarmup: Boolean = false,
    /** Perioder i intervallet der man skal stå opp (sekunder fra intervallets start). */
    val stand: List<StandWindow> = emptyList(),
) {
    val midPct get() = (powerLowPct + powerHighPct) / 2.0
    val midCadence get() = (cadenceLow + cadenceHigh) / 2
    val zone get() = Zone.forPct(midPct)

    fun powerLow(ftp: Int) = (ftp * powerLowPct / 100.0).roundToInt()
    fun powerHigh(ftp: Int) = (ftp * powerHighPct / 100.0).roundToInt()
}

data class Workout(
    val id: String,
    val name: String,
    val description: String,
    val segments: List<Segment>,
) {
    val durationSec get() = segments.sumOf { it.durationSec }

    /** Segmentet og sekundet inn i segmentet ved [elapsedSec]. Null når økta er ferdig. */
    fun locate(elapsedSec: Int): Pair<Int, Int>? {
        var t = elapsedSec
        segments.forEachIndexed { i, s ->
            if (t < s.durationSec) return i to t
            t -= s.durationSec
        }
        return null
    }

    fun startOf(index: Int) = segments.take(index).sumOf { it.durationSec }

    /** Treningsbelastning (TSS) hvis man treffer midten av alle mål. */
    /** Sekunder i hver sone (etter midten av målet), indeksert som [Zone.entries]. */
    fun zoneSeconds(): IntArray {
        val out = IntArray(Zone.entries.size)
        segments.forEach { out[it.zone.ordinal] += it.durationSec }
        return out
    }

    /** Høyeste mål i økta (% av FTP). */
    val peakPct get() = segments.maxOfOrNull { it.powerHighPct } ?: 0

    fun estimatedTss(): Int {
        var sum = 0.0
        for (s in segments) {
            val ifac = s.midPct / 100.0
            sum += s.durationSec * ifac * ifac
        }
        return (sum / 3600.0 * 100).roundToInt()
    }
}

/** Liten byggehjelp for økter. */
class WorkoutBuilder {
    private val list = mutableListOf<Segment>()

    fun seg(
        name: String, min: Double, low: Int, high: Int, cad: IntRange,
        ftpTest: Boolean = false, warmup: Boolean = false, stand: List<Pair<Int, Int>> = emptyList(),
    ) {
        list += Segment(
            name, (min * 60).roundToInt(), low, high, cad.first, cad.last, ftpTest, warmup,
            stand.map { StandWindow(it.first, it.second) },
        )
    }

    fun repeat(times: Int, block: WorkoutBuilder.(Int) -> Unit) {
        for (i in 1..times) block(i)
    }

    fun cooldown(min: Int = 5) {
        seg("Nedtrapping", min.toDouble(), 40, 55, 75..85)
    }

    fun build() = list.toList()
}

fun workout(id: String, name: String, description: String, block: WorkoutBuilder.() -> Unit) =
    Workout(id, name, description, WorkoutBuilder().apply(block).build())

/** Øktene inneholder ikke oppvarming; den legges på før start (se [WorkoutComposer]). */
object WorkoutLibrary {
    val endurance = workout("grunn45", "Grunntrening", "Rolig Z2 med kadensvariasjon. Bygger motor.") {
        repeat(3) {
            seg("Jevn Z2", 5.0, 65, 72, 85..95)
            seg("Rask fot", 2.0, 62, 70, 100..110)
            seg("Tung fot – stå opp midt i", 3.0, 70, 75, 70..80, stand = listOf(60 to 120))
        }
        cooldown(5)
    }

    val threshold = workout("terskel3x8", "Terskel 3×8", "Tre drag på terskel. Hever FTP.") {
        repeat(3) { i ->
            seg("Terskel $i/3", 8.0, 95, 100, 85..95)
            if (i < 3) seg("Pause", 4.0, 50, 55, 80..90)
        }
        seg("Rolig", 4.0, 50, 55, 80..90)
        cooldown(5)
    }

    val vo2 = workout("vo2x5", "VO2maks 5×3", "Korte, harde drag med høy kadens.") {
        seg("Rolig inn", 3.0, 55, 65, 85..95)
        repeat(5) { i ->
            seg("VO2 $i/5", 3.0, 110, 118, 95..105)
            seg("Pause", 3.0, 45, 55, 80..90)
        }
        cooldown(8)
    }

    val climbSprint = workout("klatring", "Klatring og sprint", "Tunge stigninger på lav kadens, stå opp mot toppen, korte sprinter.") {
        repeat(4) { i ->
            seg("Stigning $i/4", 4.0, 85, 95, 60..70, stand = listOf(150 to 210))
            seg("Toppen", 1.0, 100, 110, 70..80, stand = listOf(0 to 60))
            seg("Rolig", 2.0, 50, 55, 85..95)
            seg("Sprint", 0.5, 140, 170, 105..120)
            seg("Rolig", 2.5, 45, 55, 80..90)
        }
        cooldown(5)
    }

    val pyramid = workout("pyramide", "Pyramide", "1-2-3-4-3-2-1 minutter med stigende og synkende intensitet.") {
        val steps = listOf(1 to 105, 2 to 100, 3 to 95, 4 to 91, 3 to 95, 2 to 100, 1 to 105)
        steps.forEach { (m, p) ->
            seg("$m min drag", m.toDouble(), p, p + 5, 90..100)
            seg("Pause", m / 2.0 + 1, 50, 55, 80..90)
        }
        cooldown(6)
    }

    val recovery = workout("restitusjon", "Restitusjon", "Lett tråkk for å bli kvitt stølhet.") {
        seg("Lett", 10.0, 45, 55, 85..95)
        seg("Lett – rask fot", 5.0, 45, 55, 95..105)
        seg("Lett", 10.0, 45, 55, 85..95)
        cooldown(5)
    }

    val ftpTest = workout("ftptest", "FTP-test 20 min", "Kjør så hardt du klarer jevnt i 20 min. FTP = 95 % av snittet. Bruk minst 10 min oppvarming.") {
        repeat(3) {
            seg("Rask fot", 1.0, 70, 80, 105..115)
            seg("Rolig", 1.0, 50, 55, 85..95)
        }
        seg("Åpner – hardt", 5.0, 100, 110, 90..100)
        seg("Rolig", 8.0, 45, 55, 85..95)
        seg("TEST – jevnt og hardt", 20.0, 100, 110, 85..100, ftpTest = true)
        cooldown(10)
    }

    /** Kort testøkt som går gjennom alle figur-tilstandene. */
    val squirrelTest = workout("ekorntest", "Figur-test", "12 min som viser alle tilstandene til figuren ved måleren: står, løper, løper fort og hopper, og klatrer.") {
        seg("Stå stille – ikke tråkk", 0.75, 0, 5, 0..5)
        seg("Løp – jevnt", 1.5, 60, 70, 85..95)
        seg("Hoppende løp – rask fot", 1.5, 65, 75, 105..115)
        seg("Løp", 1.0, 60, 70, 85..95)
        seg("Klatring – tungt", 2.0, 85, 95, 60..68)
        seg("Over toppen – løp", 1.5, 55, 65, 85..95)
        seg("Sprint", 1.0, 140, 160, 105..115)
        seg("Klatring igjen", 1.5, 90, 100, 60..68)
        seg("Løp ut", 1.0, 55, 65, 85..95)
        seg("Stopp – ikke tråkk", 0.75, 0, 5, 0..5)
    }

    val all = listOf(endurance, threshold, vo2, climbSprint, pyramid, recovery, ftpTest, squirrelTest)

    fun byId(id: String) = all.firstOrNull { it.id == id }
}

/**
 * Enkel ukeplan i 4-ukers sykluser: tre ukers oppbygging, én roligere uke.
 */
object WeeklyPlanner {
    fun plan(sessionsPerWeek: Int, weekNumber: Int): List<Workout> {
        val n = sessionsPerWeek.coerceIn(1, 6)
        val restWeek = weekNumber % 4 == 0
        if (restWeek) {
            return listOf(WorkoutLibrary.endurance, WorkoutLibrary.recovery, WorkoutLibrary.endurance,
                WorkoutLibrary.recovery, WorkoutLibrary.endurance, WorkoutLibrary.recovery).take(n)
        }
        val hard = if (weekNumber % 2 == 0) WorkoutLibrary.vo2 else WorkoutLibrary.pyramid
        val order = listOf(
            WorkoutLibrary.threshold,
            WorkoutLibrary.endurance,
            hard,
            WorkoutLibrary.climbSprint,
            WorkoutLibrary.endurance,
            WorkoutLibrary.recovery,
        )
        return order.take(n)
    }
}
