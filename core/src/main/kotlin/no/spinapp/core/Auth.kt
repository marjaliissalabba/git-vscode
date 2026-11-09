package no.spinapp.core

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Innlogget økt. Vi lagrer ingen navn, bare e-post og bruker-id fra innloggingstjenesten. */
data class AuthSession(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtEpochSec: Long,
    val userId: String,
    val email: String,
) {
    fun isExpired(nowEpochSec: Long, marginSec: Long = 60) = nowEpochSec >= expiresAtEpochSec - marginSec
}

class AuthException(message: String, val httpCode: Int = 0) : Exception(message) {
    /** Sant når feilen skyldes ugyldig/utløpt innlogging, ikke nettverket. */
    val isRejected get() = httpCode in 400..499
}

data class HttpResponse(val code: Int, val body: String)

fun interface HttpTransport {
    fun post(url: String, headers: Map<String, String>, body: String): HttpResponse
}

/** Standard HTTP via HttpURLConnection (fungerer både på JVM og Android). */
object UrlConnectionTransport : HttpTransport {
    override fun post(url: String, headers: Map<String, String>, body: String): HttpResponse {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            conn.doOutput = true
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            return HttpResponse(code, text)
        } finally {
            conn.disconnect()
        }
    }
}

/**
 * Innlogging med e-post og engangskode (Supabase Auth, OTP).
 * 1. [sendCode] sender en kode til e-posten (brukeren opprettes automatisk første gang).
 * 2. [verifyCode] bytter koden mot en økt.
 */
class EmailOtpAuth(
    baseUrl: String,
    private val anonKey: String,
    private val transport: HttpTransport = UrlConnectionTransport,
    private val clock: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    private val base = baseUrl.trimEnd('/')

    private fun headers(accessToken: String? = null) = buildMap {
        put("apikey", anonKey)
        put("Content-Type", "application/json")
        put("Authorization", "Bearer ${accessToken ?: anonKey}")
    }

    fun sendCode(email: String) {
        val clean = normalizeEmail(email) ?: throw AuthException("Skriv inn en gyldig e-postadresse")
        val body = JSONObject().put("email", clean).put("create_user", true).toString()
        val res = call { transport.post("$base/auth/v1/otp", headers(), body) }
        when {
            res.code in 200..299 -> Unit
            res.code == 429 -> throw AuthException("For mange forsøk. Vent litt før du ber om ny kode.", res.code)
            else -> throw AuthException("Kunne ikke sende kode (${errorText(res)})", res.code)
        }
    }

    fun verifyCode(email: String, code: String): AuthSession {
        val clean = normalizeEmail(email) ?: throw AuthException("Skriv inn en gyldig e-postadresse")
        val token = code.filter { it.isDigit() }
        if (token.length !in 6..10) throw AuthException("Koden har 6 sifre")
        val body = JSONObject().put("type", "email").put("email", clean).put("token", token).toString()
        val res = call { transport.post("$base/auth/v1/verify", headers(), body) }
        if (res.code !in 200..299) {
            throw AuthException(
                if (res.code in 400..403) "Feil eller utløpt kode. Prøv igjen eller be om ny kode." else "Innlogging feilet (${errorText(res)})",
                res.code,
            )
        }
        return parseSession(res.body, clean)
    }

    fun refresh(session: AuthSession): AuthSession {
        val body = JSONObject().put("refresh_token", session.refreshToken).toString()
        val res = call { transport.post("$base/auth/v1/token?grant_type=refresh_token", headers(), body) }
        if (res.code !in 200..299) throw AuthException("Innloggingen er utløpt. Logg inn på nytt.", res.code)
        return parseSession(res.body, session.email)
    }

    fun logout(session: AuthSession) {
        runCatching { transport.post("$base/auth/v1/logout", headers(session.accessToken), "{}") }
    }

    private inline fun call(block: () -> HttpResponse): HttpResponse =
        try {
            block()
        } catch (e: java.io.IOException) {
            throw AuthException("Ingen kontakt med serveren. Sjekk nettet og prøv igjen.")
        }

    private fun errorText(res: HttpResponse): String = runCatching {
        val j = JSONObject(res.body)
        j.optString("msg").ifEmpty { j.optString("error_description") }.ifEmpty { j.optString("message") }
    }.getOrNull()?.ifEmpty { null } ?: "kode ${res.code}"

    private fun parseSession(json: String, fallbackEmail: String): AuthSession {
        val j = runCatching { JSONObject(json) }.getOrNull() ?: throw AuthException("Uventet svar fra serveren")
        val access = j.optString("access_token")
        val refresh = j.optString("refresh_token")
        if (access.isEmpty() || refresh.isEmpty()) throw AuthException("Uventet svar fra serveren")
        val expiresAt = if (j.has("expires_at")) j.getLong("expires_at") else clock() + j.optLong("expires_in", 3600)
        val user = j.optJSONObject("user")
        return AuthSession(
            accessToken = access,
            refreshToken = refresh,
            expiresAtEpochSec = expiresAt,
            userId = user?.optString("id").orEmpty(),
            email = user?.optString("email")?.ifEmpty { null } ?: fallbackEmail,
        )
    }

    companion object {
        private val EMAIL = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]{2,}$")

        fun normalizeEmail(email: String): String? {
            val e = email.trim().lowercase()
            return if (EMAIL.matches(e)) e else null
        }
    }
}
