package no.spinapp.sources

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import no.spinapp.core.BikeSample
import no.spinapp.core.BleUuids
import no.spinapp.core.CrankCadenceCalculator
import no.spinapp.core.CscParser
import no.spinapp.core.CyclingPowerParser
import no.spinapp.core.HeartRateParser
import no.spinapp.core.IndoorBikeDataParser
import no.spinapp.core.SourceType
import java.util.UUID

/** Leser watt og kadens fra en Bluetooth-enhet (Cycling Power, FTMS, CSC, puls). */
@SuppressLint("MissingPermission")
class BleSource(private val context: Context, private val address: String) : BikeDataSource {
    override val type = SourceType.BLUETOOTH
    private val _latest = MutableStateFlow<BikeSample?>(null)
    private val _status = MutableStateFlow("Ikke tilkoblet")
    override val latest: StateFlow<BikeSample?> = _latest
    override val status: StateFlow<String> = _status

    private val handler = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private var running = false
    private val pending = ArrayDeque<BluetoothGattCharacteristic>()
    private val cadenceCalc = CrankCadenceCalculator()

    private var power: Int? = null
    private var cadence: Int? = null
    private var speed: Double? = null
    private var heartRate: Int? = null

    override fun start() {
        if (running) return
        running = true
        connect()
    }

    private fun connect() {
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (adapter == null || !adapter.isEnabled) {
            _status.value = "Slå på Bluetooth"
            return
        }
        val device = runCatching { adapter.getRemoteDevice(address) }.getOrNull()
        if (device == null) {
            _status.value = "Ugyldig enhet"
            return
        }
        _status.value = "Kobler til ${device.name ?: address} …"
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
    }

    override fun stop() {
        running = false
        handler.removeCallbacksAndMessages(null)
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        _status.value = "Frakoblet"
    }

    private fun publish() {
        _latest.value = BikeSample(
            timestampMs = System.currentTimeMillis(),
            powerW = power,
            cadenceRpm = cadence,
            speedKmh = speed,
            heartRateBpm = heartRate,
            source = SourceType.BLUETOOTH,
        )
    }

    private fun enableNext(g: BluetoothGatt) {
        val ch = pending.removeFirstOrNull() ?: run {
            _status.value = "Tilkoblet – mottar data"
            return
        }
        g.setCharacteristicNotification(ch, true)
        val desc = ch.getDescriptor(BleUuids.CLIENT_CHARACTERISTIC_CONFIG)
        if (desc == null) {
            enableNext(g)
            return
        }
        val value = if (ch.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0)
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        else BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        if (Build.VERSION.SDK_INT >= 33) {
            g.writeDescriptor(desc, value)
        } else {
            @Suppress("DEPRECATION")
            desc.value = value
            @Suppress("DEPRECATION")
            g.writeDescriptor(desc)
        }
    }

    private fun handle(uuid: UUID, value: ByteArray) {
        val now = System.currentTimeMillis()
        when (uuid) {
            BleUuids.INDOOR_BIKE_DATA -> IndoorBikeDataParser.parse(value)?.let { r ->
                r.powerW?.let { power = it }
                r.cadenceRpm?.let { cadence = it }
                r.speedKmh?.let { speed = it }
                r.heartRateBpm?.let { if (it > 0) heartRate = it }
            }
            BleUuids.CYCLING_POWER_MEASUREMENT -> CyclingPowerParser.parse(value)?.let { r ->
                power = r.powerW
                if (r.crankRevs != null && r.crankEventTime != null) {
                    cadence = cadenceCalc.update(r.crankRevs!!, r.crankEventTime!!, now)
                }
            }
            BleUuids.CSC_MEASUREMENT -> CscParser.parseCrank(value)?.let { (revs, time) ->
                cadence = cadenceCalc.update(revs, time, now)
            }
            BleUuids.HEART_RATE_MEASUREMENT -> HeartRateParser.parse(value)?.let { heartRate = it }
        }
        publish()
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                _status.value = "Tilkoblet – finner tjenester …"
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                g.close()
                if (gatt == g) gatt = null
                if (running) {
                    _status.value = "Mistet forbindelsen – prøver igjen …"
                    handler.postDelayed({ if (running) connect() }, 2000)
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            pending.clear()
            val wanted = listOf(
                BleUuids.FITNESS_MACHINE_SERVICE to BleUuids.INDOOR_BIKE_DATA,
                BleUuids.CYCLING_POWER_SERVICE to BleUuids.CYCLING_POWER_MEASUREMENT,
                BleUuids.CSC_SERVICE to BleUuids.CSC_MEASUREMENT,
                BleUuids.HEART_RATE_SERVICE to BleUuids.HEART_RATE_MEASUREMENT,
            )
            for ((service, char) in wanted) {
                g.getService(service)?.getCharacteristic(char)?.let { pending.addLast(it) }
            }
            if (pending.isEmpty()) {
                _status.value = "Enheten sender ikke watt/kadens via Bluetooth"
                return
            }
            enableNext(g)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            enableNext(g)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
            handler.post { handle(ch.uuid, value) }
        }

        @Deprecated("Brukes på Android 12 og eldre")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33) {
                @Suppress("DEPRECATION")
                val v = ch.value ?: return
                val copy = v.copyOf()
                handler.post { handle(ch.uuid, copy) }
            }
        }
    }
}

/** En enhet funnet under søk. */
data class FoundDevice(
    val address: String,
    val name: String?,
    val rssi: Int,
    val services: Set<String>,
    /** Utjevnet signalstyrke (dBm), så lista ikke hopper. */
    val smoothRssi: Double = rssi.toDouble(),
    val lastSeenMs: Long = System.currentTimeMillis(),
) {
    /** Treningsutstyr: sender watt/sykkel/kadens/puls, eller har et navn som ligner en sykkel. */
    val isFitness: Boolean
        get() = services.isNotEmpty() || name?.contains(FITNESS_NAME) == true

    companion object {
        private val FITNESS_NAME = Regex("(?i)stages|bike|body ?bike|keiser|schwinn|wattbike|kickr|tacx|elite|trainer|cycle|spin|ic\\d|m3i|echelon|peloton")
    }
}

/** Søker etter Bluetooth-enheter i nærheten. */
@SuppressLint("MissingPermission")
class BleScanner(private val context: Context) {
    val devices = MutableStateFlow<Map<String, FoundDevice>>(emptyMap())
    val error = MutableStateFlow<String?>(null)

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val uuids = result.scanRecord?.serviceUuids?.map { it.uuid }.orEmpty()
            val tags = buildSet {
                if (BleUuids.CYCLING_POWER_SERVICE in uuids) add("Watt")
                if (BleUuids.FITNESS_MACHINE_SERVICE in uuids) add("Sykkel (FTMS)")
                if (BleUuids.CSC_SERVICE in uuids) add("Kadens")
                if (BleUuids.HEART_RATE_SERVICE in uuids) add("Puls")
            }
            val name = result.device.name ?: result.scanRecord?.deviceName
            val now = System.currentTimeMillis()
            val old = devices.value[result.device.address]
            // Signalstyrken svinger mye; utjevning gjør «nærmest» mer pålitelig
            val smooth = old?.let { it.smoothRssi + (result.rssi - it.smoothRssi) * 0.3 } ?: result.rssi.toDouble()
            val d = FoundDevice(
                result.device.address, name ?: old?.name, result.rssi,
                tags.ifEmpty { old?.services.orEmpty() }, smooth, now,
            )
            // Glem enheter som ikke er sett på 15 s
            devices.value = devices.value.filterValues { now - it.lastSeenMs < 15_000 } + (d.address to d)
        }

        override fun onScanFailed(errorCode: Int) {
            error.value = "Søket feilet (kode $errorCode)"
        }
    }

    fun start() {
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (adapter == null || !adapter.isEnabled) {
            error.value = "Bluetooth er av. Slå det på og prøv igjen."
            return
        }
        error.value = null
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        adapter.bluetoothLeScanner?.startScan(null, settings, callback)
    }

    fun stop() {
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        runCatching { adapter?.bluetoothLeScanner?.stopScan(callback) }
    }
}
