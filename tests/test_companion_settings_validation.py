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


# --- draw_mask_zones (BL-88) ---------------------------------------------

def test_draw_mask_zones_true_ok():
    ok, errs = validate({"draw_mask_zones": True})
    assert ok and errs == []


def test_draw_mask_zones_false_ok():
    ok, errs = validate({"draw_mask_zones": False})
    assert ok and errs == []


def test_draw_mask_zones_int_rejected():
    ok, errs = validate({"draw_mask_zones": 1})
    assert not ok
    assert any("draw_mask_zones must be a boolean" in e for e in errs)


def test_draw_mask_zones_string_rejected():
    ok, errs = validate({"draw_mask_zones": "true"})
    assert not ok
    assert any("draw_mask_zones must be a boolean" in e for e in errs)


# --- mask_zones (BL-88) ---------------------------------------------------

def test_mask_zones_empty_list_ok():
    ok, errs = validate({"mask_zones": []})
    assert ok and errs == []


def test_mask_zones_valid_rect_ok():
    ok, errs = validate({"mask_zones": [{"x": 0.8, "y": 0, "w": 0.2, "h": 1}]})
    assert ok and errs == [], errs


def test_mask_zones_multiple_valid_ok():
    ok, errs = validate({
        "mask_zones": [
            {"x": 0.0, "y": 0.0, "w": 0.5, "h": 0.5},
            {"x": 0.5, "y": 0.5, "w": 0.5, "h": 0.5},
        ]
    })
    assert ok and errs == [], errs


def test_mask_zones_full_frame_ok():
    ok, errs = validate({"mask_zones": [{"x": 0, "y": 0, "w": 1, "h": 1}]})
    assert ok and errs == [], errs


def test_mask_zones_rect_with_name_ok():
    # `name` is an additive app-local label (Android UI). The companion must
    # accept it (unknown-key tolerant) and only validate x/y/w/h; the
    # countingapp ignores `name` (reads only x/y/w/h).
    ok, errs = validate({
        "mask_zones": [{"x": 0.1, "y": 0.2, "w": 0.3, "h": 0.4, "name": "Porte"}]
    })
    assert ok and errs == [], errs


def test_mask_zones_non_list_rejected():
    ok, errs = validate({"mask_zones": {"x": 0, "y": 0, "w": 1, "h": 1}})
    assert not ok
    assert any("mask_zones must be a list" in e for e in errs)


def test_mask_zones_non_dict_element_rejected():
    ok, errs = validate({"mask_zones": ["not a rect"]})
    assert not ok
    assert any("mask_zones[0] must be an object" in e for e in errs)


def test_mask_zones_missing_field_rejected():
    ok, errs = validate({"mask_zones": [{"x": 0, "y": 0, "w": 1}]})
    assert not ok
    assert any("mask_zones[0] missing field 'h'" in e for e in errs)


def test_mask_zones_bool_field_rejected():
    # bool is a subclass of int — `true` must not be silently accepted as 1.
    ok, errs = validate({
        "mask_zones": [{"x": True, "y": 0, "w": 1, "h": 1}]})
    assert not ok
    assert any("mask_zones[0].x must be a number" in e for e in errs)


def test_mask_zones_string_field_rejected():
    ok, errs = validate({
        "mask_zones": [{"x": "0", "y": 0, "w": 1, "h": 1}]})
    assert not ok
    assert any("mask_zones[0].x must be a number" in e for e in errs)


def test_mask_zones_x_above_one_rejected():
    ok, errs = validate({"mask_zones": [{"x": 1.5, "y": 0, "w": 0.1, "h": 1}]})
    assert not ok
    assert any("mask_zones[0].x must be in [0, 1]" in e for e in errs)


def test_mask_zones_y_below_zero_rejected():
    ok, errs = validate({"mask_zones": [{"x": 0, "y": -0.1, "w": 1, "h": 1}]})
    assert not ok
    assert any("mask_zones[0].y must be in [0, 1]" in e for e in errs)


def test_mask_zones_w_zero_rejected():
    ok, errs = validate({"mask_zones": [{"x": 0, "y": 0, "w": 0, "h": 1}]})
    assert not ok
    assert any("mask_zones[0].w must be > 0" in e for e in errs)


def test_mask_zones_w_negative_rejected():
    # A negative w is below the [0, 1] range bound, so it is rejected by
    # the range check (the `w > 0` check only catches `w == 0`).
    ok, errs = validate({"mask_zones": [{"x": 0, "y": 0, "w": -0.2, "h": 1}]})
    assert not ok
    assert any("mask_zones[0].w must be in [0, 1]" in e for e in errs)


def test_mask_zones_h_zero_rejected():
    ok, errs = validate({"mask_zones": [{"x": 0, "y": 0, "w": 1, "h": 0}]})
    assert not ok
    assert any("mask_zones[0].h must be > 0" in e for e in errs)


def test_mask_zones_x_plus_w_over_one_rejected():
    ok, errs = validate({"mask_zones": [{"x": 0.8, "y": 0, "w": 0.3, "h": 1}]})
    assert not ok
    assert any("x+w must be <= 1" in e for e in errs)


def test_mask_zones_y_plus_h_over_one_rejected():
    ok, errs = validate({"mask_zones": [{"x": 0, "y": 0.8, "w": 1, "h": 0.3}]})
    assert not ok
    assert any("y+h must be <= 1" in e for e in errs)


def test_mask_zones_second_rect_invalid_rejected():
    # Strict reject-all: a single invalid rect rejects the whole PUT.
    ok, errs = validate({
        "mask_zones": [
            {"x": 0, "y": 0, "w": 0.5, "h": 0.5},
            {"x": 0, "y": 0, "w": 0, "h": 1},
        ]
    })
    assert not ok
    assert any("mask_zones[1].w must be > 0" in e for e in errs)


# --- combined BL-88 payload ----------------------------------------------

def test_combined_bl88_payload_ok():
    ok, errs = validate({
        "mask_zones": [{"x": 0.8, "y": 0, "w": 0.2, "h": 1}],
        "draw_mask_zones": True,
    })
    assert ok and errs == [], errs


# --- counting_direction_mode (BL-92) --------------------------------------

def test_direction_mode_auto_ok():
    ok, errs = validate({"counting_direction_mode": "auto"})
    assert ok and errs == [], errs


def test_direction_mode_manual_ok():
    ok, errs = validate({"counting_direction_mode": "manual"})
    assert ok and errs == [], errs


def test_direction_mode_unknown_rejected():
    ok, errs = validate({"counting_direction_mode": "diagonal"})
    assert not ok
    assert any("counting_direction_mode must be 'auto' or 'manual'" in e
               for e in errs)


def test_direction_mode_bool_rejected():
    # bool is not a str — must be rejected explicitly.
    ok, errs = validate({"counting_direction_mode": True})
    assert not ok
    assert any("counting_direction_mode must be a string" in e
               for e in errs)


def test_direction_mode_int_rejected():
    ok, errs = validate({"counting_direction_mode": 1})
    assert not ok
    assert any("counting_direction_mode must be a string" in e for e in errs)


# --- counting_direction (BL-92) -------------------------------------------

def test_direction_up_ok():
    ok, errs = validate({"counting_direction": "up"})
    assert ok and errs == [], errs


def test_direction_down_ok():
    ok, errs = validate({"counting_direction": "down"})
    assert ok and errs == [], errs


def test_direction_left_ok():
    ok, errs = validate({"counting_direction": "left"})
    assert ok and errs == [], errs


def test_direction_right_ok():
    ok, errs = validate({"counting_direction": "right"})
    assert ok and errs == [], errs


def test_direction_null_ok():
    # null is valid (manual not yet set / auto mode).
    ok, errs = validate({"counting_direction": None})
    assert ok and errs == [], errs


def test_direction_unknown_rejected():
    ok, errs = validate({"counting_direction": "sideways"})
    assert not ok
    assert any("counting_direction must be 'up', 'down', 'left' or 'right'"
               in e for e in errs)


def test_direction_bool_rejected():
    ok, errs = validate({"counting_direction": True})
    assert not ok
    assert any("counting_direction must be a string or null" in e
               for e in errs)


def test_direction_int_rejected():
    ok, errs = validate({"counting_direction": 1})
    assert not ok
    assert any("counting_direction must be a string or null" in e
               for e in errs)


# --- BL-92 soft orientation-mismatch check (same-PUT only) ---------------

def test_direction_orientation_mismatch_rejected():
    # horizontal line → up/down only; "up" with "vertical" mismatches.
    ok, errs = validate({
        "counting_direction": "up",
        "counting_line_orientation": "vertical",
    })
    assert not ok
    assert any("mismatches counting_line_orientation" in e for e in errs)


def test_direction_orientation_matching_horizontal_ok():
    ok, errs = validate({
        "counting_direction": "up",
        "counting_line_orientation": "horizontal",
    })
    assert ok and errs == [], errs


def test_direction_orientation_matching_vertical_ok():
    ok, errs = validate({
        "counting_direction": "left",
        "counting_line_orientation": "vertical",
    })
    assert ok and errs == [], errs


def test_direction_orientation_mismatch_vertical_rejected():
    ok, errs = validate({
        "counting_direction": "up",
        "counting_line_orientation": "vertical",
    })
    assert not ok
    assert any("expected 'left' or 'right'" in e for e in errs)


def test_combined_full_payload_ok():
    # Every recognised key together, including the BL-88 additions and
    # the BL-92 counting-direction keys (auto + horizontal → up/down ok).
    ok, errs = validate({
        "draw_tracking": True,
        "box_tracking": True,
        "centroid_tracking": False,
        "offset_counting_line": -10,
        "counting_line_orientation": "horizontal",
        "counting_class_ids": [1],
        "mask_zones": [{"x": 0, "y": 0, "w": 0.5, "h": 0.5}],
        "draw_mask_zones": True,
        "counting_direction_mode": "manual",
        "counting_direction": "up",
    })
    assert ok and errs == [], errs