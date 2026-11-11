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
    /** Nedtrapping på slutten. */
    val isCooldown: Boolean = false,
) {
    /** Teller med i treffprosenten (ikke oppvarming eller nedtrapping). */
    val countsForCompliance get() = !isWarmup && !isCooldown && !name.startsWith("Nedtrapping")

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
        list += Segment("Nedtrapping", min * 60, 40, 55, 75, 85, isCooldown = true)
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

    // ---------- Forskningsbaserte økter (brukes også i treningsplanene, se TrainingPlans) ----------

    /** NTNU/CERG 4×4 (Helgerud m.fl. 2007): 4 × 4 min på 90–95 % av makspuls, 3 min aktiv pause. */
    val ntnu4x4 = workout("ntnu4x4", "NTNU 4×4",
        "4 × 4 min hardt (90–95 % av makspuls ≈ 106–115 % av FTP) med 3 min aktiv pause. Kadensen varierer mellom dragene; drag 2 har en ståperiode.") {
        seg("Rask fot", 1.0, 65, 75, 100..110)
        seg("Rolig", 2.0, 55, 65, 85..95)
        seg("Drag 1/4 – jevnt", 4.0, 106, 112, 90..100)
        seg("Aktiv pause", 3.0, 55, 65, 85..95)
        seg("Drag 2/4 – tungt, stå opp", 4.0, 106, 112, 75..85, stand = listOf(120 to 180))
        seg("Aktiv pause", 3.0, 55, 65, 85..95)
        seg("Drag 3/4 – rask fot", 4.0, 106, 112, 98..108)
        seg("Aktiv pause", 3.0, 55, 65, 85..95)
        seg("Drag 4/4 – sitt, gi litt ekstra", 3.0, 106, 112, 90..100)
        seg("Drag 4/4 – siste minutt", 1.0, 112, 118, 95..105)
        cooldown(6)
    }

    /** NTNU/CERG 1×4 (Tjønna m.fl. 2013): ett drag på 4 min ved ca. 90 % av makspuls. */
    val ntnu1x4 = workout("ntnu1x4", "NTNU 1×4",
        "Ett eneste drag på 4 min (ca. 90 % av makspuls ≈ 105–112 % av FTP). Kort og effektivt.") {
        seg("Rask fot", 1.0, 65, 75, 100..110)
        seg("Rolig", 1.0, 55, 65, 85..95)
        seg("Opptrapping", 2.0, 80, 90, 90..100)
        seg("Rolig", 1.0, 55, 60, 85..95)
        seg("Drag 4 min – jevnt", 2.0, 105, 110, 90..100)
        seg("Drag – tyngre, stå opp", 1.0, 105, 110, 75..85, stand = listOf(0 to 40))
        seg("Drag – siste minutt", 1.0, 108, 112, 95..105)
        cooldown(5)
    }

    /** Rønnestad 30/15 (Rønnestad m.fl. 2020): 3 serier × 13 × (30 s hardt / 15 s rolig). */
    val ronnestad3015 = workout("ronnestad3015", "Rønnestad 30/15",
        "3 serier med 13 × 30 s hardt (ca. 120–130 % av FTP) og 15 s rolig, 3 min mellom seriene. Serie 1 middels kadens, serie 2 tyngre, serie 3 rask fot.") {
        seg("Rask fot", 1.0, 65, 75, 100..110)
        seg("Rolig", 2.0, 55, 65, 85..95)
        val cad = listOf(92..102, 82..92, 100..110)
        repeat(3) { serie ->
            repeat(13) { i ->
                seg("Serie $serie/3 · ${i}/13 hardt", 0.5, 118, 128, cad[serie - 1])
                seg("Serie $serie/3 · rolig", 0.25, 45, 55, 85..95)
            }
            if (serie < 3) seg("Pause mellom seriene", 3.0, 50, 60, 85..95)
        }
        cooldown(6)
    }

    /** Seiler-gruppen 4×8 (Seiler m.fl. 2013): 4 × 8 min ved ca. 90 % av makspuls, 2 min pause. */
    val seiler4x8 = workout("seiler4x8", "Seiler 4×8",
        "4 × 8 min rett over terskel (ca. 100–106 % av FTP) med 2 min pause. Hvert drag: 5 min sittende, så 3 min tyngre med ståperiode.") {
        seg("Rask fot", 1.0, 65, 75, 100..110)
        seg("Rolig", 2.0, 55, 65, 85..95)
        repeat(4) { i ->
            seg("Drag $i/4 – sittende", 5.0, 100, 105, 88..98)
            seg("Drag $i/4 – tyngre, stå opp", 3.0, 101, 106, 75..85, stand = listOf(60 to 120))
            if (i < 4) seg("Pause", 2.0, 50, 60, 85..95)
        }
        cooldown(6)
    }

    /** Rolig sone 2 for polarisert trening (Seiler; Stöggl & Sperlich 2014). */
    val zone2 = workout("sone2", "Sone 2 – 60 min",
        "Rolig, lang økt i sone 2 (65–72 % av FTP) med skiftende kadens. Hoveddelen av polarisert trening.") {
        repeat(3) {
            seg("Jevn sone 2", 10.0, 65, 72, 85..95)
            seg("Rask fot", 2.0, 62, 70, 100..110)
            seg("Tung fot – stå opp midt i", 4.0, 68, 74, 70..80, stand = listOf(90 to 150))
            seg("Jevn sone 2", 3.0, 65, 72, 85..95)
        }
        cooldown(3)
    }

    /** Sprintintervaller (Gibala-laben; Gist m.fl. 2014): 4–6 × 30 s alt du har, 4 min rolig. */
    private fun sprints(n: Int) = workout("sit$n", "Sprint $n×30 s",
        "$n × 30 s alt du har (sprint), 4 min helt rolig mellom. Svært hardt, men kort.") {
        seg("Rask fot", 1.0, 65, 75, 100..110)
        seg("Rolig", 2.0, 55, 65, 85..95)
        seg("Kort åpner", 0.25, 120, 140, 100..110)
        seg("Rolig", 2.0, 50, 60, 85..95)
        repeat(n) { i ->
            seg("Sprint $i/$n – alt du har", 0.5, 160, 220, 100..120)
            if (i < n) seg("Helt rolig", 4.0, 40, 50, 75..90)
        }
        cooldown(6)
    }
    val sit4 = sprints(4)
    val sit5 = sprints(5)
    val sit6 = sprints(6)

    /** Tabata (Tabata m.fl. 1996): 8 × 20 s svært hardt / 10 s pause. */
    val tabata = workout("tabata", "Tabata 8×20/10",
        "8 × 20 s svært hardt (ca. 150–170 % av FTP) med 10 s pause, så 15 min rolig sone 2.") {
        seg("Rask fot", 1.0, 65, 75, 100..110)
        seg("Rolig", 2.0, 55, 65, 85..95)
        seg("Kort åpner", 0.25, 120, 140, 100..110)
        seg("Rolig", 2.0, 50, 60, 85..95)
        repeat(8) { i ->
            seg("Tabata $i/8", 20 / 60.0, 150, 170, if (i % 2 == 1) 100..115 else 90..105)
            seg("Pause", 10 / 60.0, 30, 45, 70..90)
        }
        seg("Rolig sone 2", 15.0, 62, 70, 85..95)
        cooldown(3)
    }

    val all = listOf(endurance, threshold, vo2, climbSprint, pyramid, recovery, ftpTest,
        ntnu4x4, ntnu1x4, ronnestad3015, seiler4x8, zone2, sit4, sit5, sit6, tabata)

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
