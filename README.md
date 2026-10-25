# LG Remote 📺

[![Platform](https://img.shields.io/badge/Platform-Android%208.0%2B%20(API%2026%2B)-3DDC84.svg?logo=android&logoColor=white)](https://android.com)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Release](https://img.shields.io/badge/Release-v1.0.0-informational.svg)](https://github.com/nepalanurag/lg-remote/releases/tag/v1.0.0)
[![Privacy](https://img.shields.io/badge/Privacy-100%25%20Local%20%7C%20No%20Cloud-success.svg)]()

A fast, context-aware unified Android remote for **LG webOS TVs** and **Android TV / Google TV streaming boxes** (such as the *onn. 4K Streaming Box*, *Chromecast with Google TV*, or *Nvidia Shield TV*) on a single screen.

Everything operates strictly over your local Wi-Fi and Bluetooth: **no cloud, no accounts, no ads, no trackers, and no internet required**.

---

## 📥 Quick Download

Get the latest pre-compiled, optimized APK ready to install:

| Build | Download Link | Size | Description |
| :--- | :--- | :--- | :--- |
| **Release APK** *(Recommended)* | [**`apks/lg-remote.apk`**](apks/lg-remote.apk) or [**GitHub Releases**](https://github.com/nepalanurag/lg-remote/releases/tag/v1.0.0) | `~1.5 MB` | Minified with R8, ProGuard-optimized, debug-signed for immediate sideloading. |
| **Debug APK** | [**`apks/lg-remote-debug.apk`**](apks/lg-remote-debug.apk) | `~5.2 MB` | Includes unstripped debug symbols and verbose protocol logs. |

### Sideload via ADB:
```bash
adb install -r apks/lg-remote.apk
```

---

## ✨ Key Features

### 🧠 Context-Aware Input Switching
Control two devices simultaneously without switching remotes or remounting interfaces:
- **Active HDMI Awareness**: Link your streaming box to its HDMI port (e.g. `HDMI_3`). When that input is displayed on the TV, navigation keys, keyboard inputs, and trackpad controls seamlessly route to the box.
- **Dedicated TV Controls**: Volume, mute, inputs, and TV power remain anchored to the LG TV at all times—the box never intercepts or alters your master TV audio.
- **Instant LG Home Access**: Long-press **Home** (or open **Apps → LG Home**) to switch back to webOS from the Android TV environment.
- **HDMI-CEC Power Wake**: Turning on the TV when in deep sleep sends a Wake-on-LAN packet while simultaneously waking the box over TLS (`KEYCODE_WAKEUP` + `KEYCODE_HOME`), triggering HDMI-CEC (SIMPLINK) to power on the TV even if the TV's Wi-Fi controller is suspended.

---

### 🖱️ Windows Precision Trackpad Mode
A custom-engineered relative touchpad state machine matching modern laptop trackpad physics:
- **Kinetic Ballistic Acceleration**: Dynamically shifts from a $1.35\times$ precision multiplier for tiny cursor adjustments to over $6.0\times$ acceleration for quick flicks across 4K displays.
- **Two-Finger Natural 2D Scrolling**: Smooth, dual-axis gesture tracking. Swiping two fingers up/down scrolls vertical lists; swiping two fingers left/right scrolls horizontal carousels (e.g., YouTube, Netflix, Android TV Leanback shelves) via USB Consumer `AC Pan` (`0x0238`) and `Wheel` (`0x38`).
- **Two-Finger Tap Secondary Click**: Tapping two fingers fires `BUTTON_SECONDARY` (acting natively as Back / Context Menu).
- **Zero Cursor Sticking or Jump**: Strict multi-touch gesture isolation prevents cursor jumping during finger liftoff and eliminates auto-drag delays.

---

### 📡 Low-Latency Bluetooth HID Mouse
- **Native Android TV Pointer**: Registers the phone as a composite Bluetooth HID device (`SUBCLASS1_COMBO` with Keyboard + Mouse descriptor).
- **Independent Cursor**: Directly drives an on-screen mouse pointer on Android TV without activating or disturbing the LG Magic Remote pointer.
- **10ms Batching Dispatch**: Queues and batches cursor updates to sync with Bluetooth L2CAP polling windows, ensuring responsive tracking without event bloat.
- **Auto-Reconnection**: Reconnects to the box mouse over Bluetooth the moment its HDMI input becomes active.

---

### ⏱️ Press-and-Hold & Continuous Repeat
- **D-Pad `OK` Long-Press**: Holding the center `OK` button for $\ge 450\,\text{ms}$ provides haptic feedback and sends a long-press (`DIRECTION_START_LONG` / `DIRECTION_END_LONG`), opening native context menus and app options on Android TV or edit mode on webOS.
- **Continuous Scrubbing & Navigation**: Holding D-pad arrows, **Rewind**, or **Fast-Forward** continuously repeats commands at $120\,\text{ms}$ intervals for rapid grid navigation and media scrubbing.
- **Hold-to-Back**: Holding **Back** smoothly navigates backward through nested UI levels at an ergonomic $180\,\text{ms}$ cadence.
- **Trackpad Click & Hold**: Pressing and holding the on-screen **Click** button holds mouse button 1 down for native drag-and-drop and long-clicks.

---

### ⌨️ Automatic Remote Keyboard Sync
- Selecting an on-screen text input on either the LG TV or Android TV immediately summons the phone keyboard.
- Live deletion mirroring keeps draft text in sync across both devices.
- Keystrokes are transmitted cleanly with composition buffer safety.

---

## 🛠️ Architecture & Protocol Stack

| Target Device | Transport Protocol | Default Port | Security / Credentials |
| :--- | :--- | :--- | :--- |
| **LG webOS TV** | SSAP over WebSocket (`wss://` / `ws://`) | `3001` / `3000` | Client key encrypted with AES-GCM in Android Keystore |
| **Android TV (Pairing)** | Google TV Remote Protocol v2 over TLS | `6467` | Ephemeral X.509 cert + PIN-based secret hash |
| **Android TV (Commands)** | Google TV Remote Protocol v2 over TLS | `6466` | Persistent SSL monitor socket for keystrokes & IME events |
| **Android TV (Pointer)** | Bluetooth HID (`L2CAP` / `BTA_HD`) | *Bluetooth* | SDP `SUBCLASS1_COMBO`, Best-Effort QoS, 5-byte HID reports |

```
                    ┌─────────────────────────┐
                    │      MainActivity       │
                    │  (Touchpad, D-pad, IME) │
                    └────────────┬────────────┘
                                 │
                    ┌────────────▼────────────┐
                    │      RemoteService      │
                    │ (Context Routing Engine)│
                    └──────┬───────────┬──────┘
                           │           │
           HDMI = TV Input │           │ HDMI = Box Input
                           ▼           ▼
               ┌──────────────┐     ┌───────────────────────┐
               │ LgTvClient   │     │  BoxRemoteController  │
               │ (SSAP WS)    │     └───────────┬───────────┘
               └──────────────┘                 │
                                 ┌──────────────┴──────────────┐
                                 ▼                             ▼
                      ┌─────────────────────┐       ┌─────────────────────┐
                      │   AndroidTvRemote   │       │  BoxBluetoothMouse  │
                      │  (TLS v2 Protobuf)  │       │ (Bluetooth HID COMBO│
                      └─────────────────────┘       └─────────────────────┘
```

---

## 🚀 Setup Guide

### 1. LG TV Setup
1. On your LG webOS TV, go to:
   **Settings → All Settings → General → External Devices → TV On With Mobile**
2. Enable **Turn on via Wi-Fi**.
3. Launch the app while on the same Wi-Fi network.
4. When prompted, enter the one-time pairing PIN displayed on your TV screen. The client key is encrypted and stored locally.

### 2. Android TV Box Setup
1. In the app, open **Settings → Android TV box**.
2. Tap **Set up Android TV box** (mDNS automatically discovers your box on the network).
3. Enter the 6-character pairing code shown on your TV.
4. Tap **Link to an HDMI input** and select the port your streaming box is plugged into (e.g. `HDMI_3`).
5. In **Settings → Bluetooth Mouse**, tap **Pair Bluetooth Mouse** to link the virtual trackpad cursor to your streaming box.

---

## 🔨 Building from Source

### Prerequisites
- JDK 17 (`JAVA_HOME` pointing to OpenJDK 17)
- Android SDK Platform 34 & Build-Tools 34.0.0

```bash
# Clone the repository
git clone https://github.com/nepalanurag/lg-remote.git
cd lg-remote

# Run unit tests and lint
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew testDebugUnitTest lintDebug

# Build release APK
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew assembleRelease
```

Compiled APKs will be located in:
- `app/build/outputs/apk/release/app-release.apk`
- `app/build/outputs/apk/debug/app-debug.apk`

---

## 📄 License

This project is licensed under the [Apache License 2.0](LICENSE).
