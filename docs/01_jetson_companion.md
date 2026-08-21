# 01 — Jetson companion service & API (BL-64 / BL-68)

A stdlib-only Python HTTP service (`jetson-companion`) running on the Jetson
**host** (not k3s) on port **8090**. It exposes the **companion API** consumed
by the Android app ([BL-65](https://github.com/wloonis/animal-counter/issues))
over the WiFi HotSpot:

- **v1 (BL-64)** — clock-sync: receives the current time + timezone from the
  phone and applies it via `timedatectl`, fixing the Jetson's lack of a
  real-time clock (RTC) at offline boot.
- **v2 (BL-68)** — read-only history/video: serves the persistent
  counting-session history and recorded videos from the hostPath `/files`
  (DATA — see [`03_counting_history.md`](03_counting_history.md) for the store
  internals — JSONL schema, compaction, disk guard).
- **v2 (BL-76/BL-71)** — config/control relay: writes `runtime-settings.json`
  (hot-reload) + `.arret_requested` (power-off sentinel) to the hostPath `/conf`
  (CONFIG/CONTROL — split from `/files` by BL-79 in the sister repo; the
  companion writes `/conf` as of BL-80).

This implements [GitHub issue BL-64](https://github.com/wloonis/animal-counter/issues)
and [BL-68](https://github.com/wloonis/animal-counter/issues).

## Why it exists — the Jetson has no RTC

> **Role with a hardware RTC (BL-74).** If you've installed a **DS3231 RTC
> module** ([`13_rtc_install.md`](13_rtc_install.md)), the system clock
> survives power cycles on its own — but the DS3231 can still drift or start
> uninitialized. The companion is then the **corrector**: the Android
> "Synchroniser l'heure" button POSTs the phone's time, the companion applies
> it to the system clock **and persists it into the DS3231** (`hwclock
> --systohc`), so the correction survives the next reboot. On a Jetson with no
> RTC at all, the companion is the only clock source (see below). Either way
> the companion stays installed.

The production Jetson has **no coin-cell battery**, so it has no real-time
clock. On every offline boot (no internet, no NTP) its system clock is stuck
at the **build date** (or `1970-01-01`). Everything that stamps a wall-clock
time is then wrong until the clock is manually set:

- the `tocompress-counting-*.mp4` video clips get mis-dated filenames and
  metadata,
- the journald logs are stamped with the bogus date,
- any file/output written during the session inherits the wrong timestamp.

When the Jetson is in **WiFi HotSpot mode**, the Android phone (BL-65) is the
only source of wall-clock time: it connects to the HotSpot and POSTs the
current time + timezone to this small HTTP service on the Jetson host, which
applies it via `timedatectl`. There is no internet, no NTP server, and no other
clock reference available — the phone is the clock.

## Endpoints

The service is a plain `http.server`-based daemon (Python stdlib only — no
Flask, no FastAPI, no `pip`, no `venv`). It binds `0.0.0.0:8090`. The API is
versioned (`GET /api/identify` returns `"version"`): **v1** (BL-64) is the
clock-sync surface; **v2** (BL-68) adds the read-only history/video surface
backed by the hostPath `/files` JSONL (DATA — see
[`03_counting_history.md`](03_counting_history.md)); **v2** (BL-76/BL-71) also
writes config/control files (`runtime-settings.json`, `.arret_requested`) to
the hostPath `/conf` (CONFIG/CONTROL — split from `/files` by BL-79; companion
aligned by BL-80).

### v1 — clock-sync (BL-64)

| Method | Path | Purpose |
|--------|------|---------|
| `GET` | `/api/identify` | Service discovery — returns the service name + version |
| `POST` | `/api/time` | Set the Jetson clock + timezone from the phone's time |

#### `GET /api/identify`

**Response** `200` (with the BL-88 mask-zone endpoints deployed):
```json
{"service":"jetson-companion","version":"8"}
```
The version bumps with each API surface addition: `"1"` (clock-sync only,
pre-BL-68), `"2"` (BL-68 read-only history/video), `"6"` (BL-76/BL-82 settings
relay + countable species), `"8"` (BL-88 camera snapshot + mask zones).

Any other path returns `404`:
```json
{"error":"not found"}
```

#### `POST /api/time`

**Request body:**
```json
{"time":"2025-07-15T14:30:00","tz":"Europe/Paris"}
```

- `time` — an ISO8601 timestamp (parsed with `datetime.fromisoformat`;
  unparseable values are rejected with `400`).
- `tz` — an IANA timezone name (validated against `timedatectl
  list-timezones`; unknown zones are rejected with `400`).

**On success** `200`:
```json
{"status":"ok","time":"2025-07-15T14:30:00","tz":"Europe/Paris"}
```

**On bad input** `400` (malformed JSON, unparseable time, unknown timezone):
```json
{"error":"invalid ISO8601 time: 'not-a-date'"}
```

**On `timedatectl` failure** `500` (captured stderr included):
```json
{"error":"'timedatectl set-time 2025-07-15T14:30:00' failed: <stderr>"}
```

Input is strictly validated and `timedatectl` is always invoked via
`subprocess.run([...], shell=False)` (argument-list form, never
`shell=True`), so the JSON body is **never** interpolated into a shell
command — no injection surface.

#### Durability — persists to the DS3231 (BL-74)

On a Jetson with a **DS3231 RTC** installed, `POST /api/time` does more than
`timedatectl set-time` + `set-timezone`: after the system clock is set, the
companion runs `hwclock --systohc --rtc=/dev/rtcN` to **persist the corrected
time into the DS3231**. It finds the DS3231 dynamically by driver name
(`ds1307` in `/sys/class/rtc/*/device/name`) → `/dev/rtcN` (N varies — the
Jetson has two onboard Tegra RTCs, so the DS3231 is typically `/dev/rtc2`).
This is **best-effort**: if no DS3231 is present, it is a silent no-op and the
time-set still succeeds. Without this step a manual sync would set only the
system clock and be lost on the next reboot (the drifted DS3231 would reset
it); with it, the next boot reads the corrected DS3231 and the clock is sane
without a phone. See [`13_rtc_install.md`](13_rtc_install.md).

### v2 — read-only history & video (BL-68)

The companion is bumped to **version `"2"`** (`GET /api/identify` returns
`"version":"2"`). These endpoints are **read-only** and never mutate the
JSONL (the pod is the sole writer). The reader uses a lazy `HistoryIndex`:
on the first history request it scans the JSONL once, builds an in-memory
`session_id → {offsets, summary}` map + a list of `startup` lines, caches it,
and invalidates the cache when `os.path.getsize` changes. Partial last lines
are tolerated.

| Method | Path | Query | Purpose |
|--------|------|-------|---------|
| `GET` | `/api/sessions` | `limit=50&offset=0` | Paginated session summaries (A + net count + last event ts), newest first |
| `GET` | `/api/sessions/<id>` | — | Full session detail (A–G): aggregate `session_start` + `heartbeat`s (last = `end_at` if no `session_end`) + `event`s + `session_end` |
| `GET` | `/api/summary` | `days=7` | Daily aggregates (count per day, sessions, guard events) |
| `GET` | `/api/startups` | `limit=50` | Startup history lines |
| `GET` | `/api/videos` | `limit=50&offset=0` | Paginated video summaries (one row per recorded video + running recording as synthetic first row), newest first |
| `GET` | `/api/video/<id>` | — (Range supported) | Range-streamed compressed `counting-<id>-*.mp4` (HTTP 200/206/416); 404 if absent or not yet compressed |

See the [curl examples](#curl-examples) below and
[`03_counting_history.md`](03_counting_history.md) for the JSONL line schema
(A–G) and the store internals.

### v3 — settings relay & countable species (BL-76, BL-82)

The companion is bumped to **version `"6"`** (then `"8"` with BL-88 — see
below). These endpoints bridge the Android app's Réglages tab to the
countingapp via the hostPath `/conf` (CONFIG/CONTROL — split from `/files` by
BL-79; companion aligned by BL-80). `GET/PUT /api/settings` read/write
`runtime-settings.json` (hot-reloaded by the countingapp at each recording
start); `GET /api/classes` exposes the read-only `model-classes.json` catalog
published by the countingapp at startup (BL-78) plus the current
`counting_class_ids` selection.

| Method | Path | Body | Purpose |
|--------|------|------|---------|
| `GET` | `/api/settings` | — | Current `runtime-settings.json` (empty `{}` when absent). As of BL-88 it also returns `mask_zones` (default `[]`) and `draw_mask_zones` (default `true`). |
| `PUT` | `/api/settings` | PATCH JSON | Merge the given keys into `runtime-settings.json` (atomic write); echoes the merged object. Recognised keys: `draw_tracking`, `box_tracking`, `centroid_tracking` (bool), `offset_counting_line` (signed int, loose `-300..300` — BL-84), `counting_line_orientation` (`"vertical"`\|`"horizontal"` — BL-84), `counting_class_ids` (`int[]`, subset of the model classes — BL-82), `mask_zones` (list of `{x,y,w,h}` normalized rects — BL-88), `draw_mask_zones` (bool — BL-88). Unknown keys ignored (forward-compat); 400 on a type/range violation. |
| `GET` | `/api/classes` | — | Countable species catalog + current selection (BL-82): `{model_version, nc, classes:[{id,name}], default_counting_class, counting_class_ids}`. `404` when the countingapp has not published `model-classes.json` yet (not started / write pending) — the app shows "catalog unavailable" and can retry. |

### v4 — camera snapshot & mask zones (BL-88)

The companion is bumped to **version `"8"`**. The Android app's « Zones de
masquage » section lets the operator draw rectangles over a camera preview
and persist them as `mask_zones` in `runtime-settings.json` (the countingapp
reader is a separate follow-up in the `animal-counter` repo). A new
read-only endpoint serves the preview JPEG.

| Method | Path | Body | Purpose |
|--------|------|------|---------|
| `GET` | `/api/snapshot` | — | Serves the countingapp's periodic camera preview JPEG (`/files/snapshot.jpg` on the hostPath) as `image/jpeg` with `Content-Length` + `Cache-Control: no-store`. `404` JSON when the countingapp has not written a snapshot yet (the app shows « Aperçu pas encore disponible » + retry). |

`mask_zones` is a list of axis-aligned normalized rects `{x,y,w,h} ∈ [0..1]`
(relative to the frame, resolution-independent). Validation is strict
reject-all (consistent with BL-84's offset/orientation rejection): any
invalid rect — `x`/`y`/`w`/`h` out of `[0..1]`, `w<=0`, `h<=0`, `x+w>1`,
`y+h>1`, a non-dict element, a missing field, a bool field value, or a
non-list top-level value — rejects the whole `PUT` with `400` + a logged
`WARN`; the existing `runtime-settings.json` is left unchanged (no silent
clamping). `draw_mask_zones` is a plain bool (same pattern as
`draw_tracking`) that toggles whether the countingapp overlays the saved
zones on the live frame.

`counting_class_ids` is hot-reloaded by the countingapp at the **next
recording start** (no restart); invalid ids (out of `0..nc-1`) are dropped
with a WARNING and the selection falls back to `[default_counting_class]`.
See [`IPC_CONTRACT.md`](IPC_CONTRACT.md) for the authoritative
`runtime-settings.json` / `model-classes.json` schemas.

## NTP note

The companion always runs `timedatectl set-ntp false` **before** `set-time`.
This is required: if `systemd-timesyncd` (NTP) is still active, it can reject
the manual `set-time` write or immediately overwrite it with its own
(nonsense, offline) value. Disabling NTP first ensures the manual time sticks.

**Production keeps NTP disabled** (the Jetson is offline; the NTP daemon is
useless there). The DS3231 is the boot source and the Android
"Synchroniser l'heure" button is the corrector (and now persists to the
DS3231 — see [`13_rtc_install.md`](13_rtc_install.md)). The only time NTP is
touched is the **install-time one-shot** (`init-ds3231-from-ntp.sh`, run by
`configure_rtc.yml`): on an **online** Jetson it syncs once, persists the
result into the DS3231 (`hwclock --systohc`), then disables NTP again; on an
**offline** Jetson it skips cleanly.

If you ever want to re-enable NTP (e.g. the Jetson later gets permanent
internet), run it manually on the host:

```bash
sudo timedatectl set-ntp true
```

## Why port 8090

Port **8080** is already bound by `filebrowser` on the Jetson (the
file-management web UI), so the companion service cannot use it. **8090** is
free. The port is configurable via the `COMPANION_PORT` environment variable
in the systemd unit — change it in the playbook's `companion_port` var (or
the unit's `Environment=` line) and redeploy if 8090 is ever taken.

## Deploy

The service is deployed by the Ansible playbook
`ansible/playbooks/system/configure_companion.yml`, which installs the
script (`/usr/local/bin/jetson-companion`, mode `0755`) and the systemd unit
(`/etc/systemd/system/jetson-companion.service`), then enables + starts it. A
`notify` handler restarts the service whenever the script or unit content
changes, so the running instance picks up new code and the second playbook
run is idempotent (`changed=0` on the copy tasks).

The playbook runs on `hosts: all` with `become: true` (root, because
`timedatectl set-time` requires root). It is safe to re-run — the second run
reports `changed=0` for the copy tasks (the handler only fires on content
change).

### Offline, over the Jetson hotspot (preferred)

The companion is **stdlib-only Python** (http.server, json, subprocess,
datetime — no apt/pip/docker-pull), so it is the only system playbook that can
be deployed with **no internet** — exactly the situation once the Jetson is in
WiFi HotSpot mode (isolated LAN, no uplink). Use the standalone wrapper, which
mirrors `scripts/load_image.sh`'s offline pattern: it derives the target IP
from `JETSON_HOTSPOT_IP` (CIDR stripped) and pauses for a manual checkpoint
(the script cannot switch the Jetson to hotspot itself).

```bash
./scripts/install_companion_standalone.sh
```

Prereq (manual): switch the Jetson to **HotSpot mode** and connect this PC to
that hotspot. Required `.env.local` vars: `JETSON_HOTSPOT_IP` (e.g.
`192.168.100.1/24`), `JETSON_PASSWORD`, `JETSON_USER`. The wrapper sources
`.env.local`, exports `JETSON_IP` (CIDR stripped) for the env-based inventory,
checks SSH reachability, then runs the playbook.

### Raw ansible (if `JETSON_IP` is already exported)

If you just ran `prepare_jetson.sh` on the WiFi-internet network (which exports
`JETSON_IP` via `jetson_discover.sh`), you can run the playbook directly:

```bash
set -a; source .env.local; set +a
ansible-playbook -i ansible/inventory/jetsons.yml \
                 ansible/playbooks/system/configure_companion.yml
```

## curl examples

Assuming the Jetson is reachable at `192.168.0.180` (its IP on the HotSpot or
the local WiFi):

**Identify the service:**
```bash
curl http://192.168.0.180:8090/api/identify
# {"service":"jetson-companion","version":"8"}
```

**Set the clock from the PC's current time** (use `date -Iseconds` so the
Jetson gets the real current time, not a bogus test value):
```bash
curl -X POST http://192.168.0.180:8090/api/time \
  -H 'Content-Type: application/json' \
  -d "{\"time\":\"$(date -Iseconds)\",\"tz\":\"Europe/Paris\"}"
# {"status":"ok","time":"2025-07-15T14:30:00+02:00","tz":"Europe/Paris"}
```

**Negative test — bad ISO8601 (expect 400):**
```bash
curl -s -o /dev/null -w "%{http_code}\n" \
  -X POST http://192.168.0.180:8090/api/time \
  -H 'Content-Type: application/json' \
  -d '{"time":"not-a-date","tz":"Europe/Paris"}'
# 400
```

**Negative test — unknown timezone (expect 400):**
```bash
curl -s -o /dev/null -w "%{http_code}\n" \
  -X POST http://192.168.0.180:8090/api/time \
  -H 'Content-Type: application/json' \
  -d '{"time":"2025-07-15T14:30:00","tz":"Mars/Olympus"}'
# 400
```

After a successful `POST /api/time`, verify on the Jetson that the clock
reflects the change — and (with a DS3231) that the correction persisted into
the RTC:

```bash
ssh nano-counter@192.168.0.180 'timedatectl'                       # system clock
ssh nano-counter@192.168.0.180 'sudo hwclock -r --rtc=/dev/rtc2'    # DS3231 (BL-74)
```

And confirm the service is enabled + active:

```bash
ssh nano-counter@192.168.0.180 'systemctl is-enabled jetson-companion'
# enabled
ssh nano-counter@192.168.0.180 'systemctl is-active jetson-companion'
# active
```

**v2 — history & video (BL-68):**

**List recent sessions:**
```bash
curl 'http://192.168.0.180:8090/api/sessions?limit=10'
# {"sessions":[...],"limit":10,"offset":0,"total":N}
```

**Paginate (next page):**
```bash
curl 'http://192.168.0.180:8090/api/sessions?limit=10&offset=10'
```

**Get full detail for one session:**
```bash
curl http://192.168.0.180:8090/api/sessions/<session_id>
# {"session_id":"...","start_at":"...","end_at":"...","counters":{...},"events":[...],...}
```

**Daily summary (last 7 days):**
```bash
curl 'http://192.168.0.180:8090/api/summary?days=7'
# {"days":7,"daily":[{"date":"2025-07-15","count":9,"sessions":1,"guard_events":0},...]}
```

**List recent videos (running recording is the synthetic first row):**
```bash
curl 'http://192.168.0.180:8090/api/videos?limit=10'
# {"videos":[{"video_id":"counting-20250608-100000","filename":"counting-20250608-100000-#9.mp4","duration":120,"count_delta":9,"session_id":"...","ts":"...","status":"ready"},...],"limit":10,"offset":0,"total":N}
```

**Range-stream a video (resumable/partial download):**
```bash
curl -H 'Range: bytes=0-1023' \
  http://192.168.0.180:8090/api/video/counting-20250608-100000 -o /tmp/head.mp4
# HTTP 206, Content-Range: bytes 0-1023/<size>
curl http://192.168.0.180:8090/api/video/counting-20250608-100000 -o file.mp4
# HTTP 200, full file (playable on Android)
```

**Startup history:**
```bash
curl 'http://192.168.0.180:8090/api/startups?limit=50'
# {"startups":[{"boot_at":"...","image_tag":"...","git_commit":"...","mode":"serve",...}]}
```

**Negative test — unknown session (expect 404):**
```bash
curl -s -o /dev/null -w "%{http_code}\n" \
  http://192.168.0.180:8090/api/sessions/does-not-exist
# 404
```

**v3 — settings relay & countable species (BL-76, BL-82):**

**Read the live runtime settings:**
```bash
curl http://192.168.0.180:8090/api/settings
# {"box_tracking":true,"centroid_tracking":false,"draw_tracking":true,"offset_counting_line":0,"counting_line_orientation":"vertical","counting_class_ids":[1]}
```

**List the countable species + current selection:**
```bash
curl http://192.168.0.180:8090/api/classes
# {"model_version":"v1","nc":2,"classes":[{"id":0,"name":"human"},{"id":1,"name":"pig"}],"default_counting_class":1,"counting_class_ids":[1]}
# 404 when the counting app has not published model-classes.json yet.
```

**Select which species to count (PATCH, hot-reloaded at next recording):**
```bash
curl -X PUT http://192.168.0.180:8090/api/settings \
  -H 'Content-Type: application/json' \
  -d '{"counting_class_ids":[0,1]}'
# 200 — echoes the merged runtime-settings.json
```

**Set the counting-line orientation + signed offset (BL-84, hot-reloaded at next recording):**
```bash
curl -X PUT http://192.168.0.180:8090/api/settings \
  -H 'Content-Type: application/json' \
  -d '{"counting_line_orientation":"horizontal","offset_counting_line":-10}'
# 200 — echoes the merged runtime-settings.json (offset signed, 0 = centered)
```

**Negative test — bad orientation (expect 400):**
```bash
curl -s -o /dev/null -w "%{http_code}\n" \
  -X PUT http://192.168.0.180:8090/api/settings \
  -H 'Content-Type: application/json' \
  -d '{"counting_line_orientation":"diagonal"}'
# 400
```

**Negative test — out-of-range class id (expect 400):**
```bash
curl -s -o /dev/null -w "%{http_code}\n" \
  -X PUT http://192.168.0.180:8090/api/settings \
  -H 'Content-Type: application/json' \
  -d '{"counting_class_ids":[0,1,9]}'
# 400
```

**v4 — camera snapshot & mask zones (BL-88):**

**Fetch the camera preview JPEG (written by the countingapp to
`/files/snapshot.jpg`):**
```bash
curl -D - http://192.168.0.180:8090/api/snapshot -o /tmp/snapshot.jpg
# HTTP/1.0 200 OK
# Content-Type: image/jpeg
# Content-Length: <bytes>
# Cache-Control: no-store
```
`404` when the countingapp has not written a snapshot yet (the writer is a
separate `animal-counter` follow-up):
```bash
curl -s -o /dev/null -w "%{http_code}\n" http://192.168.0.180:8090/api/snapshot
# 404
```

**Read the live runtime settings (BL-88 adds `mask_zones` + `draw_mask_zones`):**
```bash
curl http://192.168.0.180:8090/api/settings
# {"box_tracking":true,"centroid_tracking":false,"draw_tracking":true,
#  "offset_counting_line":0,"counting_line_orientation":"vertical",
#  "counting_class_ids":[1],"mask_zones":[],"draw_mask_zones":true}
```

**Save mask zones (normalized `{x,y,w,h} ∈ [0..1]` rects, hot-reloaded at
the next recording):**
```bash
curl -X PUT http://192.168.0.180:8090/api/settings \
  -H 'Content-Type: application/json' \
  -d '{"mask_zones":[{"x":0.8,"y":0,"w":0.2,"h":1}],"draw_mask_zones":true}'
# 200 — echoes the merged runtime-settings.json (mask_zones persisted)
```

**Negative test — invalid rect `w<=0` (expect 400, file unchanged):**
```bash
curl -s -o /dev/null -w "%{http_code}\n" \
  -X PUT http://192.168.0.180:8090/api/settings \
  -H 'Content-Type: application/json' \
  -d '{"mask_zones":[{"x":0.1,"y":0.1,"w":0,"h":0.5}]}'
# 400
```

**Negative test — `x+w>1` (expect 400):**
```bash
curl -s -o /dev/null -w "%{http_code}\n" \
  -X PUT http://192.168.0.180:8090/api/settings \
  -H 'Content-Type: application/json' \
  -d '{"mask_zones":[{"x":0.8,"y":0,"w":0.3,"h":1}]}'
# 400
```

**Negative test — non-bool `draw_mask_zones` (expect 400):**
```bash
curl -s -o /dev/null -w "%{http_code}\n" \
  -X PUT http://192.168.0.180:8090/api/settings \
  -H 'Content-Type: application/json' \
  -d '{"draw_mask_zones":"yes"}'
# 400
```

## Security note

The service is open (no auth/token). It is reachable only on the HotSpot LAN —
a closed offline network where the only peer is the Android phone. Auth is
explicitly out of scope for v1/v2 and will be added in a future backlog item.

## Related

- **BL-65** — the Android app that connects to the Jetson HotSpot and pushes
  the current time to `/api/time` (the phone is the clock source), and reads
  the v2 history/video endpoints.
- **BL-68/71** — the read-only history/video endpoints (v2) served here; the
  backing JSONL store is documented in
  [`03_counting_history.md`](03_counting_history.md).
- **BL-66** — `/api/count`, the future live-counting endpoint on the same
  companion service (not yet implemented).