# Animal Counter Companion

The **client/bridge layer** of the animal-counter system: the Android phone app
+ the Jetson host companion (HTTP bridge). Split from the counting core
([`wloonis/animal-counter`](https://github.com/wloonis/animal-counter)) at the
`v1.1.0` boundary.

```
 ┌──────────────┐         HTTP          ┌──────────────────┐    shared files   ┌─────────────────────┐
 │  Android app │ ───────────────────▶ │   companion      │ ───────────────▶ │  countingapp (k3s)  │
 │  (this repo) │ ◀─────────────────── │  (this repo,     │ ◀──────────────── │  (sister repo)      │
 └──────────────┘   /api/* (port 8090)  │  host, systemd)  │  /data/orin/files │  OC-SORT + counting  │
                                     └──────────────────┘                   └─────────────────────┘
```

- **`android/`** — Kotlin + Jetpack Compose app. Live count, sessions/history,
  video download, runtime settings (tracking toggles + counting-line offset),
  Jetson shutdown. Probe-based Jetson IP (home WiFi or hotspot). Talks only to
  the companion over HTTP.
- **`companion/`** — `jetson-companion.py`, a **stdlib-only** Python HTTP service
  that runs on the Jetson **host** (systemd, port 8090). Clock sync, read-only
  history/video API, runtime-settings relay, power-off sentinel. Bridges the
  Android app to the countingapp via shared files only.
- **`ansible/playbooks/deploy_companion.yml`** — installs the companion on a
  Jetson.

The **counting core** (YOLO/TensorRT + OC-SORT + K3s `countingapp`) lives in the
**sister repo** [`wloonis/animal-counter`](https://github.com/wloonis/animal-counter).
The two repos communicate ONLY via the shared-file contract in
[`docs/IPC_CONTRACT.md`](docs/IPC_CONTRACT.md).

---

## Table of contents

| Doc | What it covers |
|-----|----------------|
| [`docs/01_jetson_companion.md`](docs/01_jetson_companion.md) | Companion service & HTTP API (clock-sync, history, video, settings, power) |
| [`docs/02_android_app.md`](docs/02_android_app.md) | Android app (probe-based IP, live count, sessions, videos, settings) |
| [`docs/03_counting_history.md`](docs/03_counting_history.md) | The `counting-history.jsonl` log format (writer side, for reference) |
| [`docs/IPC_CONTRACT.md`](docs/IPC_CONTRACT.md) | **Authoritative** shared-file contract between this repo and the sister repo |

---

## Quickstart

### Android app (build a debug APK)

The JDK 17 + Android SDK toolchain must be installed (see
[`AGENTS.md`](AGENTS.md) §7 for the exact paths used on the dev machine).

```bash
cd android
export JAVA_HOME=/home/tt/.local/jdk/jdk-17.0.19+10
export ANDROID_HOME=/home/tt/Android/Sdk
export ANDROID_SDK_ROOT=$ANDROID_HOME
chmod +x gradlew
./gradlew :app:assembleDebug --no-daemon --console=plain
# → android/app/build/outputs/apk/debug/app-debug.apk
```

Sideleload on the phone (USB + adb, or copy the `.apk` and tap it):
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Companion (deploy on the Jetson)

```bash
ansible-playbook -i <jetson_ip>, ansible/playbooks/deploy_companion.yml \
    -e ansible_user=nano-counter -e ansible_password='<JETSON_PASSWORD>'
```

The companion then listens on `http://<jetson_ip>:8090`. Verify:
```bash
curl http://<jetson_ip>:8090/api/identify
```

---

## Architecture

The Android app never talks to the countingapp directly. It talks to the
companion (HTTP, port 8090), which runs on the Jetson host. The companion talks
to the countingapp (a K3s pod) **only** via files in the shared hostPath
`/data/orin/files` (`/files` inside the pod):

- companion **reads** `counting-history.jsonl` (written by countingapp) → serves
  sessions/history/videos to the app.
- companion **writes** `runtime-settings.json` (from the app's settings screen)
  → countingapp hot-reloads at each recording start (no restart).
- companion **writes** `.arret_requested` (from the app's shutdown button) →
  countingapp stops cleanly after the current recording and powers off.

See [`docs/IPC_CONTRACT.md`](docs/IPC_CONTRACT.md) for the full, authoritative
contract. **Any change to those file formats is a coordinated change across both
repos.**

---

## Features

The Android app ("Animal Counter") is the only way to interact with the
counter from the phone. It connects to the Jetson (home WiFi or its HotSpot)
and shows a bottom navigation bar with five tabs. The UI is available in both
French and English.

### Dashboard

An overview of counting over a chosen period (**1 day**, **7 days** or **30
days**): total counted, number of sessions, guard events and average per day.
Handy for tracking a herd's activity over the week.

### Live count

**Real-time counting**: the current number, the status (live, idle, offline)
and the auto mode. This is the tab to keep open during a recording to follow
the count as it happens. When the Jetson is out of range, the last known value
stays on screen from the offline cache.

### History

The list of **all past counting sessions**, newest first, with automatic
load-more as you scroll. You can filter by **status** (running, clean, power
loss…) and by **date**.

For each session you see the net count, the direction (left → right / right →
left), the number of events and the duration.

**Tap a session** → **detail** page: header, counters, guards, performance &
thermal, configuration, system info and the **event timeline** (crossings, ID
recoveries, mirror guards…).

### Video detail & download

From a session's detail you reach the matching **video**. The video detail
page shows the size, duration, resolution, codec and count delta, with two
actions:

- **Download** — saves the `.mp4` to the phone (resumable, chunked download for
  large clips).
- **Open** — plays the video in the app once it's downloaded.

If the video is still recording or has already been cleaned up, a message says
it's unavailable.

### Sessions

The Jetson **startup** history: boot date/time, image tag, Git commit, mode and
notable config. Useful to check when the counter (re)started and with which
version.

### Settings

Five sections:

- **Clock** — "Synchroniser l'heure" button pushes the phone's time to the
  Jetson (which has no internal clock) and persists it into the DS3231 RTC
  module if present.
- **Jetson connection** — automatic IP selection (HotSpot or LAN) or a manual
  IP.
- **Power** — "Arrêter le Jetson" button: the Jetson shuts down cleanly at the
  end of the current recording.
- **Recording & tracking** — toggles to annotate videos (boxes, trails) and the
  **counting line position** setting (which affects counting).
- **About** — app version and Jetson companion version (with a warning on
  mismatch).

The tracking and counting-line settings are **hot-reloaded**: they take effect
at the next recording, with no Jetson restart.

> For the technical companion HTTP API reference (endpoints, clock sync,
> power-off sentinel), see
> [`docs/01_jetson_companion.md`](docs/01_jetson_companion.md).

---

## Development workflow (Archon)

Autonomous dev uses Archon. The Android workflow
(`archon-android-dev`) runs CLARIFY → plan (plannotator HTTP review) →
implement (`./gradlew assembleDebug`) → finalize (PR). There is **no Jetson/video
validation** here — the app does not touch the countingapp.

```bash
archon workflow run archon-android-dev "<your request>" --detach
```

See [`AGENTS.md`](AGENTS.md) for the relay protocol (driving a detached run on
the user's behalf) and the full conventions.

---

## Sister repo

The counting core: [`wloonis/animal-counter`](https://github.com/wloonis/animal-counter)
— YOLO/TensorRT inference, OC-SORT tracking, the counting pipeline, K3s
deployment, and the `validate_on_jetson.sh` reference-video validation. Start
there for anything that touches **what gets counted** (crossing detection,
guard/tracker params, the counting line).

---

## License

Copyright (C) 2026  LOONIS Wennaël

This program is free software: you can redistribute it and/or modify it
under the terms of the **GNU General Public License as published by the Free
Software Foundation, either version 3 of the License, or (at your option) any
later version**. See [`LICENSE`](LICENSE) for the full text.

This program is distributed in the hope that it will be useful, but WITHOUT
ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
FOR A PARTICULAR PURPOSE. See the GNU General Public License for details.