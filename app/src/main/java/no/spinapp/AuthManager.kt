package no.spinapp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import no.spinapp.core.AuthException
import no.spinapp.core.AuthSession
import no.spinapp.core.EmailOtpAuth

/** Innlogging med e-post og engangskode. Ingen navn eller passord. */
object AuthManager {
    private val auth: EmailOtpAuth? =
        if (BuildConfig.SUPABASE_URL.isNotBlank() && BuildConfig.SUPABASE_ANON_KEY.isNotBlank())
            EmailOtpAuth(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY)
        else null

    /** Er innlogging satt opp i denne versjonen av appen? */
    val isConfigured get() = auth != null

    private val _session = MutableStateFlow<AuthSession?>(null)
    val session: StateFlow<AuthSession?> = _session

    fun load() {
        _session.value = AppState.loadSession()
    }

    suspend fun sendCode(email: String) = withContext(Dispatchers.IO) {
        requireNotNull(auth).sendCode(email)
    }

    suspend fun verify(email: String, code: String) = withContext(Dispatchers.IO) {
        val s = requireNotNull(auth).verifyCode(email, code)
        AppState.saveSession(s)
        _session.value = s
    }

    /** Fornyer økta i bakgrunnen. Logger ut bare hvis serveren avviser den. */
    suspend fun refreshIfNeeded() = withContext(Dispatchers.IO) {
        val a = auth ?: return@withContext
        val s = _session.value ?: return@withContext
        if (!s.isExpired(System.currentTimeMillis() / 1000)) return@withContext
        try {
            val fresh = a.refresh(s)
            AppState.saveSession(fresh)
            _session.value = fresh
        } catch (e: AuthException) {
            if (e.isRejected) signOutLocal()
        }
    }

    suspend fun signOut() = withContext(Dispatchers.IO) {
        _session.value?.let { auth?.logout(it) }
        signOutLocal()
    }

    private fun signOutLocal() {
        AppState.saveSession(null)
        _session.value = null
    }
}
