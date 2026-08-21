"""Unit tests for BL-85: directional aggregation in `video_detail`
for horizontal-line sessions (direction UP/DOWN), additive alongside the
existing vertical LEFT/RIGHT.

The countingapp (BL-83, sister repo) emits `direction = LEFT/RIGHT` for a
vertical counting line and `UP/DOWN` for a horizontal one. The companion's
`HistoryIndex.video_detail` must aggregate both and surface
`counting_line_orientation` (resolved from the session_start metadata,
default "vertical") so the app can pick orientation-aware labels.

These tests build a synthetic counting-history.jsonl in a tmp file and drive
the real HistoryIndex (no HTTP layer) to verify the aggregation end-to-end.
"""
import importlib.util
import json
import os
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
COMPANION = os.path.join(HERE, "..", "companion", "jetson-companion.py")


def _load():
    spec = importlib.util.spec_from_file_location("jc", COMPANION)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


_jc = _load()
HistoryIndex = _jc.HistoryIndex


def _write_jsonl(path, lines):
    with open(path, "w") as fh:
        for ln in lines:
            fh.write(json.dumps(ln) + "\n")


def _video_line(sid, vid, count_delta):
    return {
        "type": "video",
        "session_id": sid,
        "video_id": vid,
        "filename": f"counting-20250101-100000-#{count_delta}.mp4",
        "duration": 60,
        "file_duration": 60,
        "count_delta": count_delta,
        "ts": "2025-01-01T10:01:00Z",
    }


def _crossed_event(sid, direction, ts):
    return {
        "type": "event",
        "session_id": sid,
        "event_type": "crossed",
        "detail": {"direction": direction, "track_id": 1},
        "ts": ts,
    }


def _session_start(sid, orientation=None):
    line = {
        "type": "session_start",
        "session_id": sid,
        "prev_session_id": None,
        "start_at": "2025-01-01T10:00:00Z",
        "start_reason": "manual",
        "status": "running",
        "config": {"image_tag": "test"},
        "ts": "2025-01-01T10:00:00Z",
    }
    if orientation is not None:
        # BL-83: additive top-level keys (as the countingapp writes them).
        line["counting_line_orientation"] = orientation
        line["offset_counting_line"] = 0
    return line


def _session_end(sid):
    return {
        "type": "session_end",
        "session_id": sid,
        "end_at": "2025-01-01T10:02:00Z",
        "end_reason": "manual",
        "ts": "2025-01-01T10:02:00Z",
    }


def _make_index(tmpdir, lines):
    path = os.path.join(tmpdir, "counting-history.jsonl")
    _write_jsonl(path, lines)
    return HistoryIndex(path)


def test_vertical_session_left_right_aggregated(tmp_path):
    sid = "sess-vertical"
    lines = [
        _session_start(sid),  # no orientation -> default "vertical"
        _crossed_event(sid, "LEFT", "2025-01-01T10:00:30Z"),
        _crossed_event(sid, "LEFT", "2025-01-01T10:00:35Z"),
        _crossed_event(sid, "RIGHT", "2025-01-01T10:00:40Z"),
        _video_line(sid, "v1", 1),
        _session_end(sid),
    ]
    idx = _make_index(str(tmp_path), lines)
    detail = idx.video_detail("v1")
    assert detail is not None
    assert detail["count_left_to_right"] == 2
    assert detail["count_right_to_left"] == 1
    # Vertical session -> horizontal counts are zero.
    assert detail["count_down_to_up"] == 0
    assert detail["count_up_to_down"] == 0
    # Orientation surfaced (default vertical for pre-BL-83 session_start).
    assert detail["counting_line_orientation"] == "vertical"


def test_horizontal_session_up_down_aggregated(tmp_path):
    sid = "sess-horizontal"
    lines = [
        _session_start(sid, orientation="horizontal"),
        _crossed_event(sid, "UP", "2025-01-01T10:00:30Z"),
        _crossed_event(sid, "UP", "2025-01-01T10:00:35Z"),
        _crossed_event(sid, "DOWN", "2025-01-01T10:00:40Z"),
        _crossed_event(sid, "DOWN", "2025-01-01T10:00:45Z"),
        _crossed_event(sid, "DOWN", "2025-01-01T10:00:50Z"),
        _video_line(sid, "h1", -1),
        _session_end(sid),
    ]
    idx = _make_index(str(tmp_path), lines)
    detail = idx.video_detail("h1")
    assert detail is not None
    # Horizontal session -> vertical counts are zero.
    assert detail["count_left_to_right"] == 0
    assert detail["count_right_to_left"] == 0
    # UP/DOWN aggregated (UP = +1 = down->up; DOWN = -1 = up->down).
    assert detail["count_down_to_up"] == 2
    assert detail["count_up_to_down"] == 3
    assert detail["counting_line_orientation"] == "horizontal"


def test_invalid_orientation_falls_back_vertical(tmp_path):
    sid = "sess-bad-orient"
    lines = [
        {**_session_start(sid), "counting_line_orientation": "diagonal"},
        _crossed_event(sid, "LEFT", "2025-01-01T10:00:30Z"),
        _video_line(sid, "b1", 1),
        _session_end(sid),
    ]
    idx = _make_index(str(tmp_path), lines)
    detail = idx.video_detail("b1")
    assert detail is not None
    # Invalid orientation -> fallback "vertical".
    assert detail["counting_line_orientation"] == "vertical"
    assert detail["count_left_to_right"] == 1


def test_no_crossed_events_all_zero(tmp_path):
    sid = "sess-empty"
    lines = [
        _session_start(sid, orientation="horizontal"),
        _video_line(sid, "e1", 0),
        _session_end(sid),
    ]
    idx = _make_index(str(tmp_path), lines)
    detail = idx.video_detail("e1")
    assert detail is not None
    assert detail["count_left_to_right"] == 0
    assert detail["count_right_to_left"] == 0
    assert detail["count_down_to_up"] == 0
    assert detail["count_up_to_down"] == 0
    assert detail["counting_line_orientation"] == "horizontal"


def test_running_recording_unknown_video_returns_none(tmp_path):
    sid = "sess-x"
    lines = [_session_start(sid)]
    idx = _make_index(str(tmp_path), lines)
    # No running row synthesized (no tmp-counting heartbeat) -> None.
    assert idx.video_detail("does-not-exist") is None