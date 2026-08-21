# Plan: BL-88 (issue #16) — Companion + app Android : zones de masquage

## Context

BL-87 (animal-counter, PR #113 merged) added a pre-filter `mask_zones` on the
countingapp side: detections whose centroid falls inside a masked normalized
rect `{x,y,w,h} ∈ [0..1]` are dropped before tracking. `mask_zones` is read
from `/conf/runtime-settings.json` (hot-reloaded at idle via the BL-86 watcher).
The IPC contract is already synced (PR #17 companion merged): `mask_zones`
(array[object] `{x,y,w,h}`) + `draw_mask_zones` (bool) are documented in
`docs/IPC_CONTRACT.md` (identical in both repos — DO NOT touch it).

BL-88 is the **companion + Android app** side: let the operator define/edit
the mask zones **visually** from the phone — fetch a camera snapshot from the
Jetson, draw rectangles on it, save them via `PUT /api/settings`. Numeric
inputs are explicitly rejected as UX (snapshot + draw is far better).

All product decisions are already locked by the user (geometry = axis-aligned
normalized rects; generic/all-species; snapshot+draw UX; strict reject-all +
WARN validation, no silent clamping; Option A snapshot mechanism). This plan
only resolves the one minor taste-call left open: **also wire `draw_mask_zones`**
(already in the contract, trivial bool) so the contract field is writable and
the app can toggle the on-screen overlay.

## Approach

Two targets, dual validation (`python3 -m py_compile` for the companion,
`./gradlew :app:assembleDebug` for Android):

1. **Companion** (`companion/jetson-companion.py`, stdlib-only): add
   `mask_zones` + `draw_mask_zones` to the existing `_validate_settings_payload`
   + the PUT merge list (strict reject-all on any invalid rect), and add a new
   `GET /api/snapshot` endpoint that serves `/files/snapshot.jpg` (written
   periodically by the countingapp — a separate follow-up in animal-counter,
   **out of scope here**) as `image/jpeg`, 404 when absent.
2. **Android** (`android/`, Kotlin + Compose + Material 3): add a
   « Zones de masquage » section to the existing Settings screen — a
   « Capturer l'aperçu » button fetches the JPEG, the user draws rectangles on
   it (drag), the list of normalized zones is shown with delete affordances, and
   « Enregistrer » pushes them via `PUT /api/settings {mask_zones, draw_mask_zones}`.
   The 404 (snapshot not yet available) state is handled gracefully.

Reuse the established patterns verbatim (see **Reuse**): the BL-84 settings
validation/merge, the BL-82 catalog fetch/`ApiResult` transport, the
BL-77 About-card state machine, the `Section` card wrapper, the
`resolveActiveIp()` + WiFi-bound transport, the bilingual `strings.xml`.

## Files to modify

### Companion (Python, stdlib-only)
- `companion/jetson-companion.py`
  - `_validate_settings_payload`: add `mask_zones` (list of `{x,y,w,h}` objects,
    each field a non-bool number in `[0..1]`, `w>0`, `h>0`, `x+w<=1`, `y+h<=1`;
    any invalid rect → `(False, errors)` → handler returns **400** + logs the
    rejection, the WARN equivalent) and `draw_mask_zones` (bool, same pattern as
    `draw_tracking`).
  - `do_PUT` merge tuple: add `"mask_zones"` and `"draw_mask_zones"` to the
    recognised-keys list so they are merged into `runtime-settings.json`.
  - `do_GET`: add `if path == "/api/snapshot":` — serve
    `os.path.join(FILES_DIR, "snapshot.jpg")` (`FILES_DIR` already = the `/files`
    hostPath `/data/orin/files`). 200 `image/jpeg` + `Content-Length` +
    `Cache-Control: no-store` (the app fetches on demand, always want fresh);
    404 JSON when absent. Add a small `_serve_file_bytes` helper (reads the
    whole small JPEG; snapshots are tiny) — do NOT reuse the Range/streaming
    video helper (overkill).
  - Bump `SERVICE_VERSION` `"7"` → `"8"` (convention: each feature bumps the
    informational identify version; the app does not gate on it).

### Android (Kotlin + Compose)
- `android/app/src/main/java/com/animalcounter/net/Models.kt`
  - `data class MaskZone(val x: Float, val y: Float, val w: Float, val h: Float)`
    (normalized; `Float` because JSON values are `[0..1]`).
  - `JetsonSettings`: add `maskZones: List<MaskZone>? = null` and
    `drawMaskZones: Boolean? = null` (PATCH semantics, `null` = omit).
  - `JetsonSettings.toJson()`: serialize `mask_zones` as a `JSONArray` of
    `{x,y,w,h}` `JSONObject`s; `draw_mask_zones` as bool. Omit when `null`.
  - `parseJetsonSettings`: parse `mask_zones` array defensively
    (`optMaskZonesOrNull`) + `draw_mask_zones` via `optBooleanOrNull`.
- `android/app/src/main/java/com/animalcounter/net/JetsonClient.kt`
  - `getSnapshot(ip, network): ApiResult<ByteArray>` — new **binary** GET
    transport (the existing `getJson` returns a parsed `String`; JPEG is binary).
    Add a private `getBytes` helper mirroring `getJson` but returning
    `ByteArray` (200 → `Success(bytes)`, non-2xx → `HttpError(code)`,
    throw → `NetworkError`). `Accept: image/jpeg`.
- `android/app/src/main/java/com/animalcounter/net/JetsonConnectionManager.kt`
  - `getSnapshot(): Result<ByteArray>` — `resolveActiveIp()` +
    `activeWifiNetworkSafe()` + delegate to `JetsonClient.getSnapshot`; map
    `ApiResult` → `Result` (mirrors `getSettings`/`getClasses`).
- `android/app/src/main/java/com/animalcounter/ui/settings/SettingsViewModel.kt`
  - `maskZones: StateFlow<List<MaskZone>>` (in-memory, seeded from
    `GET /api/settings` in `refreshSettingsFromJetson`).
  - `drawMaskZones: StateFlow<Boolean>` (default `true`; cached in DataStore for
    offline restore, mirroring the other bool settings).
  - `SnapshotState` sealed (Idle / Loading / Loaded(Bitmap) / Unavailable(404) /
    Error) + `snapshot: StateFlow<SnapshotState>` + `refreshSnapshot()`
    (decode bytes via `BitmapFactory.decodeByteArray` on `Dispatchers.Default`).
  - `MaskSaveState` sealed (Idle / Saving / Saved / Error) + `saveState` +
    `saveMaskZones()` (PUT `{mask_zones, draw_mask_zones}`; on success refresh
    from the echoed merged settings).
  - `addMaskZone(zone)`, `removeMaskZone(index)`, `updateMaskZone(index, zone)`,
    `setDrawMaskZones(bool)` — mutate the in-memory list/flow.
- `android/app/src/main/java/com/animalcounter/data/SettingsRepository.kt`
  - Add `drawMaskZones` Flow + `setDrawMaskZones` (DataStore bool, default `true`,
    mirroring `drawTracking`). `mask_zones` is **not** cached (it's a list of
    floats edited against a live snapshot; the Jetson is the source of truth and
    the draw UX needs the live image anyway).
- `android/app/src/main/java/com/animalcounter/ui/settings/SettingsScreen.kt`
  - New `Section(title = stringResource(R.string.section_mask_zones))` placed
    after « Espèces comptées », before « À propos ». Contents:
    - « Capturer l'aperçu » `Button` → `vm.refreshSnapshot()`.
    - Snapshot view: `Loaded` → `Box` with the decoded `Bitmap` (`asImageBitmap()`
      via `Image`/`BitmapPainter`) at a fixed aspect ratio, overlaid with a
      `Canvas` + `Modifier.pointerInput { detectDragGestures(...) }` to draw a
      rectangle; on drag end convert the drag rect (in displayed-pixel coords)
      to normalized `{x,y,w,h}` relative to the displayed image bounds and call
      `vm.addMaskZone(...)`. Existing zones are drawn over the image too.
    - `Unavailable` (404) → « Aperçu pas encore disponible » + retry button.
    - `Loading` → spinner; `Error` → error + retry.
    - List of current zones (index + `{x,y,w,h}` readout + delete `IconButton`).
    - « Afficher les zones à l'écran » `Switch` → `vm.setDrawMaskZones(...)`
      (`draw_mask_zones`).
    - « Enregistrer » `Button` → `vm.saveMaskZones()` with inline
      `MaskSaveState` feedback (spinner / green saved / red error).
- `android/app/src/main/res/values/strings.xml` +
  `android/app/src/main/res/values-fr/strings.xml`
  - New strings: `section_mask_zones`, `mask_capture_preview`,
    `mask_snapshot_unavailable`, `mask_snapshot_error`, `mask_snapshot_loading`,
    `mask_zone_label` ("Zone {index}"), `mask_draw_overlay_title`
    ("Show zones on screen"), `mask_save`, `mask_saving`, `mask_saved`,
    `mask_save_error`, `mask_drag_hint` ("Draw a rectangle on the preview"),
    `mask_empty` ("No mask zone defined").

### Docs
- `docs/01_jetson_companion.md`
  - Document `GET /api/snapshot` (purpose, 200 `image/jpeg` / 404, `no-store`).
  - Add `mask_zones` + `draw_mask_zones` to the `PUT/GET /api/settings`
    recognised-keys list + a curl example (PUT a `mask_zones` array; negative
    test for an invalid rect → 400). Bump the documented companion version to 8.
  - **Do NOT** edit `docs/IPC_CONTRACT.md` (already synced via PR #17).

### Tests
- `tests/test_companion_settings_validation.py`
  - Add `mask_zones` validation cases mirroring the existing BL-82/BL-84 style:
    valid (`[{x:0.8,y:0,w:0.2,h:1}]` ok; `[]` ok), rejected (x/y/w/h out of
    `[0..1]`, `w<=0`, `h<=0`, `x+w>1`, `y+h>1`, non-dict element, missing field,
    bool field value, non-list, negative zero ok), `draw_mask_zones` bool
    validation, combined payload ok.
- (Optional) `tests/test_companion_snapshot.py` — if a small `_serve_file_bytes`
  helper is extracted, a unit test that mocks `FILES_DIR` with a temp JPEG and
  asserts 200 bytes / 404 when absent. Add only if it stays stdlib-only and
  matches the existing test-loader style (`importlib` load of the module).

## Reuse (existing functions/utilities — do not reimplement)

- **Companion validation pattern**: `_validate_settings_payload` in
  `companion/jetson-companion.py` (returns `(ok, errors)`; the PUT handler maps
  `not ok` → `400 {"errors": [...]}` + `self._log(...)` = the WARN). Mirror the
  bool check (`isinstance(val, bool)` rejection) and the int/float handling used
  for `offset_counting_line` / `counting_class_ids`.
- **Companion merge + atomic write**: the `do_PUT` recognised-keys tuple +
  `tmp_path`/`os.replace` atomic write. Just add the two new keys to the tuple.
- **Companion file serving**: `_serve_video_file` (Range streaming) is the
  reference, but the snapshot is small → a simpler `open(...).read()` + headers
  helper is enough (no Range needed).
- **Companion paths**: `FILES_DIR` (= `/data/orin/files`, the `/files` hostPath)
  already defined; `snapshot.jpg` lives there (countingapp writer). `CONF_DIR`
  is where `runtime-settings.json` already lives (no path change).
- **Android transport**: `JetsonClient.getJson`/`sendJson` + `ApiResult`
  (Success/HttpError/NetworkError) in `Models.kt`/`JetsonClient.kt`; add a binary
  `getBytes` twin. `JetsonConnectionManager.resolveActiveIp()` +
  `activeWifiNetworkSafe()` for IP/network resolution (mirrors `getSettings`).
- **Android settings state machine**: `CompanionVersionState` /
  `ClassCatalogState` sealed interfaces in `SettingsViewModel.kt` are the
  template for `SnapshotState` / `MaskSaveState` (Idle/Loading/Loaded/Error +
  Unavailable for 404). `refreshSettingsFromJetson()` seeds new fields from
  `GET /api/settings`.
- **Android UI**: the `Section(title, content)` composable wrapper +
  `Switch`/`Button`/`CircularProgressIndicator`/`FilterChip` rhythm in
  `SettingsScreen.kt`; `BitmapFactory.decodeByteArray` (stdlib, no new dep) for
  JPEG decode; Compose `Canvas` + `pointerInput`/`detectDragGestures` (already
  available via compose foundation) for drawing.
- **Android persistence**: `SettingsRepository` DataStore bool pattern
  (`booleanPreferencesKey` + `setX`) for `drawMaskZones`.
- **Android strings**: bilingual `values/strings.xml` (EN) +
  `values-fr/strings.xml` (FR) — all user-facing text localized (no hard-coded
  strings), matching the existing convention.
- **Test style**: `tests/test_companion_settings_validation.py` loads the
  companion via `importlib.util.spec_from_file_location` and calls
  `_validate_settings_payload` directly — reuse exactly this for the new
  `mask_zones` cases.

## Steps

- [ ] **Companion — validation**: extend `_validate_settings_payload` with
  `mask_zones` (list of `{x,y,w,h}` normalized rects, strict reject-all on any
  invalid rect) + `draw_mask_zones` (bool). Log rejects (the WARN).
- [ ] **Companion — merge**: add `"mask_zones"` + `"draw_mask_zones"` to the
  `do_PUT` recognised-keys tuple; bump `SERVICE_VERSION` to `"8"`.
- [ ] **Companion — snapshot endpoint**: add `GET /api/snapshot` serving
  `FILES_DIR/snapshot.jpg` as `image/jpeg` (`Cache-Control: no-store`), 404 when
  absent (small `_serve_file_bytes` helper).
- [ ] **Companion — docs**: document `GET /api/snapshot` + the two new
  `PUT/GET /api/settings` keys + curl examples in `docs/01_jetson_companion.md`
  (do NOT touch `IPC_CONTRACT.md`).
- [ ] **Companion — tests**: add `mask_zones`/`draw_mask_zones` validation
  cases to `tests/test_companion_settings_validation.py` (+ optional snapshot
  helper test).
- [ ] **Companion — validate**: `python3 -m py_compile companion/jetson-companion.py`
  + `python3 -m pytest tests/` (all green).
- [ ] **Android — models**: `MaskZone` data class + `JetsonSettings` fields +
  `toJson`/`parseJetsonSettings` (Models.kt).
- [ ] **Android — client**: `JetsonClient.getSnapshot` (binary `getBytes`
  transport) returning `ApiResult<ByteArray>`.
- [ ] **Android — manager**: `JetsonConnectionManager.getSnapshot(): Result<ByteArray>`.
- [ ] **Android — repo**: `SettingsRepository.drawMaskZones` Flow + setter
  (DataStore bool, default `true`).
- [ ] **Android — ViewModel**: `maskZones`/`drawMaskZones` flows (seeded from
  `GET /api/settings`), `SnapshotState` + `refreshSnapshot()`,
  `MaskSaveState` + `saveMaskZones()`, zone add/remove/update + draw toggle.
- [ ] **Android — UI**: « Zones de masquage » `Section` in `SettingsScreen.kt`
  (capture button, snapshot+Canvas drag-draw, zone list w/ delete, overlay
  switch, save button + inline status, 404/loading/error states).
- [ ] **Android — strings**: bilingual strings in `values/` + `values-fr/`.
- [ ] **Android — validate**: `cd android && ./gradlew :app:assembleDebug`
  (APK builds; no new dependencies).

## Verification

End-to-end (the countingapp snapshot writer is a separate animal-counter
follow-up; until then the snapshot 404 path is the live state):

- **Companion (py)**: `python3 -m py_compile companion/jetson-companion.py` ✓;
  `python3 -m pytest tests/` — new `mask_zones` cases pass (valid ok, every
  invalid rect → 400, `draw_mask_zones` bool check).
- **Companion (manual, with a fake snapshot)**: drop a `snapshot.jpg` in
  `FILES_DIR` → `curl http://<jetson>:8090/api/snapshot -o /tmp/s.jpg` returns
  200 `image/jpeg` + the bytes; remove it → 404. `curl .../api/settings` returns
  `mask_zones` (default `[]`) + `draw_mask_zones` (default `true`).
  `curl -X PUT .../api/settings -d '{"mask_zones":[{"x":0.8,"y":0,"w":0.2,"h":1}]}'`
  → 200 echoed merged object; invalid rect (e.g. `w:0`, `x:1.5`, `x+w>1`) → 400
  + logged WARN, file unchanged.
- **Android (gradle)**: `cd android && ./gradlew :app:assembleDebug` →
  `app/build/outputs/apk/debug/app-debug.apk` builds with no new deps.
- **Android (on-device, Jetson reachable)**: open Réglages → « Zones de
  masquage » → « Capturer l'aperçu » shows the snapshot (or « Aperçu pas encore
  disponible » on 404); draw a rectangle (drag) → appears in the zone list;
  delete a zone; toggle « Afficher les zones à l'écran »; « Enregistrer » →
  green saved; `curl .../api/settings` confirms `mask_zones` + `draw_mask_zones`
  persisted. Re-open the screen → zones restored from `GET /api/settings`.

## Out of scope

- The countingapp side that **writes** `/files/snapshot.jpg` periodically — a
  separate mini-follow-up in the `animal-counter` repo (not this repo).
- Any change to `docs/IPC_CONTRACT.md` (already synced via PR #17).
- Per-species masks (generic by decision), non-rectangular zones, rotation.
- Companion auth (explicitly out of scope per the existing docs).