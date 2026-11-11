package no.spinapp.core

import kotlin.math.pow
import kotlin.math.roundToInt

enum class TargetStatus { NO_DATA, LOW, IN_RANGE, HIGH }

data class RideState(
    val elapsedSec: Int,
    val finished: Boolean,
    val segmentIndex: Int,
    val segment: Segment?,
    val nextSegment: Segment?,
    val segmentRemainingSec: Int,
    val totalRemainingSec: Int,
    val targetPowerLow: Int,
    val targetPowerHigh: Int,
    val powerW: Int?,
    val cadenceRpm: Int?,
    val heartRateBpm: Int?,
    val powerStatus: TargetStatus,
    val cadenceStatus: TargetStatus,
    val zone: Zone?,
    val stand: StandCue = StandCue(false, null, null),
)

data class RideSummary(
    val workoutName: String,
    val durationSec: Int,
    val avgPower: Int,
    val normalizedPower: Int,
    val maxPower: Int,
    val avgCadence: Int,
    val maxCadence: Int,
    val kilojoules: Int,
    val tss: Int,
    val powerCompliancePct: Int,
    val cadenceCompliancePct: Int,
    val secondsInZone: Map<Zone, Int>,
    val best20MinPower: Int?,
    val ftpTestResult: Int?,
    val suggestedFtp: Int?,
    /** Snitt-watt per 15 s, til grafen i loggen (ca. 240 tall per time). */
    val powerTrace: List<Int> = emptyList(),
    /** Virtuell distanse på flat vei (se [VirtualSpeed]), km. */
    val distanceKm: Double = 0.0,
) {
    /** Forbrent energi: arbeidet (kJ) med ca. 24 % virkningsgrad gir omtrent like mange kcal som kJ. */
    val kcal get() = (kilojoules / (4.184 * 0.24)).roundToInt()
    val avgSpeedKmh get() = if (durationSec > 0) distanceKm / (durationSec / 3600.0) else 0.0
}

/**
 * Virtuell fart på flat vei ut fra watt, som de fleste innendørsapper: luftmotstand
 * (CdA 0,32 m², luft 1,225 kg/m³) og rullemotstand (Crr 0,005) for rytter + 9 kg sykkel.
 */
object VirtualSpeed {
    fun speedMs(watts: Double, riderKg: Double): Double {
        if (watts <= 0) return 0.0
        val m = riderKg + 9.0
        val aero = 0.5 * 1.225 * 0.32
        val roll = 0.005 * m * 9.81
        // P = (aero·v² + roll)·v, løses med halvering
        var lo = 0.0; var hi = 40.0
        repeat(50) {
            val v = (lo + hi) / 2
            if ((aero * v * v + roll) * v > watts) hi = v else lo = v
        }
        return (lo + hi) / 2
    }

    /** Distanse i km for watt målt én gang i sekundet. */
    fun distanceKm(powerPerSecond: List<Int>, riderKg: Double): Double =
        powerPerSecond.sumOf { speedMs(it.toDouble(), riderKg) } / 1000.0
}

/**
 * Følger en økt sekund for sekund og samler statistikk.
 * [tick] kalles én gang i sekundet med siste måling.
 */
class RideSession(val workout: Workout, val ftp: Int) {
    private val powerLog = ArrayList<Int>()
    private val cadenceLog = ArrayList<Int>()
    private val zoneSeconds = mutableMapOf<Zone, Int>()
    private var powerHits = 0
    private var cadenceHits = 0
    private var ridingSeconds = 0
    /** Sekunder tråkket i intervallene som teller (ikke oppvarming/nedtrapping). */
    private var targetSeconds = 0
    private val ftpTestPower = ArrayList<Int>()
    var elapsedSec = 0
        private set

    fun tolerance(target: Int) = maxOf(5, (target * 0.03).roundToInt())

    fun status(value: Int?, low: Int, high: Int, tol: Int): TargetStatus = when {
        value == null -> TargetStatus.NO_DATA
        value < low - tol -> TargetStatus.LOW
        value > high + tol -> TargetStatus.HIGH
        else -> TargetStatus.IN_RANGE
    }

    fun state(sample: BikeSample?): RideState {
        val loc = workout.locate(elapsedSec)
        val seg = loc?.let { workout.segments[it.first] }
        val idx = loc?.first ?: workout.segments.size
        val segRemaining = if (loc != null && seg != null) seg.durationSec - loc.second else 0
        val pLow = seg?.powerLow(ftp) ?: 0
        val pHigh = seg?.powerHigh(ftp) ?: 0
        val power = sample?.powerW
        val cad = sample?.cadenceRpm
        return RideState(
            elapsedSec = elapsedSec,
            finished = loc == null,
            segmentIndex = idx,
            segment = seg,
            nextSegment = workout.segments.getOrNull(idx + 1),
            segmentRemainingSec = segRemaining,
            totalRemainingSec = (workout.durationSec - elapsedSec).coerceAtLeast(0),
            targetPowerLow = pLow,
            targetPowerHigh = pHigh,
            powerW = power,
            cadenceRpm = cad,
            heartRateBpm = sample?.heartRateBpm,
            powerStatus = if (seg == null) TargetStatus.NO_DATA else status(power, pLow, pHigh, tolerance(pLow)),
            cadenceStatus = if (seg == null) TargetStatus.NO_DATA else status(cad, seg.cadenceLow, seg.cadenceHigh, 3),
            zone = power?.let { Zone.forPower(it, ftp) },
            stand = StandCues.at(seg, loc?.second ?: 0),
        )
    }

    /** Registrer ett sekund. Returnerer tilstanden etter sekundet. */
    fun tick(sample: BikeSample?): RideState {
        val before = state(sample)
        if (before.finished) return before
        val seg = before.segment!!
        val p = sample?.powerW ?: 0
        val c = sample?.cadenceRpm ?: 0
        powerLog += p
        cadenceLog += c
        if (p > 0 || c > 0) {
            ridingSeconds++
            val z = Zone.forPower(p, ftp)
            zoneSeconds[z] = (zoneSeconds[z] ?: 0) + 1
            if (seg.countsForCompliance) {
                targetSeconds++
                if (before.powerStatus == TargetStatus.IN_RANGE) powerHits++
                if (before.cadenceStatus == TargetStatus.IN_RANGE) cadenceHits++
            }
        }
        if (seg.isFtpTest) ftpTestPower += p
        elapsedSec++
        return state(sample)
    }

    /** Er vi i oppvarmingen nå? */
    fun inWarmup(): Boolean {
        val loc = workout.locate(elapsedSec) ?: return false
        return workout.segments[loc.first].isWarmup
    }

    /** Hopper til første del etter oppvarmingen. Returnerer true hvis noe ble hoppet over. */
    fun skipWarmup(): Boolean {
        val loc = workout.locate(elapsedSec) ?: return false
        if (!workout.segments[loc.first].isWarmup) return false
        var i = loc.first
        while (i < workout.segments.size && workout.segments[i].isWarmup) i++
        elapsedSec = workout.startOf(i)
        return true
    }

    /**
     * Forrige: er man mer enn 3 s inn i intervallet, går den til starten av det,
     * ellers til starten av forrige intervall.
     */
    fun skipToPrevious(): Boolean {
        val loc = workout.locate(elapsedSec) ?: return false
        elapsedSec = if (loc.second > 3 || loc.first == 0) workout.startOf(loc.first) else workout.startOf(loc.first - 1)
        return true
    }

    /** Hopper til starten av neste intervall. Returnerer false hvis det ikke finnes noe neste. */
    fun skipToNext(): Boolean {
        val loc = workout.locate(elapsedSec) ?: return false
        if (loc.first + 1 >= workout.segments.size) return false
        elapsedSec = workout.startOf(loc.first + 1)
        return true
    }

    /** [riderKg] brukes bare til virtuell distanse og fart (ikke til FTP eller watt). */
    fun summary(riderKg: Double = 75.0): RideSummary {
        val n = powerLog.size
        val avgP = if (n == 0) 0 else powerLog.average().roundToInt()
        val riding = cadenceLog.filter { it > 0 }
        val avgC = if (riding.isEmpty()) 0 else riding.average().roundToInt()
        val np = normalizedPower(powerLog)
        val ifac = if (ftp > 0) np.toDouble() / ftp else 0.0
        val tss = (n * np * ifac / (ftp * 3600.0) * 100).roundToInt()
        val best20 = bestAverage(powerLog, 20 * 60)
        val ftpTest = if (ftpTestPower.size >= 18 * 60) (ftpTestPower.average() * 0.95).roundToInt() else null
        val fromBest20 = best20?.let { (it * 0.95).roundToInt() }
        val suggested = when {
            ftpTest != null -> ftpTest
            fromBest20 != null && fromBest20 > ftp -> fromBest20
            else -> null
        }
        return RideSummary(
            workoutName = workout.name,
            durationSec = n,
            avgPower = avgP,
            normalizedPower = np,
            maxPower = powerLog.maxOrNull() ?: 0,
            avgCadence = avgC,
            maxCadence = cadenceLog.maxOrNull() ?: 0,
            kilojoules = (powerLog.sumOf { it.toLong() } / 1000.0).roundToInt(),
            tss = tss,
            powerCompliancePct = if (targetSeconds == 0) 0 else powerHits * 100 / targetSeconds,
            cadenceCompliancePct = if (targetSeconds == 0) 0 else cadenceHits * 100 / targetSeconds,
            secondsInZone = Zone.entries.associateWith { zoneSeconds[it] ?: 0 },
            best20MinPower = best20,
            ftpTestResult = ftpTest,
            suggestedFtp = suggested,
            powerTrace = powerLog.chunked(15) { it.average().roundToInt() },
            distanceKm = VirtualSpeed.distanceKm(powerLog, riderKg),
        )
    }

    companion object {
        fun normalizedPower(p: List<Int>): Int {
            if (p.size < 30) return if (p.isEmpty()) 0 else p.average().roundToInt()
            var sum = 0.0
            var window = 0.0
            var count = 0
            for (i in p.indices) {
                window += p[i]
                if (i >= 30) window -= p[i - 30]
                if (i >= 29) {
                    sum += (window / 30.0).pow(4)
                    count++
                }
            }
            return (sum / count).pow(0.25).roundToInt()
        }

        fun bestAverage(p: List<Int>, seconds: Int): Int? {
            if (p.size < seconds) return null
            var window = p.take(seconds).sumOf { it.toLong() }
            var best = window
            for (i in seconds until p.size) {
                window += p[i] - p[i - seconds]
                if (window > best) best = window
            }
            return (best.toDouble() / seconds).roundToInt()
        }
    }
}

/**
 * Simulert rytter for testing uten sykkel: følger målet med litt støy og treghet.
 */
class SimulatedRider(seed: Long = 42) {
    private val rnd = java.util.Random(seed)
    private var power = 80.0
    private var cadence = 70.0

    fun next(target: Segment?, ftp: Int, nowMs: Long): BikeSample {
        val tp = target?.let { (it.powerLow(ftp) + it.powerHigh(ftp)) / 2.0 } ?: (ftp * 0.5)
        val tc = target?.midCadence?.toDouble() ?: 85.0
        power += (tp - power) * 0.25 + rnd.nextGaussian() * 6
        cadence += (tc - cadence) * 0.3 + rnd.nextGaussian() * 1.5
        return BikeSample(
            timestampMs = nowMs,
            powerW = power.roundToInt().coerceAtLeast(0),
            cadenceRpm = cadence.roundToInt().coerceAtLeast(0),
            speedKmh = cadence * 0.37,
            source = SourceType.SIMULATOR,
        )
    }
}
