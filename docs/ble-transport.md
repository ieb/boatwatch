# BLE Transport Protocol

Both WearOS apps support Bluetooth Low Energy as an alternative to WiFi/HTTP for communicating with the ESP32 firmware. BLE provides a direct connection without requiring WiFi infrastructure.

## GATT Service

| UUID | Name | Properties | Purpose |
|------|------|-----------|---------|
| `0000AA00-0000-1000-8000-00805f9b34fb` | BoatWatch Service | — | Container service |
| `0000AA01-...` | Autopilot State | NOTIFY, READ | Firmware → watch: autopilot state |
| `0000AA02-...` | Autopilot Command | WRITE | Watch → firmware: autopilot commands |
| `0000AA03-...` | Battery State | NOTIFY, READ | Firmware → watch: battery state |

## Connection Sequence

1. Watch scans for devices advertising service UUID `0000AA00`
2. Connect GATT
3. Request MTU 512 (negotiates to 517 on Galaxy Watch 6)
4. Discover services
5. Enable notifications on the relevant characteristic via CCC descriptor write
6. Begin receiving binary state notifications

Auto-reconnect on disconnect after 3 seconds.

## Battery State (0xBB) — Characteristic `0000AA03`

Sent every 5 seconds. All multi-byte values are little-endian.

```
Offset  Size  Type   Field            Scale         Range
─────────────────────────────────────────────────────────
 0      1     U8     magic            0xBB          identifier
 1      2     U16    pack_voltage     0.01V         0–655.35V
 3      2     S16    current          0.01A         ±327.67A
 5      2     U16    remaining_ah     0.01Ah        0–655.35Ah
 7      2     U16    full_ah          0.01Ah        0–655.35Ah
 9      1     U8     soc              1%            0–100%
10      2     U16    cycles           1             0–65535
12      2     U16    errors           bitmap        16 protection flags
14      1     U8     fet_status       bitmap        bit0=charge, bit1=discharge
15      1     U8     n_cells          count         1–32
16      2×N   U16[]  cell_voltages    0.001V        0–65.535V each
16+2N   1     U8     n_ntc            count         1–8
17+2N   2×M   U16[]  ntc_temps        0.1K          convert to °C: val×0.1 − 273.15
```

**Typical size:** 31 bytes (4 cells, 3 NTC sensors)

### Example

```
hex: bb 2805 f8fd 983a 204e 4b 2a00 0000 03 04 e40c df0c e90c da0c 03 7d0b 870b 730b
```

Decodes to: 13.20V, −5.20A, 150.00Ah remaining, 200.00Ah full, 75% SOC, 42 cycles, no errors, FET charge+discharge on, 4 cells [3.300V, 3.295V, 3.305V, 3.290V], 3 temps [20.9°C, 22.0°C, 19.0°C].

## Autopilot State (0xAA) — Characteristic `0000AA01`

Sent at ~5 Hz. 10 bytes, little-endian.

```
Offset  Size  Type   Field             Scale/Values
──────────────────────────────────────────────────────
 0      1     U8     magic             0xAA
 1      1     U8     mode              0=STANDBY, 1=COMPASS, 2=WIND_AWA, 3=WIND_TWA
 2      2     U16    current_heading   0.01° (0–36000)
 4      2     U16    target_heading    0.01° (0–36000)
 6      2     S16    target_wind       0.01° (−18000 to +18000)
 8      2     U16    reserved          0x0000
```

Unlike the SeaSmart/N2K protocol, the binary format explicitly distinguishes AWA and TWA wind modes, eliminating the need for client-side mode tracking.

## Autopilot Commands (0xAA) — Characteristic `0000AA02`

Written by the watch. 2–4 bytes, little-endian.

```
Offset  Size  Type   Field
──────────────────────────
 0      1     U8     magic       0xAA
 1      1     U8     command_id
 2..3   0–2   varies payload
```

### Mode Commands (2 bytes, no payload)

| Cmd ID | Name |
|--------|------|
| `0x01` | STANDBY |
| `0x02` | COMPASS (auto heading hold) |
| `0x03` | WIND_AWA (apparent wind) |
| `0x04` | WIND_TWA (true wind) |

### Set Commands (4 bytes, U16 or S16 payload)

| Cmd ID | Name | Payload | Unit |
|--------|------|---------|------|
| `0x10` | SET_HEADING | U16 heading | 0.01° (0–36000) |
| `0x11` | SET_WIND | S16 angle | 0.01° (±18000) |

### Adjust Commands (4 bytes, S16 payload)

| Cmd ID | Name | Payload | Unit |
|--------|------|---------|------|
| `0x20` | ADJUST_HEADING | S16 delta | 0.01° |
| `0x21` | ADJUST_WIND | S16 delta | 0.01° |

Adjust commands apply a relative change on the firmware side, avoiding the need for the watch to compute and send absolute values.

## Comparison with HTTP Transport

| Aspect | HTTP | BLE |
|--------|------|-----|
| **Battery state** | ~72 bytes text CSV | 31 bytes binary |
| **Autopilot state** | ~140 bytes (3 SeaSmart lines) | 10 bytes binary |
| **Autopilot command** | ~80 bytes (SeaSmart + N2K) | 2–4 bytes binary |
| **Infrastructure** | Requires WiFi network | Direct point-to-point |
| **Latency** | HTTP polling/streaming | BLE notifications |
| **Wind mode** | Can't distinguish AWA/TWA in protocol | Explicit mode byte |

## Implementation Files

### Android (Watch)

| File | Purpose |
|------|---------|
| `battery/.../data/BinaryBatteryParser.kt` | Parses 0xBB battery binary |
| `battery/.../data/BleBatteryDataSource.kt` | BLE GATT client for battery |
| `autopilot/.../protocol/BinaryAutopilotProtocol.kt` | Parses 0xAA state, builds commands |
| `autopilot/.../network/BleAutopilotClient.kt` | BLE GATT client for autopilot |

### Mock Firmware (Python)

| File | Purpose |
|------|---------|
| `mock-firmware/mock_firmware.py` | BLE GATT server using `bless` library |
| `mock-firmware/test_services.py` | Test client for HTTP and BLE verification |

## Settings UI

Both apps provide a WiFi/Bluetooth transport selector in settings (visible when demo mode is off):

- **WiFi** — existing HTTP settings (Find Server, presets, manual URL)
- **Bluetooth** — scan for BLE devices filtered by service UUID, tap to select and connect

Selected transport and BLE device address persist in SharedPreferences.
