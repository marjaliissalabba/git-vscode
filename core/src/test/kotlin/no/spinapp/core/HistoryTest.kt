package no.spinapp.core

import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HistoryTest {
    private fun rec(t: Long, d: Int = 3600, tss: Int = 50, b20: Int? = null, test: Int? = null) =
        RideRecord(t, "id", "Økt", d, 150, 160, 400, 88, 540, tss, 80, 75, b20, 200, test)

    @Test
    fun jsonRoundTripIsSmall() {
        val r = rec(1_700_000_000, b20 = 210)
        val json = r.toJson()
        assertEquals(r, RideRecord.fromJson(json))
        assertTrue(json.length < 400, "${json.length} byte")
        assertEquals(null, RideRecord.fromJson("ødelagt"))
        assertEquals(null, RideRecord.fromJson(rec(1, test = null).toJson())?.ftpTestResult)
    }

    @Test
    fun pruneByAgeAndCount() {
        val now = 1_800_000_000L
        val old = rec(now - 200 * 86400L)
        val recent = rec(now - 10 * 86400L)
        assertEquals(listOf(recent), HistoryPolicy.prune(listOf(old, recent), now, Retention.MONTHS_6))
        assertEquals(2, HistoryPolicy.prune(listOf(old, recent), now, Retention.FOREVER).size)
        val many = (1..1200).map { rec(now - it.toLong()) }
        val pruned = HistoryPolicy.prune(many, now, Retention.FOREVER)
        assertEquals(HistoryPolicy.MAX_RECORDS, pruned.size)
        assertEquals(now - 1, pruned.last().startedAtEpochSec) // nyeste beholdes
    }

    @Test
    fun shortRidesAreNotStored() {
        assertTrue(!HistoryPolicy.shouldStore(rec(0, d = 120)))
        assertTrue(HistoryPolicy.shouldStore(rec(0, d = 1800)))
    }

    @Test
    fun weekStats() {
        // Mandag 9. nov 2026
        val monday = LocalDate.of(2026, 11, 9)
        val t = monday.atTime(18, 0).toEpochSecond(ZoneOffset.UTC)
        val lastWeek = t - 3 * 86400L
        val s = HistoryStats.week(listOf(rec(t, tss = 60), rec(t + 86400, tss = 40), rec(lastWeek)), monday.plusDays(2), ZoneOffset.UTC)
        assertEquals(2, s.rides)
        assertEquals(100, s.tss)
        assertEquals(120, s.minutes)
    }

    @Test
    fun ftpEstimate() {
        val now = 2_000_000_000L
        assertNull(HistoryStats.bestFtpEstimate(listOf(rec(now)), now))
        assertEquals(209, HistoryStats.bestFtpEstimate(listOf(rec(now, b20 = 220)), now))
        assertEquals(230, HistoryStats.bestFtpEstimate(listOf(rec(now, b20 = 220), rec(now, test = 230)), now))
        assertNull(HistoryStats.bestFtpEstimate(listOf(rec(now - 100 * 86400L, test = 300)), now))
    }
}
