package no.spinapp.core

import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.temporal.WeekFields
import java.util.Locale

/**
 * Én lagret økt. Bevisst liten (ca. 300 byte): bare oppsummeringen, ikke data per sekund.
 * Ingen personopplysninger.
 */
data class RideRecord(
    val startedAtEpochSec: Long,
    val workoutId: String,
    val workoutName: String,
    val durationSec: Int,
    val avgPower: Int,
    val normalizedPower: Int,
    val maxPower: Int,
    val avgCadence: Int,
    val kilojoules: Int,
    val tss: Int,
    val powerCompliancePct: Int,
    val cadenceCompliancePct: Int,
    val best20MinPower: Int?,
    val ftpAtRide: Int,
    val ftpTestResult: Int?,
    /** Sekunder i hver sone Z1–Z8 (tom for eldre økter). */
    val zoneSec: List<Int> = emptyList(),
    /** Snitt-watt per 15 s (tom for eldre økter). */
    val trace: List<Int> = emptyList(),
) {
    fun toJson(): String = JSONObject()
        .put("t", startedAtEpochSec)
        .put("id", workoutId)
        .put("n", workoutName)
        .put("d", durationSec)
        .put("ap", avgPower)
        .put("np", normalizedPower)
        .put("mp", maxPower)
        .put("ac", avgCadence)
        .put("kj", kilojoules)
        .put("tss", tss)
        .put("pc", powerCompliancePct)
        .put("cc", cadenceCompliancePct)
        .put("b20", best20MinPower ?: JSONObject.NULL)
        .put("ftp", ftpAtRide)
        .put("ftpt", ftpTestResult ?: JSONObject.NULL)
        .put("z", org.json.JSONArray(zoneSec))
        .put("tr", org.json.JSONArray(trace))
        .toString()

    fun localDate(zone: ZoneId = ZoneId.systemDefault()): LocalDate =
        Instant.ofEpochSecond(startedAtEpochSec).atZone(zone).toLocalDate()

    companion object {
        fun fromJson(line: String): RideRecord? = runCatching {
            val j = JSONObject(line)
            RideRecord(
                startedAtEpochSec = j.getLong("t"),
                workoutId = j.optString("id"),
                workoutName = j.optString("n"),
                durationSec = j.optInt("d"),
                avgPower = j.optInt("ap"),
                normalizedPower = j.optInt("np"),
                maxPower = j.optInt("mp"),
                avgCadence = j.optInt("ac"),
                kilojoules = j.optInt("kj"),
                tss = j.optInt("tss"),
                powerCompliancePct = j.optInt("pc"),
                cadenceCompliancePct = j.optInt("cc"),
                best20MinPower = if (j.isNull("b20")) null else j.optInt("b20"),
                ftpAtRide = j.optInt("ftp"),
                ftpTestResult = if (j.isNull("ftpt")) null else j.optInt("ftpt"),
                zoneSec = j.optJSONArray("z")?.let { a -> (0 until a.length()).map { a.optInt(it) } } ?: emptyList(),
                trace = j.optJSONArray("tr")?.let { a -> (0 until a.length()).map { a.optInt(it) } } ?: emptyList(),
            )
        }.getOrNull()

        fun from(summary: RideSummary, workoutId: String, ftp: Int, startedAtEpochSec: Long) = RideRecord(
            startedAtEpochSec = startedAtEpochSec,
            workoutId = workoutId,
            workoutName = summary.workoutName,
            durationSec = summary.durationSec,
            avgPower = summary.avgPower,
            normalizedPower = summary.normalizedPower,
            maxPower = summary.maxPower,
            avgCadence = summary.avgCadence,
            kilojoules = summary.kilojoules,
            tss = summary.tss,
            powerCompliancePct = summary.powerCompliancePct,
            cadenceCompliancePct = summary.cadenceCompliancePct,
            best20MinPower = summary.best20MinPower,
            ftpAtRide = ftp,
            ftpTestResult = summary.ftpTestResult,
            zoneSec = Zone.entries.map { summary.secondsInZone[it] ?: 0 },
            trace = summary.powerTrace,
        )
    }
}

/** Hvor lenge historikken beholdes. */
enum class Retention(val label: String, val days: Int?) {
    MONTHS_3("3 måneder", 92),
    MONTHS_6("6 måneder", 183),
    YEAR_1("1 år", 366),
    FOREVER("Til jeg sletter", null),
}

object HistoryPolicy {
    /** Øvre grense uansett innstilling, så filen aldri vokser seg stor (ca. 300 kB). */
    const val MAX_RECORDS = 1000

    /** Korte økter (under 5 min) lagres ikke. */
    const val MIN_DURATION_SEC = 5 * 60

    fun shouldStore(r: RideRecord) = r.durationSec >= MIN_DURATION_SEC

    /** Fjerner det som er for gammelt eller over maks antall. Nyeste beholdes. */
    fun prune(records: List<RideRecord>, nowEpochSec: Long, retention: Retention): List<RideRecord> {
        val cutoff = retention.days?.let { nowEpochSec - it * 86400L }
        return records
            .filter { cutoff == null || it.startedAtEpochSec >= cutoff }
            .sortedBy { it.startedAtEpochSec }
            .takeLast(MAX_RECORDS)
    }
}

data class WeekStats(val rides: Int, val minutes: Int, val tss: Int, val kilojoules: Int)

object HistoryStats {
    /** Minutter per sone for de siste [weeks] ukene (eldst først), til «Tid i soner». */
    fun zoneMinutesByWeek(records: List<RideRecord>, today: LocalDate, weeks: Int = 8, zone: ZoneId = ZoneId.systemDefault()): List<IntArray> {
        val wf = WeekFields.of(Locale("nb", "NO"))
        val thisWeekStart = today.with(wf.dayOfWeek(), 1)
        return (weeks - 1 downTo 0).map { back ->
            val start = thisWeekStart.minusWeeks(back.toLong())
            val acc = IntArray(Zone.entries.size)
            records.filter { val d = it.localDate(zone); !d.isBefore(start) && d.isBefore(start.plusDays(7)) }
                .forEach { r -> r.zoneSec.forEachIndexed { i, s -> if (i < acc.size) acc[i] += s } }
            IntArray(acc.size) { acc[it] / 60 }
        }
    }

    fun week(records: List<RideRecord>, today: LocalDate, zone: ZoneId = ZoneId.systemDefault()): WeekStats {
        val wf = WeekFields.of(Locale("nb", "NO"))
        val start = today.with(wf.dayOfWeek(), 1)
        val inWeek = records.filter {
            val d = it.localDate(zone)
            !d.isBefore(start) && ChronoUnit.DAYS.between(start, d) < 7
        }
        return WeekStats(
            rides = inWeek.size,
            minutes = inWeek.sumOf { it.durationSec } / 60,
            tss = inWeek.sumOf { it.tss },
            kilojoules = inWeek.sumOf { it.kilojoules },
        )
    }

    /** Beste FTP-estimat de siste [days] dagene (FTP-test eller 95 % av beste 20 min). */
    fun bestFtpEstimate(records: List<RideRecord>, nowEpochSec: Long, days: Int = 42): Int? {
        val cutoff = nowEpochSec - days * 86400L
        return records.filter { it.startedAtEpochSec >= cutoff }
            .mapNotNull { r -> r.ftpTestResult ?: r.best20MinPower?.let { (it * 0.95).toInt() } }
            .maxOrNull()
    }
}
