package no.spinapp.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ParserTest {
    @Test
    fun cyclingPowerWithCrankData() {
        // flags=0x20 (krankdata), power=250, revs=10, time=1024
        val data = byteArrayOf(0x20, 0x00, 0xFA.toByte(), 0x00, 0x0A, 0x00, 0x00, 0x04)
        val r = CyclingPowerParser.parse(data)!!
        assertEquals(250, r.powerW)
        assertEquals(10, r.crankRevs)
        assertEquals(1024, r.crankEventTime)
    }

    @Test
    fun cadenceFromCrankEvents() {
        val c = CrankCadenceCalculator()
        c.update(10, 0, 0)
        // 1 omdreining på 0,6667 s = 90 rpm
        assertEquals(90, c.update(11, 683, 700))
        // wrap-around
        val c2 = CrankCadenceCalculator()
        c2.update(65535, 65000, 0)
        assertEquals(90, c2.update(0, (65000 + 683) and 0xFFFF, 700))
    }

    @Test
    fun cadenceDropsToZeroWhenStopped() {
        val c = CrankCadenceCalculator(stopAfterMs = 3000)
        c.update(10, 0, 0)
        c.update(11, 683, 700)
        assertEquals(90, c.update(11, 683, 2000))
        assertEquals(0, c.update(11, 683, 4000))
    }

    @Test
    fun indoorBikeData() {
        // flags: speed present (bit0=0), cadence (bit2), power (bit6) = 0x0044
        val data = byteArrayOf(0x44, 0x00, 0xC4.toByte(), 0x09, 0xB4.toByte(), 0x00, 0xC8.toByte(), 0x00)
        val r = IndoorBikeDataParser.parse(data)!!
        assertEquals(25.0, r.speedKmh)
        assertEquals(90, r.cadenceRpm)
        assertEquals(200, r.powerW)
    }

    @Test
    fun heartRate() {
        assertEquals(142, HeartRateParser.parse(byteArrayOf(0x00, 142.toByte())))
        assertEquals(300, HeartRateParser.parse(byteArrayOf(0x01, 0x2C, 0x01)))
    }
}

/** Tegner syntetiske 7-segment-tall for testing. */
object SegRenderer {
    private val SEGS = mapOf(
        '0' to "abcdef", '1' to "bc", '2' to "abdeg", '3' to "abcdg", '4' to "bcfg",
        '5' to "acdfg", '6' to "acdefg", '7' to "abc", '8' to "abcdefg", '9' to "abcdfg",
    )

    fun render(text: String, digitH: Int = 40, seed: Long = 1, border: Boolean = true): GrayImage {
        val digitW = (digitH * 0.55).toInt()
        val gap = (digitH * 0.25).toInt()
        val t = maxOf(2, digitH / 9)
        val margin = 8
        val width = margin * 2 + text.sumOf { if (it == '.' || it == ':') t * 2 + gap / 2 else digitW + gap }
        val height = digitH + margin * 2
        val rnd = java.util.Random(seed)
        val px = IntArray(width * height) { 205 + rnd.nextInt(20) }
        fun rect(x0: Int, y0: Int, x1: Int, y1: Int) {
            for (y in y0 until y1) for (x in x0 until x1) {
                if (x in 0 until width && y in 0 until height) px[y * width + x] = 40 + rnd.nextInt(30)
            }
        }
        if (border) {
            rect(0, 1, width, 2)
            rect(0, height - 2, width, height - 1)
        }
        var x = margin
        val y = margin
        for (ch in text) {
            if (ch == '.') { rect(x, y + digitH - t, x + t, y + digitH); x += t * 2 + gap / 2; continue }
            if (ch == ':') {
                rect(x, y + digitH / 4, x + t, y + digitH / 4 + t)
                rect(x, y + 3 * digitH / 4, x + t, y + 3 * digitH / 4 + t)
                x += t * 2 + gap / 2; continue
            }
            val s = SEGS[ch]!!
            val mid = y + digitH / 2
            val g = 1 // liten luke mellom segmenter
            if ('a' in s) rect(x + t + g, y, x + digitW - t - g, y + t)
            if ('d' in s) rect(x + t + g, y + digitH - t, x + digitW - t - g, y + digitH)
            if ('g' in s) rect(x + t + g, mid - t / 2, x + digitW - t - g, mid - t / 2 + t)
            if ('f' in s) rect(x, y + t + g, x + t, mid - g)
            if ('e' in s) rect(x, mid + g, x + t, y + digitH - t - g)
            if ('b' in s) rect(x + digitW - t, y + t + g, x + digitW, mid - g)
            if ('c' in s) rect(x + digitW - t, mid + g, x + digitW, y + digitH - t - g)
            x += digitW + gap
        }
        return GrayImage(width, height, px)
    }
}

class SevenSegmentTest {
    @Test
    fun decodesAllDigits() {
        for (text in listOf("0123", "4567", "89", "100", "71", "250", "8", "1", "11")) {
            val r = SevenSegmentDecoder.decode(SegRenderer.render(text))
            assertEquals(text, r.text, "Feil for $text")
        }
    }

    @Test
    fun decodesSmallDigits() {
        for (text in listOf("124", "78", "305", "96")) {
            val r = SevenSegmentDecoder.decode(SegRenderer.render(text, digitH = 22, seed = 7))
            assertEquals(text, r.text, "Feil for $text (liten)")
        }
    }

    @Test
    fun decodesDecimalAndTimeText() {
        assertEquals(27.1, SevenSegmentDecoder.decode(SegRenderer.render("271")).asTenths())
        assertEquals(45 * 60 + 22, SevenSegmentDecoder.parseTime("0:45:22"))
    }

    @Test
    fun lowContrastGivesNoResult() {
        val img = GrayImage(40, 20, IntArray(800) { 128 })
        assertTrue(!SevenSegmentDecoder.decode(img).isValid)
    }

    @Test
    fun readingFilterRejectsSpikes() {
        val f = ReadingFilter(maxJump = 20, maxValue = 200)
        assertEquals(85, f.accept(85))
        assertEquals(85, f.accept(18)) // én feillesning forkastes
        assertEquals(88, f.accept(88))
        assertEquals(88, f.accept(150))
        assertEquals(151, f.accept(151)) // bekreftet stort hopp godtas
    }
}

class WorkoutTest {
    @Test
    fun libraryWorkoutsAreReasonable() {
        for (w in WorkoutLibrary.all) {
            assertTrue(w.durationSec in 10 * 60..60 * 60, "${w.name} varer ${w.durationSec / 60} min")
            assertTrue(w.segments.none { it.isWarmup }, "${w.name} skal ikke ha oppvarming")
            assertTrue(w.segments.all { it.powerLowPct <= it.powerHighPct && it.cadenceLow <= it.cadenceHigh })
        }
    }

    @Test
    fun locateSegments() {
        val w = WorkoutLibrary.threshold
        assertEquals(0 to 0, w.locate(0))
        val first = w.segments[0].durationSec
        assertEquals(1 to 0, w.locate(first))
        assertEquals(null, w.locate(w.durationSec))
    }

    @Test
    fun zones() {
        assertEquals(Zone.Z2, Zone.forPower(140, 200))
        assertEquals(Zone.Z5, Zone.forPower(200, 200)) // 100 % = oransje terskel
        assertEquals(Zone.Z7, Zone.forPower(300, 200))
        assertEquals(Zone.Z8, Zone.forPower(320, 200))
    }

    @Test
    fun skipWarmup() {
        val s = RideSession(WorkoutComposer.compose(WorkoutLibrary.threshold, 10), 200)
        assertTrue(s.inWarmup())
        s.tick(null)
        assertTrue(s.skipWarmup())
        val st = s.state(null)
        assertEquals("Terskel 1/3", st.segment?.name)
        assertTrue(!s.inWarmup())
        assertTrue(!s.skipWarmup())
        assertEquals(1, s.summary().durationSec)
        assertTrue(!RideSession(WorkoutLibrary.recovery, 200).inWarmup())
    }

    @Test
    fun weeklyPlan() {
        assertEquals(3, WeeklyPlanner.plan(3, 1).size)
        assertTrue(WeeklyPlanner.plan(3, 4).none { it.id == "terskel3x8" })
    }

    @Test
    fun simulatedSessionGivesSensibleSummary() {
        val ftp = 200
        val s = RideSession(WorkoutLibrary.ftpTest, ftp)
        val rider = SimulatedRider()
        var state = s.state(null)
        var t = 0L
        while (!state.finished) {
            val sample = rider.next(state.segment, ftp, t)
            state = s.tick(sample)
            t += 1000
        }
        val sum = s.summary()
        assertEquals(WorkoutLibrary.ftpTest.durationSec, sum.durationSec)
        assertTrue(sum.powerCompliancePct > 50, "compliance ${sum.powerCompliancePct}")
        val ftpResult = assertNotNull(sum.ftpTestResult)
        assertTrue(ftpResult in 190..215, "ftp $ftpResult")
        assertTrue(sum.normalizedPower >= sum.avgPower)
    }
}
