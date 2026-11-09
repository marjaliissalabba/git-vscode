package no.spinapp.core

import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AuthTest {
    private class FakeTransport(val responses: MutableList<HttpResponse>) : HttpTransport {
        val calls = mutableListOf<Triple<String, Map<String, String>, String>>()
        override fun post(url: String, headers: Map<String, String>, body: String): HttpResponse {
            calls += Triple(url, headers, body)
            return responses.removeAt(0)
        }
    }

    private val sessionJson = """{"access_token":"acc","refresh_token":"ref","expires_in":3600,
        "user":{"id":"u-1","email":"test@example.no"}}"""

    @Test
    fun sendCodeAndVerify() {
        val t = FakeTransport(mutableListOf(HttpResponse(200, "{}"), HttpResponse(200, sessionJson)))
        val auth = EmailOtpAuth("https://x.supabase.co/", "anon", t, clock = { 1000 })
        auth.sendCode("  Test@Example.no ")
        val (url, headers, body) = t.calls[0]
        assertEquals("https://x.supabase.co/auth/v1/otp", url)
        assertEquals("anon", headers["apikey"])
        assertEquals("test@example.no", JSONObject(body).getString("email"))

        val s = auth.verifyCode("test@example.no", "123 456")
        assertEquals("https://x.supabase.co/auth/v1/verify", t.calls[1].first)
        assertEquals("123456", JSONObject(t.calls[1].third).getString("token"))
        assertEquals("acc", s.accessToken)
        assertEquals(4600, s.expiresAtEpochSec)
        assertEquals("u-1", s.userId)
        assertTrue(s.isExpired(4560))
    }

    @Test
    fun wrongCodeGivesNorwegianError() {
        val t = FakeTransport(mutableListOf(HttpResponse(403, """{"msg":"Token has expired or is invalid"}""")))
        val auth = EmailOtpAuth("https://x.supabase.co", "anon", t)
        val e = assertFailsWith<AuthException> { auth.verifyCode("a@b.no", "111111") }
        assertTrue(e.isRejected)
        assertTrue(e.message!!.contains("Feil eller utløpt kode"))
    }

    @Test
    fun rateLimit() {
        val t = FakeTransport(mutableListOf(HttpResponse(429, "{}")))
        val e = assertFailsWith<AuthException> { EmailOtpAuth("https://x", "k", t).sendCode("a@b.no") }
        assertTrue(e.message!!.contains("Vent litt"))
    }

    @Test
    fun emailValidation() {
        assertNull(EmailOtpAuth.normalizeEmail("ikke-epost"))
        assertNull(EmailOtpAuth.normalizeEmail("a@b"))
        assertEquals("a@b.no", EmailOtpAuth.normalizeEmail(" A@B.no "))
        assertFailsWith<AuthException> { EmailOtpAuth("https://x", "k", FakeTransport(mutableListOf())).sendCode("feil") }
    }

    @Test
    fun refresh() {
        val t = FakeTransport(mutableListOf(HttpResponse(200, sessionJson)))
        val old = AuthSession("old", "ref0", 0, "u-1", "test@example.no")
        val s = EmailOtpAuth("https://x", "k", t, clock = { 10 }).refresh(old)
        assertTrue(t.calls[0].first.endsWith("/auth/v1/token?grant_type=refresh_token"))
        assertEquals("ref0", JSONObject(t.calls[0].third).getString("refresh_token"))
        assertEquals("acc", s.accessToken)
    }
}
