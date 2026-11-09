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
        set(v) = prefs.edit().putInt("ftp", v.coerceIn(50, 600)).apply()

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

    /** Ny sang inn i hoveddragene (se MusicPolicy). */
    var spotifySmartSkip: Boolean
        get() = prefs.getBoolean("spotifySmartSkip", true)
        set(v) = prefs.edit().putBoolean("spotifySmartSkip", v).apply()

}
