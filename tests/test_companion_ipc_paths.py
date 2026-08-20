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

"""
BL-80 unit tests for the companion IPC path split (/files data vs /conf control).

The companion used to derive ``RUNTIME_SETTINGS_FILE`` + ``POWER_SENTINEL_FILE``
from ``FILES_DIR`` (the ``/files`` hostPath). BL-79 (sister repo) split
config/control files into a separate ``/conf`` hostPath; BL-80 aligns the
companion so it writes ``runtime-settings.json`` + ``.arret_requested`` to
``/conf`` while ``HISTORY_FILE`` + ``FILES_DIR`` stay on ``/files``.

These tests load ``companion/jetson-companion.py`` fresh (importlib, not
registered in ``sys.modules``) so each load reads the *current* env at exec
time, then assert:

  * defaults: HISTORY_FILE/FILES_DIR on ``/data/orin/files``;
    CONF_DIR + RUNTIME_SETTINGS_FILE + POWER_SENTINEL_FILE on ``/data/orin/conf``
  * env override: CONF_DIR_HOST + HISTORY_FILE_HOST are honoured
  * ``_ensure_conf_dir()`` creates CONF_DIR if absent (lazy, before first write)

The script's ``main()`` (server start) is guarded by ``if __name__ == "__main__"``
so importing it has no side effects (no server, no makedirs at import time).
"""

import importlib.util
import os
import shutil
import sys

import pytest

_COMPANION_SCRIPT = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "companion",
    "jetson-companion.py",
)


def _load_companion():
    """Load companion/jetson-companion.py as a FRESH module (no sys.modules cache).

    A fresh module is built each call so the module-level ``os.environ.get``
    calls (HISTORY_FILE_HOST, CONF_DIR_HOST) read the env as set by the test
    via monkeypatch at exec time. The module is intentionally NOT registered
    in ``sys.modules`` to avoid caching across loads.
    """
    spec = importlib.util.spec_from_file_location(
        "jetson_companion_under_test", _COMPANION_SCRIPT
    )
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)  # runs top-level: defines constants from env
    return mod


def test_default_paths(monkeypatch):
    """Defaults: history on /files, control files on /conf (BL-80)."""
    monkeypatch.delenv("HISTORY_FILE_HOST", raising=False)
    monkeypatch.delenv("CONF_DIR_HOST", raising=False)
    mod = _load_companion()

    # DATA stays on /files
    assert mod.HISTORY_FILE == "/data/orin/files/counting-history.jsonl"
    assert mod.FILES_DIR == "/data/orin/files"
    # CONFIG/CONTROL moved to /conf (BL-79/BL-80)
    assert mod.CONF_DIR == "/data/orin/conf"
    assert mod.RUNTIME_SETTINGS_FILE == "/data/orin/conf/runtime-settings.json"
    assert mod.POWER_SENTINEL_FILE == "/data/orin/conf/.arret_requested"


def test_env_overrides(monkeypatch):
    """CONF_DIR_HOST + HISTORY_FILE_HOST are honoured (mirrors HISTORY_FILE_HOST)."""
    monkeypatch.setenv("HISTORY_FILE_HOST", "/tmp/bl80_test_hist.jsonl")
    monkeypatch.setenv("CONF_DIR_HOST", "/tmp/bl80_test_conf")
    mod = _load_companion()

    assert mod.HISTORY_FILE == "/tmp/bl80_test_hist.jsonl"
    assert mod.FILES_DIR == "/tmp"  # dirname of the history file
    assert mod.CONF_DIR == "/tmp/bl80_test_conf"
    assert mod.RUNTIME_SETTINGS_FILE == "/tmp/bl80_test_conf/runtime-settings.json"
    assert mod.POWER_SENTINEL_FILE == "/tmp/bl80_test_conf/.arret_requested"


def test_ensure_conf_dir_creates_dir(tmp_path, monkeypatch):
    """_ensure_conf_dir() lazily creates CONF_DIR if absent (BL-80)."""
    monkeypatch.delenv("CONF_DIR_HOST", raising=False)
    mod = _load_companion()

    target = tmp_path / "fresh_conf"
    assert not target.exists()
    # Point the module global at a fresh tmp dir + create it lazily.
    mod.CONF_DIR = str(target)
    mod._ensure_conf_dir()
    assert target.is_dir()
    # Idempotent: a second call must not raise.
    mod._ensure_conf_dir()
    assert target.is_dir()
    shutil.rmtree(str(target), ignore_errors=True)