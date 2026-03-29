package com.votol.controller.model

data class VotolMonitorData(
    val voltage: Double,        // Volt
    val current: Double,        // Amper
    val rpm: Int,
    val controllerTemp: Int,    // °C
    val externalTemp: Int,      // °C
    val faultCode: Int,
    val gear: Int,              // 0=L, 1=M, 2=H, 3=S
    val isReverse: Boolean,
    val isPark: Boolean,
    val isBrake: Boolean,
    val isAntitheft: Boolean,
    val isSideStand: Boolean,
    val isRegen: Boolean,
    val controllerState: ControllerState,
    val timestampMs: Long = System.currentTimeMillis()
) {
    val gearLabel: String get() = when(gear) { 0->"L" 1->"M" 2->"H" else->"S" }
    val power: Double get() = voltage * current  // Watt
    val hasFault: Boolean get() = faultCode != 0
}

enum class ControllerState(val code: Int, val label: String) {
    IDLE(0,"Bekleme"), INIT(1,"Başlatılıyor"), START(2,"Start"),
    RUN(3,"Çalışıyor"), STOP(4,"Durdu"), BRAKE(5,"Frenleme"),
    WAIT(6,"Bekle"), FAULT(7,"Hata");
    companion object { fun fromByte(v:Int) = entries.firstOrNull{it.code==v}?: IDLE }
}

data class VotolParam(
    val page: Byte,
    val rawBytes: ByteArray,
    val description: String = ""
) {
    override fun equals(other: Any?) = other is VotolParam && page == other.page
    override fun hashCode() = page.toInt()
}

sealed class BleState {
    object Disconnected : BleState()
    object Scanning     : BleState()
    data class Connecting(val deviceName: String) : BleState()
    data class Connected(val deviceName: String)  : BleState()
    data class Error(val message: String)          : BleState()
}

data class LogEntry(
    val timestampMs: Long = System.currentTimeMillis(),
    val voltage: Double,
    val current: Double,
    val rpm: Int,
    val power: Double,
    val controllerTemp: Int
)
