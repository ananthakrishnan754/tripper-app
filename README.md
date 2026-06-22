# Tripper Bridge

Android app that connects to a Royal Enfield Tripper BLE navigation pod and relays Google Maps turn-by-turn directions, YouTube Music track info, and incoming caller ID to the round display.

## How It Works

```
┌─────────────┐     Notification Listener     ┌─────────────┐
│ Google Maps │ ──── (turn-by-turn data) ────→ │             │
├─────────────┤                                │  Tripper    │
│ YT Music /  │ ──── (song title + artist) ──→ │   Bridge    │
│ Spotify     │                                │  (Android)  │
├─────────────┤                                │             │
│ Phone       │ ──── (caller name/number) ───→ │             │
└─────────────┘                                └──────┬──────┘
                                                       │
                                            BLE WRITE_NO_RESPONSE
                                            (20-byte packets)
                                                       │
                                                       ▼
                                            ┌──────────────────┐
                                            │  RE Tripper Pod  │
                                            │ (Round Display)  │
                                            └──────────────────┘
```

The app runs as a foreground service with a notification listener. When Google Maps announces a turn, or when music playback starts, or when a call comes in, the app parses the notification and encodes the data into the Tripper's proprietary BLE packet format, then writes it to the characteristic.

## Tripper BLE Protocol (Reverse Engineered)

**Device:** `RE_DISP` (MAC `00:60:37:AB:26:82`)  
**Service UUID:** `01FF0100-BA5E-F4EE-5CA1-EB1E5E4B1CE0`  
**Characteristic UUID:** `01FF0101-BA5E-F4EE-5CA1-EB1E5E4B1CE0`  
**Characteristic Properties:** `WRITE_NO_RESPONSE` only (no NOTIFY/INDICATE)  
**CCCD UUID:** `00002902-0000-1000-8000-00805f9b34fb` (not used — write-only char)

### Protocol Discovery

The Tripper pod was reverse-engineered by:

1. **GATT walk** — Scanning all services/characteristics to find the vendor-specific service
2. **HCI snoop** — Capturing BLE traffic between the official Royal Enfield app and the Tripper pod using Android's Bluetooth HCI snoop log
3. **Packet structure analysis** — Deducing the 20-byte frame format from captured traffic:
   - Byte 0: command ID
   - Bytes 1–17: payload
   - Bytes 18–19: CRC-16 (CCITT 0x1021)

### Packet Commands

| CMD | Name | Purpose |
|-----|------|---------|
| `0x10` | `CMD_NAV_DATA` | Navigation display data |
| `0x12` | `CMD_MUSIC_DATA` | Music track text display |
| `0x21` | `CMD_SHOW_PIN` | Pairing code / logo screen |
| `0x40` | `CMD_STATE_CHANGE` | Switch display mode |
| `0x50` | `CMD_TIME_SYNC` | Set clock time |

### Navigation Packet (CMD 0x10) — 20 bytes

```
Offset  Size  Field
──────────────────────────────────────────────
  0      1    Command (0x10)
  1      1    Flags (upper=version, lower=sub-version)
  2      1    Primary turn icon ID
  3-4    2    Distance to next turn (meters, big-endian)
  5      1    Secondary type
  6      1    Horizon nibble (upper 4) | Mode nibble (lower 4)
  7      1    Secondary icon ID
  8-9    2    Secondary distance (big-endian)
  10     1    Secondary distance units
  11-12  2    ETA (hours:minutes) OR total distance (depends on etaFormat)
  13     1    ETA format (0=none, 10=HH:MM)
  14-17  4    Reserved (zero)
  18-19  2    CRC-16 (CCITT 0x1021)
```

**Turn Icon IDs:** 0=destination, 9=straight, 20=left, 21=right, 22=sharp left, 23=sharp right, 24=keep left, 25=keep right, 26=U-turn CW, 27=merge, 60=depart, 61=U-turn CCW, 62=ferry, 66=off-route, 68=low battery

### Music Packet (CMD 0x12) — 20 bytes

```
Offset  Size  Field
──────────────────────────────────────────────
  0      1    Command (0x12)
  1      1    Flags (1=title, 2=artist)
  2-17   16   ASCII text (null-padded)
  18-19  2    CRC-16
```

### State Change (CMD 0x40) — 20 bytes

```
Offset  Size  Field
──────────────────────────────────────────────
  0      1    Command (0x40)
  1      1    Mode: 0=idle/clock, 1=nav, 2=music, 3=call
  2-17   16   Reserved (zero)
  18-19  2    CRC-16
```

### Connection Characteristics

- **Transport:** `TRANSPORT_AUTO` (compatible across devices)
- **Write type:** `WRITE_TYPE_NO_RESPONSE` (characteristic only has PROPERTY_WRITE_NO_RESPONSE)
- **Heartbeat:** A nav data packet must be written every ~2.5 seconds to prevent the Tripper from disconnecting (GATT status 19 = `GATT_CONN_TERMINATE_PEER_USER` after ~60s of silence)
- **Pairing:** BLE bonding (`createBond()`) is not used — the Tripper rejects it. Pairing is handled via the custom `buildShowPin` protocol command
- **No notifications:** The characteristic does not support NOTIFY/INDICATE — writes are fire-and-forget

## Features

- **BLE auto-connect** — Remembers last paired device and reconnects on launch
- **Google Maps relay** — Listens for Maps turn-by-turn notifications, parses turn icon, distance, and ETA
- **Music relay** — Supports YouTube Music, Spotify, JioSaavn, Gaana (and any app whose notification includes title + artist)
- **Caller ID** — Shows incoming caller name/number from the phone's telephony stack
- **Live preview** — In-app round display preview that crossfades between NAV/MUSIC/CALL modes
- **Bike customisation** — 12 Royal Enfield models with official colour options (long-press the bike image)
- **OLED theme** — Pitch-black background with `#C6FC03` neon green accent

## Permissions Required

- `BLUETOOTH_SCAN` — BLE device discovery
- `BLUETOOTH_CONNECT` — BLE connection
- `ACCESS_FINE_LOCATION` — Required by Android for BLE scanning on some devices (MediaTek/vivo)
- `ACCESS_BACKGROUND_LOCATION` — Keep BLE scanning in background
- `POST_NOTIFICATIONS` — Foreground service notification
- `READ_PHONE_STATE` — Detect incoming calls
- `BIND_NOTIFICATION_LISTENER_SERVICE` — Read Maps/music notifications (user must enable in system settings)

## Building

```bash
git clone https://github.com/ananthakrishnan754/tripper-app.git
cd tripper-app

# Linux/macOS
./gradlew assembleDebug

# Windows
gradlew assembleDebug
```

## Tech Stack

- **Language:** Kotlin
- **UI:** Jetpack Compose + Material 3
- **State:** StateFlow + `collectAsStateWithLifecycle()`
- **BLE:** Android `BluetoothGatt` API
- **Notifications:** `NotificationListenerService` + `NotificationCompat`
- **Calls:** `PhoneStateListener` + `TelephonyManager`
- **Build:** Gradle with Compose BOM 2024.12.01
