package com.votol.controller.ui

import android.app.Application
import android.bluetooth.BluetoothDevice
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.votol.controller.ble.VotolBleManager
import com.votol.controller.model.BleState
import com.votol.controller.model.LogEntry
import com.votol.controller.model.VotolMonitorData
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class MainViewModel(app: Application) : AndroidViewModel(app) {

    val bleManager = VotolBleManager(app)

    val bleState: StateFlow<BleState> = bleManager.bleState
    val monitorData: StateFlow<VotolMonitorData?> = bleManager.monitorData

    private val _scannedDevices = MutableStateFlow<List<BluetoothDevice>>(emptyList())
    val scannedDevices: StateFlow<List<BluetoothDevice>> = _scannedDevices.asStateFlow()

    private val _log = MutableStateFlow<List<LogEntry>>(emptyList())
    val log: StateFlow<List<LogEntry>> = _log.asStateFlow()

    private val _faults = MutableStateFlow<List<String>>(emptyList())
    val faults: StateFlow<List<String>> = _faults.asStateFlow()

    init {
        // Monitor verisini log'a ekle ve fault çöz
        viewModelScope.launch {
            monitorData.filterNotNull().collect { data ->
                val entry = LogEntry(
                    voltage = data.voltage,
                    current = data.current,
                    rpm = data.rpm,
                    power = data.power,
                    controllerTemp = data.controllerTemp
                )
                _log.value = (_log.value + entry).takeLast(500)

                if (data.hasFault) {
                    _faults.value = com.votol.controller.ble.VotolProtocol.decodeFaults(data.faultCode)
                } else {
                    _faults.value = emptyList()
                }
            }
        }
    }

    fun startScan() {
        _scannedDevices.value = emptyList()
        bleManager.startScan { device ->
            val current = _scannedDevices.value
            if (current.none { it.address == device.address }) {
                _scannedDevices.value = current + device
            }
        }
    }

    fun connect(device: BluetoothDevice) {
        bleManager.connect(device)
    }

    fun disconnect() {
        bleManager.disconnect()
    }

    fun clearLog() {
        _log.value = emptyList()
    }

    override fun onCleared() {
        super.onCleared()
        bleManager.disconnect()
    }
}
