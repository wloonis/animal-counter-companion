# AGENTS.md — Conventions for AI agents working in this repo

This file is the entry point for any AI agent (pi sessions, Archon workflows,
review bots) operating in `animal-counter-companion`. Read it before running
anything.

This repo is the **client/bridge layer** of the animal-counter system:

- **`android/`** — the Kotlin + Jetpack Compose phone app (live count, sessions,
  history, video download, settings, Jetson shutdown). Talks only to the
  companion HTTP API.
- **`companion/`** — `jetson-companion.py`, a **stdlib-only** Python HTTP
  service that runs on the Jetson **host** (systemd, NOT k3s). Bridges the
  Android app to the countingapp via shared files.
- **`ansible/playbooks/deploy_companion.yml`** — installs the companion.

The **counting core** (OC-SORT tracking + counting + TensorRT + K3s countingapp)
lives in the **sister repo** `wloonis/animal-counter`. The two repos
communicate ONLY via the shared-file contract in
[`docs/IPC_CONTRACT.md`](docs/IPC_CONTRACT.md) — read it, it is authoritative.

---

## 1. Archon — what it is and how it's configured here

Archon is a workflow runner (`archon` CLI at `/home/tt/.bun/bin/archon`, source
`/home/tt/repository/Archon`). It runs multi-phase AI workflows as detached
background processes, each phase spawning its own pi session in an isolated git
worktree.

Repo-local Archon config: `.archon/config.yaml` + `.archon/workflows/*.yaml`.

Prereqs already satisfied on this machine:
- `pi install npm:@plannotator/pi-extension` (plan review UI).
- `.archon/config.yaml`: `extensionFlags.plan: true` and env
  `PLANNOTATOR_REMOTE: "1"` (so the plan review server binds `0.0.0.0:19432`
  for remote/forwarded access).
- Model tier defaults to `ollama/glm-5.2` (local). Provider `pi`.

Verify setup any time:
```bash
archon doctor
archon validate workflows archon-android-dev
```

---

## 2. The `archon-android-dev` workflow

File: `.archon/workflows/archon-android-dev.yaml`. Use for autonomous
development of the **Android app** (the companion has no automated workflow yet
— deploy is manual via the playbook).

**Four phases:**

| # | Node | Type | What happens | Human gate? |
|---|------|------|-------------|-------------|
| 1 | `clarify` | interactive loop | Pi asks 2-3 questions, converges on intent. Emits `READY_FOR_PLAN`. | **Yes** (pauses each iteration) |
| 2 | `plannotator-plan` | single pi session | Pi writes `PLAN.md`, calls `plannotator_submit_plan`. | **Yes** (HTTP review, NOT a pause) |
| 3 | `verify-plan` | bash | Sanity-checks `PLAN.md` has task checkboxes. | No |
| 4 | `implement` | loop (fresh context) | Pi implements one task at a time, `./gradlew :app:assembleDebug`, commits. Emits `IMPL_DONE`. | No |
| 5 | `finalize` + `verify-pr-base` | pi + bash | Push branch, create draft PR, verify PR base. | No |

**Validation is Android, NOT Python/JS/TS.** Never run `python3 -m py_compile`,
`bun run type-check`, or any Jetson/video validation — they don't apply here.
The ONLY validation is a successful gradle build:
```bash
cd android && ./gradlew :app:assembleDebug
```
There is **no Jetson/video validation** — the app does not touch the countingapp.
The workflow goes straight from implement to finalize.

---

## 3. Launching a workflow (detached, returns immediately)

Always launch **detached** so the workflow runs in the background and the CLI
returns the run-id at once:

```bash
cd <this repo>
archon workflow run archon-android-dev "<your request, with any research already done>" --detach
```

> ⚠ Never launch an interactive Archon workflow in the foreground from a pi
> bash tool — the CLARIFY phase is `interactive: true` and waits for user input
> on a live TTY. A foreground launch in a non-interactive bash call deadlocks at
> the first gate. Always use `--detach` and drive it via the relay protocol.

---

## 4. Relay protocol — driving a detached run on the user's behalf

This is the protocol a pi session uses to relay Archon states to the user and
feed their answers back, without a live TTY. **Identical to the sister repo** —
see `wloonis/animal-counter` `AGENTS.md` §4 for the full loop. Summary:

### 4.1 State files (beacons) — `.archon-relay/`

| Phase | File | States |
|-------|------|--------|
| CLARIFY | `CLARIFY.md` | `WAITING_FOR_USER` / `READY_FOR_PLAN` |
| PLAN | `PLANNOTATOR.md` | `PLAN_REVIEW_PENDING` / `PLAN_APPROVED` |

The worktree path comes from `workflow get`. Read the beacon with the `read`
tool. `.archon-relay/` is gitignored — never commit beacons.

### 4.2 Polling run state
```bash
archon workflow get <run-id> --json --verbose
archon workflow status
archon workflow runs --json
```

### 4.3 Feeding feedback back
```bash
nohup archon workflow approve <run-id> "<the user's reply>" > /tmp/archon_resume.log 2>&1 &
```

### 4.4 Plannotator plan review (HTTP — NOT a paused gate)
URL: `http://127.0.0.1:19432`. The relay MUST tell the user to open it and
**must NOT click Approve/Deny for them** — plan approval is the user's action.

### 4.5 Full relay loop
1. Launch detached → capture run-id.
2. Poll ~20-30s.
3. CLARIFY paused → surface questions → on reply, `nohup archon workflow approve … &`.
4. PLAN phase → tell user to open `http://127.0.0.1:19432`. Do not approve for them.
5. implement → autonomous; report progress from commits/events.
6. Completed → report PR URL.

---

## 5. Quick reference — Archon CLI

```bash
archon workflow list
archon workflow run archon-android-dev "<msg>" --detach
archon workflow status
archon workflow get <run-id> --json --verbose
archon workflow approve <run-id> "<feedback>"
archon workflow resume <run-id>
archon workflow abandon <run-id>
archon isolation list
archon validate workflows archon-android-dev
archon doctor
```

Plannotator (plan review): `http://127.0.0.1:19432` (only while a plan is pending).

---

## 6. The IPC contract with the sister repo

**Read [`docs/IPC_CONTRACT.md`](docs/IPC_CONTRACT.md).** The companion talks to
the `animal-counter` countingapp ONLY via shared files in two hostPaths on the
Jetson: `/data/orin/files` (hostPath `/files` — **data**: counting-history.jsonl
+ mp4 clips) and `/data/orin/conf` (hostPath `/conf` — **config/control**:
runtime-settings.json + .arret_requested; split introduced by BL-79 in the
sister repo, companion aligned by BL-80):

- **companion reads** `counting-history.jsonl` (written by countingapp) from
  `/files` — the JSONL schema is the tightest contract; the companion's
  `HistoryIndex` parser must match the countingapp's `app/src/core/history.py`
  writer.
- **companion writes** `runtime-settings.json` (read by countingapp at each
  recording start — hot-reload, BL-76) and `.arret_requested` (power-off
  sentinel, BL-71) to `/conf` (BL-80; previously `/files`).
- **companion streams** `counting-*.mp4` clips (produced by the countingapp)
  from `/files`.

Any format change is a **coordinated change across both repos**. Keep
`docs/IPC_CONTRACT.md` identical in both. Prefer additive changes (new fields,
new `type` values); the reader must tolerate unknown fields.

---

## 7. Android app — building the APK (toolchain already installed)

The Android app lives under `android/` (Compose, AGP 8.7.0, Kotlin 2.0.21,
Gradle 8.9, `compileSdk=35`, `build-tools 34.0.0`, `minSdk=33`, Java 17 target).
The user reloads it on the phone via **sideload of a debug APK**.

**The build toolchain is ALREADY INSTALLED — do NOT reinstall it.**

- **JDK 17 (Temurin)** → `~/.local/jdk/jdk-17.0.19+10` (`$JAVA_HOME`).
- **Android SDK** → `~/Android/Sdk` (`$ANDROID_HOME` / `$ANDROID_SDK_ROOT`):
  `platform-tools` (adb), `platforms/android-35`, `build-tools/34.0.0`,
  `cmdline-tools/latest`. Licenses accepted.
- **Gradle cache** → `~/.gradle` (Gradle 8.9 dist + deps). First build downloads
  nothing.
- **`android/local.properties`** points to the SDK:
  `sdk.dir=/home/tt/Android/Sdk`. It is **NOT gitignored** and must **never be
  committed** (machine-local; reference `sdk.dir` only, don't `git add`
  `local.properties`).

**Build a debug APK:**
```bash
cd android
export JAVA_HOME=/home/tt/.local/jdk/jdk-17.0.19+10
export ANDROID_HOME=/home/tt/Android/Sdk
export ANDROID_SDK_ROOT=$ANDROID_HOME
chmod +x gradlew
./gradlew :app:assembleDebug --no-daemon --console=plain
# Output: android/app/build/outputs/apk/debug/app-debug.apk
```

Copy to the user's Desktop (naming convention
`animal-counter-<bl-or-feature>-debug.apk`):
```bash
cp app/build/outputs/apk/debug/app-debug.apk \
   /mnt/c/Users/tt/Desktop/animal-counter-<label>-debug.apk
```

Install via adb (USB):
```bash
/mnt/c/Dev/platform-tools/adb.exe install -r 'C:\Users\tt\Desktop\<apk>.apk'
```

**Notes:**
- Debug APK signed with the per-machine debug keystore (`~/.android/debug.keystore`).
  If the phone has the app from a different keystore → uninstall first
  (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`).
- `release` has no signingConfig → `assembleRelease` is unsigned. Use
  `assembleDebug` for sideload.
- App icon: adaptive icon `mipmap-anydpi-v26/ic_launcher{,_round}.xml` →
  `@drawable/ic_launcher_foreground` + `@color/ic_launcher_background` (teal).
- The Android app is a **no-counting-code change** → no Jetson validation applies
  (the countingapp is in the sister repo).

---

## 8. Companion — deploy + conventions

The companion is **stdlib-only Python** (no pip, no requirements.txt). It runs
on the Jetson **host** as a systemd service (`jetson-companion.service`, port
8090), NOT in k3s.

**Deploy:**
```bash
ansible-playbook -i <jetson_ip>, ansible/playbooks/deploy_companion.yml \
    -e ansible_user=nano-counter -e ansible_password='<JETSON_PASSWORD from .env.local>'
```

The playbook (`deploy_companion.yml`) installs
`/usr/local/bin/jetson-companion` + the systemd unit + enables the service. It
does NOT touch the countingapp or k3s.

**Conventions:**
- The companion **must never starve inference**: its systemd unit caps CPU to
  30%, `Nice=10`, best-effort I/O (BL-71). Do not remove these limits.
- The companion reads `/data/orin/files/counting-history.jsonl` (HISTORY_FILE_HOST
  env, default `/data/orin/files/counting-history.jsonl`). The shared path is
  created by the countingapp deploy; the companion deploy only ensures the dir.
- The companion writes `runtime-settings.json` + `.arret_requested` to
  `/data/orin/conf/` (CONF_DIR_HOST env, default `/data/orin/conf` — BL-80; split
  from `/files` data by BL-79). The companion deploy ensures `/data/orin/conf`
  exists too (it must not depend on the countingapp deploy order).
- Companion API: `POST /api/time` (clock sync), `GET /api/count` (live
  heartbeat), `GET /api/sessions`, `GET /api/summary`, `GET /api/videos`,
  `GET /api/video/<id>` (range-stream), `GET|PUT /api/settings`,
  `POST /api/power`, `GET /api/identify`. See `docs/01_jetson_companion.md`.

**Versioning:** bump the `COMPANION_VERSION` constant in
`companion/jetson-companion.py` on any API/behavior change. The Android app
checks `GET /api/identify` and warns on mismatch.

---

## 9. Pitfalls

- **Foreground Archon launch deadlocks** at CLARIFY. Always `--detach`.
- **`paused` run `metadata.approval.message` is generic** — real questions are in
  the beacon file / transcript.
- **`approve` human mode auto-resumes inline** — background with `nohup`.
- **`node_completed` is not written for interactive loops** until the completion
  signal emits — don't use its absence as a failure signal.
- **Plannotator review does not pause the run** — detect via beacon + port 19432.
- **Plan approval is the user's gate** — relay only surfaces the URL, never
  clicks Approve/Deny.
- **`.archon-relay/` is gitignored** — beacons are scratch, never commit.
- **`android/local.properties` must never be committed** — it's machine-local.
- **The IPC contract is cross-repo** — a `counting-history.jsonl` schema change
  must be coordinated with `wloonis/animal-counter` and `IPC_CONTRACT.md` updated
  in both. See `docs/IPC_CONTRACT.md`.
- **The companion is stdlib-only** — do not add `pip install` dependencies; if
  you need a lib, vendor it or reimplement with stdlib.

---

## 10. Project-specific conventions

- **API contract with Android**: the companion HTTP API is internal to THIS
  repo (companion + Android move together). Document endpoint shapes in
  `docs/01_jetson_companion.md`. The Android app's `JetsonClient` is the client.
- **GitHub issues** use the `BL-<n> — <title>` naming convention. Always
  increment from the highest existing `BL-<n>` **across both repos** — the BL
  numbering is shared system-wide (check `gh issue list --state all` in BOTH
  repos; the animal-counter repo is the source of truth for the BL counter
  since it started there). Never use `P1`/`P2` prefixes.
- **`.env.local` is gitignored** — never commit its value; reference only key
  names (`JETSON_PASSWORD`, `GITHUB_TOKEN`) in tracked files.
- **Jetson**: Orin Nano 8GB "Super". Home WiFi static `192.168.0.180`; hotspot
  `192.168.100.1`. User `nano-counter`, password from `.env.local`
  (`JETSON_PASSWORD`). Companion port 8090. App path on Jetson
  `/data/orin/git/animal-counting/app` (sister repo); data `/data/orin/files/`,
  config/control `/data/orin/conf/` (BL-80).
- **No counting logic here** — never touch OC-SORT, crossing detection, guard
  params, tracker params. Those live in the sister repo. If a change seems to
  need counting logic, it belongs in `wloonis/animal-counter`, not here.