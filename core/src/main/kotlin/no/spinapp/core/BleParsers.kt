package no.spinapp.core

import java.util.UUID

/** Standard Bluetooth-UUID-er (16-bit utvidet til 128-bit). */
object BleUuids {
    private fun uuid16(short: Int): UUID =
        UUID.fromString(String.format("0000%04x-0000-1000-8000-00805f9b34fb", short))

    val CYCLING_POWER_SERVICE: UUID = uuid16(0x1818)
    val CYCLING_POWER_MEASUREMENT: UUID = uuid16(0x2A63)
    val FITNESS_MACHINE_SERVICE: UUID = uuid16(0x1826)
    val INDOOR_BIKE_DATA: UUID = uuid16(0x2AD2)
    val CSC_SERVICE: UUID = uuid16(0x1816)
    val CSC_MEASUREMENT: UUID = uuid16(0x2A5B)
    val HEART_RATE_SERVICE: UUID = uuid16(0x180D)
    val HEART_RATE_MEASUREMENT: UUID = uuid16(0x2A37)
    val CLIENT_CHARACTERISTIC_CONFIG: UUID = uuid16(0x2902)
}

private fun ByteArray.u8(i: Int) = this[i].toInt() and 0xFF
private fun ByteArray.u16(i: Int) = u8(i) or (u8(i + 1) shl 8)
private fun ByteArray.s16(i: Int) = u16(i).toShort().toInt()

/**
 * Regner ut kadens fra kumulative kranktråkk og hendelsestid (1/1024 s),
 * slik Cycling Power og CSC sender det.
 */
class CrankCadenceCalculator(private val stopAfterMs: Long = 3000) {
    private var lastRevs: Int? = null
    private var lastEventTime: Int? = null
    private var lastChangeMs: Long = 0
    private var lastRpm: Int = 0

    fun update(cumulativeRevs: Int, eventTime1024: Int, nowMs: Long): Int {
        val prevRevs = lastRevs
        val prevTime = lastEventTime
        lastRevs = cumulativeRevs
        lastEventTime = eventTime1024
        if (prevRevs == null || prevTime == null) {
            lastChangeMs = nowMs
            return lastRpm
        }
        val dRevs = (cumulativeRevs - prevRevs) and 0xFFFF
        val dTime = (eventTime1024 - prevTime) and 0xFFFF
        if (dRevs > 0 && dTime > 0) {
            val rpm = kotlin.math.round(dRevs * 1024.0 * 60.0 / dTime).toInt()
            lastRpm = rpm.coerceIn(0, 250)
            lastChangeMs = nowMs
        } else if (nowMs - lastChangeMs > stopAfterMs) {
            lastRpm = 0
        }
        return lastRpm
    }
}

object CyclingPowerParser {
    data class Result(val powerW: Int, val crankRevs: Int?, val crankEventTime: Int?)

    /** Parser Cycling Power Measurement (0x2A63). */
    fun parse(data: ByteArray): Result? {
        if (data.size < 4) return null
        val flags = data.u16(0)
        val power = data.s16(2)
        var offset = 4
        if (flags and 0x01 != 0) offset += 1 // pedal power balance
        if (flags and 0x04 != 0) offset += 2 // accumulated torque
        if (flags and 0x10 != 0) offset += 6 // wheel revolution data
        var revs: Int? = null
        var time: Int? = null
        if (flags and 0x20 != 0 && data.size >= offset + 4) {
            revs = data.u16(offset)
            time = data.u16(offset + 2)
        }
        return Result(power.coerceAtLeast(0), revs, time)
    }
}

object CscParser {
    /** Parser CSC Measurement (0x2A5B) og returnerer krankdata (omdreininger, tid) hvis de finnes. */
    fun parseCrank(data: ByteArray): Pair<Int, Int>? {
        if (data.isEmpty()) return null
        val flags = data.u8(0)
        var offset = 1
        if (flags and 0x01 != 0) offset += 6
        if (flags and 0x02 != 0 && data.size >= offset + 4) {
            return data.u16(offset) to data.u16(offset + 2)
        }
        return null
    }
}

object IndoorBikeDataParser {
    data class Result(
        val speedKmh: Double?,
        val cadenceRpm: Int?,
        val powerW: Int?,
        val heartRateBpm: Int?,
    )

    /** Parser FTMS Indoor Bike Data (0x2AD2). */
    fun parse(data: ByteArray): Result? {
        if (data.size < 2) return null
        val flags = data.u16(0)
        var offset = 2
        var speed: Double? = null
        var cadence: Int? = null
        var power: Int? = null
        var hr: Int? = null
        fun has(bytes: Int) = data.size >= offset + bytes
        // Bit 0 = "More data". Når den er 0, er momentan fart med.
        if (flags and 0x0001 == 0 && has(2)) { speed = data.u16(offset) / 100.0; offset += 2 }
        if (flags and 0x0002 != 0) offset += 2 // average speed
        if (flags and 0x0004 != 0 && has(2)) { cadence = data.u16(offset) / 2; offset += 2 }
        if (flags and 0x0008 != 0) offset += 2 // average cadence
        if (flags and 0x0010 != 0) offset += 3 // total distance
        if (flags and 0x0020 != 0) offset += 2 // resistance level
        if (flags and 0x0040 != 0 && has(2)) { power = data.s16(offset).coerceAtLeast(0); offset += 2 }
        if (flags and 0x0080 != 0) offset += 2 // average power
        if (flags and 0x0100 != 0) offset += 5 // expended energy
        if (flags and 0x0200 != 0 && has(1)) { hr = data.u8(offset); offset += 1 }
        return Result(speed, cadence, power, hr)
    }
}

object HeartRateParser {
    /** Parser Heart Rate Measurement (0x2A37). */
    fun parse(data: ByteArray): Int? {
        if (data.size < 2) return null
        val flags = data.u8(0)
        return if (flags and 0x01 != 0) {
            if (data.size < 3) null else data.u16(1)
        } else {
            data.u8(1)
        }
    }
}
