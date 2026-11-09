package no.spinapp.core

import kotlin.math.roundToInt

/** Treningssoner i prosent av FTP (Coggan). */
enum class Zone(val label: String, val lowPct: Int, val highPct: Int, val argb: Long) {
    Z1("Z1 Restitusjon", 0, 55, 0xFF7E8A97),
    Z2("Z2 Utholdenhet", 56, 75, 0xFF2E86DE),
    Z3("Z3 Tempo", 76, 90, 0xFF10AC84),
    Z4("Z4 Terskel", 91, 105, 0xFFF5B800),
    Z5("Z5 VO2maks", 106, 120, 0xFFEE5A24),
    Z6("Z6 Anaerob", 121, 999, 0xFFC0392B);

    companion object {
        fun forPct(pct: Double): Zone = entries.firstOrNull { pct <= it.highPct + 0.5 } ?: Z6
        fun forPower(watts: Int, ftp: Int): Zone = forPct(watts * 100.0 / ftp)
    }
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

    fun seg(name: String, min: Double, low: Int, high: Int, cad: IntRange, ftpTest: Boolean = false) {
        list += Segment(name, (min * 60).roundToInt(), low, high, cad.first, cad.last, ftpTest)
    }

    fun repeat(times: Int, block: WorkoutBuilder.(Int) -> Unit) {
        for (i in 1..times) block(i)
    }

    fun warmup(min: Int = 10) {
        seg("Oppvarming", min * 0.5, 45, 55, 80..90)
        seg("Oppvarming", min * 0.3, 56, 65, 85..95)
        seg("Oppvarming – høy kadens", min * 0.2, 60, 70, 95..105)
    }

    fun cooldown(min: Int = 5) {
        seg("Nedtrapping", min.toDouble(), 40, 55, 75..85)
    }

    fun build() = list.toList()
}

fun workout(id: String, name: String, description: String, block: WorkoutBuilder.() -> Unit) =
    Workout(id, name, description, WorkoutBuilder().apply(block).build())

object WorkoutLibrary {
    val endurance = workout("grunn45", "Grunntrening 45", "Rolig Z2 med kadensvariasjon. Bygger motor.") {
        warmup(10)
        repeat(3) {
            seg("Jevn Z2", 5.0, 65, 72, 85..95)
            seg("Rask fot", 2.0, 62, 70, 100..110)
            seg("Tung fot", 3.0, 70, 75, 70..80)
        }
        cooldown(5)
    }

    val threshold = workout("terskel3x8", "Terskel 3×8", "Tre drag på terskel. Hever FTP.") {
        warmup(10)
        repeat(3) { i ->
            seg("Terskel $i/3", 8.0, 95, 100, 85..95)
            if (i < 3) seg("Pause", 4.0, 50, 55, 80..90)
        }
        seg("Rolig", 4.0, 50, 55, 80..90)
        cooldown(5)
    }

    val vo2 = workout("vo2x5", "VO2maks 5×3", "Korte, harde drag med høy kadens.") {
        warmup(12)
        repeat(5) { i ->
            seg("VO2 $i/5", 3.0, 110, 118, 95..105)
            seg("Pause", 3.0, 45, 55, 80..90)
        }
        cooldown(8)
    }

    val climbSprint = workout("klatring", "Klatring og sprint", "Tunge stigninger på lav kadens og korte sprinter.") {
        warmup(10)
        repeat(4) { i ->
            seg("Stigning $i/4", 4.0, 85, 95, 60..70)
            seg("Toppen – stå opp", 1.0, 100, 110, 70..80)
            seg("Rolig", 2.0, 50, 55, 85..95)
            seg("Sprint", 0.5, 140, 170, 105..120)
            seg("Rolig", 2.5, 45, 55, 80..90)
        }
        cooldown(5)
    }

    val pyramid = workout("pyramide", "Pyramide", "1-2-3-4-3-2-1 minutter med stigende og synkende intensitet.") {
        warmup(10)
        val steps = listOf(1 to 105, 2 to 100, 3 to 95, 4 to 91, 3 to 95, 2 to 100, 1 to 105)
        steps.forEach { (m, p) ->
            seg("$m min drag", m.toDouble(), p, p + 5, 90..100)
            seg("Pause", m / 2.0 + 1, 50, 55, 80..90)
        }
        cooldown(6)
    }

    val recovery = workout("restitusjon", "Restitusjon 30", "Lett tråkk for å bli kvitt stølhet.") {
        seg("Lett", 10.0, 45, 55, 85..95)
        seg("Lett – rask fot", 5.0, 45, 55, 95..105)
        seg("Lett", 10.0, 45, 55, 85..95)
        cooldown(5)
    }

    val ftpTest = workout("ftptest", "FTP-test 20 min", "Kjør så hardt du klarer jevnt i 20 min. FTP = 95 % av snittet.") {
        seg("Oppvarming", 10.0, 50, 65, 85..95)
        repeat(3) {
            seg("Rask fot", 1.0, 70, 80, 105..115)
            seg("Rolig", 1.0, 50, 55, 85..95)
        }
        seg("Åpner – hardt", 5.0, 100, 110, 90..100)
        seg("Rolig", 8.0, 45, 55, 85..95)
        seg("TEST – jevnt og hardt", 20.0, 100, 110, 85..100, ftpTest = true)
        cooldown(10)
    }

    val all = listOf(endurance, threshold, vo2, climbSprint, pyramid, recovery, ftpTest)

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
