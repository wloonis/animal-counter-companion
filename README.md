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