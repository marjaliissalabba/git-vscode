package no.spinapp

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import no.spinapp.core.NormRect
import no.spinapp.core.Segment
import no.spinapp.core.SourceType
import no.spinapp.sources.BikeDataSource
import java.time.LocalDate

/** Innstillinger og delt tilstand for appen. */
object AppState {
    private lateinit var prefs: SharedPreferences

    /** Målet akkurat nå (brukes av simulatoren). */
    val currentTarget = MutableStateFlow<Segment?>(null)

    /** Aktiv datakilde. */
    val source = MutableStateFlow<BikeDataSource?>(null)

    fun init(context: Context) {
        prefs = context.getSharedPreferences("spinapp", Context.MODE_PRIVATE)
        prefs.edit().remove("lastSummary").remove("authAccess").remove("authRefresh")
            .remove("authExpires").remove("authUser").remove("authEmail").apply()
        if (!prefs.contains("planStart")) {
            prefs.edit().putLong("planStart", LocalDate.now().toEpochDay()).apply()
        }
    }

    var ftp: Int
        get() = prefs.getInt("ftp", 180)
        set(v) {
            val nv = v.coerceIn(50, 600)
            prefs.edit().putInt("ftp", nv).apply()
            recordFtp(nv)
        }

    /** FTP-historikk som (dag, watt), én verdi per dag. Til grafen i Logg. */
    fun ftpHistory(): List<Pair<Long, Int>> {
        val raw = prefs.getString("ftpHist", null)
        val list = raw?.split(",")?.mapNotNull { e ->
            val p = e.split(":"); if (p.size == 2) (p[0].toLongOrNull() ?: return@mapNotNull null) to (p[1].toIntOrNull() ?: return@mapNotNull null) else null
        }.orEmpty()
        return list.ifEmpty { listOf(LocalDate.now().toEpochDay() to ftp) }
    }

    private fun recordFtp(v: Int) {
        val today = LocalDate.now().toEpochDay()
        val list = ftpHistory().filter { it.first != today } + (today to v)
        prefs.edit().putString("ftpHist", list.takeLast(50).joinToString(",") { "${it.first}:${it.second}" }).apply()
    }

    var sessionsPerWeek: Int
        get() = prefs.getInt("sessionsPerWeek", 3)
        set(v) = prefs.edit().putInt("sessionsPerWeek", v.coerceIn(1, 6)).apply()

    val weekNumber: Int
        get() {
            val start = prefs.getLong("planStart", LocalDate.now().toEpochDay())
            return ((LocalDate.now().toEpochDay() - start) / 7).toInt() + 1
        }

    var sourceType: SourceType
        get() = runCatching { SourceType.valueOf(prefs.getString("sourceType", null)!!) }
            .getOrDefault(SourceType.SIMULATOR)
        set(v) = prefs.edit().putString("sourceType", v.name).apply()

    var bleAddress: String?
        get() = prefs.getString("bleAddress", null)
        set(v) = prefs.edit().putString("bleAddress", v).apply()

    var bleName: String?
        get() = prefs.getString("bleName", null)
        set(v) = prefs.edit().putString("bleName", v).apply()

    /** LCD-glassets plassering i kamerabildet (normalisert, stående bilde). */
    var lcdRect: NormRect
        get() = NormRect(
            prefs.getFloat("lcdL", 0.30f),
            prefs.getFloat("lcdT", 0.20f),
            prefs.getFloat("lcdR", 0.70f),
            prefs.getFloat("lcdB", 0.80f),
        )
        set(r) = prefs.edit()
            .putFloat("lcdL", r.left).putFloat("lcdT", r.top)
            .putFloat("lcdR", r.right).putFloat("lcdB", r.bottom)
            .apply()

    var retention: no.spinapp.core.Retention
        get() = runCatching { no.spinapp.core.Retention.valueOf(prefs.getString("retention", null)!!) }
            .getOrDefault(no.spinapp.core.Retention.YEAR_1)
        set(v) = prefs.edit().putString("retention", v.name).apply()

    /** Spotify-spilleliste (spotify:-URI) som åpnes til økta. */
    var spotifyUri: String?
        get() = prefs.getString("spotifyUri", null)
        set(v) = prefs.edit().putString("spotifyUri", v).apply()

    /** Start/pause Spotify sammen med økta. */
    var spotifyControl: Boolean
        get() = prefs.getBoolean("spotifyControl", true)
        set(v) = prefs.edit().putBoolean("spotifyControl", v).apply()

    /** Terskler for ekornet ved måleren (justeres under Profil → Ekornet). */
    var squirrelThresholds: no.spinapp.core.SquirrelThresholds
        get() {
            val d = no.spinapp.core.SquirrelThresholds()
            fun g(k: String, v: Int) = prefs.getInt("sq_$k", v)
            return no.spinapp.core.SquirrelThresholds(
                idleRpm = g("idleRpm", d.idleRpm),
                idlePct = g("idlePct", d.idlePct),
                sprintRpm = g("sprintRpm", d.sprintRpm),
                sprintPct = g("sprintPct", d.sprintPct),
                sprintAltPct = g("sprintAltPct", d.sprintAltPct),
                sprintAltRpm = g("sprintAltRpm", d.sprintAltRpm),
                sprintExitRpm = g("sprintExitRpm", d.sprintExitRpm),
                sprintExitPct = g("sprintExitPct", d.sprintExitPct),
                climbPct = g("climbPct", d.climbPct),
                climbMaxRpm = g("climbMaxRpm", d.climbMaxRpm),
                climbExitPct = g("climbExitPct", d.climbExitPct),
                climbExitRpm = g("climbExitRpm", d.climbExitRpm),
            )
        }
        set(t) {
            prefs.edit()
                .putInt("sq_idleRpm", t.idleRpm)
                .putInt("sq_idlePct", t.idlePct)
                .putInt("sq_sprintRpm", t.sprintRpm)
                .putInt("sq_sprintPct", t.sprintPct)
                .putInt("sq_sprintAltPct", t.sprintAltPct)
                .putInt("sq_sprintAltRpm", t.sprintAltRpm)
                .putInt("sq_sprintExitRpm", t.sprintExitRpm)
                .putInt("sq_sprintExitPct", t.sprintExitPct)
                .putInt("sq_climbPct", t.climbPct)
                .putInt("sq_climbMaxRpm", t.climbMaxRpm)
                .putInt("sq_climbExitPct", t.climbExitPct)
                .putInt("sq_climbExitRpm", t.climbExitRpm)
                .apply()
        }

    /** Ønsket oppvarming i minutter (legges foran alle økter). */
    var warmupMinutes: Int
        get() = prefs.getInt("warmupMinutes", 10)
        set(v) = prefs.edit().putInt("warmupMinutes", v).apply()

    /** Client ID fra appen du registrerte på developer.spotify.com (ikke hemmelig). */
    var spotifyClientId: String
        get() = prefs.getString("spotifyClientId", "") ?: ""
        set(v) = prefs.edit().putString("spotifyClientId", v.trim()).apply()

    /** Bytt mellom tempo-listene (Spin Tung / Middels / Rask) ut fra kadensen. */
    var spotifyTempoLists: Boolean
        get() = prefs.getBoolean("spotifyTempoLists", true)
        set(v) = prefs.edit().putBoolean("spotifyTempoLists", v).apply()

    fun playlistName(band: no.spinapp.core.CadenceBand): String =
        prefs.getString("playlist_" + band.name, null) ?: band.defaultName

    fun setPlaylistName(band: no.spinapp.core.CadenceBand, name: String) =
        prefs.edit().putString("playlist_" + band.name, name.trim().ifEmpty { band.defaultName }).apply()

    /** Ny sang inn i hoveddragene (se MusicPolicy). */
    var spotifySmartSkip: Boolean
        get() = prefs.getBoolean("spotifySmartSkip", true)
        set(v) = prefs.edit().putBoolean("spotifySmartSkip", v).apply()

}
