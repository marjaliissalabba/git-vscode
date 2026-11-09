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

    fun saveSession(s: no.spinapp.core.AuthSession?) {
        val e = prefs.edit()
        if (s == null) {
            e.remove("authAccess").remove("authRefresh").remove("authExpires").remove("authUser")
        } else {
            e.putString("authAccess", s.accessToken).putString("authRefresh", s.refreshToken)
                .putLong("authExpires", s.expiresAtEpochSec).putString("authUser", s.userId)
                .putString("authEmail", s.email)
        }
        e.apply()
    }

    fun loadSession(): no.spinapp.core.AuthSession? {
        val access = prefs.getString("authAccess", null) ?: return null
        val refresh = prefs.getString("authRefresh", null) ?: return null
        return no.spinapp.core.AuthSession(
            access, refresh, prefs.getLong("authExpires", 0),
            prefs.getString("authUser", "") ?: "", prefs.getString("authEmail", "") ?: "",
        )
    }

    /** Sist brukte e-post, så den er fylt ut neste gang. */
    val lastEmail: String?
        get() = prefs.getString("authEmail", null)

    var lastSummary: String?
        get() = prefs.getString("lastSummary", null)
        set(v) = prefs.edit().putString("lastSummary", v).apply()
}
