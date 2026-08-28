#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# animal-counter-companion — client/bridge layer (Android app + Jetson host companion HTTP bridge).
# Copyright (C) 2026  LOONIS Wennaël
#
# This program is free software: you can redistribute it and/or modify
# it under the terms of the GNU General Public License as published by
# the Free Software Foundation, either version 3 of the License, or
# (at your option) any later version.
#
# This program is distributed in the hope that it will be useful,
# but WITHOUT ANY WARRANTY; without even the implied warranty of
# MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
# GNU General Public License for more details.
#
# You should have received a copy of the GNU General Public License
# along with this program.  If not, see <https://www.gnu.org/licenses/>.

# BL-64: Jetson companion clock-sync service.
#
# A stdlib-only HTTP server that runs on the Jetson HOST (not k3s) and
# receives the current time/timezone from the Android phone (BL-65)
# over the HotSpot, then applies it via timedatectl. The Jetson has no
# RTC, so on every offline boot its clock is stuck at the build date
# (or 1970) until this service sets it.
#
# Endpoints:
#   GET  /api/identify        -> {"service":"jetson-companion","version":"6"}
#   GET  /api/count           -> live count/status/auto_mode (newest heartbeat)
#   POST /api/time            -> timedatectl set-time/set-timezone
#   POST /api/power           -> writes .arret_requested sentinel (BL-76);
#        the counting app consumes it and runs the BL-62 poweroff
#        sequence (nsenter systemctl poweroff). Returns 200 before
#        the actual poweroff (best-effort sentinel write).
#   GET  /api/settings        -> runtime-settings.json ({} if absent)
#   PUT  /api/settings        -> PATCH-like merge into runtime-settings.json
#        (draw_tracking/box_tracking/centroid_tracking bool,
#         offset_counting_line int signed (-300..300, BL-83),
#         counting_line_orientation str "vertical"|"horizontal" (BL-83),
#         counting_class_ids list[int] subset of model-classes names — BL-82,
#         mask_zones list[{x,y,w,h} normalized rects (BL-88),
#         draw_mask_zones bool (BL-88),
#         counting_direction_mode str "auto"|"manual" (BL-92, global),
#         counting_direction str "up"|"down"|"left"|"right"|null
#            manual-only (BL-92, global));
#        validated, atomic write.
#   GET  /api/snapshot        -> camera preview JPEG (image/jpeg, no-store)
#        served read-only from /files/snapshot.jpg (written by the
#        countingapp); 404 when absent (BL-88).
#   GET  /api/classes         -> countable species catalog (BL-82):
#        {model_version, nc, classes:[{id,name}], default_counting_class,
#         counting_class_ids} from model-classes.json + the current
#        runtime-settings selection; 404 when the catalog is not yet
#        published (countingapp not started / write pending).
#   GET  /api/sessions        -> paginated session summaries (newest first)
#        ?limit=50&offset=0
#   GET  /api/summary         -> daily aggregates ?days=7
#   GET  /api/videos          -> paginated video list (running first)
#        ?limit=50&offset=0
#   GET  /api/video/<id>      -> Range-streamed compressed MP4 (206 partial)
#   GET  /api/sessions/<id>   -> full session detail (A–G)
#   GET  /api/count           -> live count/status/auto_mode (newest heartbeat)
#   GET  /api/startups        -> startup history lines ?limit=50
#
# The history endpoints read the counting-history JSONL written by the
# countingapp pod (BL-68) onto the hostPath /files (host
# /data/orin/files/counting-history.jsonl). The companion reads it
# read-only and never mutates it; a lazy in-memory index (HistoryIndex)
# is rebuilt when the file size changes, tolerating a partial last
# line (power cut mid-append).
#
# Input is strictly validated; timedatectl is invoked via
# subprocess.run([...], shell=False) so the JSON body is never passed
# to a shell. Every request + result is logged to stdout (journald).
#
# No external dependencies. Uses only the Python standard library.
import datetime
import glob
import json
import os
import re
import subprocess
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs

SERVICE_NAME = "jetson-companion"
SERVICE_VERSION = "8"
HOST = "0.0.0.0"
DEFAULT_PORT = 8090
# Path on the Jetson HOST to the counting-history JSONL written by the
# countingapp pod via the /files hostPath (BL-68). Configurable via
# the HISTORY_FILE_HOST env var (mirroring COMPANION_PORT).
DEFAULT_HISTORY_FILE = "/data/orin/files/counting-history.jsonl"
HISTORY_FILE = os.environ.get("HISTORY_FILE_HOST", DEFAULT_HISTORY_FILE)
# Directory holding the JSONL and the compressed counting-*.mp4 files
# (the compression cron writes counting-{ts}-#N.mp4 here). Used by
# /api/video/<id> to resolve a video_id to its on-disk file. This is
# the /files hostPath (DATA: counting-history.jsonl + mp4 clips); it is
# NOT used for config/control files (see CONF_DIR below, BL-79/BL-80).
FILES_DIR = os.path.dirname(HISTORY_FILE) or "/data/orin/files"
# BL-79/BL-80: config/control IPC files live in a SEPARATE hostPath
# /conf (host /data/orin/conf), split from /files (data). The
# countingapp reads runtime-settings.json + .arret_requested from /conf
# (BL-79 migrated them out of /files); the companion must write them
# there too, or the BL-76 hot-reload + BL-71 power-off break. Configurable
# via CONF_DIR_HOST (mirrors HISTORY_FILE_HOST). HISTORY_FILE/FILES_DIR
# stay on /files (data) — only the two control files moved.
DEFAULT_CONF_DIR = "/data/orin/conf"
CONF_DIR = os.environ.get("CONF_DIR_HOST", DEFAULT_CONF_DIR)
# BL-76: runtime-settings.json is read at hot-reload time by the counting
# app at the start of each recording (main.py); the .arret_requested
# sentinel is polled by display_thread.py and, when fresh
# (mtime > app_start_time), triggers the BL-62 poweroff seq. Both now
# live in CONF_DIR (/conf hostPath, BL-79/BL-80), NOT FILES_DIR.
RUNTIME_SETTINGS_FILE = os.path.join(CONF_DIR, "runtime-settings.json")
POWER_SENTINEL_FILE = os.path.join(CONF_DIR, ".arret_requested")
# BL-82: read-only model class catalog published by the countingapp at
# startup (state.py::publish_model_classes_json, BL-78). The companion reads
# it to expose the countable species (class id + name) and the model default
# to the Android app, and to validate counting_class_ids proposals. Same
# /conf hostPath as the other control files.
MODEL_CLASSES_FILE = os.path.join(CONF_DIR, "model-classes.json")


def _ensure_conf_dir():
    """Best-effort create CONF_DIR if absent (BL-80).

    The companion may start before the countingapp deploy has created
    /data/orin/conf, or the dir may be absent on a partial deploy. We
    create it lazily before the first write of runtime-settings / the
    power sentinel so PUT /api/settings and POST /api/power don't fail
    with ENOENT. Errors are logged to stderr, not fatal (the write itself
    is guarded by its own try/except -> 500).
    """
    try:
        os.makedirs(CONF_DIR, exist_ok=True)
    except OSError as exc:
        sys.stderr.write("[{}] [conf-dir] makedirs failed: {}\n".format(
            datetime.datetime.now().isoformat(), exc))
# Chunk size for streaming video bytes to the client.
_VIDEO_CHUNK = 64 * 1024

# Event types counted as "guard" events in the daily summary.
_GUARD_EVENT_TYPES = frozenset({
    "mirror_guard", "mirror_guard_enforce", "mirror_suppress",
    "reid_suppress", "lost_buffer_expired", "resurrection",
    "id_switch_recovery",
})

# Module-level lazy history index (built on first history request,
# invalidated on file-size/mtime change).
_INDEX = None


def _parse_iso(ts):
    """Parse an ISO-8601 timestamp into an aware datetime.

    Tolerates a trailing 'Z' (which datetime.fromisoformat rejects on
    Python < 3.11) and naive timestamps (assumed UTC). Raises
    ValueError on unparseable input.
    """
    if not ts or not isinstance(ts, str):
        raise ValueError("empty ts")
    s = ts.strip()
    if s.endswith("Z"):
        s = s[:-1] + "+00:00"
    dt = datetime.datetime.fromisoformat(s)
    if dt.tzinfo is None:
        dt = dt.replace(tzinfo=datetime.timezone.utc)
    return dt


def _int_arg(qs, key, default):
    """Pull an integer query-string arg with a default; clamped to >= 0."""
    vals = qs.get(key)
    if not vals:
        return default
    try:
        v = int(vals[0])
    except (TypeError, ValueError):
        return default
    return v if v >= 0 else 0


def _get_index():
    """Return the module-level HistoryIndex (lazily built)."""
    global _INDEX
    if _INDEX is None:
        _INDEX = HistoryIndex(HISTORY_FILE)
    return _INDEX


class HistoryIndex:
    """Lazy, read-only in-memory index over the counting-history JSONL.

    Scans the file once on first use, builds a per-session map plus a
    startup list, and caches it. The cache is invalidated when the
    file size or mtime changes (so a growing JSONL is re-indexed on
    the next request). The companion NEVER writes to the JSONL; it
    only reads. A partial/truncated last line (power cut mid-append)
    is skipped.

    Session ordering is by ``session_start.start_at`` descending
    (newest first), which is what /api/sessions paginates over.
    """

    def __init__(self, path):
        self.path = path
        self._size = -1
        self._mtime = 0.0
        self._sessions = {}
        self._session_order = []
        self._startups = []
        self._videos = {}
        self._video_order = []
        self._latest_hb = None  # newest heartbeat across all sessions (/api/count)

    def _maybe_rebuild(self):
        try:
            size = os.path.getsize(self.path)
            mtime = os.path.getmtime(self.path)
        except FileNotFoundError:
            self._size = -1
            self._mtime = 0.0
            self._sessions = {}
            self._session_order = []
            self._startups = []
            self._videos = {}
            self._video_order = []
            return
        except OSError:
            return
        if size == self._size and mtime == self._mtime:
            return
        self._rebuild()

    def _rebuild(self):
        sessions = {}
        order = []
        startups = []
        videos = {}
        video_order = []
        latest_hb = None
        try:
            f = open(self.path, "rb")
        except FileNotFoundError:
            self._sessions = {}
            self._session_order = []
            self._startups = []
            self._videos = {}
            self._video_order = []
            self._size = -1
            self._mtime = 0.0
            return
        with f:
            for raw_b in f:
                raw = raw_b.decode("utf-8", errors="replace").rstrip("\n")
                if not raw.strip():
                    continue
                try:
                    obj = json.loads(raw)
                except json.JSONDecodeError:
                    # Partial/truncated last line — skip.
                    continue
                if not isinstance(obj, dict):
                    continue
                t = obj.get("type")
                if t == "startup":
                    startups.append(obj)
                    continue
                if t == "video":
                    # Per-recording video line (BL-71). Keyed by
                    # video_id (the counting-{ts} stem); newest first
                    # by the line's ts. Survives compaction (the
                    # compactor re-emits video lines verbatim, like
                    # startup lines).
                    vid = obj.get("video_id")
                    if not vid:
                        continue
                    if vid not in videos:
                        videos[vid] = obj
                        video_order.append(vid)
                    else:
                        # Keep the newest by ts on collision.
                        # Use >= (not >) so a corrected video line
                        # with the SAME finalize ts (e.g. the cron
                        # file_duration rewrite) supersedes the
                        # original finalize-time entry.
                        if (obj.get("ts") or "") >= (
                            videos[vid].get("ts") or ""
                        ):
                            videos[vid] = obj
                    continue
                sid = obj.get("session_id")
                if not sid:
                    continue
                sess = sessions.get(sid)
                if sess is None:
                    sess = {
                        "session_id": sid,
                        "start": None,
                        "end": None,
                        "last_hb": None,
                        "events": [],
                        "heartbeats": [],
                        "significant_events": None,
                        "count": None,
                        "start_at": None,
                    }
                    sessions[sid] = sess
                    order.append(sid)
                if t == "session_start":
                    sess["start"] = obj
                    sess["start_at"] = obj.get("start_at")
                elif t == "session_end":
                    sess["end"] = obj
                elif t == "heartbeat":
                    sess["heartbeats"].append(obj)
                    sess["last_hb"] = obj
                    if obj.get("count") is not None:
                        sess["count"] = obj.get("count")
                    # Track the globally-newest heartbeat for /api/count
                    # (ISO8601 UTC strings compare lexically).
                    hb_ts = obj.get("ts") or ""
                    if (
                        latest_hb is None
                        or hb_ts > (latest_hb.get("ts") or "")
                    ):
                        latest_hb = obj
                elif t == "event":
                    sess["events"].append(obj)
                elif t == "summary":
                    # Compaction output: a collapsed session. Use it
                    # as the start/end/count proxy when raw lines are
                    # gone.
                    if sess["start"] is None:
                        sess["start"] = obj
                        sess["start_at"] = obj.get("start_at")
                    if obj.get("net_count") is not None:
                        sess["count"] = obj.get("net_count")
                    if obj.get("end_at") and sess["end"] is None:
                        sess["end"] = obj
                    sess["significant_events"] = obj.get(
                        "significant_events")
        # Newest first by start_at; missing start_at sorts last.
        order.sort(
            key=lambda s: (sessions[s].get("start_at") or ""),
            reverse=True,
        )
        # Startups newest first (appended in chronological order).
        startups.reverse()
        # Videos newest first by the line's ts (ISO8601 UTC sorts
        # lexically).
        video_order.sort(
            key=lambda v: (videos[v].get("ts") or ""),
            reverse=True,
        )
        self._sessions = sessions
        self._session_order = order
        self._startups = startups
        self._videos = videos
        self._video_order = video_order
        self._latest_hb = latest_hb
        try:
            self._size = os.path.getsize(self.path)
            self._mtime = os.path.getmtime(self.path)
        except OSError:
            pass

    def latest_count(self):
        """Latest heartbeat of the current (newest-started, still-
        running) session, for /api/count.

        Bases the choice on session start_at + the running/ended
        flag rather than the heartbeat timestamp, so a clock
        regression (the Jetson has no RTC; a reboot can move the
        wall clock backwards) cannot make a past ended session's
        heartbeat look "newest" forever. Returns None (-> count 0)
        when no session is currently running."""
        self._maybe_rebuild()
        for sid in self._session_order:
            sess = self._sessions.get(sid) or {}
            if sess.get("end") is None:
                return sess.get("last_hb")
        return None

    def _summary_for(self, sid):
        sess = self._sessions[sid]
        start = sess.get("start") or {}
        end = sess.get("end") or {}
        last_hb = sess.get("last_hb") or {}
        last_event_ts = None
        evs = sess.get("events") or []
        if evs:
            last_event_ts = evs[-1].get("ts")
        end_at = end.get("end_at") or last_hb.get("ts")
        cfg = start.get("config") if isinstance(start, dict) else None
        return {
            "session_id": sid,
            "start_at": sess.get("start_at"),
            "end_at": end_at,
            "end_reason": end.get("end_reason"),
            "status": "ended" if sess.get("end") else "running",
            "net_count": sess.get("count"),
            "events": len(sess.get("events") or []),
            "heartbeats": len(sess.get("heartbeats") or []),
            "last_event_ts": last_event_ts,
            "image_tag": (cfg or {}).get("image_tag")
                          if isinstance(cfg, dict) else None,
            # BL-71: the video name + per-video duration now live on
            # the VIDEO entity (/api/videos), NOT the session. The
            # session keeps only global facts (count, perf/thermal,
            # config, status). last_segment + video_duration removed.
        }

    def session_summaries(self, limit=50, offset=0):
        self._maybe_rebuild()
        out = []
        ids = self._session_order[offset:offset + limit]
        for sid in ids:
            out.append(self._summary_for(sid))
        return out, len(self._session_order)

    def session_detail(self, sid):
        self._maybe_rebuild()
        sess = self._sessions.get(sid)
        if sess is None:
            return None
        start = sess.get("start") or {}
        end = sess.get("end") or {}
        last_hb = sess.get("last_hb") or {}
        end_at = end.get("end_at") or last_hb.get("ts")
        cfg = start.get("config") if isinstance(start, dict) else None
        # BL-71: per-video counting (events, guards, directional) now
        # lives on the VIDEO entity (/api/videos/<id>). The session
        # keeps only global facts: net_count, perf/thermal (from
        # heartbeats), config, status + the list of its videos. The
        # video name/duration are stripped from `end` (they belong to
        # the video).
        end_clean = dict(sess.get("end") or {})
        end_clean.pop("video", None)
        vids = [v for v in self._video_order
                if (self._videos[v].get("session_id") == sid)]
        return {
            "session_id": sid,
            "start": start,
            "end": end_clean or None,
            "end_at": end_at,
            "end_reason": end.get("end_reason"),
            "status": "ended" if sess.get("end") else "running",
            "net_count": sess.get("count"),
            "config": cfg,
            "heartbeats": sess.get("heartbeats") or [],
            "videos": vids,
        }

    def daily_summary(self, days=7):
        self._maybe_rebuild()
        now = datetime.datetime.now(datetime.timezone.utc)
        cutoff = now - datetime.timedelta(days=days)
        daily = {}
        for sid in self._session_order:
            sess = self._sessions[sid]
            start = sess.get("start")
            if not isinstance(start, dict):
                continue
            sa = start.get("start_at")
            if not sa:
                continue
            try:
                dt = _parse_iso(sa)
            except ValueError:
                continue
            if dt < cutoff:
                continue
            day = dt.strftime("%Y-%m-%d")
            d = daily.setdefault(day, {
                "date": day,
                "sessions": 0,
                "net_count": 0,
                "guard_events": 0,
                "events": 0,
            })
            d["sessions"] += 1
            d["net_count"] += (sess.get("count") or 0)
            for ev in sess.get("events") or []:
                d["events"] += 1
                et = ev.get("event_type", "") if isinstance(ev, dict) else ""
                if et in _GUARD_EVENT_TYPES:
                    d["guard_events"] += 1
        return [
            daily[k] for k in sorted(daily.keys(), reverse=True)
        ]

    def startups(self, limit=50):
        self._maybe_rebuild()
        if limit <= 0:
            return []
        return list(self._startups[:limit])

    def video_summaries(self, limit=50, offset=0):
        """Paginated list of finalized videos (newest first by the
        video line's ts), mirroring session_summaries. Each row is
        a ready (compressed) video fact. The running recording is
        NOT included here — it is prepended by the /api/videos
        route via _running_video_row()."""
        self._maybe_rebuild()
        out = []
        ids = self._video_order[offset:offset + limit]
        for vid in ids:
            obj = self._videos.get(vid) or {}
            out.append({
                "video_id": vid,
                "filename": obj.get("filename"),
                "duration": obj.get("duration"),
                "file_duration": obj.get("file_duration"),
                "count_delta": obj.get("count_delta"),
                "session_id": obj.get("session_id"),
                "ts": obj.get("ts"),
                "status": "ready",
            })
        return out, len(self._video_order)

    def _running_video_row(self):
        """Synthesize a first row for the currently-running
        recording, derived from the newest heartbeat. Returns a
        row dict or None when no recording is in progress / the
        heartbeat cannot be parsed.

        The video_id is the counting-{ts} stem parsed from the
        heartbeat's last_segment filename (tocompress-counting-
        {ts}-#N.mp4 / tmp-counting-{ts}.mp4 / counting-{ts}-#N.mp4).
        count_delta = hb.count - hb.record_start_count, only when
        record_start_count is present and non-None; otherwise the
        row is omitted (no phantom running row when idle)."""
        hb = self.latest_count()
        if not isinstance(hb, dict):
            return None
        rsc = hb.get("record_start_count")
        if rsc is None:
            return None
        count = hb.get("count")
        if count is None:
            return None
        last_segment = hb.get("last_segment")
        ts_stem = _parse_video_stem(last_segment)
        if not ts_stem:
            return None
        try:
            delta = int(count) - int(rsc)
        except (TypeError, ValueError):
            return None
        return {
            "video_id": "counting-" + ts_stem,
            "filename": "counting-{}.mp4".format(ts_stem),
            "duration": None,
            "file_duration": None,
            "count_delta": delta,
            "session_id": hb.get("session_id"),
            "ts": hb.get("ts"),
            "status": "running",
        }

    def video_detail(self, video_id):
        """Full video detail: the video line + per-video counting
        metadata (directional counts, guard interventions,
        track_lost, events timeline) + perf/thermal, all
        attributed by timespan [ts, ts+duration] from the
        session's events + heartbeats. Returns None if the
        video_id is unknown."""
        self._maybe_rebuild()
        obj = self._videos.get(video_id)
        running = False
        if obj is None:
            # Not a finalized video - maybe the currently-running
            # recording. Synthesize a detail from the running row +
            # the heartbeats/events since the recording start.
            row = self._running_video_row()
            if row is None or row.get("video_id") != video_id:
                return None
            obj = row
            running = True
        sid = obj.get("session_id")
        sess = self._sessions.get(sid) if sid else None
        duration = obj.get("duration")
        start_ts = obj.get("ts")
        finalize_dt = None
        start_dt = None
        end_dt = None
        if running:
            # Recording in progress: attribute events/heartbeats from
            # the first heartbeat whose last_segment matches this
            # recording's tmp filename, up to now (end_dt = None).
            stem = _parse_video_stem(obj.get("filename") or "")
            if sess is not None and stem:
                for h in (sess.get("heartbeats") or []):
                    ls = h.get("last_segment") or ""
                    if ("tmp-counting-" + stem) in ls:
                        try:
                            start_dt = _parse_iso(h.get("ts"))
                        except Exception:
                            start_dt = None
                        break
        elif start_ts:
            # The video line's `ts` is the FINALIZE (stop) time
            # (_utcnow_iso() in history.video(), called at
            # _finalize_recording). The recording STARTED `duration`
            # seconds before that. So the attribution timespan is
            # [ts - duration, ts], NOT [ts, ts + duration].
            try:
                finalize_dt = _parse_iso(start_ts)
            except Exception:
                finalize_dt = None
            if finalize_dt is not None and duration is not None:
                try:
                    end_dt = finalize_dt
                    start_dt = finalize_dt - datetime.timedelta(
                        seconds=float(duration))
                except Exception:
                    start_dt = None
                    end_dt = None
            else:
                start_dt = finalize_dt

        def _in_span(ts_str):
            if not ts_str or start_dt is None:
                return False
            if end_dt is None:
                return True
            try:
                t = _parse_iso(ts_str)
            except Exception:
                return False
            return start_dt <= t <= end_dt

        evs = []
        hbs = []
        if sess is not None:
            evs = [e for e in (sess.get("events") or [])
                   if _in_span(e.get("ts"))]
            hbs = [h for h in (sess.get("heartbeats") or [])
                   if _in_span(h.get("ts"))]
        # Directional counts from "crossed" events (direction LEFT
        # -> count_left_to_right, RIGHT -> count_right_to_left,
        # matching counting.py semantics).
        count_left = 0
        count_right = 0
        for e in evs:
            if e.get("event_type") == "crossed":
                d = (e.get("detail") or {}).get("direction")
                if d == "LEFT":
                    count_left += 1
                elif d == "RIGHT":
                    count_right += 1
        # Guard interventions by type + track_lost count.
        guard_types = ("reid_suppress", "mirror_suppress",
                       "mirror_guard_enforce", "mirror_candidate",
                       "resurrection", "id_switch_recovery",
                       "lost_buffer_expired")
        guard_interventions = {}
        track_lost = 0
        for e in evs:
            et = e.get("event_type", "")
            if et in guard_types:
                guard_interventions[et] = (
                    guard_interventions.get(et, 0) + 1)
            elif et == "track_lost":
                track_lost += 1
        # Perf/thermal: aggregate thermal zones + system from the
        # heartbeats that fell inside the video's timespan.
        thermal_samples = []
        cpu_loads = []
        mem_used = []
        disk_free = []
        for h in hbs:
            th = h.get("thermal") or {}
            if isinstance(th, dict) and th:
                for v in th.values():
                    try:
                        fv = float(v)
                        thermal_samples.append(
                            fv / 1000.0 if fv > 1000 else fv)
                    except Exception:
                        pass
            sy = h.get("system") or {}
            if isinstance(sy, dict):
                cl = sy.get("cpu_load_avg")
                if isinstance(cl, list) and cl:
                    try:
                        cpu_loads.append(float(cl[0]))
                    except Exception:
                        pass
                mu = sy.get("mem_used")
                if mu is not None:
                    try:
                        mem_used.append(float(mu))
                    except Exception:
                        pass
                df = sy.get("disk_free")
                if df is not None:
                    try:
                        disk_free.append(float(df))
                    except Exception:
                        pass

        def _agg(vals, fn):
            return fn(vals) if vals else None
        perf = {
            "thermal_avg": _agg(thermal_samples,
                                lambda v: round(sum(v) / len(v), 1)),
            "thermal_peak": _agg(thermal_samples,
                                 lambda v: round(max(v), 1)),
            "cpu_load_avg": _agg(cpu_loads,
                                 lambda v: round(sum(v) / len(v), 2)),
            "mem_used_avg": _agg(mem_used,
                                 lambda v: round(sum(v) / len(v), 2)),
            "disk_free_avg": _agg(disk_free,
                                  lambda v: round(sum(v) / len(v), 2)),
            "heartbeat_count": len(hbs),
        }
        return {
            "video_id": video_id,
            "filename": obj.get("filename"),
            "duration": duration,
            "file_duration": obj.get("file_duration"),
            "count_delta": obj.get("count_delta"),
            "session_id": sid,
            "ts": start_ts,
            "status": "running" if running else "ready",
            "count_left_to_right": count_left,
            "count_right_to_left": count_right,
            "guard_interventions": guard_interventions,
            "track_lost": track_lost,
            "events": evs,
            "perf": perf,
        }

# Parse the {YYYYMMDD-HHMMSS} timestamp stem out of a recording
# filename. Accepts tocompress-counting-{ts}-#N.mp4,
# tmp-counting-{ts}.mp4, and counting-{ts}-#N.mp4 (basename or full
# path). Returns the stem string or None when unparseable.
_VIDEO_STEM_RE = re.compile(r"counting-(\d{8}-\d{6})")


def _parse_video_stem(filename):
    if not filename or not isinstance(filename, str):
        return None
    m = _VIDEO_STEM_RE.search(filename)
    if m:
        return m.group(1)
    return None

# Cache of valid IANA timezone names (populated lazily on first use).
_VALID_TIMEZONES = None


def _valid_timezones():
    """Return the set of valid IANA timezone names from timedatectl."""
    global _VALID_TIMEZONES
    if _VALID_TIMEZONES is None:
        try:
            result = subprocess.run(
                ["timedatectl", "list-timezones"],
                check=True,
                capture_output=True,
                text=True,
            )
            _VALID_TIMEZONES = {
                line.strip()
                for line in result.stdout.splitlines()
                if line.strip()
            }
        except (subprocess.CalledProcessError, FileNotFoundError):
            # Fallback: if timedatectl is unavailable, accept any
            # non-empty string and let set-timezone reject bad ones.
            _VALID_TIMEZONES = None
    return _VALID_TIMEZONES


def _send_json(handler, status, payload):
    """Send a JSON response with the given HTTP status and dict."""
    body = json.dumps(payload).encode("utf-8")
    handler.send_response(status)
    handler.send_header("Content-Type", "application/json")
    handler.send_header("Content-Length", str(len(body)))
    handler.end_headers()
    handler.wfile.write(body)


def _serve_file_bytes(handler, file_path, content_type, label):
    """Serve a whole (small) file from disk as raw bytes with the given
    content type. No Range support (the snapshot JPEG is small). Sends
    `Cache-Control: no-store` so the app never serves a stale preview.
    404 JSON when the file is absent or unreadable.

    `label` is a short route tag for logging (e.g. "snapshot").
    """
    if not os.path.isfile(file_path):
        handler._log("GET /api/{} -> 404 (no file)".format(label))
        _send_json(handler, 404, {"error": "{} not available".format(label)})
        return
    try:
        with open(file_path, "rb") as fh:
            data = fh.read()
    except OSError as exc:
        handler._log("GET /api/{} -> 404 ({})".format(label, exc))
        _send_json(handler, 404, {"error": "{} not available".format(label)})
        return
    handler.send_response(200)
    handler.send_header("Content-Type", content_type)
    handler.send_header("Content-Length", str(len(data)))
    handler.send_header("Cache-Control", "no-store")
    handler.end_headers()
    handler.wfile.write(data)
    handler._log("GET /api/{} -> 200 ({} bytes)".format(label, len(data)))


def _serve_video_file(handler, vid):
    """Serve a compressed counting-<vid>-*.mp4 from FILES_DIR with
    HTTP Range / 206 partial streaming.

    `vid` is the video_id stem (e.g. "counting-20250608-100000")
    WITHOUT the #N delta suffix. The file is resolved by globbing
    `counting-<vid>-*.mp4` in FILES_DIR. 404 if absent; 416 on a
    malformed/unsatisfiable Range; 200 full file or 206 partial
    otherwise. Streams in _VIDEO_CHUNK chunks to avoid loading
    large files into memory. Single-range only (sufficient for the
    Android player's resumable download).
    """
    # Resolve the on-disk file via glob.
    # video_id already includes the 'counting-' prefix (it is
    # 'counting-{ts}'), so glob '<vid>-*.mp4' directly — do NOT
    # re-prepend 'counting-' or the pattern becomes 'counting-counting-…'.
    pattern = os.path.join(FILES_DIR, vid + "-*.mp4")
    matches = glob.glob(pattern)
    if not matches:
        handler._log("GET /api/video/{} -> 404".format(vid))
        _send_json(handler, 404, {"error": "video not found"})
        return
    # Defensive: if multiple matches (e.g. same-second double
    # finalize), pick the newest by mtime.
    if len(matches) > 1:
        matches.sort(
            key=lambda p: os.path.getmtime(p), reverse=True)
    file_path = matches[0]

    try:
        st = os.stat(file_path)
    except OSError:
        handler._log("GET /api/video/{} -> 404".format(vid))
        _send_json(handler, 404, {"error": "video not found"})
        return
    size = st.st_size

    range_hdr = handler.headers.get("Range")
    start = 0
    end = size - 1
    partial = False

    if range_hdr:
        # Parse a single "bytes=start-end" / "bytes=start-" range.
        m = re.match(r"^bytes=(\d*)-(\d*)$", range_hdr.strip())
        if m is None:
            # Malformed Range -> 416 with Content-Range */size.
            handler.send_response(416)
            handler.send_header("Content-Type", "video/mp4")
            handler.send_header("Accept-Ranges", "bytes")
            handler.send_header(
                "Content-Range", "bytes */{}".format(size))
            handler.send_header("Content-Length", "0")
            handler.end_headers()
            handler._log(
                "GET /api/video/{} -> 416 (bad range {!r})".format(
                    vid, range_hdr))
            return
        s_str, e_str = m.group(1), m.group(2)
        if s_str == "" and e_str == "":
            handler.send_response(416)
            handler.send_header("Content-Type", "video/mp4")
            handler.send_header("Accept-Ranges", "bytes")
            handler.send_header(
                "Content-Range", "bytes */{}".format(size))
            handler.send_header("Content-Length", "0")
            handler.end_headers()
            handler._log(
                "GET /api/video/{} -> 416 (empty range)".format(
                    vid))
            return
        if s_str == "":
            # Suffix range: last N bytes.
            n = int(e_str)
            if n <= 0:
                handler.send_response(416)
                handler.send_header("Content-Type", "video/mp4")
                handler.send_header("Accept-Ranges", "bytes")
                handler.send_header(
                    "Content-Range", "bytes */{}".format(size))
                handler.send_header("Content-Length", "0")
                handler.end_headers()
                handler._log(
                    "GET /api/video/{} -> 416 (bad suffix)".format(
                        vid))
                return
            start = max(0, size - n)
            end = size - 1
        else:
            start = int(s_str)
            end = int(e_str) if e_str != "" else size - 1
        if start >= size or start > end or end >= size:
            handler.send_response(416)
            handler.send_header("Content-Type", "video/mp4")
            handler.send_header("Accept-Ranges", "bytes")
            handler.send_header(
                "Content-Range", "bytes */{}".format(size))
            handler.send_header("Content-Length", "0")
            handler.end_headers()
            handler._log(
                "GET /api/video/{} -> 416 (out of range)".format(
                    vid))
            return
        partial = True

    length = end - start + 1
    handler.send_response(206 if partial else 200)
    handler.send_header("Content-Type", "video/mp4")
    handler.send_header("Accept-Ranges", "bytes")
    handler.send_header("Content-Length", str(length))
    if partial:
        handler.send_header(
            "Content-Range", "bytes {}-{}/{}".format(
                start, end, size))
    handler.end_headers()

    with open(file_path, "rb") as fh:
        fh.seek(start)
        remaining = length
        while remaining > 0:
            chunk = fh.read(min(_VIDEO_CHUNK, remaining))
            if not chunk:
                break
            handler.wfile.write(chunk)
            remaining -= len(chunk)

    handler._log(
        "GET /api/video/{} -> {} ({} bytes, file={})".format(
            vid, 206 if partial else 200, length,
            os.path.basename(file_path)))


def _read_json_body(handler):
    """Read + parse the request body as JSON. Returns (obj, None) on
    success or (None, error_message) on failure."""
    try:
        length = int(handler.headers.get("Content-Length", 0))
    except (TypeError, ValueError):
        return None, "invalid Content-Length header"
    if length <= 0:
        return None, "empty request body"
    try:
        raw = handler.rfile.read(length)
        return json.loads(raw), None
    except (json.JSONDecodeError, UnicodeDecodeError) as exc:
        return None, "malformed JSON: {}".format(exc)


def _load_runtime_settings():
    """Best-effort load of RUNTIME_SETTINGS_FILE. Returns the parsed
    dict, or {} if the file is absent or unreadable (a missing file
    is the normal state at boot — the counting app then falls back
    to os.getenv/defaults). Logs a warning on an unexpected read
    failure (absence is silent). Used by GET /api/settings and as
    the merge base for PUT /api/settings."""
    try:
        with open(RUNTIME_SETTINGS_FILE, "r") as fh:
            data = json.load(fh)
        if not isinstance(data, dict):
            sys.stderr.write(
                "[runtime-settings] not a JSON object: {}\n".format(
                    type(data).__name__))
            return {}
        return data
    except FileNotFoundError:
        return {}
    except (OSError, json.JSONDecodeError) as exc:
        sys.stderr.write(
            "[runtime-settings] read failed: {}\n".format(exc))
        return {}


def _load_model_classes():
    """Best-effort load of MODEL_CLASSES_FILE (BL-82, BL-78).

    Returns the parsed dict, or None if the file is absent or unreadable.
    Absence is the normal state before the countingapp has started /
    published the catalog (the app shows "catalog unavailable" and can
    retry). Logs a warning on an unexpected read failure (absence is
    silent). Used by GET /api/classes and by counting_class_ids validation
    in _validate_settings_payload."""
    try:
        with open(MODEL_CLASSES_FILE, "r") as fh:
            data = json.load(fh)
        if not isinstance(data, dict):
            sys.stderr.write(
                "[model-classes] not a JSON object: {}\n".format(
                    type(data).__name__))
            return None
        return data
    except FileNotFoundError:
        return None
    except (OSError, json.JSONDecodeError) as exc:
        sys.stderr.write(
            "[model-classes] read failed: {}\n".format(exc))
        return None


# BL-89: per-model (scene/camera/model-specific) vs global (user pref) runtime
# settings keys. Per-model keys live under runtime-settings.json `models.<name>`;
# global keys at the top level. The companion routes PUT keys accordingly and
# serves a flat resolved view (global + active model's per-model) on GET so
# the Android app's API stays flat.
_PER_MODEL_SETTINGS_KEYS = (
    "counting_class_ids",
    "counting_line_orientation",
    "offset_counting_line",
    "mask_zones",
)
_GLOBAL_SETTINGS_KEYS = (
    "draw_tracking",
    "box_tracking",
    "centroid_tracking",
    "draw_mask_zones",
    # BL-92 — counting direction toggles (global, top-level).
    "counting_direction_mode",
    "counting_direction",
)


def _active_model_name():
    """Best-effort read of the active model_name from model-classes.json (BL-89).

    Returns the model_name string, or None when the catalog is absent or has
    no model_name (legacy pre-BL-89 deploy). Used to route per-model settings.
    """
    mc = _load_model_classes()
    if mc and isinstance(mc.get("model_name"), str) and mc["model_name"]:
        return mc["model_name"]
    return None


def _resolve_settings_flat(data, model_name):
    """Return a flat settings dict: global keys + the active model's per-model
    keys + `model_name` (BL-89).

    Mirrors the countingapp's state.py::load_runtime_settings so the companion
    reports the SAME settings the countingapp will apply. When `data` has a
    `models` section + the active model has an entry, per-model keys come from
    there; otherwise (legacy flat, or active model absent) per-model keys come
    from the top level (backward compat — pre-BL-89 files keep working until
    the first PUT migrates them to `models.<active>`).
    """
    if not isinstance(data, dict):
        data = {}
    flat = {k: data[k] for k in _GLOBAL_SETTINGS_KEYS if k in data}
    models = data.get("models")
    if isinstance(models, dict) and models and model_name and isinstance(
            models.get(model_name), dict):
        pm = models[model_name]
        flat.update({k: pm[k] for k in _PER_MODEL_SETTINGS_KEYS if k in pm})
    else:
        # legacy flat layout (no models, or active model absent): per-model
        # keys from the top level (pre-BL-89 behavior).
        flat.update({k: data[k] for k in _PER_MODEL_SETTINGS_KEYS if k in data})
    if model_name:
        flat["model_name"] = model_name
    return flat


def _merge_settings_per_model(data, body, model_name):
    """Merge a flat PUT body into the settings dict with per-model routing.

    Global keys go to the top level. Per-model keys go to `models.<active>`;
    on the first per-model PUT of a legacy flat file, the existing flat
    per-model keys are migrated into `models.<active>` (and stripped from the
    top level) so the nested layout takes over cleanly. When no active
    model_name is known (no model-classes.json), per-model keys fall back to
    the top level (legacy behavior). Returns the mutated `data`.
    """
    if not isinstance(data, dict):
        data = {}
    # Global keys go to the top level.
    for k in _GLOBAL_SETTINGS_KEYS:
        if k in body:
            data[k] = body[k]
    # Per-model keys go to models.<active> (with one-time migration).
    pm_body = {k: body[k] for k in _PER_MODEL_SETTINGS_KEYS if k in body}
    if not pm_body:
        return data
    if not model_name:
        # No active model known — legacy flat top-level write.
        data.update(pm_body)
        return data
    models = data.get("models")
    if not isinstance(models, dict):
        # Migration: legacy flat file — nest the existing top-level per-model
        # keys under the active model, then strip them from the top level.
        legacy = {k: data[k] for k in _PER_MODEL_SETTINGS_KEYS if k in data}
        for k in _PER_MODEL_SETTINGS_KEYS:
            data.pop(k, None)
        models = {model_name: legacy} if legacy else {}
        data["models"] = models
    pm = models.get(model_name)
    if not isinstance(pm, dict):
        pm = {}
        models[model_name] = pm
    pm.update(pm_body)
    return data


def _resolve_counting_class_ids(settings, model_classes):
    """Resolve the effective counting_class_ids (BL-82).

    Mirrors the countingapp's state.py::resolve_counting_class_ids so the
    companion reports the SAME selection the countingapp will apply at the
    next recording start:
      1. settings['counting_class_ids'] override — a list of ints, each a
         valid index into model_classes['names']; invalid ids are dropped.
      2. Fallback to [model_classes['default_counting_class']] when the
         override is absent / empty / entirely invalid (or [1] when no
         catalog / no valid default).
    Returns a list of ints (never empty, never None). Never raises."""
    names = model_classes.get("names", []) if model_classes else []
    nc = len(names) if isinstance(names, list) else 0
    default = model_classes.get("default_counting_class") if model_classes else None

    raw = settings.get("counting_class_ids") if isinstance(settings, dict) else None
    if isinstance(raw, list) and raw:
        valid = []
        for cid in raw:
            if isinstance(cid, int) and not isinstance(cid, bool) and 0 <= cid < nc:
                valid.append(cid)
        if valid:
            return valid
    # Fallback to the model default (or legacy 1 when no valid default).
    fallback = (default if isinstance(default, int) and not isinstance(default, bool)
                and 0 <= default < nc else 1)
    return [fallback]


def _validate_settings_payload(payload):
    """Validate a settings dict for PUT /api/settings.

    Recognised keys (all optional — PATCH-like):
      - draw_tracking     : bool (strict, not a truthy string)
      - box_tracking      : bool
      - centroid_tracking : bool
      - offset_counting_line : signed int (loose sanity range
        -300..300, BL-83; the authoritative bound is the line staying
        inside the image with a 200px margin, clamped at use-time by
        the counting app)
      - counting_line_orientation : str "vertical" | "horizontal" (BL-83)
      - counting_direction_mode : str "auto" | "manual" (BL-92,
        global; default "auto")
      - counting_direction : str "up" | "down" | "left" | "right"
        | null (BL-92, manual only, default null). When non-null AND
        counting_line_orientation is present in the same PUT, a soft
        self-contained mismatch check runs (horizontal → up/down only,
        vertical → left/right only); otherwise the value passes through
        unchanged and the countingapp does the authoritative reject+WARN.
      - counting_class_ids   : list[int] (BL-82), each a valid class id
        (0..nc-1 of model-classes.json when available; else non-negative int)
      - mask_zones           : list of axis-aligned normalized rects
        {x,y,w,h} (each a non-bool number in [0..1], w>0, h>0,
        x+w<=1, y+h<=1; BL-88). Strict reject-all: any invalid rect
        rejects the whole PUT (no silent clamping), consistent with
        the offset/orientation rejection in BL-84.
      - draw_mask_zones      : bool (BL-88), overlays the saved zones
        on the counting app's display; same strict-bool pattern as
        draw_tracking.

    Returns (ok, errors). `ok` is True when every present key is
    valid; `errors` is a list of human-readable strings (empty when
    ok). Unknown keys are ignored (forward-compat). A non-bool
    truthy value (e.g. "true" string) is rejected so the counting
    app never has to re-coerce types."""
    errors = []
    if not isinstance(payload, dict):
        return False, ["payload must be a JSON object"]
    for key in ("draw_tracking", "box_tracking",
                "centroid_tracking"):
        if key in payload and not isinstance(payload[key], bool):
            errors.append(
                "{} must be a boolean, got {}".format(
                    key, type(payload[key]).__name__))
    if "offset_counting_line" in payload:
        val = payload["offset_counting_line"]
        # bool is a subclass of int — reject it explicitly so
        # `true` is not silently accepted as 1. BL-83: the offset is now
        # SIGNED (0 = centered; -N/+N = shift along the perpendicular axis).
        # The -300..300 cap only garbage-filters absurd values; the
        # authoritative bound (line inside the image, 200px margin) is
        # clamped at use-time by the counting app, where the frame size is
        # known. Existing 0..100 values stay valid (a sub-range).
        if isinstance(val, bool) or not isinstance(val, int):
            errors.append(
                "offset_counting_line must be an integer, got {}".format(
                    type(val).__name__))
        elif val < -300 or val > 300:
            errors.append(
                "offset_counting_line must be in [-300, 300], got {}".format(
                    val))
    if "counting_line_orientation" in payload:
        val = payload["counting_line_orientation"]
        # BL-83: string "vertical" | "horizontal". Reject bool (a bool is
        # not a str) and non-str so the counting app never re-coerces.
        if isinstance(val, bool) or not isinstance(val, str):
            errors.append(
                "counting_line_orientation must be a string, got {}".format(
                    type(val).__name__))
        elif val not in ("vertical", "horizontal"):
            errors.append(
                "counting_line_orientation must be 'vertical' or 'horizontal', "
                "got {!r}".format(val))
    if "counting_direction_mode" in payload:
        val = payload["counting_direction_mode"]
        # BL-92: string "auto" | "manual". Reject bool (a bool is not a
        # str) and non-str so the counting app never re-coerces, mirroring
        # the counting_line_orientation block.
        if isinstance(val, bool) or not isinstance(val, str):
            errors.append(
                "counting_direction_mode must be a string, got {}".format(
                    type(val).__name__))
        elif val not in ("auto", "manual"):
            errors.append(
                "counting_direction_mode must be 'auto' or 'manual', "
                "got {!r}".format(val))
    if "counting_direction" in payload:
        val = payload["counting_direction"]
        # BL-92: null is valid (manual not yet set / auto mode). Otherwise a
        # string "up" | "down" | "left" | "right". Reject bool/non-str so
        # the counting app never re-coerces.
        if val is not None:
            if isinstance(val, bool) or not isinstance(val, str):
                errors.append(
                    "counting_direction must be a string or null, "
                    "got {}".format(type(val).__name__))
            elif val not in ("up", "down", "left", "right"):
                errors.append(
                    "counting_direction must be 'up', 'down', 'left' or "
                    "'right' (or null), got {!r}".format(val))
    # BL-92 soft self-contained orientation-mismatch check: only runs
    # when BOTH counting_direction (non-null) and counting_line_orientation
    # are present in the SAME PUT payload (cheap, no extra file read).
    # Otherwise the value passes through unchanged and the authoritative
    # reject+WARN stays in the countingapp's main.py (per the contract).
    cd = payload.get("counting_direction")
    clo = payload.get("counting_line_orientation")
    if (cd is not None and isinstance(cd, str)
            and clo is not None and isinstance(clo, str)
            and cd in ("up", "down", "left", "right")
            and clo in ("vertical", "horizontal")):
        if clo == "horizontal" and cd not in ("up", "down"):
            errors.append(
                "counting_direction {!r} mismatches counting_line_orientation "
                "'horizontal' (expected 'up' or 'down')".format(cd))
        elif clo == "vertical" and cd not in ("left", "right"):
            errors.append(
                "counting_direction {!r} mismatches counting_line_orientation "
                "'vertical' (expected 'left' or 'right')".format(cd))
    if "counting_class_ids" in payload:
        val = payload["counting_class_ids"]
        if not isinstance(val, list):
            errors.append(
                "counting_class_ids must be a list of ints, got {}".format(
                    type(val).__name__))
        else:
            # Validate each id: a non-bool int. Range-check against the
            # model catalog when it is available so the companion rejects
            # unknown ids early (the countingapp would drop them with a
            # WARNING anyway); when the catalog is absent (countingapp not
            # started yet) we only require non-negative ints and let the
            # countingapp do the final range check at hot-reload time.
            catalog = _load_model_classes()
            nc = 0
            if catalog is not None:
                names = catalog.get("names", [])
                nc = len(names) if isinstance(names, list) else 0
            for cid in val:
                if isinstance(cid, bool) or not isinstance(cid, int):
                    errors.append(
                        "counting_class_ids must contain only ints, "
                        "got {}".format(type(cid).__name__))
                    break
                if cid < 0:
                    errors.append(
                        "counting_class_ids must be non-negative, "
                        "got {}".format(cid))
                    break
                if nc > 0 and cid >= nc:
                    errors.append(
                        "counting_class_ids id {} is out of range "
                        "(valid 0..{})".format(cid, nc - 1))
                    break
    if "draw_mask_zones" in payload and not isinstance(
            payload["draw_mask_zones"], bool):
        errors.append(
            "draw_mask_zones must be a boolean, got {}".format(
                type(payload["draw_mask_zones"]).__name__))
    if "mask_zones" in payload:
        val = payload["mask_zones"]
        if not isinstance(val, list):
            errors.append(
                "mask_zones must be a list of objects, got {}".format(
                    type(val).__name__))
        else:
            # Each element must be a dict {x,y,w,h} of non-bool
            # numbers in [0..1] with w>0, h>0, x+w<=1, y+h<=1.
            # Strict reject-all: a single invalid rect rejects the
            # whole PUT (no silent clamping), matching the IPC
            # contract (BL-87/BL-88) so the companion never accepts
            # a value the counting app would drop.
            for i, rect in enumerate(val):
                if not isinstance(rect, dict):
                    errors.append(
                        "mask_zones[{}] must be an object, got {}".format(
                            i, type(rect).__name__))
                    break
                ok_rect = True
                for field in ("x", "y", "w", "h"):
                    fv = rect.get(field)
                    if field not in rect:
                        errors.append(
                            "mask_zones[{}] missing field '{}'".format(
                                i, field))
                        ok_rect = False
                        break
                    # bool is a subclass of int — reject it so
                    # `true` is not silently accepted as 1.
                    if isinstance(fv, bool) or not isinstance(fv, (int, float)):
                        errors.append(
                            "mask_zones[{}].{} must be a number, got {}".format(
                                i, field, type(fv).__name__))
                        ok_rect = False
                        break
                    if not (0 <= fv <= 1):
                        errors.append(
                            "mask_zones[{}].{} must be in [0, 1], got {}".format(
                                i, field, fv))
                        ok_rect = False
                        break
                if not ok_rect:
                    break
                x, y, w, h = (
                    float(rect["x"]), float(rect["y"]),
                    float(rect["w"]), float(rect["h"]))
                if w <= 0:
                    errors.append(
                        "mask_zones[{}].w must be > 0, got {}".format(
                            i, w))
                    break
                if h <= 0:
                    errors.append(
                        "mask_zones[{}].h must be > 0, got {}".format(
                            i, h))
                    break
                if x + w > 1:
                    errors.append(
                        "mask_zones[{}] x+w must be <= 1, got {}".format(
                            i, x + w))
                    break
                if y + h > 1:
                    errors.append(
                        "mask_zones[{}] y+h must be <= 1, got {}".format(
                            i, y + h))
                    break
    return (len(errors) == 0), errors


def _apply_time(time_str, tz):
    """Run the timedatectl sequence to set the clock + timezone.

    Always disables NTP first so systemd-timesyncd does not reject or
    immediately overwrite the manual time. Then, if the DS3231 hardware
    RTC (BL-74) is registered, persist the new time into it with
    `hwclock --systohc --rtc=/dev/rtcN` so the correction survives a
    reboot (otherwise the DS3231 would reset the system clock back at
    the next boot). Returns (ok, error_msg).
    """
    steps = [
        ["timedatectl", "set-ntp", "false"],
        ["timedatectl", "set-time", time_str],
        ["timedatectl", "set-timezone", tz],
    ]
    for cmd in steps:
        result = subprocess.run(
            cmd,
            capture_output=True,
            text=True,
        )
        if result.returncode != 0:
            err = result.stderr.strip() or result.stdout.strip()
            return False, "'{}' failed: {}".format(
                " ".join(cmd), err
            )
    _persist_to_ds3231()  # best-effort: keep the DS3231 in sync with the system clock
    return True, None


def _persist_to_ds3231():
    """Best-effort: copy the system clock into the DS3231 hardware RTC
    (BL-74) so a manual time sync survives a reboot. No-op if the
    DS3231 is absent (the RTC then just isn't updated; the next boot
    sets the system clock from whatever the DS3231 still holds).
    """
    for name_path in glob.glob("/sys/class/rtc/*/device/name"):
        try:
            with open(name_path) as f:
                if f.read().strip() != "ds1307":
                    continue
            # name_path = /sys/class/rtc/rtcN/device/name -> rtcN
            rtc_dev = "/dev/" + name_path.split("/")[4]
            subprocess.run(
                ["hwclock", "--systohc", "--rtc=" + rtc_dev],
                capture_output=True,
                text=True,
            )
        except OSError:
            continue


class CompanionHandler(BaseHTTPRequestHandler):
    """HTTP request handler for the companion clock-sync service."""

    # Quieter access log prefix in journald.
    def log_message(self, fmt, *args):
        sys.stdout.write(
            "{} - - {}\n".format(self.address_string(), fmt % args)
        )
        sys.stdout.flush()

    def _log(self, message):
        sys.stdout.write("[{}] {}\n".format(
            datetime.datetime.now().isoformat(), message))
        sys.stdout.flush()

    def do_GET(self):
        parsed = urlparse(self.path)
        path = parsed.path
        qs = parse_qs(parsed.query)

        if path == "/api/identify":
            payload = {
                "service": SERVICE_NAME,
                "version": SERVICE_VERSION,
            }
            self._log("GET /api/identify -> 200")
            _send_json(self, 200, payload)
            return

        if path == "/api/snapshot":
            # BL-88: serve the countingapp's periodic camera preview
            # JPEG (written to /files/snapshot.jpg on the hostPath)
            # read-only. The app fetches it to draw mask zones on top
            # of the live frame. 404 when the countingapp has not yet
            # written a snapshot (app shows "Aperçu pas encore
            # disponible" + retry). no-store so a stale preview is
            # never cached by the app or an intermediary.
            _serve_file_bytes(
                self,
                os.path.join(FILES_DIR, "snapshot.jpg"),
                "image/jpeg",
                "snapshot",
            )
            return

        if path == "/api/settings":
            # Hot-path runtime toggles shared with the counting
            # app via the hostPath /conf volume. Always returns an
            # object — empty {} when no file has been written yet
            # (normal at boot; the app falls back to os.getenv). BL-89:
            # returns a FLAT resolved view (global + active model's
            # per-model keys + `model_name`) so the Android app's API
            # stays flat while storage is per-model.
            payload = _resolve_settings_flat(_load_runtime_settings(),
                                             _active_model_name())
            self._log("GET /api/settings -> 200")
            _send_json(self, 200, payload)
            return

        if path == "/api/classes":
            # BL-82: countable species catalog (read-only model-classes.json,
            # published by the countingapp at startup) + the current
            # counting_class_ids selection (from runtime-settings.json,
            # resolved the same way the countingapp will at the next
            # recording start). 404 when the catalog is not yet published
            # (countingapp not started / write pending) so the app shows
            # "catalog unavailable" and can retry.
            catalog = _load_model_classes()
            if catalog is None:
                self._log("GET /api/classes -> 404 (no model-classes.json)")
                _send_json(self, 404, {
                    "error": "model-classes catalog not published yet",
                })
                return
            names = catalog.get("names", [])
            if not isinstance(names, list):
                names = []
            classes = [
                {"id": i, "name": str(name)}
                for i, name in enumerate(names)
            ]
            model_name = catalog.get("model_name")
            settings = _resolve_settings_flat(_load_runtime_settings(),
                                              model_name)
            selected = _resolve_counting_class_ids(settings, catalog)
            payload = {
                "model_version": catalog.get("model_version"),
                "model_name": model_name,
                "nc": len(classes),
                "classes": classes,
                "default_counting_class": catalog.get(
                    "default_counting_class"),
                "counting_class_ids": selected,
            }
            self._log("GET /api/classes -> 200 (nc={}, selected={})".format(
                len(classes), selected))
            _send_json(self, 200, payload)
            return

        if path == "/api/count":
            # Live counting state (absorbed BL-66 scope). Reads the
            # newest heartbeat from the JSONL via the cached index —
            # no separate state file, single source of truth.
            idx = _get_index()
            hb = idx.latest_count()
            if hb is None:
                payload = {
                    "count": 0,
                    "status": None,
                    "auto_mode": None,
                    "timestamp": None,
                    "session_id": None,
                }
            else:
                payload = {
                    "count": hb.get("count", 0),
                    "status": hb.get("status"),
                    "auto_mode": hb.get("auto_mode"),
                    "timestamp": hb.get("ts"),
                    "session_id": hb.get("session_id"),
                }
            self._log("GET /api/count -> 200")
            _send_json(self, 200, payload)
            return

        if path == "/api/sessions":
            limit = _int_arg(qs, "limit", 50)
            offset = _int_arg(qs, "offset", 0)
            idx = _get_index()
            summaries, total = idx.session_summaries(
                limit=limit, offset=offset)
            self._log(
                "GET /api/sessions -> 200 ({} of {})".format(
                    len(summaries), total))
            _send_json(self, 200, {
                "sessions": summaries,
                "limit": limit,
                "offset": offset,
                "total": total,
            })
            return

        if path == "/api/summary":
            days = _int_arg(qs, "days", 7)
            idx = _get_index()
            daily = idx.daily_summary(days=days)
            self._log("GET /api/summary -> 200")
            _send_json(self, 200, {"days": days, "daily": daily})
            return

        if path == "/api/videos":
            limit = _int_arg(qs, "limit", 50)
            offset = _int_arg(qs, "offset", 0)
            idx = _get_index()
            rows, total = idx.video_summaries(
                limit=limit, offset=offset)
            running = idx._running_video_row()
            if running is not None:
                # The running recording is always index 0 and is
                # excluded from the pagination offset math — total
                # includes it, and offset=0 returns it first.
                rows = [running] + rows
                total += 1
            self._log(
                "GET /api/videos -> 200 ({} of {})".format(
                    len(rows), total))
            _send_json(self, 200, {
                "videos": rows,
                "limit": limit,
                "offset": offset,
                "total": total,
            })
            return

        if path.startswith("/api/videos/"):
            # Per-video detail (metadata JSON): directional counts,
            # guard interventions, track_lost, events timeline,
            # perf/thermal — attributed by timespan. Distinct from
            # /api/video/<id> (singular, streams the MP4 bytes).
            vid = path[len("/api/videos/"):]
            if not vid:
                self._log("GET {} -> 404".format(path))
                _send_json(self, 404, {"error": "missing video id"})
                return
            idx = _get_index()
            detail = idx.video_detail(vid)
            if detail is None:
                self._log("GET /api/videos/{} -> 404".format(vid))
                _send_json(self, 404, {"error": "video not found"})
                return
            self._log("GET /api/videos/{} -> 200".format(vid))
            _send_json(self, 200, detail)
            return

        if path.startswith("/api/video/"):
            # Range-streamed compressed MP4 for a finalized video.
            # Must be checked AFTER the exact "/api/videos" match so
            # the plural list endpoint is never shadowed (the trailing
            # slash in the prefix means "/api/videos" would not match
            # startswith("/api/video/") anyway, but ordering removes
            # all doubt).
            vid = path[len("/api/video/"):]
            # Strip any trailing query-like cruft defensively.
            if not vid:
                self._log("GET {} -> 404".format(path))
                _send_json(self, 404, {"error": "missing video id"})
                return
            try:
                _serve_video_file(self, vid)
            except Exception as exc:  # best-effort, never kill thread
                self._log(
                    "GET /api/video/{} -> 500 ({})".format(
                        vid, exc))
                try:
                    _send_json(self, 500, {"error": str(exc)})
                except Exception:
                    pass
            return

        if path.startswith("/api/sessions/"):
            sid = path[len("/api/sessions/"):]
            if not sid:
                self._log("GET {} -> 404".format(path))
                _send_json(self, 404, {"error": "missing session id"})
                return
            idx = _get_index()
            detail = idx.session_detail(sid)
            if detail is None:
                self._log("GET /api/sessions/{} -> 404".format(sid))
                _send_json(self, 404, {"error": "session not found"})
                return
            self._log("GET /api/sessions/{} -> 200".format(sid))
            _send_json(self, 200, detail)
            return

        if path == "/api/startups":
            limit = _int_arg(qs, "limit", 50)
            idx = _get_index()
            ups = idx.startups(limit=limit)
            self._log("GET /api/startups -> 200 ({})".format(len(ups)))
            _send_json(self, 200, {"startups": ups})
            return

        self._log("GET {} -> 404".format(self.path))
        _send_json(self, 404, {"error": "not found"})

    def do_PUT(self):
        # PATCH-like write of runtime settings shared with the
        # counting app via the hostPath /files volume. Only the keys
        # present in the body are overwritten; the rest of the
        # existing file is preserved. The merged object is written
        # atomically (temp file + os.replace) so the counting app
        # never observes a half-written file.
        if self.path != "/api/settings":
            self._log("PUT {} -> 404".format(self.path))
            _send_json(self, 404, {"error": "not found"})
            return

        body, err = _read_json_body(self)
        if err is not None:
            self._log("PUT /api/settings -> 400 ({})".format(err))
            _send_json(self, 400, {"error": err})
            return

        ok, errors = _validate_settings_payload(body)
        if not ok:
            msg = "; ".join(errors)
            self._log("PUT /api/settings -> 400 ({})".format(msg))
            _send_json(self, 400, {"errors": errors})
            return

        # Merge the incoming keys into the existing settings
        # (PATCH-like) with BL-89 per-model routing: global keys to the
        # top level, per-model keys to `models.<active_model>` (with a
        # one-time migration of legacy flat per-model keys).
        merged = _load_runtime_settings()
        model_name = _active_model_name()
        merged = _merge_settings_per_model(merged, body, model_name)

        # Atomic write: temp file in the same dir, then os.replace
        # so a crash mid-write never leaves a partial JSON file
        # (the counting app reads best-effort on the next video).
        try:
            _ensure_conf_dir()
            tmp_path = RUNTIME_SETTINGS_FILE + ".tmp"
            with open(tmp_path, "w") as fh:
                json.dump(merged, fh, indent=2, sort_keys=True)
            os.replace(tmp_path, RUNTIME_SETTINGS_FILE)
        except OSError as exc:
            self._log("PUT /api/settings -> 500 ({})".format(exc))
            _send_json(self, 500, {"error": str(exc)})
            return

        # Respond with the flat resolved view (active model) so the app
        # sees the effective settings, not the nested storage.
        flat = _resolve_settings_flat(merged, model_name)
        self._log("PUT /api/settings -> 200 ({})".format(flat))
        _send_json(self, 200, flat)

    def do_POST(self):
        # /api/power: write the .arret_requested sentinel so the
        # counting app (DisplayThread.run polls it) triggers the
        # BL-62 finalize -> stop -> nsenter poweroff sequence. We do
        # NOT execute the poweroff here: the endpoint only writes
        # the sentinel and replies 200, the counting app consumes
        # it. An optional JSON body (e.g. {"action":"poweroff"}) is
        # accepted and ignored. Any pre-existing sentinel is removed
        # first so a stale file is replaced with a fresh timestamp
        # (anti-stale guard handled on the app side via mtime).
        if self.path == "/api/power":
            # Accept and ignore an optional JSON body. A malformed
            # body is not fatal here: the action is the sentinel
            # write, which does not depend on the body content.
            _read_json_body(self)  # best-effort, ignore errors

            try:
                _ensure_conf_dir()
                if os.path.exists(POWER_SENTINEL_FILE):
                    os.remove(POWER_SENTINEL_FILE)
                tmp_path = POWER_SENTINEL_FILE + ".tmp"
                with open(tmp_path, "w") as fh:
                    fh.write(datetime.datetime.now().isoformat())
                os.replace(tmp_path, POWER_SENTINEL_FILE)
            except OSError as exc:
                self._log("POST /api/power -> 500 ({})".format(exc))
                _send_json(self, 500, {"error": str(exc)})
                return

            self._log("POST /api/power -> 200 (sentinel written)")
            _send_json(self, 200, {"status": "poweroff_requested"})
            return

        if self.path != "/api/time":
            self._log("POST {} -> 404".format(self.path))
            _send_json(self, 404, {"error": "not found"})
            return

        body, err = _read_json_body(self)
        if err is not None:
            self._log("POST /api/time -> 400 ({})".format(err))
            _send_json(self, 400, {"error": err})
            return

        time_str = body.get("time")
        tz = body.get("tz")
        if not time_str or not isinstance(time_str, str):
            msg = "missing or invalid 'time' field"
            self._log("POST /api/time -> 400 ({})".format(msg))
            _send_json(self, 400, {"error": msg})
            return
        if not tz or not isinstance(tz, str):
            msg = "missing or invalid 'tz' field"
            self._log("POST /api/time -> 400 ({})".format(msg))
            _send_json(self, 400, {"error": msg})
            return

        # Validate the time is parseable ISO8601.
        try:
            parsed_dt = datetime.datetime.fromisoformat(time_str)
        except ValueError:
            msg = "invalid ISO8601 time: {!r}".format(time_str)
            self._log("POST /api/time -> 400 ({})".format(msg))
            _send_json(self, 400, {"error": msg})
            return

        # Strip timezone offset and reformat for timedatectl, which
        # rejects ISO8601 strings with a +HH:MM suffix (e.g. the
        # output of `date -Iseconds`). We set the timezone separately
        # via set-timezone, so the naive local-time string is correct.
        local_time_str = parsed_dt.replace(tzinfo=None).strftime(
            "%Y-%m-%d %H:%M:%S")

        # Validate the timezone is a known IANA name.
        known = _valid_timezones()
        if known is not None and tz not in known:
            msg = "unknown timezone: {!r}".format(tz)
            self._log("POST /api/time -> 400 ({})".format(msg))
            _send_json(self, 400, {"error": msg})
            return

        # Apply via timedatectl (shell=False, argument-list form).
        ok, apply_err = _apply_time(local_time_str, tz)
        if not ok:
            self._log("POST /api/time -> 500 ({})".format(apply_err))
            _send_json(self, 500, {"error": apply_err})
            return

        payload = {
            "status": "ok",
            "time": time_str,
            "tz": tz,
        }
        self._log("POST /api/time -> 200 (time={}, tz={})".format(
            time_str, tz))
        _send_json(self, 200, payload)


def main():
    port = int(os.environ.get("COMPANION_PORT", DEFAULT_PORT))
    server = ThreadingHTTPServer((HOST, port), CompanionHandler)
    print("[{}] jetson-companion listening on {}:{}".format(
        datetime.datetime.now().isoformat(), HOST, port))
    sys.stdout.flush()
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
