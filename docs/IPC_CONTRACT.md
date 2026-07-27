# IPC Contract — companion ⇄ countingapp

This document is the **authoritative contract** between the two repos that make
up the animal-counter system. It MUST stay identical in both:

- **`wloonis/animal-counter`** — the counting core (OC-SORT tracking + counting,
  TensorRT, K3s `countingapp` pod). Owns the **writer** side of the history log.
- **`wloonis/animal-counter-companion`** — the Android app + the Jetson host
  companion (HTTP bridge). Owns the **reader** side of the history log and the
  **writer** side of the runtime settings / power sentinel.

The companion (host, systemd) and the countingapp (k3s pod, hostPath `/files`)
communicate **only** via files in the shared directory
`/data/orin/files` on the Jetson (`/files` inside the pod). There is **no HTTP
or RPC** between them. Any change to a format below is a coordinated change
across both repos.

## Shared path

| | Path | Owner |
|---|------|-------|
| Host (companion) | `/data/orin/files/` | created by the countingapp deploy |
| Pod (countingapp) | `/files/` (hostPath mount of the above) | k3s manifest |

## Files

### 1. `counting-history.jsonl` — append-only event log

The countingapp **appends** one JSON object per line. The companion **reads**
it (read-only, builds an in-memory index, re-indexes on mtime change).

Schema (one JSON object per line):

| Field | Type | Present in | Meaning |
|-------|------|-------------|---------|
| `type` | string | all | `event` \| `heartbeat` \| `session_end` |
| `session_id` | string | all | UUID of the recording session |
| `video_id` | string\|null | `event` | id of the clip recorded on a detection |
| `count` | int | `heartbeat` | net bidirectional counter at heartbeat time |
| `count_delta` | int | `event` | +1 (right→left) / −1 (left→right) |
| `status` | int | `heartbeat` | running state code |
| `auto_mode` | bool | `heartbeat` | auto-record mode on/off |
| `last_segment` | string\|null | `heartbeat` / `session_end` | current clip filename |
| `ts` | string (ISO-8601) | all | event timestamp |

> ⚠ **This is the tightest contract.** The countingapp's writer lives in
> `app/src/core/history.py` (animal-counter repo); the companion's reader is
> `HistoryIndex` in `jetson-companion.py` (this repo). A field rename or type
> change in the writer silently breaks the reader (returns `None`). Any change
> here MUST be made in both repos in a coordinated commit, and the companion
> bumped to parse the new shape. A `schema_version` field SHOULD be added to
> records to make drift detectable (future work).

### 2. `runtime-settings.json` — live runtime settings (hot-reload)

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
  "offset_counting_line": 10
}
```

| Key | Type | Range | Default | Effect |
|-----|------|-------|---------|--------|
| `draw_tracking` | bool | — | `false` | master toggle: write rendered (tracked) frame vs raw frame |
| `box_tracking` | bool | — | `false` | draw bounding boxes (sub-toggle, only if `draw_tracking`) |
| `centroid_tracking` | bool | — | `false` | draw centroid trails (sub-toggle, only if `draw_tracking`) |
| `offset_counting_line` | int | 0–100 (% of frame height) | `10` | vertical offset of the counting line; **takes full effect only when a new InferThread/Counting is created** (a mid-session change needs a recording restart) |

Keys not present are ignored (defaults from `app/src/settings.py` apply). A
missing/empty/invalid file → countingapp keeps current settings.

### 3. `.arret_requested` — power-off sentinel

The companion **creates** this file (from the Android app's `POST /api/power`).
The countingapp's `display_thread.py` **polls** for it; when present, it stops
cleanly after the current recording finishes, then power-offs. The companion
does NOT remove it (the countingapp consumes it).

Format: empty file (presence is the signal). Filename: `.arret_requested`
(hidden, in the shared path).

### 4. `counting-{ts}-#N.mp4` — recorded clips

Produced by the countingapp / video-compress pod, **range-streamed** by the
companion via `GET /api/video/<id>`. Filename pattern:

```
counting-{session_or_run_ts}-{N}.mp4
```

The companion globs `counting-*.mp4` in the shared path and exposes them by
`video_id`. A clip may be **temporarily absent** (compression in progress) or
**cleaned up** (retention) → the companion returns `404`, and the Android app
shows the "video no longer accessible" state.

## Deployment conventions

- The shared path `/data/orin/files` is created/owned by the **countingapp**
  repo's deployment (k3s hostPath). The companion deploy only ensures the dir
  exists; it does NOT manage retention or the pod.
- The companion must **never starve inference**: its systemd unit caps CPU to
  30%, `Nice=10`, best-effort I/O (BL-71). Do not remove these limits.
- The companion is **stdlib-only Python** (no pip). It runs as root on the host
  (needs to read `/data/orin/files` owned by the pod's hostPath).

## Change protocol

When you change any format above:

1. Open/Update an issue in **both** repos referencing this contract.
2. Change the writer (animal-counter) and the reader (this repo) in a
   coordinated pair of PRs; do not merge one before the other is ready.
3. Update this `IPC_CONTRACT.md` in **both** repos (keep them identical).
4. For `counting-history.jsonl`, prefer **additive** changes (new fields, new
   `type` values) over renames; the reader must tolerate unknown fields.