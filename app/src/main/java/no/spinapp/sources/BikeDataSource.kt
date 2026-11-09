package no.spinapp.sources

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import no.spinapp.AppState
import no.spinapp.core.BikeSample
import no.spinapp.core.SimulatedRider
import no.spinapp.core.SourceType

/** Felles grensesnitt for Bluetooth, ANT+, kamera og simulator. */
interface BikeDataSource {
    val type: SourceType
    val latest: StateFlow<BikeSample?>
    val status: StateFlow<String>
    fun start()
    fun stop()
}

/** Siste måling, men bare hvis den er fersk. */
fun BikeDataSource.freshSample(maxAgeMs: Long = 5000): BikeSample? {
    val s = latest.value ?: return null
    return if (System.currentTimeMillis() - s.timestampMs <= maxAgeMs) s else null
}

class SimulatorSource : BikeDataSource {
    override val type = SourceType.SIMULATOR
    private val _latest = MutableStateFlow<BikeSample?>(null)
    private val _status = MutableStateFlow("Simulator klar")
    override val latest: StateFlow<BikeSample?> = _latest
    override val status: StateFlow<String> = _status
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private val rider = SimulatedRider(System.currentTimeMillis())

    override fun start() {
        if (job != null) return
        _status.value = "Simulerer data (ingen sykkel)"
        job = scope.launch {
            while (isActive) {
                _latest.value = rider.next(AppState.currentTarget.value, AppState.ftp, System.currentTimeMillis())
                delay(1000)
            }
        }
    }

    override fun stop() {
        job?.cancel()
        job = null
        _status.value = "Simulator stoppet"
    }
}
