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

## Companion features

`companion/jetson-companion.py` is a **stdlib-only** Python HTTP service
(`http.server` — no Flask, no FastAPI, no `pip`, no `venv`) that runs on the
Jetson **host** as a systemd unit (`jetson-companion.service`, port **8090**).
It is intentionally lightweight so it can be deployed offline, over the Jetson
WiFi HotSpot, with no internet and no package download.

It is the **only** thing the Android app talks to. It bridges the app to the
countingapp exclusively via the shared files in `/data/orin/files` — it never
calls the countingapp's container API directly.

### Never starves inference

The companion runs **on the same host** as the inference pod, so its systemd
unit is deliberately constrained to keep it subservient to the countingapp:
`Nice=10`, `CPUQuota=30%`, best-effort I/O. **Do not remove these limits** —
the counting core must always get the CPU/IO it needs.

### Clock sync (the Jetson has no RTC)

The production Jetson has no coin-cell battery → no real-time clock. On every
offline boot its clock is stuck at the build date (or `1970`). In HotSpot mode
the Android phone is the **only** clock source: the app's "Synchroniser
l'heure" button `POST /api/time` with the phone's ISO8601 time + IANA timezone,
and the companion applies it via `timedatectl` (NTP is disabled first so the
manual write sticks). On a Jetson with a **DS3231 RTC** installed, the same
call also runs `hwclock --systohc` to **persist** the correction into the RTC,
so the next boot is sane without a phone. Best-effort: silently no-ops if no
DS3231 is present.

### HTTP API

All endpoints are JSON, unauthenticated (the HotSpot is a closed offline LAN —
the only peer is the phone). Versioning: `GET /api/identify` returns the
service `version`; the Android app checks it and warns on mismatch. Bump the
`COMPANION_VERSION` constant on any API/behavior change.

| Method | Path | Purpose |
|--------|------|---------|
| `GET` | `/api/identify` | Service discovery — name + `version` |
| `POST` | `/api/time` | Set the Jetson clock + timezone (persists to DS3231 if present) |
| `GET` | `/api/count` | Live count / status / auto_mode from the newest heartbeat |
| `GET` | `/api/sessions` | Paginated session summaries (`limit`/`offset`), newest first |
| `GET` | `/api/sessions/<id>` | Full session detail (aggregate + heartbeats + events + end) |
| `GET` | `/api/summary` | Daily aggregates (`?days=7`): count / sessions / guard events |
| `GET` | `/api/startups` | Startup history lines (`?limit=50`) |
| `GET` | `/api/videos` | Paginated video list (running recording is the synthetic first row) |
| `GET` | `/api/video/<id>` | Range-streamed compressed `counting-<id>-*.mp4` (HTTP 200/206/416) |
| `GET` | `/api/settings` | Current `runtime-settings.json` (`{}` if absent) |
| `PUT` | `/api/settings` | PATCH-like merge into `runtime-settings.json` (atomic write) |
| `POST` | `/api/power` | Writes the `.arret_requested` sentinel → countingapp stops + powers off |

### Read-only history & video

The history/video endpoints (`/api/sessions`, `/api/sessions/<id>`,
`/api/summary`, `/api/startups`, `/api/videos`, `/api/video/<id>`) are
**read-only** — the countingapp is the sole writer of `counting-history.jsonl`.
The reader uses a lazy in-memory `HistoryIndex`: it scans the JSONL once on the
first request, caches the `session_id → {offsets, summary}` map, and
invalidates the cache when the file size changes. Partial last lines are
tolerated. Video streaming supports HTTP `Range` requests so the Android app
can resume/partial-download large clips.

### Runtime-settings relay (hot-reload, no restart)

`PUT /api/settings` writes `runtime-settings.json` (tracking toggles
`draw_tracking` / `box_tracking` / `centroid_tracking` +
`offset_counting_line`). The write is a **PATCH-like merge** (only the keys
present in the body are overwritten; unknown keys are ignored for
forward-compat) and is **atomic** (temp file + `os.replace`, so the
countingapp never reads a half-written file). The countingapp hot-reloads this
file at each recording start — **no restart needed** to pick up new settings.

### Power-off sentinel

`POST /api/power` does **not** power off the Jetson itself. It writes the
`.arret_requested` sentinel file; the countingapp polls it and runs its own
clean finalize → stop → poweroff sequence after the current recording. The
companion stays a thin relay.

See [`docs/01_jetson_companion.md`](docs/01_jetson_companion.md) for the full
endpoint reference, curl examples, and the NTP/RTC notes.

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