"""
BL-68 companion history reader — importable stdlib mirror.

This module is a **faithful stdlib-only copy** of the ``HistoryIndex`` reader
(and the small helpers it depends on) that is inlined in the Ansible playbook
``ansible/playbooks/system/configure_companion.yml`` (the
``jetson-companion`` host service, BL-64 / BL-68). It exists so the read-only
JSONL indexing/pagination/detail/summary/startups logic can be unit-tested
without importing the inline playbook script (which is not a normal import
target).

**Keep in sync.** Any change to the reader logic in the playbook MUST be
mirrored here (and vice versa). The test ``tests/test_companion_history_api.py``
imports from this module, so this is the copy under test.

Only the Python standard library is used, matching the deployed service.
"""

import datetime
import json
import os


# Event types counted as "guard" events in the daily summary.
# (Mirror of ``_GUARD_EVENT_TYPES`` in companion/jetson-companion.py.)
_GUARD_EVENT_TYPES = frozenset({
    "mirror_guard", "mirror_guard_enforce", "mirror_suppress",
    "reid_suppress", "lost_buffer_expired", "resurrection",
    "id_switch_recovery",
})


def _parse_iso(ts):
    """Parse an ISO-8601 timestamp into an aware datetime.

    Tolerates a trailing 'Z' and naive timestamps (assumed UTC). Raises
    ValueError on unparseable input. (Mirror of the playbook helper.)
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


class HistoryIndex:
    """Lazy, read-only in-memory index over the counting-history JSONL.

    Scans the file once on first use, builds a per-session map plus a
    startup list, and caches it. The cache is invalidated when the file
    size or mtime changes (so a growing JSONL is re-indexed on the next
    request). The reader NEVER writes to the JSONL; it only reads. A
    partial/truncated last line (power cut mid-append) is skipped.

    Session ordering is by ``session_start.start_at`` descending (newest
    first), which is what ``/api/history`` paginates over.
    """

    def __init__(self, path):
        self.path = path
        self._size = -1
        self._mtime = 0.0
        self._sessions = {}
        self._session_order = []
        self._startups = []
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
        latest_hb = None
        try:
            f = open(self.path, "rb")
        except FileNotFoundError:
            self._sessions = {}
            self._session_order = []
            self._startups = []
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
                    # Track globally-newest heartbeat for /api/count
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
                    # Compaction output: a collapsed session. Use it as the
                    # start/end/count proxy when raw lines are gone.
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
        self._sessions = sessions
        self._session_order = order
        self._startups = startups
        self._latest_hb = latest_hb
        try:
            self._size = os.path.getsize(self.path)
            self._mtime = os.path.getmtime(self.path)
        except OSError:
            pass

    def latest_count(self):
        """Newest heartbeat across all sessions, for /api/count.

        Returns the heartbeat dict or None when no heartbeat exists."""
        self._maybe_rebuild()
        return self._latest_hb

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
        return {
            "session_id": sid,
            "start": start,
            "end": sess.get("end"),
            "end_at": end_at,
            "end_reason": end.get("end_reason"),
            "status": "ended" if sess.get("end") else "running",
            "net_count": sess.get("count"),
            "config": cfg,
            "heartbeats": sess.get("heartbeats") or [],
            "events": sess.get("events") or [],
            "significant_events": sess.get("significant_events"),
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