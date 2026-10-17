# Pad Bridge: Bluetooth gamepad → Wear OS watch → PC

Wear OS 2 app for the **TicWatch Pro 3 GPS** (Android 9 / API 28). It pairs an **8BitDo SN30 / SN30 Pro**
with the watch, reads every button and axis, and streams the state over Wi-Fi to a small server on
your PC. The server turns it into a **virtual Xbox 360 controller** that any game sees.

```
8BitDo ──Bluetooth──▶ watch (Pad Bridge) ──Wi-Fi UDP/TCP, 28-byte frames──▶ PC (padbridge_server.py) ──▶ virtual Xbox 360 pad
                                         ◀── rumble ── (the watch vibrates)
```

It follows the brief's 4 steps: input prototyping (**Input test** screen), custom pairing UI
(**Pair gamepad**), foreground service + sockets (**BridgeService**), round-screen UI.

## Read this first: the one big unknown

Wear OS 2 has no pairing screen for HID devices. The deeper question is whether the watch's
Bluetooth stack has a **HID host profile** at all, and that varies by firmware. The app checks
and shows the answer on the Pair screen:

| Pair screen says | What happens |
|---|---|
| **HID host: available ✓** | The normal path. Pair in **Android mode (START + B)**. The pad becomes a regular Android input device and works exactly as in the brief. Input only reaches the app while it's on screen, so the bridge keeps the screen on (dimmed). |
| **HID host: not available ✗** | Plan B, **Raw L2CAP (experimental)**. Pair in **Switch mode (START + Y)**. The app opens the two HID L2CAP channels itself (hidden-but-reachable Android 9 APIs) and decodes Switch Pro Controller reports. This also works with the screen off. If it fails, the Pair screen shows exactly which call failed. Send me that text. |

In **Auto** (the default link mode), the app picks the path for you.

## What you need

- The watch, with Wi-Fi set up and ADB over Wi-Fi on (you already have that).
- A PC on the same Wi-Fi with Python 3.8+.
- To build the APK: **GitHub Actions** (same setup as Bio-Focus: push the repo and grab the APK from
  the `apk` branch or the run's artifacts), **or** Android Studio, **or** JDK 17 + Android SDK 34
  and `gradlew.bat assembleDebug`.

## 1. Run the PC server

```bat
cd server
pip install -r requirements.txt      REM vgamepad; on Windows accept the ViGEmBus driver install
python padbridge_server.py
```

It prints the IP to enter on the watch. Allow Python through Windows Firewall on **private
networks** when asked: UDP and TCP **7777**.

Check it without the watch: `python fake_watch.py` (it discovers the server, says hello, presses
buttons and spins the stick). You should see a new Xbox controller in *joy.cpl*.

Options: `--port`, `--no-vgamepad` (print only), `--raw` (dump raw HID reports from Plan B), `--quiet`.

## 2. Install on the watch

```bat
adb connect <watch-ip>:5555
adb -s <watch-ip>:5555 install -r pad-bridge-debug.apk
```

## 3. First run

1. **Pair gamepad**. Allow location (Android 9 requires it for Bluetooth scanning). Power the pad on in the
   right mode (see the table above), hold its **PAIR** button for 3 s until the LED blinks fast, then tap
   it in the list (gamepads are highlighted). Long-press an entry to forget it.
2. **Input test**: press everything. You see the decoded state at the top and Android's raw events below
   (key codes, axis names, vendor/product IDs). If the right stick shows up as triggers, change
   *Settings → Right stick axes*.
3. **Settings → Find server on Wi-Fi** fills in the IP. You can also type it in.
4. **Start bridge**. The status line shows the server name, round-trip time and packets per second.
   A notification with a **Stop** action stays up while it runs.

## Settings

| Setting | Default | Notes |
|---|---|---|
| Transport | UDP | UDP is lowest latency. TCP if your network drops UDP. |
| Resend interval | 100 ms | Changes are sent instantly. This is the "nothing changed" repeat, which guards against lost datagrams. |
| Gamepad link | Auto | Auto / Android HID / Raw L2CAP. |
| Right stick axes | Auto | Z+RZ (Google/8BitDo Android mode) or RX+RY (xpad-style pads). |
| Stick dead zone | 10 % | Radial, rescaled so full tilt still reaches 100 %. |
| Nintendo A/B layout | off | Swaps A↔B and X↔Y, for label-based instead of position-based mapping. |
| Keep screen on / Dim | on / on | Needed for the Android HID path (a screen-off watch pauses the app). Dims to 5 %. |
| Rumble on watch | on | Game rumble → watch vibration, scaled by motor strength. |

## Wire protocol

Little-endian, 28-byte STATE frames, one per UDP datagram (TCP: u16 length prefix). Full spec at the top of
`server/padbridge_server.py`. Both sides are pinned by the same golden byte vectors in
`app/src/test/.../ProtocolTest.kt` and `server/test_server.py`. Sticks follow Android's convention
(Y negative = up); the server flips Y for XInput.

## Design notes (where the brief meets the platform)

- **Wi-Fi on Wear OS:** while the phone is connected over Bluetooth, Wear routes app traffic through the phone
  and can't reach your LAN. The service explicitly requests a Wi-Fi network (no internet capability required),
  binds its sockets to it, and holds a high-perf Wi-Fi lock. If Wi-Fi doesn't come up in 15 s, it falls back
  to the default route and says so.
- **Latency:** the send path doesn't go through coroutines. An input event is encoded on the main thread and handed
  to a dedicated urgent-priority network thread that sends it immediately. Coroutines were left out on purpose,
  since dispatcher hops add jitter and the app has no other async work that needs them.
- **Stuck buttons:** the server drops reordered datagrams by sequence number and releases a pad after 1 s of silence.
- **Multiple pads:** up to 4 slots; each becomes its own virtual controller.
- **B / Back:** gamepad keys are always consumed by the app, so B never closes a screen. The watch's own
  side buttons work normally.
- **Not done yet:** Bluetooth RFCOMM output (the brief lists it as an alternative to Wi-Fi), background capture
  on the Android HID path (an accessibility service could capture keys but not stick axes), and a decoder
  for raw D-input reports (use `--raw`, send me the dump).

## Building

```bat
gradlew.bat test            REM protocol, Switch parser, pad tracker
gradlew.bat assembleDebug   REM app\build\outputs\apk\debug\app-debug.apk
cd server && python -m unittest test_server -v
```

Logs: `adb logcat -s PadBridge`
