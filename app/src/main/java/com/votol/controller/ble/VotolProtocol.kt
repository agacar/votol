package com.votol.controller.ble

/**
 * Votol Serial/BLE Protocol
 *
 * Reverse-engineered from community research (Endless Sphere forum).
 * Reference: https://endless-sphere.com/sphere/threads/votol-serial-communication-protocol.112970/
 *
 * Communication: UART over BLE (HC-05 / HM-10 / similar SPP module bağlantısı)
 * Baud Rate: 115200 (varsayılan)
 *
 * BLE UART Service UUID: "0000ffe0-0000-1000-8000-00805f9b34fb" (HM-10 SPP)
 * BLE UART TX Char UUID: "0000ffe1-0000-1000-8000-00805f9b34fb"
 */
object VotolProtocol {

    // BLE UART Service (HM-10 / HC-08 modülleri)
    const val SERVICE_UUID_SPP       = "0000ffe0-0000-1000-8000-00805f9b34fb"
    const val CHAR_UUID_SPP_RW       = "0000ffe1-0000-1000-8000-00805f9b34fb"

    // Nordic UART Service (nRF modüller)
    const val SERVICE_UUID_NORDIC    = "6e400001-b5a3-f393-e0a9-e50e24dcca9e"
    const val CHAR_UUID_NORDIC_TX    = "6e400002-b5a3-f393-e0a9-e50e24dcca9e"
    const val CHAR_UUID_NORDIC_RX    = "6e400003-b5a3-f393-e0a9-00805f9b34fb"

    const val CLIENT_CHAR_CONFIG_UUID = "00002902-0000-1000-8000-00805f9b34fb"

    /**
     * Monitor (Display) sayfası isteği
     * Host → Controller: SHOW isteği gönder
     * Controller → Host: 24 byte cevap
     */
    val CMD_REQUEST_MONITOR: ByteArray = byteArrayOf(
        0xC9.toByte(), 0x14, 0x02,
        0x53, 0x48, 0x4F, 0x57,  // "SHOW" ASCII
        0x00, 0x00, 0x00, 0x00, 0x00,
        0xAA.toByte(), 0x00, 0x00, 0x00,
        0x1E, 0xAA.toByte(), 0x04, 0x67, 0x00,
        0xF3.toByte(), 0x52, 0x0D
    )

    /** Response header bytes */
    const val RESPONSE_HEADER_B0: Byte = 0xC0.toByte()
    const val RESPONSE_HEADER_B1: Byte = 0x14
    const val RESPONSE_LENGTH = 24

    /**
     * 24-byte monitor response byte map:
     * B0~B1  : 0xC014 — header (controller→host)
     * B2     : packet length
     * B3~B4  : unknown
     * B5~B6  : battery voltage  → fixed-point / 10.0 → Volt
     * B7~B8  : battery current  → fixed-point / 10.0 → Amper
     * B9     : unknown
     * B10~B13: 32-bit fault code (bitmask)
     * B14~B15: unknown
     * B16~B17: RPM (uint16 big-endian)
     * B18    : controller temp (raw - 50 = °C)
     * B19    : external temp   (raw - 50 = °C)
     * B20    : gear / antitheft / regen / brake status (bitmask)
     * B21    : controller state (0=IDLE,1=INIT,2=START,3=RUN,4=STOP,5=BRAKE,6=WAIT,7=FAULT)
     * B22    : XOR checksum of B0~B21
     * B23    : 0x0D (terminator)
     */
    fun parseMonitorResponse(data: ByteArray): VotolMonitorData? {
        if (data.size < RESPONSE_LENGTH) return null
        if (data[0] != RESPONSE_HEADER_B0 || data[1] != RESPONSE_HEADER_B1) return null

        // XOR checksum doğrula
        var xor = 0
        for (i in 0 until 22) xor = xor xor data[i].toInt()
        if ((xor and 0xFF) != (data[22].toInt() and 0xFF)) return null

        val voltageRaw  = ((data[5].toInt() and 0xFF) shl 8) or (data[6].toInt() and 0xFF)
        val currentRaw  = ((data[7].toInt() and 0xFF) shl 8) or (data[8].toInt() and 0xFF)
        val faultCode   = ((data[10].toInt() and 0xFF) shl 24) or
                          ((data[11].toInt() and 0xFF) shl 16) or
                          ((data[12].toInt() and 0xFF) shl  8) or
                          (data[13].toInt() and 0xFF)
        val rpm         = ((data[16].toInt() and 0xFF) shl 8) or (data[17].toInt() and 0xFF)
        val controllerTemp = (data[18].toInt() and 0xFF) - 50
        val externalTemp   = (data[19].toInt() and 0xFF) - 50
        val statusByte     = data[20].toInt() and 0xFF
        val stateByte      = data[21].toInt() and 0xFF

        return VotolMonitorData(
            voltage        = voltageRaw / 10.0,
            current        = currentRaw / 10.0,
            rpm            = rpm,
            controllerTemp = controllerTemp,
            externalTemp   = externalTemp,
            faultCode      = faultCode,
            gear           = statusByte and 0x03,
            isReverse      = (statusByte and 0x04) != 0,
            isPark         = (statusByte and 0x08) != 0,
            isBrake        = (statusByte and 0x10) != 0,
            isAntitheft    = (statusByte and 0x20) != 0,
            isSideStand    = (statusByte and 0x40) != 0,
            isRegen        = (statusByte and 0x80.toByte().toInt()) != 0,
            controllerState = ControllerState.fromByte(stateByte)
        )
    }

    // ── Parametre okuma komutları ──────────────────────────────────────────

    /**
     * Parametre sayfası isteği (Page 0x50 = temel ayarlar)
     * Format: C9 14 02 [PAGE] ...
     */
    fun buildParamReadCommand(page: Byte): ByteArray {
        val cmd = ByteArray(24)
        cmd[0] = 0xC9.toByte()
        cmd[1] = 0x14
        cmd[2] = 0x02
        cmd[3] = page
        // B4~B22: 0x00, checksum hesapla
        var xor = 0
        for (i in 0 until 22) xor = xor xor cmd[i].toInt()
        cmd[22] = (xor and 0xFF).toByte()
        cmd[23] = 0x0D
        return cmd
    }

    /**
     * Parametre yazma — tam paket (24 byte)
     * Checksum otomatik hesaplanır.
     */
    fun buildParamWriteCommand(payload: ByteArray): ByteArray {
        require(payload.size == 24) { "Payload must be 24 bytes" }
        val cmd = payload.copyOf()
        var xor = 0
        for (i in 0 until 22) xor = xor xor cmd[i].toInt()
        cmd[22] = (xor and 0xFF).toByte()
        cmd[23] = 0x0D
        return cmd
    }

    // ── Fault Code çözümleyici ──────────────────────────────────────────────
    fun decodeFaults(faultCode: Int): List<String> {
        val faults = mutableListOf<String>()
        val map = mapOf(
            0  to "Throttle hatası",
            1  to "Motor fazı kesik",
            2  to "Aşırı akım (overcurrent)",
            3  to "Aşırı voltaj (overvoltage)",
            4  to "Düşük voltaj (undervoltage)",
            5  to "Kontroller aşırı ısınma",
            6  to "Motor aşırı ısınma",
            7  to "Hall sensörü hatası",
            8  to "Faz dengesizliği",
            9  to "EEPROM hatası",
            10 to "CAN haberleşme hatası",
            11 to "Fren sinyali hatası",
            12 to "Hız sensörü hatası",
            13 to "Ters bağlantı koruma",
            14 to "Kısa devre koruması",
            15 to "Sistem hatası"
        )
        for ((bit, name) in map) {
            if ((faultCode shr bit) and 1 == 1) faults.add(name)
        }
        return faults.ifEmpty { listOf("Hata yok") }
    }
}

enum class ControllerState(val code: Int, val label: String) {
    IDLE (0, "Bekleme"),
    INIT (1, "Başlatılıyor"),
    START(2, "Start"),
    RUN  (3, "Çalışıyor"),
    STOP (4, "Durdu"),
    BRAKE(5, "Frenleme"),
    WAIT (6, "Bekle"),
    FAULT(7, "Hata");

    companion object {
        fun fromByte(v: Int) = entries.firstOrNull { it.code == v } ?: IDLE
    }
}
