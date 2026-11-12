package no.spinapp.core

/** Kilden en måling kommer fra. */
enum class SourceType(val label: String) {
    BLUETOOTH("Bluetooth"),
    ANT_PLUS("ANT+"),
    CAMERA("Kamera"),
    SIMULATOR("Simulator"),
}

/**
 * Én måling fra sykkelen. Felter som kilden ikke kan levere er null.
 */
data class BikeSample(
    val timestampMs: Long,
    val powerW: Int? = null,
    val cadenceRpm: Int? = null,
    val speedKmh: Double? = null,
    val heartRateBpm: Int? = null,
    val source: SourceType,
)
