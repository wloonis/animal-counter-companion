# 03 — Persistent counting-session history (store) (BL-68)

An **append-only JSONL** counting-session history (`counting-history.jsonl`)
written read-only from the `countingapp` pod onto the hostPath `/files`, with
a single in-process history thread (heartbeat + compaction) that is resilient
to power cuts and bounded to ~200 MB on the small SSD. The store is exposed
**read-only** to the Android app through the `jetson-companion` host service
(BL-64, port 8090) v2 endpoints — the full HTTP API surface (sessions,
summary, videos, video streaming) is documented in
[`01_jetson_companion.md`](01_jetson_companion.md). This doc covers the
**store** internals: JSONL schema, writer, compaction, disk guard, settings.

This implements [GitHub issue BL-68](https://github.com/wloonis/animal-counter/issues).

> **Read-only instrumentation only.** The counting/tracking/guard decision
> logic is **untouched** — every history emit/counter increment is additive
> on a code path that already executes. Validation STANDARD (tolerance 0)
> keeps the reference count unchanged. See
> `tests/test_counting_invariance.py` for the unit-level proof.

## Why it exists

The Jetson runs unattended in the field with no internet and no RTC. Until
BL-68 there was no persistent record of a counting session: after a power
cut the only evidence a session happened was the video clips and a stale
`result.json` from the last validate run. BL-68 adds a durable, append-only,
power-loss-resilient history of every session — lifecycle, counting/tracking
health, sampled perf/thermal, config snapshot, video metadata, system
health, and a per-session event timeline — so an operator (or the Android
app) can ask "what happened in the last N sessions?" without touching the pod.

The history is **only written in serve mode** (production). The
`RESULT_JSON_PATH` env var (set only by the validate Job) is the guard:
history recording is enabled iff `RESULT_JSON_PATH` is unset, consistent
with the existing `write_result_json` branch in `main.py`. The validate
`result.json` stays the validation source of truth.

## Architecture

- **One writer (pod), one reader (companion host), same hostPath `/files`.**
  The pod appends the JSONL; the companion reads it read-only and never
  mutates the source file. The JSONL is the single source of truth — the pod
  keeps no sidecar index, so a power cut can only lose the last partial line,
  never corrupt an index.
- **A dedicated `HistoryThread` in the pod** owns (a) a one-shot startup
  compaction, (b) the heartbeat loop, and (c) a 1x/day compaction timer.
  Compaction and heartbeat are never concurrent because they run in the same
  thread.
- **Secondary index by `session_id` is reader-built lazily by the
  companion.** On the first history request the companion scans the JSONL
  once, builds an in-memory map `session_id → {offsets, summary}`, and caches
  it; the cache is invalidated on file-size change. This keeps the pod writer
  simple and is fine for the infrequent Android-driven requests.
- **`git_commit` + `image_tag` via a build-info file baked into the image.**
  `app/Dockerfile` writes `/app/.build-info.json` from build args
  (`IMAGE_TAG` + a `GIT_COMMIT` arg populated by `git rev-parse HEAD` in
  `build_countingapp.yml`). `main.py` reads it at startup; fallback to env
  `IMAGE_TAG` if missing, `git_commit='unknown'`. Robust to K3s env drift;
  travels with the image.

## hostPath layout

| Pod path            | Host path                              | Purpose                              |
|---------------------|----------------------------------------|--------------------------------------|
| `/files`            | `/data/orin/files`                     | The persistent hostPath volume         |
| `/files/counting-history.jsonl` | `/data/orin/files/counting-history.jsonl` | The live JSONL (append-only) |
| `/files/counting-history.<ts>.jsonl.gz` | `/data/orin/files/counting-history.<ts>.jsonl.gz` | Rotated gz archives (cold) |

The companion reads the host-side path (`/data/orin/files/...`), configurable
via the `HISTORY_FILE_HOST` env var in the systemd unit (default
`/data/orin/files/counting-history.jsonl`). The pod writes the pod-side path
(`/files/...`), configurable via `HISTORY_FILE` in `app/src/settings.py`.

## JSONL line schema

Every line is a self-contained JSON object with a `"type"` discriminator and a
UTC ISO-8601 `"ts"`. The reader tolerates a partial (truncated) last line —
it is skipped on open. Line types:

| `type`          | Emitted by                | Purpose                                              |
|-----------------|---------------------------|------------------------------------------------------|
| `session_start` | `start_session()`         | A lifecycle + D config snapshot                       |
| `heartbeat`     | `HistoryThread` loop      | periodic count + last segment + C/F/G samples        |
| `event`         | `Counting._emit_event`    | from the counting instrumentation (via subscribers)  |
| `session_end`    | `end_session()` / recovery| E video metadata + B final counters + F system       |
| `startup`       | `start_session()`         | boot_at, image_tag, git_commit, mode, config_notable |

### A — session lifecycle (`session_start` / `session_end`)

```json
{
  "type": "session_start",
  "session_id": "uuid4",
  "prev_session_id": "uuid4-or-null",
  "start_at": "2025-07-15T14:30:00Z",
  "start_at_locale": "2025-07-15T16:30:00+02:00",
  "start_reason": "serve",
  "status": "running",
  "config": { ... },          // D — config snapshot
  "ts": "2025-07-15T14:30:00Z"
}
```

```json
{
  "type": "session_end",
  "session_id": "uuid4",
  "start_at": "2025-07-15T14:30:00Z",
  "end_at":   "2025-07-15T18:00:00Z",
  "end_reason": "clean",        // clean | power-loss | sigterm | ...
  "status": "clean",
  "synthetic": false,           // true for power-loss recovery session_end
  "counters": { ... },          // B — final counters
  "video":   { ... },           // E — video metadata
  "system":  { ... },           // F — disk_free, cpu_load_avg, mem_used
  "ts": "2025-07-15T18:00:00Z"
}
```

### B — counting/tracking health (final, in `session_end.counters`)

```json
{
  "count_left_to_right": 9,
  "count_right_to_left": 0,
  "guard_interventions": {
    "lost_buffer_expired": 0,
    "mirror_guard": 0,
    "resurrection": 0,
    "reid_rebind": 0
  },
  "id_switch_recoveries": 0,
  "unique_track_ids": 27,
  "max_concurrent_tracks": 4
}
```

### C — sampled perf/thermal (in each `heartbeat`)

```json
{
  "type": "heartbeat",
  "session_id": "uuid4",
  "ts": "2025-07-15T14:30:05Z",
  "count": 9,
  "last_segment": "/files/tocompress-counting-20250715-143000.mp4",
  "thermal": { ... },          // C — temp samples (best-effort)
  "system":  { ... }           // F — disk_free, cpu_load_avg, mem_used
}
```

### D — config snapshot (in `session_start.config`)

Emitted once at startup from `Settings` + `/app/.build-info.json`. Includes
`image_tag`, `git_commit`, `mode`, and `config_notable` (a curated subset of
the counting-relevant settings, e.g. confidence threshold, guard buffer
length).

### E — video metadata (in `session_end.video`)

Best-effort: the last video segment filename from
`shared_state.display_thread.filename`.

### F — system health (in `heartbeat.system` and `session_end.system`)

```json
{
  "disk_free": 12.3,            // GB free on /files
  "cpu_load_avg": [0.42, 0.51, 0.48],  // /proc/loadavg (1/5/15 min)
  "mem_used": 3.2              // GB used (from /proc/meminfo)
}
```

### G — per-session event timeline (each `event` line)

```json
{
  "type": "event",
  "session_id": "uuid4",
  "event_type": "crossed_right",   // crossed_left | crossed_right |
                                   // id_switch_recovery | mirror_guard |
                                   // resurrection | reid_suppress |
                                   // track_lost | lost_buffer_expired
  "detail": { ... },               // opaque, from the counting instrumentation
  "ts": "2025-07-15T14:31:12Z"
}
```

## Heartbeat, compaction, disk guard

### Heartbeat

The `HistoryThread` loop appends a `heartbeat` line every
`HISTORY_HEARTBEAT_S` seconds (default 5). Each line is `os.fsync`'d
atomically — a power cut can only lose the last partial line, never corrupt
earlier lines. There is **no per-frame I/O**; sampling (thermal, system) is
cheap and only happens in the heartbeat/compaction path.

### Power-loss-resilient end-time

A clean shutdown (BL-62 `stop()` / SIGTERM) writes a real `session_end` with
`end_reason="clean"`. If the pod is killed by a power cut, no `session_end`
is written for that session. At the next boot, `start_session()` runs
**recovery**: it scans the JSONL for the last session that has no
`session_end` and writes a synthetic `session_end` using the last
`heartbeat`'s `ts` as `end_at`:

- if the last heartbeat is **recent** (within a staleness threshold, ~1h) →
  `end_reason="power-loss"`;
- if the last heartbeat is **stale** (older than the threshold) →
  `end_reason="unknown"`.

Crash/OOM classification is best-effort via `journalctl -b -1` / `dmesg` and
is non-fatal if unavailable.

### Compaction (2-level, in the pod, in the history thread)

Hot sessions (≤ `HISTORY_RETENTION_DAYS`, default 30) keep their raw lines.
Cold sessions (> retention) are replaced with **one summary line** (A–F
aggregates) keeping only significant events; heartbeats are dropped. The
rewrite is atomic: write to a temp file in the same dir, `os.fsync`, then
`os.replace` — a crash before `os.replace` leaves the old file intact, and
the partial temp is discarded on reopen.

Compaction runs:
1. once at `HistoryThread` start (before the heartbeat loop), and
2. once per day via a deadline timestamp checked each loop iteration.

Compaction and heartbeat are never concurrent (same thread).

### Bounded size (~200 MB)

- **`HISTORY_MAX_BYTES`** (default 200 MB) — compaction keeps the live file
  ≤ this cap (cold sessions are collapsed to summary lines).
- **`HISTORY_ROTATE_BYTES`** (default 10 MB) — if the file exceeds this, the
  cold portion is gzip-archived to `counting-history.<ts>.jsonl.gz`.
- **`HISTORY_ARCHIVE_MAX`** (default 20) — archives beyond this count are
  deleted oldest-first.

### Disk guard

Before each heartbeat, the writer checks `disk_free("/files")`:

- **≥ `HISTORY_DISK_WARN_GB`** (default 2 GB) → normal 5s heartbeat interval.
- **< `HISTORY_DISK_WARN_GB`** → heartbeat interval bumped to 30s (writes
  slow down, counting continues).
- **< `HISTORY_DISK_CRIT_GB`** (default 0.5 GB) → **writes suspended**;
  counting continues unaffected; a `disk_warning` event is emitted and a
  log alert is raised.

## `HISTORY_*` settings (`app/src/settings.py`)

All env-overridable, mirroring the existing `os.getenv(...)` pattern.

| Setting                    | Env var                    | Default                  | Purpose                                            |
|----------------------------|----------------------------|--------------------------|----------------------------------------------------|
| `HISTORY_FILE`             | `HISTORY_FILE`             | `/files/counting-history.jsonl` | Pod-side JSONL path                        |
| `HISTORY_RETENTION_DAYS`   | `HISTORY_RETENTION_DAYS`   | `30`                     | Hot/cold compaction threshold (days)               |
| `HISTORY_MAX_BYTES`        | `HISTORY_MAX_BYTES`        | `200 * 1024 * 1024`      | Live-file size cap after compaction                |
| `HISTORY_HEARTBEAT_S`      | `HISTORY_HEARTBEAT_S`      | `5`                      | Heartbeat interval (normal)                        |
| `HISTORY_DISK_WARN_GB`     | `HISTORY_DISK_WARN_GB`     | `2`                      | Below this → heartbeat interval 30s                |
| `HISTORY_DISK_CRIT_GB`     | `HISTORY_DISK_CRIT_GB`     | `0.5`                    | Below this → suspend writes                        |
| `HISTORY_ROTATE_BYTES`     | `HISTORY_ROTATE_BYTES`     | `10 * 1024 * 1024`      | Above this → gzip-archive the cold portion         |
| `HISTORY_ARCHIVE_MAX`      | `HISTORY_ARCHIVE_MAX`      | `20`                     | Max number of `*.jsonl.gz` archives kept           |

## Build-info baking

`app/Dockerfile` adds:

```dockerfile
ARG IMAGE_TAG=local
ARG GIT_COMMIT=unknown
RUN printf '{"git_commit":"%s","image_tag":"%s"}\n' "$GIT_COMMIT" "$IMAGE_TAG" \
    > /app/.build-info.json
```

`ansible/playbooks/app/build_countingapp.yml` passes
`--build-arg IMAGE_TAG={{ image_tag }}` and
`--build-arg GIT_COMMIT=$(git rev-parse HEAD)` to `docker buildx build`. At
startup `main.py` reads `/app/.build-info.json`; if missing it falls back to
env `IMAGE_TAG`, and `git_commit` defaults to `"unknown"`. This travels with
the image and is robust to K3s env drift.

## Companion API (HTTP)

The store is served **read-only** to the Android app through the
`jetson-companion` host service (BL-64, port 8090), bumped to **version `"2"`**
(`GET /api/identify`). The full endpoint table + curl examples live in
[`01_jetson_companion.md`](01_jetson_companion.md) § Endpoints (v2):
`/api/sessions`, `/api/sessions/<id>`, `/api/summary`, `/api/startups`,
`/api/videos`, `/api/video/<id>` (range-streamed).

The companion **never** mutates the JSONL (the pod is the sole writer); its
reader builds a lazy in-memory `session_id -> {offsets, summary}` index on
first request and invalidates it on file-size change (see
[Architecture](#architecture)). Partial last lines are tolerated.
## rsync guard

`scripts/validate_on_jetson.sh` adds
`--exclude='counting-history*.jsonl*'` (covers the live file + gz archives)
to the rsync `--delete` command in step 3, so a code rsync never wipes the
persisted history on the Jetson. The existing excludes (`model/`, `.env`,
`video/`, `img/old/`) are unchanged.

## Validation

- **Unit (Python, no Jetson):**
  ```bash
  python3 -m pytest tests/test_counting_invariance.py \
                   tests/test_history_writer.py \
                   tests/test_companion_history_api.py -v
  ```
  - `test_counting_invariance.py` — `counter_to_right` identical with and
    without subscribers attached; new accumulators increment correctly on a
    known crossing sequence.
  - `test_history_writer.py` — JSONL append+fsync, partial-line tolerance on
    reopen, recovery writes a synthetic `session_end` for an unterminated last
    session (power-loss vs `unknown` staleness), compaction drops heartbeats
    for cold sessions and keeps a summary line, bounded size ≤
    `HISTORY_MAX_BYTES`, rotation creates a gz archive and bounds the count,
    disk guard suspends writes below the crit threshold.
  - `test_companion_history_api.py` — reader/indexer: parse JSONL, build
    index, paginate, session detail, daily summary, startups, against a
    fixture with a few sessions + heartbeats + events + an unterminated
    session.
- **Jetson business (the gate):** `./scripts/validate_on_jetson.sh` in
  **STANDARD** mode (reference video `validation-1-#9.mp4`, tolerance 0).
  Pass = reference count unchanged AND (after a serve-mode run on the Jetson)
  `counting-history.jsonl` appears on `/files` with `session_start` /
  `heartbeat` / `session_end` lines, and a forced compaction yields a file ≤
  `HISTORY_MAX_BYTES` with cold sessions collapsed to one summary line.
- **Companion API smoke (on Jetson host, post-deploy):**
  `curl http://127.0.0.1:8090/api/sessions?limit=10` and
  `curl http://127.0.0.1:8090/api/sessions/<id>` return JSON;
  `curl http://127.0.0.1:8090/api/identify` still returns the service (now
  version `"2"`).

## Risks

- **Instrumentation accidentally alters the count** — every instrumentation
  line is additive on an existing code path; the invariance unit test asserts
  byte-identical `counter_to_right` with/without subscribers; the Jetson
  STANDARD validation (tolerance 0) is the final gate.
- **Power cut mid-compaction corrupts the JSONL** — compaction writes to a
  temp file in the same dir + `os.fsync` + `os.replace` (atomic); a crash
  before `os.replace` leaves the old file intact; the partial-temp is
  discarded on reopen.
- **SSD fills up** — disk guard suspends writes below `HISTORY_DISK_CRIT_GB`
  (counting continues), compaction bounds size to `HISTORY_MAX_BYTES`,
  rotation bounds archive count.
- **Companion reads a file the pod is mid-append to** — JSONL lines are
  fsync'd atomically; the reader tolerates a partial last line; the companion
  only ever reads, never rewrites, so no writer/reader race on the content.
- **`build_countingapp.yml` git SHA capture fails in a shallow/non-git
  worktree** — `GIT_COMMIT` build arg defaults to `"unknown"`; `main.py`
  falls back to `git_commit='unknown'`; history still records, just without a
  precise commit.

## Related

- **BL-64** — the `jetson-companion` host service (port 8090) whose v2
  endpoints serve this store read-only (see
  [`01_jetson_companion.md`](01_jetson_companion.md)).
- **BL-65** — the Android app that connects to the Jetson HotSpot; the
  history endpoints give it a "what happened in the last N sessions?" view.
- **BL-62** — the clean-shutdown (`stop()` / `arret_requested` / poweroff)
  path that writes the real `session_end` before poweroff.