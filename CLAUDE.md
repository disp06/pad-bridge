# Pad Bridge — notes for Claude Code

Wear OS app that bridges a Bluetooth gamepad (8BitDo SN30 / SN30 Pro) to a PC over Wi-Fi.
Package `com.padbridge.wear`. Original brief: `brief.md`. Companion server: `server/`.

## Hard platform constraints
- Device: TicWatch Pro 3 GPS, Wear OS 2.x = **Android 9, API 28**. minSdk 28, **targetSdk 28 on purpose**:
  API-28 permission model (no BLUETOOTH_SCAN/CONNECT, no foregroundServiceType) and hidden-API
  greylist access for `BluetoothHidHost.connect`, `removeBond`, `createL2capSocket`. Sideload only.
- No Compose, no AppCompat. Platform `Activity` + XML Views + `androidx.wear:wear` (BoxInsetLayout,
  WearableRecyclerView). Theme parent `@android:style/Theme.DeviceDefault`.
- No APIs above 28 without a version check.
- Runtime permission: only `ACCESS_FINE_LOCATION` (for Bluetooth discovery). The rest are install-time.

## Architecture
- `input/InputHub` (main thread) is the single source of pad state. Every activity extends
  `ui/GamepadActivity`, which forwards KeyEvent/MotionEvent to it. Gamepad keys are always consumed
  (so B/Back never closes a screen).
- `input/AxisLayout` decides which Android axes are the right stick/triggers per device
  (Z/RZ vs RX/RY). `input/PadTracker` holds one pad's mutable state and emits immutable `PadState`.
- `bt/BtManager`: in-app discovery + bonding + HID host proxy (profile id 4, hidden). Reports
  whether the watch's stack even has a HID host.
- `bt/RawHidLink`: Plan B when there's no HID host — opens L2CAP PSM 0x11/0x13 via hidden APIs and parses
  Switch Pro reports (`protocol/SwitchReportParser`). Feeds InputHub with a negative key.
- `service/BridgeService`: foreground service. Requests a Wi-Fi network explicitly (Wear routes
  through the phone otherwise), binds sockets to it, sends on a dedicated HandlerThread.
  Re-sends last state every keepalive interval; server releases a silent pad after 1 s.
- `protocol/` is pure Kotlin (no Android imports): wire format + parsers, unit-tested.
  **Any change to the wire format must be mirrored in `server/padbridge_server.py`** and the golden
  vectors in both test suites.

## Commands
- Build: `gradlew.bat assembleDebug`   Tests: `gradlew.bat test`
- Server tests: `cd server && python -m unittest test_server -v`
- Install: `adb connect <ip>:5555` then `adb install -r app\build\outputs\apk\debug\app-debug.apk`
- Logs: `adb logcat -s PadBridge`
