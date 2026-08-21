"""Unit tests for the `GET /api/snapshot` file-serving helper
(`_serve_file_bytes` in companion/jetson-companion.py).

Covers:
  - 200 `image/jpeg` with `Content-Length` + `Cache-Control: no-store` when
    the snapshot JPEG exists (a small temp JPEG is written).
  - 404 JSON when the file is absent.

Keeps stdlib-only (no `requests`): the handler is faked with a minimal
in-memory recorder mirroring the `BaseHTTPRequestHandler` surface used by
`_serve_file_bytes` (`send_response`/`send_header`/`end_headers`/`wfile`).
"""
import importlib.util
import io
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
serve_file_bytes = _jc._serve_file_bytes


class _FakeHandler:
    """Minimal stand-in for the BaseHTTPRequestHandler surface used by
    `_serve_file_bytes`. Records the status, headers and body bytes."""

    def __init__(self):
        self.status = None
        self.headers = []
        self.wfile = io.BytesIO()
        self.logs = []

    # --- handler surface -------------------------------------------------

    def send_response(self, code):
        self.status = code

    def send_header(self, name, value):
        self.headers.append((name, str(value)))

    def end_headers(self):
        # Real BaseHTTPRequestHandler ends the header block; nothing to
        # record here (the body is written separately via wfile).
        pass

    def _log(self, msg):
        self.logs.append(msg)

    # --- helpers for assertions -----------------------------------------

    def header(self, name):
        for k, v in self.headers:
            if k == name:
                return v
        return None

    @property
    def body(self):
        return self.wfile.getvalue()


def test_snapshot_served_when_present():
    with tempfile.TemporaryDirectory() as d:
        jpg = os.path.join(d, "snapshot.jpg")
        payload = b"\xff\xd8\xff\xe0fake-jpeg-bytes\xff\xd9"
        with open(jpg, "wb") as fh:
            fh.write(payload)

        h = _FakeHandler()
        serve_file_bytes(h, jpg, "image/jpeg", "snapshot")

        assert h.status == 200
        assert h.header("Content-Type") == "image/jpeg"
        assert h.header("Content-Length") == str(len(payload))
        assert h.header("Cache-Control") == "no-store"
        assert h.body == payload


def test_snapshot_404_when_absent():
    with tempfile.TemporaryDirectory() as d:
        jpg = os.path.join(d, "snapshot.jpg")  # never written
        h = _FakeHandler()
        serve_file_bytes(h, jpg, "image/jpeg", "snapshot")

        assert h.status == 404
        # The 404 body is JSON produced by `_send_json`.
        body = json.loads(h.body.decode("utf-8"))
        assert "error" in body
        assert h.header("Cache-Control") is None