# Plan: BL-85 — Directional aggregation/display for the horizontal counting line (UP/DOWN)

## Summary

BL-83 (sister repo) introduced horizontal counting-line orientation, emitting `crossed` events with `direction=UP/DOWN`. The companion history reader only aggregates LEFT/RIGHT, silently dropping UP/DOWN. This plan adds `count_down_to_up`/`count_up_to_down` (additive, Option A), resolves `counting_line_orientation` per session, surfaces it across companion endpoints + the Android app UI, and bumps `SERVICE_VERSION` 8→9.

## In Scope

- **Companion** (`companion/jetson-companion.py`): resolve `counting_line_orientation` per session (default `"vertical"`); aggregate UP/DOWN from crossed events in `video_detail()` + session-level; surface orientation in session summary/detail + `/api/videos` rows + video detail; bump `SERVICE_VERSION` 8→9; tests.
- **App** (`android/`): parse new fields; new session-level "Comptage" card (SessionDetailScreen); orientation-aware labels (SessionDetail + VideoDetail); orientation-aware HistoryScreen arrow; FR/EN strings; parsing tests.
- **Docs** (`docs/01_jetson_companion.md`): document new fields in session summary/detail + video rows + video detail.

## Out of Scope

- No counting core changes (sister repo BL-83 already done).
- No `docs/IPC_CONTRACT.md` changes (already documents `direction` UP/DOWN + `counting_line_orientation` per session).
- No Jetson/counting validation.

## Architecture Decisions

- **Option A (additive)**: `count_down_to_up`/`count_up_to_down` added alongside existing `count_left_to_right`/`count_right_to_left` (unchanged). Forward-compatible — old app reading old summary still works (unknown fields tolerated by `optInt`/`optStringOrNull`).
- **Session-level directional counts**: the companion aggregates ALL four directional counts (LEFT/RIGHT + UP/DOWN) from the session's raw `crossed` events in `counting-history.jsonl`, as top-level fields in the `session_detail()` response. This works for running sessions (no `session_end` yet) and is consistent with the per-video aggregation. `session_end.counters` (countingapp-written, LEFT/RIGHT only) remains as-is for backward compat — the app's new Comptage card uses the companion-aggregated top-level fields, not `end.counters`.
- **Orientation resolver**: a small helper that reads `counting_line_orientation` from `session_start` first, falls back to `session_end`, defaults to `"vertical"` for pre-BL-83 sessions. Reused by all builders (summary, detail, video rows, video detail).
- **`counting_line_orientation` on video rows**: added to `/api/videos` rows (additive) so the app reads it directly — no separate `/api/sessions` fetch + client-side map.
- **Arrow semantics**: vertical line → positive `count_delta` (LEFT, +1) renders a left-pointing arrow, negative (RIGHT, −1) right-pointing; horizontal line → positive (UP, +1) up-pointing, negative (DOWN, −1) down-pointing. Uses `ArrowBack`/`ArrowForward` for horizontal, keeps `ArrowUpward`/`ArrowDownward` for vertical.

## Tasks

### Companion (`python3 -m py_compile` + pytest validation)

- [x] **Task 1: ADD orientation resolver helper + bump SERVICE_VERSION** `companion/jetson-companion.py` — Add a `_session_orientation(sess)` helper (or inline) that reads `sess["start"].get("counting_line_orientation")` or `sess["end"].get("counting_line_orientation")`, returns `"vertical"` when absent/invalid (pre-BL-83 default). Bump `SERVICE_VERSION = "8"` → `"9"` (line 88). Update the `GET /api/identify` version note (line ~74: `"version":"8"` → `"9"`).

- [x] **Task 2: ADD UP/DOWN aggregation + orientation to video_detail()** `companion/jetson-companion.py` — In `video_detail()` (lines ~644–740): alongside the existing LEFT/RIGHT loop (lines 651–655), add `elif d == "UP": count_down += 1` / `elif d == "DOWN": count_up += 1`. Add `"count_down_to_up": count_down` + `"count_up_to_down": count_up` + `"counting_line_orientation": _session_orientation(sess)` to the response dict (lines ~725–740). Initialize `count_down = 0` / `count_up = 0` next to `count_left`/`count_right` (line 647).

- [x] **Task 3: ADD orientation + session-level directional counts to session summary/detail** `companion/jetson-companion.py` — In `_summary_for()` (line 388): add `"counting_line_orientation": _session_orientation(sess)` to the returned dict. In `session_detail()` (line 436): aggregate all four directional counts from the session's `crossed` events (loop over `sess.get("events")`, count LEFT/RIGHT/UP/DOWN from `event_type == "crossed"` + `detail.direction`); add `"counting_line_orientation"`, `"count_left_to_right"`, `"count_right_to_left"`, `"count_down_to_up"`, `"count_up_to_down"` as top-level fields in the returned dict. (`end.counters` stays unchanged — pass-through.)

- [x] **Task 4: ADD orientation to /api/videos rows** `companion/jetson-companion.py` — In `video_summaries()` (line 502): for each video, resolve `sid = obj.get("session_id")`, `sess = self._sessions.get(sid)`, add `"counting_line_orientation": _session_orientation(sess) if sess else "vertical"` to the row dict. In `_running_video_row()` (line 525): resolve the session from `hb.get("session_id")` and add the same field to the returned row dict.

- [x] **Task 5: ADD companion tests — horizontal/vertical/mixed/pre-BL-83** `tests/test_companion_history_api.py` — Extend the `history_file` fixture (or add a second fixture) with: (a) a horizontal session (`counting_line_orientation: "horizontal"` in `session_start`, crossed events with `direction: "UP"`/`"DOWN"`) asserting `video_detail` returns `count_down_to_up`/`count_up_to_down` > 0 and `counting_line_orientation == "horizontal"`; (b) a vertical session asserting existing LEFT/RIGHT unchanged + `counting_line_orientation == "vertical"`; (c) a pre-BL-83 session (no `counting_line_orientation` in metadata) asserting orientation defaults to `"vertical"`; (d) session_detail returns session-level directional counts for both pairs. Assert `GET /api/identify` returns `"version":"9"`.

### App (`cd android && ./gradlew :app:assembleDebug` validation)

- [x] **Task 6: ADD new fields + parsers to Models.kt** `android/app/src/main/java/com/animalcounter/net/Models.kt` — (1) `VideoRow` (line ~131): add `val countingLineOrientation: String? = null`. (2) `VideoDetail` (line ~160): add `val countingLineOrientation: String? = null`, `val countDownToUp: Int = 0`, `val countUpToDown: Int = 0`. (3) `SessionSummary` (line ~100): add `val countingLineOrientation: String? = null`. (4) `SessionDetail` (line ~286): add `val countingLineOrientation: String? = null`, `val countLeftToRight: Int? = null`, `val countRightToLeft: Int? = null`, `val countDownToUp: Int? = null`, `val countUpToDown: Int? = null`. (5) Update parsers: `parseVideoRow` (line ~627) add `countingLineOrientation = o.optStringOrNull("counting_line_orientation")`; `parseVideoDetail` (line ~665) add the three new `optStringOrNull`/`optInt` lines; `parseSessionSummary` (line ~605) add `countingLineOrientation = o.optStringOrNull("counting_line_orientation")`; `parseSessionDetail` (line ~784) add the five new fields from the top-level JSON object `o`.

- [x] **Task 7: ADD "Comptage" card to SessionDetailScreen.kt** `android/app/src/main/java/com/animalcounter/ui/sessiondetail/SessionDetailScreen.kt` — Insert a new `ComptageCard(d)` composable call between `HeaderCard(d)` and `VideosCard(d)` (line ~168). The card: shows `d.netCount` as headline + directional rows based on `d.countingLineOrientation`. If `"horizontal"`: show `d.countDownToUp` / `d.countUpToDown` with labels `R.string.detail_count_dtu` ("Bas → Haut") / `R.string.detail_count_utd` ("Haut → Bas") and up/down arrow icons. Else (vertical/default): show `d.countLeftToRight` / `d.countRightToLeft` with labels `R.string.detail_count_ltr` ("Gauche → Droite") / `R.string.detail_count_rtl` ("Droite → Gauche") and left/right arrow icons. Revive/repurpose the existing `DirectionalRow` composable (line 401) to accept an arrow `ImageVector` parameter instead of the hardcoded `ArrowUpward`. Use `Icons.AutoMirrored.Filled.ArrowBack` (left) / `Icons.Filled.ArrowForward` (right) for vertical, `Icons.Filled.ArrowUpward` / `Icons.Filled.ArrowDownward` for horizontal.

- [x] **Task 8: MAKE VideoDetailScreen labels orientation-aware** `android/app/src/main/java/com/animalcounter/ui/sessiondetail/VideoDetailScreen.kt` — At lines 236–237: replace the hardcoded `R.string.detail_count_ltr` / `R.string.detail_count_rtl` with orientation-aware label selection based on `detail.countingLineOrientation`. If `"horizontal"`: use `R.string.detail_count_dtu` / `R.string.detail_count_utd` and show `detail.countDownToUp` / `detail.countUpToDown` instead of LEFT/RIGHT. Else (vertical/default): keep `R.string.detail_count_ltr` / `R.string.detail_count_rtl` with `detail.countLeftToRight` / `detail.countRightToLeft` (current behavior). The `detail` here is the `VideoDetail` fetched from `/api/videos/<id>` (loaded by `VideoDetailViewModel.loadDetail()`).

- [ ] **Task 9: MAKE HistoryScreen arrow orientation-aware** `android/app/src/main/java/com/animalcounter/ui/history/HistoryScreen.kt` — At line ~413: replace the fixed `if (delta >= 0) ArrowUpward else ArrowDownward` with orientation-aware logic. If `row.countingLineOrientation == "horizontal"`: `if (delta >= 0) ArrowUpward else ArrowDownward` (current arrows — UP/DOWN). Else (vertical/default): `if (delta >= 0) Icons.AutoMirrored.Filled.ArrowBack else Icons.Filled.ArrowForward` (LEFT/RIGHT). Add the `ArrowBack`/`ArrowForward` imports. The `VideoRow` already carries `countingLineOrientation` (added in Task 6); the row is available at line ~410 (`row`).

- [ ] **Task 10: ADD FR/EN strings** `android/app/src/main/res/values/strings.xml` + `android/app/src/main/res/values-fr/strings.xml` — Add `detail_count_dtu` ("Down → Up" / "Bas → Haut") and `detail_count_utd` ("Up → Down" / "Haut → Bas") near the existing `detail_count_ltr`/`detail_count_rtl` (line ~80). Optionally add a `group_comptage` string ("Counting" / "Comptage") for the new SessionDetailScreen card header.

- [ ] **Task 11: ADD app parsing tests** `android/app/src/test/java/com/animalcounter/net/JetsonClientParsingTest.kt` — Add test cases: (a) `parseVideoDetail` with `count_down_to_up` + `count_up_to_down` + `counting_line_orientation: "horizontal"` → fields parsed correctly; (b) `parseVideoRow` with `counting_line_orientation` → field parsed; (c) `parseSessionSummary` with `counting_line_orientation`; (d) `parseSessionDetail` with top-level `counting_line_orientation` + `count_down_to_up`/`count_up_to_down`/`count_left_to_right`/`count_right_to_left`; (e) backward-compat: `parseVideoDetail` without the new fields → `countDownToUp == 0`, `countUpToDown == 0`, `countingLineOrientation == null` (no crash).

### Docs

- [ ] **Task 12: DOCUMENT new fields in docs/01_jetson_companion.md** `docs/01_jetson_companion.md` — (1) Update the version note (line ~74: `"version":"8"` → `"9"`) and the v-version bump history. (2) Update the endpoint table (line ~147): note that `/api/videos` rows + `/api/sessions` summaries now carry `counting_line_orientation`, and `/api/sessions/<id>` + `/api/videos/<id>` carry `count_down_to_up`/`count_up_to_down` + `counting_line_orientation`. (3) Update the curl example for `/api/videos` (line ~363) to include `counting_line_orientation` in the row JSON. (4) Add a note that `counting_line_orientation` defaults to `"vertical"` for pre-BL-83 sessions, and that UP/DOWN fields are companion-aggregated from `crossed` events (additive, alongside the countingapp's `session_end.counters` LEFT/RIGHT).

## Validation

- **Companion**: `python3 -m py_compile companion/jetson-companion.py` (syntax) + `cd tests && python3 -m pytest test_companion_history_api.py -v` (horizontal/vertical/mixed/pre-BL-83 + version bump).
- **App**: `cd android && export JAVA_HOME=~/jdk-17 && export ANDROID_HOME=~/Android/Sdk && ./gradlew :app:assembleDebug --no-daemon --console=plain` (APK builds) + `./gradlew :app:testDebugUnitTest --no-daemon` (parsing tests).
- **Docs**: manual review of `docs/01_jetson_companion.md` new field documentation.

## Risks

- **`session_end.counters` vs companion-aggregated divergence**: for ended sessions, the countingapp's `session_end.counters` LEFT/RIGHT should match the companion's event-based aggregation. If they diverge (e.g., countingapp dedupes events differently), the SessionDetailScreen Comptage card (using companion-aggregated top-level fields) and any screen using `end.counters` could show different numbers. Mitigated by documenting that the Comptage card uses companion-aggregated counts; `end.counters` is kept as-is for backward compat.
- **Arrow icon availability**: `Icons.Filled.ArrowForward` exists in Material Icons; `Icons.AutoMirrored.Filled.ArrowBack` is already imported in SessionDetailScreen. HistoryScreen needs both new imports — verify they resolve at compile time.
- **Running-session orientation**: `_running_video_row()` synthesizes from the latest heartbeat; the session is resolved by `session_id` from the heartbeat. If the running session has no `session_start` with orientation (shouldn't happen post-BL-83), it defaults to `"vertical"`.
- **Forward-compat of old app**: an old app APK (pre-BL-85) reading new companion responses ignores unknown fields (`optInt` default 0, `optStringOrNull` → null) — no crash. A new app reading an old companion (pre-BL-85, SERVICE_VERSION < 9) gets null orientation → defaults to vertical labels — graceful degradation.