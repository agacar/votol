package com.votol.controller.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.votol.controller.model.BleState
import com.votol.controller.model.VotolMonitorData
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

@SuppressLint("MissingPermission")
class VotolBleManager(private val context: Context) {

    companion object {
        private const val TAG = "VotolBLE"
        private const val SCAN_TIMEOUT_MS = 15_000L
        private const val POLL_INTERVAL_MS = 500L
    }

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    }

    private var gatt: BluetoothGatt? = null
    private var txCharacteristic: BluetoothGattCharacteristic? = null
    private var rxCharacteristic: BluetoothGattCharacteristic? = null

    private val _bleState = MutableStateFlow<BleState>(BleState.Disconnected)
    val bleState: StateFlow<BleState> = _bleState.asStateFlow()

    private val _monitorData = MutableStateFlow<VotolMonitorData?>(null)
    val monitorData: StateFlow<VotolMonitorData?> = _monitorData.asStateFlow()

    private val _rawBuffer = mutableListOf<Byte>()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var pollRunnable: Runnable? = null
    private var scanner: BluetoothLeScanner? = null
    private var scanCallback: ScanCallback? = null

    // ── Tarama ──────────────────────────────────────────────────────────────

    fun startScan(onDevice: (BluetoothDevice) -> Unit) {
        scanner = bluetoothAdapter?.bluetoothLeScanner ?: run {
            _bleState.value = BleState.Error("Bluetooth kapalı veya desteklenmiyor")
            return
        }
        _bleState.value = BleState.Scanning

        val filters = listOf(
            ScanFilter.Builder().build()  // tüm cihazlar; isterseniz name filter ekleyin
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device
                if (!device.name.isNullOrBlank()) {
                    onDevice(device)
                }
            }
            override fun onScanFailed(errorCode: Int) {
                _bleState.value = BleState.Error("Tarama hatası: $errorCode")
            }
        }

        scanner?.startScan(filters, settings, scanCallback!!)

        // Timeout
        mainHandler.postDelayed({ stopScan() }, SCAN_TIMEOUT_MS)
    }

    fun stopScan() {
        scanCallback?.let { scanner?.stopScan(it) }
        scanCallback = null
        if (_bleState.value is BleState.Scanning) {
            _bleState.value = BleState.Disconnected
        }
    }

    // ── Bağlantı ─────────────────────────────────────────────────────────────

    fun connect(device: BluetoothDevice) {
        stopScan()
        _bleState.value = BleState.Connecting(device.name ?: device.address)
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    fun disconnect() {
        stopPolling()
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        txCharacteristic = null
        rxCharacteristic = null
        _bleState.value = BleState.Disconnected
    }

    // ── GATT Callback ────────────────────────────────────────────────────────

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.d(TAG, "Bağlandı, servisler keşfediliyor…")
                    g.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d(TAG, "Bağlantı kesildi")
                    stopPolling()
                    _bleState.value = BleState.Disconnected
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                _bleState.value = BleState.Error("Servis keşfi başarısız")
                return
            }

            // HM-10 / HC-08 SPP servisini dene
            var svc = g.getService(UUID.fromString(VotolProtocol.SERVICE_UUID_SPP))
            if (svc != null) {
                val ch = svc.getCharacteristic(UUID.fromString(VotolProtocol.CHAR_UUID_SPP_RW))
                txCharacteristic = ch
                rxCharacteristic = ch
                enableNotify(g, ch)
            } else {
                // Nordic UART dene
                svc = g.getService(UUID.fromString(VotolProtocol.SERVICE_UUID_NORDIC))
                if (svc != null) {
                    txCharacteristic = svc.getCharacteristic(UUID.fromString(VotolProtocol.CHAR_UUID_NORDIC_TX))
                    rxCharacteristic = svc.getCharacteristic(UUID.fromString(VotolProtocol.CHAR_UUID_NORDIC_RX))
                    rxCharacteristic?.let { enableNotify(g, it) }
                } else {
                    _bleState.value = BleState.Error("Uyumlu BLE UART servisi bulunamadı")
                    return
                }
            }

            val name = g.device.name ?: g.device.address
            _bleState.value = BleState.Connected(name)
            startPolling()
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleIncomingBytes(value)
        }

        // Android < 13 compat
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            handleIncomingBytes(characteristic.value ?: return)
        }
    }

    // ── Notify aktifleştir ────────────────────────────────────────────────────

    private fun enableNotify(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
        g.setCharacteristicNotification(ch, true)
        val descriptor = ch.getDescriptor(UUID.fromString(VotolProtocol.CLIENT_CHAR_CONFIG_UUID))
        descriptor?.let {
            it.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            g.writeDescriptor(it)
        }
    }

    // ── Gelen veri işleme ─────────────────────────────────────────────────────

    private fun handleIncomingBytes(bytes: ByteArray) {
        synchronized(_rawBuffer) {
            _rawBuffer.addAll(bytes.toList())

            // 0xC014 header ara ve 24 byte'lık paketi çıkar
            while (_rawBuffer.size >= VotolProtocol.RESPONSE_LENGTH) {
                val headerIdx = findHeader()
                if (headerIdx < 0) {
                    if (_rawBuffer.size > 64) _rawBuffer.clear()
                    break
                }
                if (headerIdx > 0) repeat(headerIdx) { _rawBuffer.removeAt(0) }
                if (_rawBuffer.size < VotolProtocol.RESPONSE_LENGTH) break

                val packet = _rawBuffer.take(VotolProtocol.RESPONSE_LENGTH).toByteArray()
                repeat(VotolProtocol.RESPONSE_LENGTH) { _rawBuffer.removeAt(0) }

                VotolProtocol.parseMonitorResponse(packet)?.let { data ->
                    _monitorData.value = data
                }
            }
        }
    }

    private fun findHeader(): Int {
        for (i in 0 until _rawBuffer.size - 1) {
            if (_rawBuffer[i] == VotolProtocol.RESPONSE_HEADER_B0 &&
                _rawBuffer[i+1] == VotolProtocol.RESPONSE_HEADER_B1) return i
        }
        return -1
    }

    // ── Polling ───────────────────────────────────────────────────────────────

    private fun startPolling() {
        pollRunnable = object : Runnable {
            override fun run() {
                sendCommand(VotolProtocol.CMD_REQUEST_MONITOR)
                mainHandler.postDelayed(this, POLL_INTERVAL_MS)
            }
        }
        mainHandler.post(pollRunnable!!)
    }

    private fun stopPolling() {
        pollRunnable?.let { mainHandler.removeCallbacks(it) }
        pollRunnable = null
    }

    // ── Komut gönder ─────────────────────────────────────────────────────────

    @Suppress("DEPRECATION")
    fun sendCommand(bytes: ByteArray) {
        val g = gatt ?: return
        val ch = txCharacteristic ?: return
        ch.value = bytes
        ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        g.writeCharacteristic(ch)
    }

    fun sendParamRead(page: Byte) {
        sendCommand(VotolProtocol.buildParamReadCommand(page))
    }

    fun sendParamWrite(payload: ByteArray) {
        sendCommand(VotolProtocol.buildParamWriteCommand(payload))
    }
}
