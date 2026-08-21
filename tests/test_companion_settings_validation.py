"""Unit tests for `PUT /api/settings` payload validation
(`_validate_settings_payload` in companion/jetson-companion.py).

Covers:
  - BL-82: `counting_class_ids` (list[int], non-negative when no catalog).
  - BL-84: `offset_counting_line` SIGNED (-300..300, was 0..100) and
    `counting_line_orientation` ("vertical" | "horizontal").

These mirror the countingapp's own resolution/validation (state.py + main.py)
so the companion never accepts a value the countingapp would reject/drop.
"""
import importlib.util
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
COMPANION = os.path.join(HERE, "..", "companion", "jetson-companion.py")


def _load():
    spec = importlib.util.spec_from_file_location("jc", COMPANION)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


_jc = _load()
validate = _jc._validate_settings_payload


# --- offset_counting_line (BL-84: signed -300..300) -----------------------

def test_offset_signed_zero_ok():
    ok, errs = validate({"offset_counting_line": 0})
    assert ok and errs == []


def test_offset_signed_negative_ok():
    ok, errs = validate({"offset_counting_line": -50})
    assert ok and errs == []


def test_offset_signed_positive_within_new_range_ok():
    # 101 was rejected under the old [0,100] cap; it is now valid (the
    # authoritative bound is clamped at use-time by the counting app).
    ok, errs = validate({"offset_counting_line": 101})
    assert ok and errs == []


def test_offset_upper_bound_ok():
    ok, errs = validate({"offset_counting_line": 300})
    assert ok and errs == []


def test_offset_lower_bound_ok():
    ok, errs = validate({"offset_counting_line": -300})
    assert ok and errs == []


def test_offset_above_range_rejected():
    ok, errs = validate({"offset_counting_line": 301})
    assert not ok
    assert any("[-300, 300]" in e for e in errs)


def test_offset_below_range_rejected():
    ok, errs = validate({"offset_counting_line": -301})
    assert not ok
    assert any("[-300, 300]" in e for e in errs)


def test_offset_bool_rejected():
    # bool is a subclass of int — must be rejected explicitly.
    ok, errs = validate({"offset_counting_line": True})
    assert not ok
    assert any("must be an integer" in e for e in errs)


def test_offset_float_rejected():
    ok, errs = validate({"offset_counting_line": 10.5})
    assert not ok
    assert any("must be an integer" in e for e in errs)


# --- counting_line_orientation (BL-84) --------------------------------------

def test_orientation_vertical_ok():
    ok, errs = validate({"counting_line_orientation": "vertical"})
    assert ok and errs == []


def test_orientation_horizontal_ok():
    ok, errs = validate({"counting_line_orientation": "horizontal"})
    assert ok and errs == []


def test_orientation_unknown_rejected():
    ok, errs = validate({"counting_line_orientation": "diagonal"})
    assert not ok
    assert any("must be 'vertical' or 'horizontal'" in e for e in errs)


def test_orientation_bool_rejected():
    ok, errs = validate({"counting_line_orientation": True})
    assert not ok
    assert any("must be a string" in e for e in errs)


def test_orientation_int_rejected():
    ok, errs = validate({"counting_line_orientation": 1})
    assert not ok
    assert any("must be a string" in e for e in errs)


# --- counting_class_ids (BL-82 backfill) ------------------------------------

def test_class_ids_ok():
    ok, errs = validate({"counting_class_ids": [0, 1]})
    assert ok and errs == []


def test_class_ids_negative_rejected():
    ok, errs = validate({"counting_class_ids": [-1]})
    assert not ok
    assert any("counting_class_ids" in e for e in errs)


def test_class_ids_bool_element_rejected():
    ok, errs = validate({"counting_class_ids": [True]})
    assert not ok


# --- forward-compat / structural -------------------------------------------

def test_unknown_key_ignored():
    ok, errs = validate({"future_key": "whatever"})
    assert ok and errs == []


def test_non_dict_rejected():
    ok, errs = validate("not an object")  # type: ignore[arg-type]
    assert not ok
    assert any("must be a JSON object" in e for e in errs)


def test_combined_payload_ok():
    # All recognised keys together.
    ok, errs = validate({
        "draw_tracking": True,
        "box_tracking": True,
        "centroid_tracking": False,
        "offset_counting_line": -10,
        "counting_line_orientation": "horizontal",
        "counting_class_ids": [1],
    })
    assert ok and errs == [], errs