# Plan: BL-88 (issue #16) — Companion + app Android : zones de masquage

## Summary
Add visual mask-zone editing to the phone: the companion serves a camera
snapshot JPEG (`GET /api/snapshot`) and accepts `mask_zones` (normalized
axis-aligned rects `{x,y,w,h} ∈ [0..1]`) + `draw_mask_zones` (bool) in
`PUT/GET /api/settings` with strict reject-all validation; the Android app
fetches the snapshot, lets the operator draw rectangles on it (drag), and
saves them via `PUT`. No numeric inputs, no silent clamping, no countingapp
change.

## In Scope
- **Companion** (`companion/jetson-companion.py`, stdlib-only):
  - `mask_zones` + `draw_mask_zones` in `_validate_settings_payload` (strict
    reject-all on any invalid rect → 400 + logged WARN) and in the `PUT /api/settings`
    merge tuple; `GET /api/settings` returns them (defaults `[]` / `true`).
  - New `GET /api/snapshot` serving `/files/snapshot.jpg` as `image/jpeg`
    (`Cache-Control: no-store`), 404 when absent.
  - Bump `SERVICE_VERSION` `"7"` → `"8"`.
- **Android** (`android/`, Kotlin + Compose + Material 3):
  - `MaskZone` model + `JetsonSettings` fields + `toJson`/`parseJetsonSettings`.
  - `JetsonClient.getSnapshot` (binary `ApiResult<ByteArray>`); `JetsonConnectionManager.getSnapshot()`.
  - `SettingsRepository.drawMaskZones` (DataStore bool, default `true`).
  - `SettingsViewModel` mask-zones/snapshot/save state machines.
  - « Zones de masquage » `Section` in `SettingsScreen.kt`: capture button →
    snapshot image + Compose `Canvas`/`pointerInput` drag-draw → normalized
    rects → zone list w/ delete → overlay switch → « Enregistrer » `PUT`.
  - Bilingual strings (`values/` + `values-fr/`).
- **Docs**: `docs/01_jetson_companion.md` — `GET /api/snapshot` + new settings
  keys + curl examples + version 8. **Not** `docs/IPC_CONTRACT.md` (synced via PR #17).
- **Tests**: `tests/test_companion_settings_validation.py` — `mask_zones`/`draw_mask_zones`
  validation cases (+ optional snapshot helper test).

## Out of Scope
- The countingapp side that writes `/files/snapshot.jpg` periodically (separate
  mini-follow-up in the `animal-counter` repo).
- `docs/IPC_CONTRACT.md` (already synced via PR #17).
- Per-species masks / non-rectangular zones / rotation.
- Companion auth (explicitly out of scope per existing docs).

## Architecture Decisions
- **Geometry**: axis-aligned normalized rects `{x,y,w,h} ∈ [0..1]` (relative to
  frame), resolution-independent. Generic (all species).
- **UX**: snapshot + draw (not numeric inputs). The app fetches a JPEG, the
  operator draws rectangles by dragging; each rect is normalized against the
  displayed image bounds.
- **Snapshot mechanism**: Option A — the countingapp writes `snapshot.jpg` into
  `/files` (hostPath `/data/orin/files`); the companion serves it read-only via
  `GET /api/snapshot`. 404 when absent → app shows « Aperçu pas encore disponible ».
  The countingapp writer is a separate follow-up (out of scope here).
- **Validation**: strict reject-all + WARN, no silent clamping (consistent with
  BL-84's offset/orientation rejection). Any invalid rect (x/y/w/h out of
  `[0..1]`, `w<=0`, `h<=0`, `x+w>1`, `y+h>1`, non-dict element, missing/bool
  field, non-list) → the whole PUT is rejected with 400 + a logged WARN; the
  existing `runtime-settings.json` is left unchanged.
- **State ownership**: `mask_zones` is kept in-memory in the ViewModel (the
  Jetson `runtime-settings.json` is the source of truth; the draw UX needs the
  live snapshot anyway) and seeded from `GET /api/settings` on init.
  `draw_mask_zones` is cached in DataStore (simple bool, mirrors the existing
  tracking toggles) for offline restore.
- **`draw_mask_zones`**: also wired (already in the IPC contract) as a small
  overlay toggle so the contract field is writable; trivial bool validation.
- **Binary transport**: the existing `JetsonClient.getJson` returns a parsed
  `String`; a JPEG is binary, so a new `getBytes` twin returns `ByteArray`.
- **Reuse**: BL-84 settings validation/merge, BL-82 `ApiResult` transport +
  `resolveActiveIp()`/`activeWifiNetworkSafe()`, BL-77 About-card state-machine
  pattern, the `Section` card wrapper, `BitmapFactory.decodeByteArray` (stdlib,
  no new dependency), Compose `Canvas` + `pointerInput`/`detectDragGestures`.

## Tasks
- [x] Task 1: EDIT `companion/jetson-companion.py` — extend `_validate_settings_payload`
  with `mask_zones` (list of `{x,y,w,h}` objects; each field a non-bool number in
  `[0..1]`, `w>0`, `h>0`, `x+w<=1`, `y+h<=1`; any invalid rect → `(False, errors)`)
  and `draw_mask_zones` (bool, same pattern as `draw_tracking`). Strict reject-all,
  no clamping.
- [x] Task 2: EDIT `companion/jetson-companion.py` — add `"mask_zones"` and
  `"draw_mask_zones"` to the `do_PUT` recognised-keys merge tuple so they are
  merged into `runtime-settings.json` (atomic write unchanged); bump
  `SERVICE_VERSION` to `"8"`.
- [x] Task 3: EDIT `companion/jetson-companion.py` — add `GET /api/snapshot`
  serving `os.path.join(FILES_DIR, "snapshot.jpg")` as `image/jpeg` with
  `Content-Length` + `Cache-Control: no-store`; 404 JSON when absent. Add a small
  `_serve_file_bytes` helper (read whole small JPEG; no Range needed).
- [x] Task 4: EDIT `android/app/src/main/java/com/animalcounter/net/Models.kt` —
  add `data class MaskZone(x: Float, y: Float, w: Float, h: Float)`; add
  `maskZones: List<MaskZone>?` and `drawMaskZones: Boolean?` to `JetsonSettings`;
  serialize `mask_zones` as a `JSONArray` of `{x,y,w,h}` objects + `draw_mask_zones`
  bool in `toJson` (omit when `null`); parse both defensively in
  `parseJetsonSettings`.
- [x] Task 5: EDIT `android/app/src/main/java/com/animalcounter/net/JetsonClient.kt`
  — add `getSnapshot(ip, network): ApiResult<ByteArray>` via a new private
  `getBytes` transport (mirrors `getJson` but returns `ByteArray`; 200 →
  `Success(bytes)`, non-2xx → `HttpError(code)`, throw → `NetworkError`);
  `Accept: image/jpeg`.
- [x] Task 6: EDIT `android/app/src/main/java/com/animalcounter/net/JetsonConnectionManager.kt`
  — add `getSnapshot(): Result<ByteArray>` using `resolveActiveIp()` +
  `activeWifiNetworkSafe()` + `JetsonClient.getSnapshot` (mirrors `getSettings`).
- [ ] Task 7: EDIT `android/app/src/main/java/com/animalcounter/data/SettingsRepository.kt`
  — add `drawMaskZones` Flow (default `true`) + `setDrawMaskZones` (DataStore bool,
  mirroring `drawTracking`). `mask_zones` is NOT cached (in-memory in the VM).
- [ ] Task 8: EDIT `android/app/src/main/java/com/animalcounter/ui/settings/SettingsViewModel.kt`
  — add `maskZones: StateFlow<List<MaskZone>>` (seeded from `GET /api/settings` in
  `refreshSettingsFromJetson`), `drawMaskZones: StateFlow<Boolean>` (seeded +
  cached), `SnapshotState` sealed (Idle/Loading/Loaded(Bitmap)/Unavailable(404)/Error)
  + `refreshSnapshot()` (decode via `BitmapFactory.decodeByteArray` on
  `Dispatchers.Default`), `MaskSaveState` sealed (Idle/Saving/Saved/Error) +
  `saveMaskZones()` (PUT `{mask_zones, draw_mask_zones}`; refresh from echoed
  merged settings on success), and `addMaskZone`/`removeMaskZone`/`updateMaskZone`/
  `setDrawMaskZones` mutators.
- [ ] Task 9: EDIT `android/app/src/main/java/com/animalcounter/ui/settings/SettingsScreen.kt`
  — add a « Zones de masquage » `Section` (after « Espèces comptées », before
  « À propos »): « Capturer l'aperçu » button → snapshot `Image` (decoded
  `Bitmap` via `asImageBitmap()`) in a fixed-aspect `Box` overlaid with a
  `Canvas` + `Modifier.pointerInput { detectDragGestures(...) }` to draw a rect
  (on drag end normalize against displayed image bounds → `addMaskZone`); draw
  existing zones over the image; zone list with delete `IconButton`; « Afficher
  les zones à l'écran » `Switch` (`draw_mask_zones`); « Enregistrer » button
  with inline `MaskSaveState` feedback; 404 → « Aperçu pas encore disponible » +
  retry; Loading → spinner; Error → error + retry.
- [ ] Task 10: EDIT `android/app/src/main/res/values/strings.xml` +
  `android/app/src/main/res/values-fr/strings.xml` — add bilingual strings:
  `section_mask_zones`, `mask_capture_preview`, `mask_snapshot_unavailable`,
  `mask_snapshot_error`, `mask_snapshot_loading`, `mask_zone_label`,
  `mask_draw_overlay_title`, `mask_save`, `mask_saving`, `mask_saved`,
  `mask_save_error`, `mask_drag_hint`, `mask_empty`.
- [ ] Task 11: EDIT `docs/01_jetson_companion.md` — document `GET /api/snapshot`
  (purpose, 200 `image/jpeg` / 404, `no-store`), add `mask_zones` +
  `draw_mask_zones` to the `PUT/GET /api/settings` recognised-keys list + curl
  examples (PUT a `mask_zones` array; negative test for an invalid rect → 400),
  bump the documented companion version to 8. Do NOT edit `docs/IPC_CONTRACT.md`.
- [ ] Task 12: EDIT `tests/test_companion_settings_validation.py` — add
  `mask_zones` validation cases (valid `[{x:0.8,y:0,w:0.2,h:1}]` ok, `[]` ok;
  rejected: x/y/w/h out of `[0..1]`, `w<=0`, `h<=0`, `x+w>1`, `y+h>1`, non-dict
  element, missing field, bool field value, non-list) + `draw_mask_zones` bool
  check + combined payload ok, mirroring the existing BL-82/BL-84 test style.
  Optionally add `tests/test_companion_snapshot.py` for the `_serve_file_bytes`
  helper (temp JPEG → 200 bytes / absent → 404) if it stays stdlib-only.

## Validation
- `python3 -m py_compile companion/jetson-companion.py` — companion compiles
  (for Tasks 1–3).
- `python3 -m pytest tests/` — all settings-validation tests green, including
  the new `mask_zones`/`draw_mask_zones` cases (for Task 12).
- `cd android && ./gradlew :app:assembleDebug` — APK builds with no new
  dependencies (for Tasks 4–10).
- Manual (Jetson reachable, with a fake `snapshot.jpg` in `FILES_DIR`):
  `curl .../api/snapshot -o /tmp/s.jpg` → 200 `image/jpeg` + bytes; remove it →
  404. `curl .../api/settings` returns `mask_zones` (default `[]`) +
  `draw_mask_zones` (default `true`). `PUT {"mask_zones":[{"x":0.8,"y":0,"w":0.2,"h":1}]}` →
  200 echoed merged object; invalid rect (`w:0`, `x:1.5`, `x+w>1`) → 400 + WARN,
  file unchanged. On-device: draw a rect → appears in list → « Enregistrer » →
  green saved; `curl .../api/settings` confirms persistence; re-open screen →
  zones restored.

## Risks
- **Snapshot not yet written by the countingapp** (the writer is a separate
  animal-counter follow-up): until then `GET /api/snapshot` always 404s. Mitigated
  by the in-app « Aperçu pas encore disponible » + retry state; the mask-zone
  list + save still work against the last-known zones (the user can still
  clear/save an empty list). The endpoint is correct and ready for the writer.
- **Binary transport drift**: a new `getBytes` path duplicates `getJson`'s
  transport logic (timeouts, WiFi binding, error mapping). Keep it a faithful
  twin to avoid divergent failure handling.
- **Draw-coordinate normalization**: the displayed image is letterboxed/scaled
  inside its `Box`; the drag coordinates must be mapped to the actual image
  rect (not the `Box` bounds) before normalizing, or zones will be off. Clamp
  the normalized rect to `[0..1]` on the client (a drawn rect is always valid
  by construction, but dragging outside the image edge must not produce `>1`).
- **Large snapshot / decode on main thread**: decode `BitmapFactory.decodeByteArray`
  off the main thread (`Dispatchers.Default`) and hold the `Bitmap` in state, not
  the raw bytes.
- **Validation parity with the countingapp**: the companion's `mask_zones` rules
  must match the countingapp's BL-87 reader exactly (the IPC contract is the
  authority); drift would let the companion accept a value the countingapp drops.
  The tests cover the contract's stated invalid cases.