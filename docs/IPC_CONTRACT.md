# IPC Contract — companion ⇄ countingapp

This document is the **authoritative contract** between the two repos that make
up the animal-counter system. It MUST stay identical in both:

- **`wloonis/animal-counter`** — the counting core (OC-SORT tracking + counting,
  TensorRT, K3s `countingapp` pod). Owns the **writer** side of the history log.
- **`wloonis/animal-counter-companion`** — the Android app + the Jetson host
  companion (HTTP bridge). Owns the **reader** side of the history log and the
  **writer** side of the runtime settings / power sentinel.

The companion (host, systemd) and the countingapp (k3s pod, hostPaths `/files`
and `/conf`) communicate **only** via files in two shared directories on the
Jetson. There is **no HTTP or RPC** between them. Any change to a format below
is a coordinated change across both repos.

> **BL-79 split (this repo):** config/control files (`runtime-settings.json`,
> `.arret_requested`) moved from `/files` to a dedicated `/conf` hostPath to
> separate them from data files (`counting-history.jsonl`, mp4 clips, dataset)
> which stay in `/files`. The companion (sister repo
> `wloonis/animal-counter-companion`) must be updated in a **separate BL** to
> write to `/data/orin/conf` instead of `/data/orin/files`. This document
> describes the **target contract** (post-split); the companion update is
> coordinated but out of scope for BL-79 in this repo.

## Shared paths (BL-79: two hostPaths)

### `/files` — data

| | Path | Owner |
|---|------|-------|
| Host (companion) | `/data/orin/files/` | created by the countingapp deploy |
| Pod (countingapp) | `/files/` (hostPath mount of the above) | k3s manifest |

Contains: `counting-history.jsonl`, `counting-*.mp4` clips, `dataset/`, `snapshot.jpg` (BL-88).

### `/conf` — config/control

| | Path | Owner |
|---|------|-------|
| Host (companion) | `/data/orin/conf/` | created by the countingapp deploy |
| Pod (countingapp) | `/conf/` (hostPath mount of the above) | k3s manifest |

Contains: `runtime-settings.json`, `.arret_requested`, `model-classes.json` (BL-78).

## Files

### `/files` (data)

#### 1. `counting-history.jsonl` — append-only event log

The countingapp **appends** one JSON object per line. The companion **reads**
it (read-only, builds an in-memory index, re-indexes on mtime change).

Schema (one JSON object per line):

| Field | Type | Present in | Meaning |
|-------|------|-------------|---------|
| `type` | string | all | `event` \| `heartbeat` \| `session_end` |
| `session_id` | string | all | UUID of the recording session |
| `video_id` | string\|null | `event` | id of the clip recorded on a detection |
| `count` | int | `heartbeat` | net bidirectional counter at heartbeat time |
| `count_delta` | int | `event` | +1 (right→left) / −1 (left→right) for a **vertical** line; +1 (down→up) / −1 (up→down) for a **horizontal** line. +1 is always the crossing-axis position **decreasing** past the line (BL-83) |
| `direction` | string | `event` (`crossed`/`id_switch_recovery`) | **(BL-83, additive)** the crossing direction of the event: `LEFT`/`RIGHT` for a vertical line, `UP`/`DOWN` for a horizontal line. `LEFT`/`UP` = +1 (crossing-axis decreasing); `RIGHT`/`DOWN` = −1. The companion's `counting-history.jsonl` reader MUST tolerate the new `UP`/`DOWN` values (additive — see companion follow-up issue) |
| `status` | int | `heartbeat` | running state code |
| `auto_mode` | bool | `heartbeat` | auto-record mode on/off |
| `last_segment` | string\|null | `heartbeat` / `session_end` | current clip filename |
| `ts` | string (ISO-8601) | all | event timestamp |
| `counts` | object | `heartbeat` / `session_end` | **(BL-78, additive)** per-species sub-counts `{class_name: count}`; the global `count` stays the sum of these (retro-compatible) |
| `class_id` | int | `event` (`crossed`/`id_switch_recovery`) | **(BL-78, additive)** class id of the crossing track |
| `species` | string | `event` (`crossed`/`id_switch_recovery`) | **(BL-78, additive)** resolved class name (from `model-classes.json`); falls back to the raw id string |
| `counting_line_orientation` | string | `session_start` / `session_end` | **(BL-83, additive)** effective counting-line orientation for the session (`"vertical"` \| `"horizontal"`); persisted per recording with the session metadata |
| `offset_counting_line` | int | `session_start` / `session_end` | **(BL-83, additive)** effective signed counting-line offset (percent) for the session; persisted per recording with the session metadata |

> ⚠ **This is the tightest contract.** The countingapp's writer lives in
> `app/src/core/history.py` (animal-counter repo); the companion's reader is
> `HistoryIndex` in `jetson-companion.py` (this repo). A field rename or type
> change in the writer silently breaks the reader (returns `None`). Any change
> here MUST be made in both repos in a coordinated commit, and the companion
> bumped to parse the new shape. A `schema_version` field SHOULD be added to
> records to make drift detectable (future work).

#### 2. `counting-{ts}-#N.mp4` — recorded clips

Produced by the countingapp / video-compress pod, **range-streamed** by the
companion via `GET /api/video/<id>`. Filename pattern:

```
counting-{session_or_run_ts}-{N}.mp4
```

The companion globs `counting-*.mp4` in the `/files` path and exposes them by
`video_id`. A clip may be **temporarily absent** (compression in progress) or
**cleaned up** (retention) → the companion returns `404`, and the Android app
shows the "video no longer accessible" state.

#### 3. `snapshot.jpg` — live preview snapshot (BL-88)

The countingapp **writes** this periodically from `display_thread.py`
(`DisplayThread._write_snapshot`): a JPEG (quality 85, default) of the **raw**
counting-resolution frame, captured at the top of the display loop (before any
tracking/overlay rendering — a clean canvas). It is written **atomically**
(encode to bytes → write `snapshot.jpg.tmp` → `os.replace` to `snapshot.jpg`)
approximately every `SNAPSHOT_INTERVAL_SECONDS` (default 5 s), gated by a
wall-clock timestamp (not per-frame). Best-effort: any encode/write failure is
logged at WARNING and swallowed (never breaks the display loop). Written
regardless of `shared_state.status` (idle/counting/pause/auto) as long as frames
flow, so the Android mask-zone editor gets a live preview even when idle.

The companion **reads** it via `GET /api/snapshot` (BL-88, PR #19) and serves it
to the Android app's visual mask-zone editor (`image/jpeg`). The companion reads
the fully-renamed `snapshot.jpg` directly via `send_file` — it never observes the
`.tmp` file (atomic rename guarantees this). The file **may be absent** (→ the
companion returns `404`) before the first write (cold start) or if
`SNAPSHOT_ENABLED=false` (boot param, default `true`). It is a single file
overwritten in place (no retention needed — constant size, ~50–150 KB).

| | |
|---|---|
| Writer | countingapp `display_thread.py` (atomic tmp+rename, ~5 s) |
| Reader | companion `GET /api/snapshot` (BL-88, PR #19) |
| Format | JPEG, raw counting-resolution frame, quality 85 (default) |
| Absent | `404` before first write / `SNAPSHOT_ENABLED=false` |
| Boot params | `SNAPSHOT_ENABLED`, `SNAPSHOT_INTERVAL_SECONDS`, `SNAPSHOT_PATH`, `SNAPSHOT_JPEG_QUALITY` (see `docs/04_configuration.md`) — boot params, NOT hot-reloaded via `/conf` |

### `/conf` (config/control)

#### 1. `runtime-settings.json` — live runtime settings (hot-reload)

The companion **writes** this (from the Android app's `PUT /api/settings`). The
countingapp **reads** it at the start of every recording
(`app/src/state.py::load_runtime_settings`, applied in `app/src/main.py::start`)
— hot-reload, no restart.

Schema:

```json
{
  "draw_tracking": true,
  "box_tracking": true,
  "centroid_tracking": true,
  "draw_mask_zones": true,
  "counting_direction_mode": "auto",
  "counting_direction": null,
  "models": {
    "sheep_template": {
      "counting_class_ids": [0],
      "counting_line_orientation": "vertical",
      "offset_counting_line": 0,
      "mask_zones": []
    },
    "my_model": {
      "counting_class_ids": [1],
      "counting_line_orientation": "vertical",
      "offset_counting_line": -13,
      "mask_zones": [{"x": 0, "y": 0, "w": 1, "h": 0.165, "name": "Mur"}]
    }
  }
}
```

**BL-89 — per-model settings.** The 4 scene/camera/model-specific keys
(`mask_zones`, `counting_line_orientation`, `offset_counting_line`,
`counting_class_ids`) live under a **`models.<model_name>`** section, one per
deployed model, so switching models (pig → sheep) loads that model's own
mask zones + counting line instead of the other's. The 4 display/tracker
toggles (`draw_tracking`, `box_tracking`, `centroid_tracking`, `draw_mask_zones`)
are **global** (user preference) and stay at the top level. The active
`model_name` comes from `model-classes.json` (BL-89, see file #5).

The countingapp (`load_runtime_settings`) returns a **flat** dict = global keys
+ the active model's per-model keys merged, so the downstream resolve_* /
main.py / watcher read flat keys unchanged. **Backward compat**: when `models`
is absent (legacy flat file), the per-model keys are read from the top level
(pre-BL-89 behavior); the companion migrates flat → `models.<active>` on the
first PUT. When `models` exists but the active model has no entry, the
per-model keys are absent (defaults apply).

| Key | Type | Range | Default | Effect |
|-----|------|-------|---------|--------|
| `draw_tracking` | bool | — | `false` | master toggle: write rendered (tracked) frame vs raw frame |
| `box_tracking` | bool | — | `false` | draw bounding boxes (sub-toggle, only if `draw_tracking`) |
| `centroid_tracking` | bool | — | `false` | draw centroid trails (sub-toggle, only if `draw_tracking`) |
| `offset_counting_line` | int | **signed** (loose sanity range `-300..300`, reject non-int/bool in `main.py`) | `10` | **(BL-83)** signed offset of the counting line, in percent of the frame dimension **along the perpendicular axis** (frame width for a vertical line, frame height for a horizontal line). The **authoritative bound is the line staying inside the image with a 200px margin on both edges**: vertical `x ∈ [200, W-200]`, horizontal `y ∈ [200, H-200]`. Because the offset is a percentage but the frame size is only known at runtime, this bound is **enforced by clamping the computed line position to `[200, dim-200]` at use-time** in `counting.py` + `rendering.py` (a clamp that changes the value logs a WARNING). The `main.py` `-300..300` cap only garbage-filters absurd values. Existing `0..100` values stay valid (a sub-range, in-bounds for typical frames). **Takes full effect only when a new InferThread/Counting is created** (a mid-session change needs a recording restart) |
| `counting_line_orientation` | string | `"vertical"` \| `"horizontal"` | `"vertical"` | **(BL-83)** orientation of the counting line. `"vertical"` = current behavior (a vertical line at `x = W/2 + W*off/100`, animals cross right→left = +1 / `LEFT`). `"horizontal"` = a horizontal line at `y = H/2 + H*off/100`, animals cross down→up = +1 / `UP`. Hot-reloaded per recording with the same "next recording" semantics as `offset_counting_line` (absent/invalid/bool ⇒ `"vertical"`) |
| `counting_class_ids` | array[int] | subset of `model-classes.json` `names` ids | `[default_counting_class]` | **(BL-78)** which class IDs the countingapp counts; hot-reloaded per recording (validated against `model-classes.json`; invalid IDs dropped with a WARNING; fallback `[default_counting_class]` when absent/empty/all-invalid) |
| `mask_zones` | array[object] | each `{x,y,w,h, name?}` in `[0..1]`, `w>0`, `h>0`, `x+w<=1`, `y+h<=1` | `[]` | **(BL-87)** normalized axis-aligned exclusion rects; detections whose centroid falls inside any rect are dropped before tracking (no track → no count). Strict reject-all on any invalid rect (field ignored, prior kept, WARNING). Hot-reloaded at idle. Generic (all species). **`name` (optional string, additive)** is an app-local label for the rect (Android UI): the companion stores + returns it (generic merge, unknown-key tolerant), the countingapp ignores it (reads only `x/y/w/h`) |
| `draw_mask_zones` | bool | — | `true` | **(BL-87)** draw a semi-transparent overlay of the `mask_zones` rects (independent of `draw_tracking`). Hot-reloaded |
| `counting_direction_mode` | string | `"auto"` \| `"manual"` | `"auto"` | **(BL-92)** auto-detect the dominant crossing direction per run via a warm-up of N=3 crossings or T=10s then lock, vs manual operator-set +1. Hot-reloaded at idle (BL-86 gating). *Additive — companion byte-identical sync later* |
| `counting_direction` | string | `up` \| `down` \| `left` \| `right` \| `null` | `null` | **(BL-92, manual only)** the +1 direction. Validated vs the active `counting_line_orientation` (horizontal → `up`/`down`, vertical → `left`/`right`, reject+WARN on mismatch → fall back to auto/default). A change resets the counter like `counting_class_ids`. *Additive — companion byte-identical sync later* |

**BL-93 — per-model input config + output_fps (startup-only, NOT hot-reloaded).**
In addition to the 4 counting/visual keys above, each `models.<model_name>`
section MAY carry the following input/recording keys. These are read **once at
startup** (`main.py::start`, via `state.py::resolve_input_config` /
`resolve_output_fps`) and are **deliberately excluded from the hot-reload
watcher** (`RuntimeSettingsWatcher._build_pending` only processes the
counting/visual keys above) — switching the physical sensor (camera ↔ drone)
is a restart, not a hot-swap. The resolvers fall back to the env defaults
(`settings.INPUT_SOURCE` / `settings.VIDEO_PATH` / `settings.INPUT_WIDTH` /
`settings.INPUT_HEIGHT` / `settings.FPS_OUTPUT`) when a key is absent or invalid
(retrocompat for pre-BL-93 flat files). Invalid values log a WARNING and fall
back; resolvers never raise (fail-open).

| Key | Type | Range | Default | Effect |
|-----|------|-------|---------|--------|
| `input_source` | string | `"CAMERA"` \| `"STREAM"` \| `"FILE"` | env `INPUT_SOURCE` (`CAMERA`) | **(BL-93, startup-only)** frame input type. `CAMERA` = V4L2 `/dev/videoN` (pig prod). `STREAM` = RTSP URL (sheep drone, 720p). `FILE` = validation/test. Validated ∈ {CAMERA, STREAM, FILE}; invalid → env fallback. Precedence: CLI `-m`/`-f` (validation/test) > per-model `input_source` > env `INPUT_SOURCE` |
| `input_url` | string | non-empty RTSP URL | — | **(BL-93, startup-only)** RTSP URL, **required when `input_source == "STREAM"`**. Absent/empty when STREAM → env fallback (logged WARNING). Ignored for CAMERA/FILE |
| `input_device` | string | e.g. `"/dev/video0"` | — | **(BL-93, startup-only)** V4L2 device path, **required when `input_source == "CAMERA"`**. Absent when CAMERA → env fallback (`settings.VIDEO_PATH`). Ignored for STREAM/FILE |
| `input_width` | int | positive (>0, reject bool) | env `INPUT_WIDTH` (`640`) | **(BL-93, startup-only)** **capture** resolution width passed to `cv2.CAP_PROP_FRAME_WIDTH` (decoupled from the recording `OUTPUT_WIDTH`). For CAMERA this sets the sensor capture size; for STREAM it is a hint only (RTSP negotiates native 720p); FILE ignores it. Invalid (bool/zero/negative) → env fallback |
| `input_height` | int | positive (>0, reject bool) | env `INPUT_HEIGHT` (`480`) | **(BL-93, startup-only)** **capture** resolution height passed to `cv2.CAP_PROP_FRAME_HEIGHT` (decoupled from the recording `OUTPUT_HEIGHT`). Same semantics as `input_width` |
| `output_fps` | int | positive (>0, reject bool) | env `FPS_OUTPUT` (`30`) | **(BL-93, startup-only)** the `cv2.VideoWriter` frame rate for recorded clips. Replaces the previously hardcoded `30`, fixing the writer@30fps vs 15fps-FP16@1280 time-compression bug (recordings played ~2× too fast). The writer frame size stays `OUTPUT_WIDTH/HEIGHT` 640×480 (PR #129 resize). Validation is byte-identical: `my_model` sets `output_fps=30` = today's hardcoded value; legacy files without the key fall back to `FPS_OUTPUT=30`. v1 uses a static per-model estimate (option A); runtime auto-detection (option B) is a documented future enhancement |

**runtime-settings.json — full BL-93 example** (pig `my_model` CAMERA + sheep
`sheep_template` STREAM, matching issue §1):

```json
{
  "draw_tracking": true,
  "box_tracking": true,
  "centroid_tracking": true,
  "draw_mask_zones": true,
  "models": {
    "my_model": {
      "counting_class_ids": [1],
      "counting_line_orientation": "vertical",
      "offset_counting_line": -13,
      "mask_zones": [{"x": 0, "y": 0, "w": 1, "h": 0.165, "name": "Mur"}],
      "input_source": "CAMERA",
      "input_device": "/dev/video0",
      "input_width": 640,
      "input_height": 480,
      "output_fps": 30
    },
    "sheep_template": {
      "counting_class_ids": [0],
      "counting_line_orientation": "vertical",
      "offset_counting_line": 0,
      "mask_zones": [],
      "input_source": "STREAM",
      "input_url": "rtsp://drone.local:8554/live",
      "input_width": 1280,
      "input_height": 720,
      "output_fps": 15
    }
  }
}
```

> **BL-93 — additive, companion byte-identical sync pending.** The 6
> startup-only input/recording keys above are new additive `models.<name>`
> keys. The companion's generic merge (unknown-key tolerant) already stores
> and returns them unmodified; the Android UI input-config editor + the
> byte-identical `IPC_CONTRACT.md` sync in the sister repo
> (`wloonis/animal-counter-companion`) are a **separate follow-up** (a
> co-issue, like the BL-92 notes). Not gating this run's standard validation.

Keys not present are ignored (defaults from `app/src/settings.py` apply). A
missing/empty/invalid file → countingapp keeps current settings.

#### 2. `.arret_requested` — power-off sentinel

The companion **creates** this file (from the Android app's `POST /api/power`).
The countingapp's `display_thread.py` **polls** for it; when present, it stops
cleanly after the current recording finishes, then power-offs. The companion
does NOT remove it (the countingapp consumes it).

Format: empty file (presence is the signal). Filename: `.arret_requested`
(hidden, in the `/conf` path).

#### 3. `model-classes.json` — read-only model class catalog (BL-78)

The countingapp **writes** this at startup from `app/model/classes.yaml`
(`app/src/state.py::publish_model_classes_json`, atomic write). The companion
**reads** it read-only to know which classes the deployed model can count and
to label per-species sub-counts. It is the IPC mirror of the build-time
`classes.yaml` (source of truth, captured by `ansible/playbooks/model/build_model.yml`
from the Roboflow `data.yaml`). Written once at startup; best-effort (a write
failure is logged and the app keeps counting — the companion just won't see the
catalog until the next successful write). Globally IPC file #5.

Schema:

```json
{
  "model_version": "sheep_template",
  "model_name": "sheep_template",
  "nc": 1,
  "names": ["sheep"],
  "default_counting_class": 0
}
```

| Field | Type | Meaning |
|-------|------|---------|
| `model_version` | string\|null | dataset version label (from `classes.yaml`; for local datasets = the dataset name) |
| `model_name` | string\|null | **(BL-89)** active model name (basename of the dataset dir = the `.engine`/`.onnx`/`.pt` stem). The companion reads this to select the matching `models.<model_name>` runtime-settings section |
| `nc` | int | number of classes the model detects |
| `names` | array[string] | ordered class names (index = class id) |
| `default_counting_class` | int | class id counted by default (model property; `[default_counting_class]` is the fallback `counting_class_ids`) |

The companion's `counting_class_ids` proposals in `runtime-settings.json` are
validated against `names`; unknown ids are dropped.

## Deployment conventions

- The shared paths `/data/orin/files` (data) and `/data/orin/conf`
  (config/control) are created/owned by the **countingapp** repo's deployment
  (k3s hostPaths). The companion deploy only ensures the dirs exist; it does
  NOT manage retention or the pod.
- The companion must **never starve inference**: its systemd unit caps CPU to
  30%, `Nice=10`, best-effort I/O (BL-71). Do not remove these limits.
- The companion is **stdlib-only Python** (no pip). It runs as root on the host
  (needs to read `/data/orin/files` and `/data/orin/conf` owned by the pod's
  hostPaths).

## Change protocol

When you change any format above:

1. Open/Update an issue in **both** repos referencing this contract.
2. Change the writer (animal-counter) and the reader (this repo) in a
   coordinated pair of PRs; do not merge one before the other is ready.
3. Update this `IPC_CONTRACT.md` in **both** repos (keep them identical).
4. For `counting-history.jsonl`, prefer **additive** changes (new fields, new
   `type` values) over renames; the reader must tolerate unknown fields.