# LG Remote

An Android remote for an **LG webOS TV** and an **Android TV / Google TV streaming box**, on one
screen. Everything happens over your own Wi-Fi: no cloud, no account, no internet connection
required.

Java only. Package `com.nepalanurag.lgremote`. Minimum Android 8.0 (API 26), target API 34.

---

## What it does

**Controls the LG TV**
- D-pad, OK, Back, Home, menu, info, colour buttons
- Playback: play/pause, rewind, fast-forward, stop
- Volume, mute, channel up/down
- Trackpad mode: drag to move, tap to click, two-finger scroll, long-press drag-and-drop
- Apps and inputs lists, live volume and power state
- Wake-on-LAN from a saved MAC address
- Live TV power badge: **ON**, **SCREEN OFF**, **STANDBY**, **OFF?**

**Controls the Android TV box**
- Pairing over the Google TV / Android TV Remote protocol v2
- D-pad, OK, Back, Home, Settings, playback keys
- Text entry, delete and Enter through the Android TV remote IME
- mDNS discovery, with a saved host tried first and manual IP as the fallback

**One screen, two devices**
- When the box's HDMI input is the one on screen, Home, settings and text entry go to the box
- Everything else keeps working on the TV
- Long-pressing **Home** (or opening **Apps → LG Home**) returns to the LG TV Home screen from the Android TV box
- Turning on the TV when offline or in standby sends Wake-on-LAN and simultaneously wakes the paired Android TV box over TLS (`KEYCODE_WAKEUP` + `KEYCODE_HOME`), triggering HDMI-CEC (One Touch Play / SIMPLINK) to wake the TV even when the TV's Wi-Fi interface is asleep
- Long-pressing **Power** or selecting *Turn on box (Wake TV via HDMI-CEC)* in Settings allows waking the box and TV on demand

**Automatic keyboard takeover**
- Select a text field on the TV and the phone keyboard opens by itself
- Select a text field on the box and the phone keyboard opens by itself
- Typed text is sent to whichever device is on screen after a short pause
- Only newly typed characters are sent; the draft stays in the field while the keyboard is composing
- The keyboard's backspace deletes in the phone draft and mirrors the deletion to the active screen; on an empty draft it sends the delete directly
- Enter is never pressed for you; the Enter button is there for apps that need it
- A failed send leaves the text in the phone field so it can be retried

---

## How it works

| Device | Transport | Port |
| --- | --- | --- |
| LG webOS TV | SSAP over WebSocket, `wss://<ip>:3001` with a fallback to `ws://<ip>:3000` | 3001 / 3000 |
| Android TV box (pairing) | Google TV Remote protocol v2 over TLS | 6467 |
| Android TV box (commands) | Google TV Remote protocol v2 over TLS | 6466 |

The TV's TLS certificate is trusted as-is, because webOS TVs use self-signed certificates.

The box speaks the protobuf-based remote protocol directly: key events go out as field `10`, IME
batch edits as field `21`, and the box answers with `RemoteImeKeyInject` (field `20`) and
`RemoteImeShowRequest` (field `22`). The field focus and the IME counters come from one persistent
monitor socket, so the app always knows when a field is selected without opening a new connection
per keystroke.

**Credentials.** The TV's client key and the box's certificate and private key are encrypted with
AES-GCM. The key lives in the Android Keystore. Only the local network is used; nothing about your
TV is sent anywhere.

---

## Setup

### 1. The LG TV

On the TV: **Settings → All Settings → General → External Devices → TV On With Mobile**, and turn
on **Turn on via Wi-Fi**.

Keep the phone and the TV on the same Wi-Fi. The first time you connect, the TV shows a one-time
PIN. Enter it on the phone — no TV remote is needed for the first pairing. The pairing is
remembered, and the client key is stored encrypted.

Older webOS builds that do not support PIN pairing fall back to the TV's on-screen approval
prompt. If a saved client key is rejected after a TV reset, the app retries once with PIN pairing
instead of deleting the old key until the TV issues a new one.

### 2. The Android TV box

1. Open **Settings → Android TV box** in the app.
2. Tap **Set up Android TV box**. The app finds the box over mDNS (or you can type its address).
3. The box shows a six-character code. Enter it.
4. Tap **Link to an HDMI input** and pick the input the box is plugged into.

The link is stored as the input id the TV reported, for example `HDMI_3`. While that input is the
one on screen, Home, settings and text entry go to the box.

**Forgetting the box.** *Forget Android TV pairing* deletes the box certificate and the paired
flag, but keeps the address, the name and the HDMI link, so re-pairing is quick. A fresh
certificate is generated for every pairing attempt, so the box always shows a new code.

---

## Building

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew assembleDebug testDebugUnitTest
```

The debug APK lands in `app/build/outputs/apk/debug/app-debug.apk`.

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## Architecture

```
MainActivity  ── UI, keyboard, dialogs
     │
RemoteService ── connection state, power, text focus, listeners
     │
     ├── activeRemote()  ← picks the controller for whatever is on screen
     │        ├── LgRemoteController   SSAP: D-pad, Home, settings, text
     │        └── BoxRemoteController  Android TV: keys, Home, settings, IME text
     │
LgTvClient        SSAP, power subscription, IME text, screenshot
AndroidTvRemote   box pairing, persistent IME monitor, keys, IME text
SecureStore       encrypted keys and identity
Routing           the one rule that decides TV vs box
```

**The routing rule.** `Routing.isBoxInput()` compares the linked input id against the input
currently on screen, by id and by app id. `RemoteService.activeRemote()` asks that rule and returns
the matching controller, so every button follows the same context rule instead of each button
deciding for itself.

**What stays on the TV.** Volume, mute, power, inputs and apps are always TV controls, even while
the box input is on screen — the box has no business changing the TV's volume.

**When the box is not paired.** Navigation falls back to the TV, which forwards the keys over
HDMI-CEC. Home, settings and text entry tell you to pair the box, because those cannot work over
CEC.

---

## Tests

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew testDebugUnitTest
```

63 unit tests, no device required:

| Test | What it covers |
| --- | --- |
| `ProtocolTest` | SSAP register, PIN pairing payloads, request and subscribe messages, pointer format, text payloads |
| `BoxImeProtocolTest` | Exact bytes for box key events and IME batch edits, and the parsing of the box's focus messages |
| `RoutingTest` | The TV-vs-box routing rule |
| `BackspacePolicyTest` | Local draft edits vs. device backspace |
| `TextDiffTest` | Incremental draft mirroring, including surrogate pairs |
| `BackoffTest` | Reconnect backoff |
| `WakeOnLanTest` | MAC normalisation |

---

## Known limitations

- **LG webOS YouTube ignores remote keyboards.** Text entry works in other TV apps; YouTube on
  webOS will not accept it. Use the box for YouTube search.
- **The box needs the IME feature.** Text entry on the box requires `FEATURE_IME` in the box's
  remote configuration. Boxes without it still accept key presses.
- **Same network required.** The TV and the box are found on the local network. Guest Wi-Fi with
  client isolation blocks discovery; enter the address manually in that case.
- **The debug screenshot hook is debug-only.** A long-press on the header requests a screenshot
  from the TV. It is compiled out of release builds via `BuildConfig.DEBUG`.

---

## Privacy

Local network only. No analytics, no crash reporting, no cloud service, no account. The app talks
to your TV and your box and to nothing else.
