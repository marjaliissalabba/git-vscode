package no.spinapp.sources

import android.app.Activity
import com.dsi.ant.plugins.antplus.pcc.AntPlusBikePowerPcc
import com.dsi.ant.plugins.antplus.pcc.defines.DeviceState
import com.dsi.ant.plugins.antplus.pcc.defines.RequestAccessResult
import com.dsi.ant.plugins.antplus.pccbase.AntPluginPcc
import com.dsi.ant.plugins.antplus.pccbase.PccReleaseHandle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import no.spinapp.core.BikeSample
import no.spinapp.core.SourceType

/**
 * Leser watt og kadens over ANT+ via ANT+ Plugins Service.
 * Krever ANT-radio i telefonen eller en USB ANT+-pinne (med appen «ANT USB Service»).
 */
class AntSource(private val activity: Activity) : BikeDataSource {
    override val type = SourceType.ANT_PLUS
    private val _latest = MutableStateFlow<BikeSample?>(null)
    private val _status = MutableStateFlow("Ikke tilkoblet")
    override val latest: StateFlow<BikeSample?> = _latest
    override val status: StateFlow<String> = _status

    private var handle: PccReleaseHandle<AntPlusBikePowerPcc>? = null
    @Volatile private var power: Int? = null
    @Volatile private var cadence: Int? = null

    override fun start() {
        if (handle != null) return
        _status.value = "Søker etter ANT+ kraftmåler … tråkk i gang sykkelen"
        handle = AntPlusBikePowerPcc.requestAccess(
            activity,
            activity.applicationContext,
            AntPluginPcc.IPluginAccessResultReceiver<AntPlusBikePowerPcc> { pcc, result, _ ->
                onAccess(pcc, result)
            },
            AntPluginPcc.IDeviceStateChangeReceiver { state ->
                _status.value = when (state) {
                    DeviceState.TRACKING -> "ANT+ tilkoblet – mottar data"
                    DeviceState.SEARCHING -> "ANT+: søker …"
                    DeviceState.DEAD -> "ANT+: forbindelsen er borte"
                    else -> "ANT+: $state"
                }
            },
        )
    }

    private fun onAccess(pcc: AntPlusBikePowerPcc?, result: RequestAccessResult) {
        when (result) {
            RequestAccessResult.SUCCESS -> {
                if (pcc == null) return
                _status.value = "ANT+ tilkoblet: ${pcc.deviceName}"
                pcc.subscribeCalculatedPowerEvent { _, _, _, calculatedPower ->
                    power = calculatedPower?.toInt()
                    publish()
                }
                pcc.subscribeInstantaneousCadenceEvent { _, _, _, instantaneousCadence ->
                    cadence = instantaneousCadence
                    publish()
                }
            }
            RequestAccessResult.DEPENDENCY_NOT_INSTALLED -> _status.value =
                "Mangler apper: installer «ANT Radio Service» og «ANT+ Plugins Service» fra Play Butikk"
            RequestAccessResult.ADAPTER_NOT_DETECTED -> _status.value =
                "Telefonen har ikke ANT+. Bruk USB ANT+-pinne med appen «ANT USB Service», eller velg Bluetooth/kamera"
            RequestAccessResult.USER_CANCELLED -> _status.value = "Søk avbrutt"
            RequestAccessResult.SEARCH_TIMEOUT -> _status.value = "Fant ingen ANT+ kraftmåler. Tråkk og prøv igjen"
            else -> _status.value = "ANT+-feil: $result"
        }
        if (result != RequestAccessResult.SUCCESS) {
            handle = null
        }
    }

    private fun publish() {
        _latest.value = BikeSample(
            timestampMs = System.currentTimeMillis(),
            powerW = power,
            cadenceRpm = cadence,
            source = SourceType.ANT_PLUS,
        )
    }

    override fun stop() {
        handle?.close()
        handle = null
        _status.value = "Frakoblet"
    }
}
