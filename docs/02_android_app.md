# 02 — Android companion app (BL-65/69/72/73/74)

The Android app that talks to the **Jetson companion service**
([`01_jetson_companion.md`](./01_jetson_companion.md)) over HTTP. It selects
the Jetson IP by **probing** (`GET /api/identify`), shows a reachability
banner, browses the Jetson-side counting history/videos, and — when the
Jetson has no correct clock — lets the user push the phone's current time +
timezone on demand with the **"Synchroniser l'heure"** button
(`POST http://<jetson_ip>:8090/api/time`).

**Architecture:** phone = client, Jetson = server. The connection management
is **app-lifecycle-scoped** (BL-73): it starts when the app is foregrounded
(`ON_START`) and stops when it goes to the background (`ON_STOP`) — it **never
runs in the background**, there is **no foreground service, no boot receiver,
no keep-alive loop**. Time sync is **manual** (BL-74): a button in Settings,
not an automatic push.

---

## Features

- **Probe-based Jetson IP selection** (BL-73) — on app foreground, the app
  probes `GET /api/identify` on the candidate Jetson IPs (the configured IP
  and, in hotspot mode, the fixed `192.168.100.1`) and picks the one that
  responds with `{"service":"jetson-companion",...}`. **No SSID sniffing, no
  `ACCESS_FINE_LOCATION`** — it trusts the service-name response, not the
  WiFi SSID.
- **« Jetson hors de portée » banner** — while the app is open, an
  `OfflineBanner` reflects reachability: « Jetson connecté » (green) /
  « Jetson hors de portée » (amber). Foreground only (it stops when the app
  is backgrounded).
- **Manual "Synchroniser l'heure"** (BL-74) — a button in the **Settings**
  tab fires `POST /api/time` on demand. On success it shows a green
  "Heure synchronisée ✓" that auto-clears after ~5s; on failure a red
  "Échec de la synchronisation" persists until the next action. The
  companion **persists the correction into the DS3231** (`hwclock --systohc`)
  so it survives reboots — see [`13_rtc_install.md`](./13_rtc_install.md).
  This replaces the old automatic ~30s keep-alive time-push loop (removed).
- **Multi-tab hub** — **Dashboard** (BL-73, aggregate counts + a "Sessions"
  deep-link), **Live count** (BL-69), **History** (BL-72, videos list +
  download/open), **Startups** (boot history), **Settings** (Jetson IP +
  time sync). Built incrementally; see
  [`03_counting_history.md`](./03_counting_history.md) for the Jetson-side
  history that backs these screens.
- **Configurable Jetson IP** — persisted via Jetpack DataStore Preferences
  (default `192.168.100.1`, the hotspot gateway).
- **Multilingual (FR/EN)** — follows the phone's default system locale.
  `res/values/strings.xml` (English fallback) + `res/values-fr/strings.xml`
  (French). Structure ready for more locales via `values-<lang>/`.
- **Material 3** dynamic color (Material You) + dark theme forced. Custom
  launcher icon (`animal_counter_v2.png` raster foreground at 72% in the safe
  zone, teal `#FF00897B` background; PR #80/#84). App name « Animal Counter ».
- **Mask zones editor (BL-88)** — in the **Settings** tab, « Zones de
  masquage » lets you capture the live camera snapshot, then **draw / move /
  resize / name** exclusion zones directly on the preview (see
  [Mask zones editor](#mask-zones-editor-bl-88) below). Saved via
  `PUT /api/settings {mask_zones, draw_mask_zones}`.
- **Sens de comptage (BL-92)** — in the **Settings** tab, the « Sens de
  comptage » section holds an **Auto/Manual** toggle for
  `counting_direction_mode`, and when Manual, an **Up/Down/Left/Right**
  selector for `counting_direction` gated by the active
  `counting_line_orientation` (Up/Down when the line is horizontal, Left/Right
  when vertical). A red warning notes that a direction change resets the
  counter. Saved via the coalesced `PUT /api/settings` PATCH.
- **Network-change resilience (PR #22)** — when the phone leaves a WiFi
  network the cached Jetson IP is cleared so the next call re-probes, and
  `PUT`/`GET` retry once on a network error. Fixes the mask-zones save
  silently failing when switching from the home WiFi (192.168.0.180) to the
  Jetson hotspot (192.168.100.1) — the cached home IP stayed stale.

---

## Mask zones editor (BL-88)

The **Settings → Zones de masquage** section is a visual editor for the
`mask_zones` exclusion rects (see [`IPC_CONTRACT.md`](./IPC_CONTRACT.md)). It
lets you define **where the countingapp should NOT count** (e.g. a door, a
feeder, a corridor) — detections whose centroid falls inside a zone are
dropped before tracking (no track → no count).

### Workflow

1. **Capturer l'aperçu** — fetches the live camera snapshot (`GET
   /api/snapshot`, served by the companion from `/files/snapshot.jpg`, written
   by the countingapp every ~5s). The preview fills its box at the frame's
   aspect ratio (no letterboxing), so drag coordinates map directly to
   normalized `[0..1]`.
2. **Draw a new zone** — drag on an **empty area** of the preview → creates a
   normalized rect `{x,y,w,h}` (clamped to `[0..1]`), auto-named `Zone N`.
3. **Move a zone** — drag **inside** an existing zone → translates it (clamped
   so the whole rect stays in the frame).
4. **Resize a zone** — drag an **edge** (top/bottom/left/right) or a
   **corner** → stretches it; the opposite edge stays anchored, with a 2%
   minimum size (anti-collapse) + frame bounds. 4 corner handles are drawn as a
   visual affordance.
5. **Name a zone** — each zone has an editable name field
   (`OutlinedTextField`) in the list; the name is drawn as a label on the rect
   in the preview (the name, or `Zone N` fallback). The name is **app-local**:
   stored in `/conf` + returned on `GET /api/settings`; the countingapp
   ignores it (reads only `x/y/w/h`).
6. **Enregistrer** — `PUT /api/settings {mask_zones, draw_mask_zones}`.
   Strict reject-all on any invalid rect (companion returns `400` with a
   human-readable message; the whole array is rejected, no silent clamping).
7. **Afficher les zones à l'écran** — `draw_mask_zones` toggle: the countingapp
   draws the zones as a semi-transparent overlay (independent of
   `draw_tracking`).

The hit-test priority is **resize handle (edge/corner) → move (inside) →
create (empty)**. Edges are grabbable within a 28px threshold, and only when
the zone is larger than 2× the threshold in that dimension (anti-ambiguity for
tiny zones); corners win over single edges.

The countingapp **hot-reloads** `mask_zones` (BL-86 idle-gating): the watcher
stores the pending value and applies it at the next idle window — **no pod
restart**. A `mask_zones` change does **not** reset the running counter (it
changes *where* we count, not *what*).

---

## Prerequisites

1. **Jetson companion service running** (BL-64) on port `8090`
   (`/api/identify` + `/api/time` + the read-only history/video API). See
   [01_jetson_companion.md](./01_jetson_companion.md).
2. **The Jetson reachable from the phone** — either:
   - **WiFi HotSpot mode** — SSID + password from `.env.local`
     (`JETSON_HOTSPOT_SSID` / `JETSON_HOTSPOT_PASSWORD`), gateway IP
     `192.168.100.1` (activated via `ansible/playbooks/system/hotspot_setup.yml`),
     or
   - **LAN mode** — the Jetson on the same WiFi as the phone (static
     `192.168.0.180`), reachable at that IP.
3. **The phone** — Android 13+ (minSdk 33), e.g. Google Pixel 9 (ships with
   Android 14, upgradable to 15).

---

## Jetson companion — the bridge (install on the Jetson)

The Android app talks to the **Jetson companion service** (BL-64): a small
stdlib-only Python HTTP server running on the Jetson **host** (not k3s) on
port **8090**, exposing `GET /api/identify` (reachability probe — the app
validates `service == "jetson-companion"` by exact name, **no version check**,
BL-73), `POST /api/time` (set the clock + persist to DS3231, BL-74), and the
read-only history/video API (BL-68). **Without it, the app has nothing to
talk to** — the « Jetson hors de portée » banner stays amber and the sync
button fails. It must be installed on the Jetson **before** the app is usable.

The companion is the only system playbook that deploys **offline, over the
Jetson's WiFi hotspot** (no internet needed — it's stdlib Python, no
apt/pip/docker-pull), which is exactly the situation once the Jetson is in
HotSpot mode (the same network the app will join). It also deploys fine over
the LAN.

### Prerequisites (`.env.local`)

Make sure these are set in `.env.local` (gitignored — never committed):

```ini
JETSON_HOTSPOT_IP=192.168.100.1/24   # Jetson hotspot IP with CIDR
JETSON_PASSWORD=********             # sudo/SSH password on the Jetson
JETSON_USER=nano-counter             # SSH user (default nano-counter)
JETSON_HOTSPOT_SSID=********         # hotspot SSID (for the phone to join)
JETSON_HOTSPOT_PASSWORD=********     # hotspot password
# LAN-mode discovery (optional, used if the Jetson isn't on the hotspot):
WIFI_NETWORK=192.168.0.0/24          # nmap scan range for jetson_discover.sh
JETSON_IP=192.168.0.180              # (optional) explicit LAN IP, skips discovery
```

### Steps

1. **Switch the Jetson to WiFi HotSpot mode** (if not already):
   ```bash
   set -a; source .env.local; set +a
   ansible-playbook -i ansible/inventory/jetsons.yml \
     ansible/playbooks/system/hotspot_setup.yml
   ```
   The Jetson reboots and comes up as an access point on `192.168.100.1`.
   (Requires internet once for the apt packages; see
   [03_deployment.md](./03_deployment.md).) Skip this if you run the Jetson
   on the LAN instead.

2. **Connect this PC to the Jetson hotspot** (join the SSID from
   `JETSON_HOTSPOT_SSID`) — or, in LAN mode, just be on the same network.
   The standalone deploy runs over this network.

3. **Deploy the companion (offline standalone, hotspot or LAN):**
   ```bash
   ./scripts/install_companion_standalone.sh
   ```
   The wrapper sources `.env.local`, resolves the target IP (hotspot SSH
   probe → `jetson_discover.sh` on `WIFI_NETWORK` → `JETSON_HOTSPOT_IP`
   fallback, mirroring `install_rtc_standalone.sh`), checks SSH reachability,
   pauses for a manual checkpoint, then runs
   `ansible/playbooks/system/configure_companion.yml`. This installs
   `/usr/local/bin/jetson-companion` (mode `0755`) + the systemd unit
   `/etc/systemd/system/jetson-companion.service`, enables + starts it
   (`User=root`, needed for `timedatectl set-time` + `hwclock --systohc`).
   Idempotent — safe to re-run. Flags: `--check` (dry-run), `--tags <t>`.

4. **Verify the bridge is up:**
   ```bash
   # reachability probe (from this PC, on the hotspot or LAN)
   curl http://192.168.100.1:8090/api/identify   # hotspot
   # or: curl http://192.168.0.180:8090/api/identify   # LAN
   # expected: {"service":"jetson-companion","version":"4"}

   # service status on the Jetson
   ssh nano-counter@192.168.100.1 'systemctl is-active jetson-companion'   # active
   ssh nano-counter@192.168.100.1 'systemctl is-enabled jetson-companion'  # enabled
   ```

5. **Test a manual time push** (before using the app):
   ```bash
   curl -X POST http://192.168.100.1:8090/api/time \
     -H 'Content-Type: application/json' \
     -d '{"time":"2025-07-15T14:30:00+02:00","tz":"Europe/Paris"}'
   # expected: {"status":"ok",...}
   ssh nano-counter@192.168.100.1 'timedatectl | grep "Local time"'
   # with a DS3231 installed, the correction also persisted to the RTC:
   ssh nano-counter@192.168.100.1 'sudo hwclock -r --rtc=/dev/rtc2'
   ```

Once `/api/identify` returns the JSON above, the Android app's « Jetson
connecté » banner goes green and the sync button works. Full companion
reference (endpoints, durability, NTP note, why port 8090, raw-ansible
deploy, curl examples): [01_jetson_companion.md](./01_jetson_companion.md).

---

## Build the APK

The build environment is already installed on the dev WSL host
(see [07_development_workflow.md](./07_development_workflow.md) for the
toolchain — JDK 17, Android SDK, Gradle).

```bash
export JAVA_HOME="$HOME/jdk-17"
export ANDROID_HOME="$HOME/Android/Sdk"

cd android
./gradlew :app:assembleDebug --no-daemon --console=plain
```

Output:

```
android/app/build/outputs/apk/debug/app-debug.apk   (~58 MB)
```

> The debug APK is signed with the debug key (good for testing, not for the Play
> Store). No Jetson/video round-trip is required — the only validation is a
> successful Gradle build.

---

## Install the app on the phone

The phone install is a **debug install** (no Play Store). Two methods: **ADB
over USB** (recommended) or **sideload the APK**.

### Method A — ADB over USB (recommended)

This requires a host with a **physical USB connection** to the phone. On this
setup, run it from **Windows** (the dev environment is WSL, which has no USB
access).

#### 1. Enable Developer options on the Pixel 9 (stock Android)

1. On the phone, open **Settings → About phone**.
2. Find **Build number** and tap it **7 times** rapidly.
3. You'll see « Developer mode has been enabled » (you may need to enter your
   PIN/pattern).

#### 2. Enable USB debugging

1. Go to **Settings → System → Developer options** (now visible).
2. Toggle **USB debugging** ON.
3. (Optional) toggle **Wireless debugging** OFF for now — USB is simpler.

> **No battery-tuning needed.** BL-73 removed the foreground service + boot
> receiver, so the app no longer needs the "Unrestricted" battery setting that
> a background service required. Standard battery settings are fine.

#### 3. Install ADB on Windows

Download **Platform Tools** from Google and unzip:

```
https://developer.android.com/tools/releases/platform-tools
→ download "SDK Platform-Tools for Windows"
→ unzip to e.g. C:\platform-tools
```

Open **PowerShell** (or cmd) in the unzipped folder, or add it to `PATH`.

#### 4. Connect + authorize

1. Plug the phone into the PC with a **USB cable** (data cable, not charge-only).
2. On the phone, select the USB mode **Transferring files / Android Auto** (not
   « charge only »).
3. A dialog « Allow USB debugging? » appears — tick **Always allow from this
   computer** and tap **Allow / OK**.
4. Verify the connection:

```powershell
adb devices
# should list a device, e.g.:
# List of devices attached
# R58Mxxxxxxx    device
```

If you see `unauthorized`, re-plug and accept the prompt. If you see nothing,
check the cable and the USB mode.

#### 5. Copy the APK to Windows + install

Copy the built APK from WSL to Windows (the Windows filesystem is mounted in
WSL under `/mnt/c/`):

```bash
# from WSL
cp android/app/build/outputs/apk/debug/app-debug.apk /mnt/c/Users/<you>/Desktop/
```

Then install from Windows PowerShell:

```powershell
adb install "%USERPROFILE%\Desktop\app-debug.apk"
```

Expected:

```
Success
```

> `adb install` reinstalls the app if already present. Use `adb install -r` to
> reinstall while keeping data. If you get `INSTALL_FAILED_UPDATE_INCOMPATIBLE`,
> uninstall first: `adb uninstall com.animalcounter`.

#### 6. Launch

1. Open **Animal Counter** from the app drawer.
2. The app opens on the **Dashboard** tab. It probes the Jetson on foreground
   and shows the reachability banner. (No notification permission prompt —
   the app no longer uses notifications, BL-73.)

### Method B — Sideload the APK (no PC)

If you can't use ADB:

1. Copy `app-debug.apk` to the phone (Bluetooth, email, USB file transfer,
   `adb install app-debug.apk` from a PC with platform-tools, etc.).
2. On the phone, open the APK with **My Files** (or any file manager).
3. If prompted, enable **Install unknown apps** for the file manager
   (**Settings → Apps → [file manager] → Install unknown apps → Allow**).
4. Tap **Install** → **Open**.

---

## Configuration & usage

1. **Jetson IP** — open the **Settings** tab; the OutlinedTextField defaults to
   `192.168.100.1` (the Jetson hotspot gateway). Change it to the LAN IP
   (`192.168.0.180`) if you run the Jetson on the LAN. The value is persisted
   across app restarts.
2. **Out-of-range banner** — with the app open (foreground), the
   `OfflineBanner` reflects the probe: green « Jetson connecté » =
   reachable; amber « Jetson hors de portée » = timeout / wrong IP / Jetson
   off. It updates automatically from the `NetworkCallback`; reopening the
   app re-probes.
3. **Synchroniser l'heure** — in the **Settings** tab, the button fires
   `POST /api/time` on demand with the phone's current time + timezone. On
   success: green "Heure synchronisée ✓" (auto-clears ~5s); on failure: red
   "Échec de la synchronisation" (persists until you retry). With a DS3231
   installed, the correction also persists into the RTC (durable — see
   [`13_rtc_install.md`](./13_rtc_install.md)).
4. **No background push** — there is no automatic time push and no background
   service (BL-73/74). The clock is corrected only when you tap the button
   while the app is open.
5. **Verify on the Jetson** — after a sync, check the Jetson clock (and the
   DS3231):
   ```bash
   ssh nano-counter@192.168.100.1 'timedatectl | grep "Local time"'
   ssh nano-counter@192.168.100.1 'sudo hwclock -r --rtc=/dev/rtc2'   # DS3231
   ssh nano-counter@192.168.100.1 'journalctl -u jetson-companion -n 20 --no-pager'
   ```

---

## Permissions

| Permission | Why |
|---|---|
| `INTERNET` | HTTP calls to the Jetson companion (`/api/identify`, `/api/time`, history/video). |
| `ACCESS_NETWORK_STATE` | `ConnectivityManager.registerNetworkCallback` — drive the reachability banner + IP selection while the app is open. |

**That's all.** No `FOREGROUND_SERVICE`, no `FOREGROUND_SERVICE_DATA_SYNC`, no
`RECEIVE_BOOT_COMPLETED`, no `POST_NOTIFICATIONS` (all removed in BL-73 — the
app no longer runs a background service, no longer starts at boot, no longer
posts notifications). **No location permission** — the app probes
`/api/identify` and validates the service name rather than sniffing the SSID,
so it needs no `ACCESS_FINE_LOCATION`.

---

## Troubleshooting

- **« Jetson hors de portée » permanently** — check the phone is on the same
  network as the Jetson (the hotspot, or the LAN), the IP in Settings is
  correct, and the companion is running
  (`curl http://192.168.100.1:8090/api/identify` from a browser — should
  return `{"service":"jetson-companion",...}`). The app validates
  `service == "jetson-companion"` exactly (no version check, BL-73).
- **Sync button fails ("Échec de la synchronisation")** — the app could not
  reach a Jetson with the right service name, or `POST /api/time` returned
  non-2xx. Check the banner is green first, then
  `journalctl -u jetson-companion` on the Jetson (e.g. NTP still active, or
  the service not running as root — it needs root for `timedatectl set-time`
  + `hwclock --systohc`).
- **Time not persisted across reboot** — you need a DS3231 RTC installed
  ([`13_rtc_install.md`](./13_rtc_install.md)) for durability. Without it,
  `POST /api/time` sets only the system clock (lost on reboot → fake-hwclock
  / companion only). With it, the companion persists to the DS3231 and the
  next boot reads it.
- **Build fails on WSL** — ensure `JAVA_HOME=$HOME/jdk-17` and
  `ANDROID_HOME=$HOME/Android/Sdk` are exported; `java -version` should
  report 17. The first Gradle build downloads the Gradle distribution (internet
  required once).
- **`adb devices` empty on Windows** — on a Pixel you usually don't need a
  vendor driver (Google's ADB driver is bundled with Platform Tools). Use a
  data cable, select « Transferring files » USB mode, and re-accept the USB
  debugging prompt. If Windows doesn't recognize the device at all, install
  the Google USB driver from the Android SDK Extras.

---

## Source layout

```
android/
  app/src/main/
    AndroidManifest.xml              (only INTERNET + ACCESS_NETWORK_STATE)
    java/com/animalcounter/
      MainActivity.kt
      data/OfflineCache.kt, SettingsRepository.kt, SyncEvent.kt, SyncLog.kt
      net/JetsonClient.kt, JetsonConnectionManager.kt, Models.kt, ProbeState.kt
      ui/common/OfflineBanner.kt
      ui/dashboard/DashboardScreen.kt, DashboardViewModel.kt
      ui/history/HistoryScreen.kt, HistoryViewModel.kt
      ui/livecount/LiveCountScreen.kt, LiveCountViewModel.kt
      ui/nav/AnimalCounterApp.kt        (5 tabs + NavHost, app-lifecycle observer)
      ui/sessiondetail/SessionDetailScreen.kt, SessionDetailViewModel.kt,
                       VideoDetailScreen.kt, VideoDetailViewModel.kt
      ui/sessions/SessionsScreen.kt, SessionsViewModel.kt   (deep-link from Dashboard)
      ui/startups/StartupsScreen.kt, StartupsViewModel.kt
      ui/settings/SettingsScreen.kt, SettingsViewModel.kt (sync button + syncResult)
      ui/theme/Color.kt, Theme.kt, Type.kt
    res/
      values/strings.xml          (English fallback)
      values-fr/strings.xml        (Français)
      values/themes.xml, colors.xml
      mipmap-anydpi-v26/ic_launcher.xml (adaptive: @drawable/ic_launcher_foreground + @color/ic_launcher_background)
      drawable-{mdpi…xxxhdpi}/ic_launcher_foreground.png  (raster foreground, 108dp; PR #80/#84)
      mipmap-{mdpi…xxxhdpi}/ic_launcher.png + ic_launcher_round.png  (legacy pre-v26)
  build.gradle.kts, settings.gradle.kts, gradle/wrapper/...
```

> **Note on `R.mipmap.ic_launcher*` in Compose (BL-75).** Do **not** use
> `painterResource(R.mipmap.ic_launcher*)` — on API 26+ the mipmap resolves to
> the `<adaptive-icon>` XML, which is not a raster/VectorDrawable, and Compose
> throws `IllegalArgumentException: Only VectorDrawables and rasterized asset
> types are supported`. Use `R.drawable.ic_launcher_foreground` (the raster
> PNG) instead.