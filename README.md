# Votol Controller – Android Uygulaması

Native Android uygulaması (Kotlin + Jetpack Compose) — Votol EM serisi kontrolcüleri BLE üzerinden izleme ve ayarlama.

---

## Donanım Bağlantısı

### Gerekli Malzeme
- HM-10 veya HC-08 BLE UART modülü (≈ 20–50 TL)
- 5V → 3.3V voltaj bölücü (eğer modül 3.3V ise; HM-10 için gerekli)
- Bağlantı kablosu

### Votol Programlama Portu Pinout (6 pin konnektör)

```
┌─────────────────────────────┐
│  GND  │  5V  │  TX  │  RX  │
└─────────────────────────────┘
```

| Votol Pin | HM-10 Pin |
|-----------|-----------|
| GND       | GND       |
| 5V        | VCC (3.3V bölücü üzerinden) |
| TX        | RXD       |
| RX        | TXD       |

> ⚠️ HM-10 modülü 3.3V çalışır. Votol TX (5V) → HM-10 RXD bağlantısına  
> **10kΩ / 20kΩ voltaj bölücü** ekleyin. Votol RX → HM-10 TXD direkt bağlanabilir.

### Baud Rate
Votol programlama portu varsayılan: **115200 baud**  
HM-10'u ayarlamak için AT komutu: `AT+BAUD4` (115200)

---

## Uygulama Kurulumu

### Gereksinimler
- Android Studio Hedgehog (2023.1.1) veya üzeri
- Android SDK 34
- Kotlin 1.9.x
- Fiziksel Android cihaz (API 23+, BLE destekli)

### Adımlar
```bash
git clone <repo>
cd VotolApp
# Android Studio'da aç → Run
```

---

## Protokol Notları

Votol serial protokolü topluluk tarafından tersine mühendislikle çözülmüştür.  
Kaynak: [Endless Sphere Forum](https://endless-sphere.com/sphere/threads/votol-serial-communication-protocol.112970/)

### Monitor Komutu (Host → Votol)
```
C9 14 02 53 48 4F 57 00 00 00 00 00 AA 00 00 00 1E AA 04 67 00 F3 52 0D
```

### Monitor Yanıt (Votol → Host) — 24 byte
```
Byte    Alan                 Hesaplama
──────────────────────────────────────────
B0~B1   Header (C0 14)       Sabit
B5~B6   Batarya Voltajı      (uint16 BE) / 10.0 → Volt
B7~B8   Batarya Akımı        (uint16 BE) / 10.0 → Amper
B10~B13 Hata Kodu            32-bit bitmask
B16~B17 RPM                  uint16 BE
B18     Kontrolcü Sıcaklığı  raw - 50 = °C
B19     Harici Sıcaklık      raw - 50 = °C
B20     Durum                Bitmask (vites/fren/regen/antihız)
B21     Kontrolcü Durumu     0=IDLE … 7=FAULT
B22     XOR Checksum         B0~B21 XOR
```

---

## Desteklenen Özellikler

- [x] BLE UART tarama ve bağlantı (HM-10 / HC-08 / Nordic UART)
- [x] Gerçek zamanlı veri: voltaj, akım, RPM, güç, sıcaklık
- [x] Hata kodu çözümleme (32-bit bitmask → Türkçe açıklama)
- [x] Veri loglama (500 kayıt, bellekte)
- [x] Parametre sayfası okuma (0x50–0x53)
- [ ] CSV export (yakında)
- [ ] Grafik görünümü (yakında)
- [ ] Parametre yazma UI (yakında)

---

## Lisans
MIT — Kendi kullanımınız için serbestçe değiştirebilirsiniz.
