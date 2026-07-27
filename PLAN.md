# Plan: BL-77 follow-up — "À propos" (About) section in Settings

## Summary
Append a sixth "À propos" `Section` card to the existing Settings screen showing the app version (`BuildConfig.VERSION_NAME`) and the live Jetson companion version (fetched from `GET /api/identify`, field `version`). Small, self-contained, zero counting/core impact.

## In Scope
- Enable `buildConfig = true` in `android/app/build.gradle.kts` so `BuildConfig` is generated (disabled by default in AGP 8+).
- Add `JetsonClient.identifyVersion(ip, network): ApiResult<String>` — a lightweight GET `/api/identify` that reuses the existing `getJson` transport + `isValidIdentifyBody` validator and returns just the `version` string.
- Add `JetsonConnectionManager.identifyVersion(): Result<String>` — resolves the active IP (reuse `resolveActiveIp()` + `activeWifiNetworkSafe()`) then delegates to the client method.
- Add `companionVersion` UI state to `SettingsViewModel` (Idle / Loading / Loaded / Error) with a `refreshCompanionVersion()` entry point, auto-fetched on init (best-effort, mirroring `refreshSettingsFromJetson()`).
- Append the "À propos" `Section` to `SettingsScreen.kt` with two rows (app version + companion version), a small refresh button, and spinner/error states matching the existing visual rhythm.
- Add bilingual strings to `values/strings.xml` (English) and `values-fr/strings.xml` (French).
- Optional unit test for the new `identifyVersion` JSON parsing path.

## Out of Scope
- BL-66/67 placeholder tabs or any nav-scaffold changes.
- Any change to the probe/counting/SyncEvent logic or the `identify()` reachability path.
- New dependencies (OkHttp, etc.) or manifest/permission changes.
- Caching/persisting the companion version (live fetch only).

## Architecture Decisions
- **Dedicated `identifyVersion` method, not reuse of `identify()`**: the existing `JetsonClient.identify()` returns a `SyncEvent` (probe-typed, logged to `SyncLog`). A separate `identifyVersion` returning `ApiResult<String>` keeps the About fetch entirely off the probe/log path → zero counting/core impact, and gives the ViewModel a clean `Result<String>`.
- **Reuse `getJson` transport + `isValidIdentifyBody`**: the new client method uses the existing private `getJson(ip, path, network, parse)` helper (WiFi-bound, 5s timeouts, never throws) and the existing `isValidIdentifyBody` validator, so no new transport/validation code is written.
- **`BuildConfig.VERSION_NAME` for app version**: `versionName = "1.0"` is already in `defaultConfig`; `versionCode` is not shown (operator-facing display).
- **Auto-fetch on init + manual refresh**: mirrors the established `refreshSettingsFromJetson()` pattern — best-effort on screen open, a small refresh button for on-demand retry. Offline → "Hors ligne" state.
- **Bilingual strings**: the app already maintains `values/` (English) and `values-fr/` (French); all new user-facing text goes through `stringResource(R.string.*)`.

## Tasks
- [x] **Task 1: ENABLE BuildConfig** `android/app/build.gradle.kts` — add `buildConfig = true` inside the existing `buildFeatures { compose = true }` block so `com.animalcounter.BuildConfig` is generated and `BuildConfig.VERSION_NAME` is referenceable.
- [x] **Task 2: ADD `identifyVersion` to JetsonClient** `android/app/src/main/java/com/animalcounter/net/JetsonClient.kt` — add `suspend fun identifyVersion(ip: String, network: Network? = null): ApiResult<String>` that calls the existing private `getJson(ip, "/api/identify", network) { body -> ... }`; in the parse lambda, validate with `isValidIdentifyBody(body)`, then extract `JSONObject(body).optString("version")`, throwing `IllegalArgumentException` on an invalid body so `getJson` maps it to `ApiResult.NetworkError` (consistent with the other typed getters). Returns `ApiResult.Success(version)` on a valid 200.
- [x] **Task 3: ADD `identifyVersion` to JetsonConnectionManager** `android/app/src/main/java/com/animalcounter/net/JetsonConnectionManager.kt` — add `suspend fun identifyVersion(): Result<String>` mirroring `getSettings()`/`poweroff()`: `resolveActiveIp()` → `activeWifiNetworkSafe()` → `JetsonClient.identifyVersion(ip, network)`, mapping `ApiResult.Success` → `Result.success(version)`, `HttpError`/`NetworkError` → `Result.failure(...)`. Never throws.
- [x] **Task 4: ADD `companionVersion` state to SettingsViewModel** `android/app/src/main/java/com/animalcounter/ui/settings/SettingsViewModel.kt` — add a `sealed interface CompanionVersionState { Idle; Loading; Loaded(version: String); Error }` with a `_companionVersion` `MutableStateFlow` + public `companionVersion: StateFlow<CompanionVersionState>`; add `fun refreshCompanionVersion()` that sets Loading, launches in `viewModelScope`, calls `JetsonConnectionManager.identifyVersion()`, and maps success/failure; call `refreshCompanionVersion()` once in `init` alongside `refreshSettingsFromJetson()`.
- [x] **Task 5: ADD "À propos" Section to SettingsScreen** `android/app/src/main/java/com/animalcounter/ui/settings/SettingsScreen.kt` — after the 5th section (Ligne de comptage), append a 6th `Section(title = stringResource(R.string.section_about))` containing: (a) a row "Application : `<BuildConfig.VERSION_NAME>`" (label via `R.string.about_app_version`); (b) a row "Companion Jetson : …" (label via `R.string.about_companion_version`) whose value renders `Loaded` → version, `Loading` → `about_companion_loading` + small `CircularProgressIndicator`, `Error`/`Idle` → `about_companion_offline`; (c) a small `OutlinedButton` (`R.string.about_refresh`) calling `vm::refreshCompanionVersion`, disabled while `Loading`. Collect `companionVersion` state via `collectAsState()` alongside the existing fields.
- [x] **Task 6: ADD English strings** `android/app/src/main/res/values/strings.xml` — append: `section_about` ("About"), `about_app_version` ("App version"), `about_companion_version` ("Jetson companion"), `about_companion_offline` ("Offline"), `about_companion_loading` ("Retrieving…"), `about_companion_error` ("Unavailable"), `about_refresh` ("Refresh").
- [x] **Task 7: ADD French strings** `android/app/src/main/res/values-fr/strings.xml` — append the same keys: `section_about` ("À propos"), `about_app_version` ("Application"), `about_companion_version` ("Companion Jetson"), `about_companion_offline` ("Hors ligne"), `about_companion_loading` ("Récupération…"), `about_companion_error` ("Indisponible"), `about_refresh` ("Actualiser").
- [x] **Task 8 (optional): UNIT TEST** `android/app/src/test/java/com/animalcounter/net/IdentifyVersionTest.kt` — a JUnit test feeding a valid `{"service":"jetson-companion","version":"2"}` body and a non-Jetson body through the parse logic to confirm the version is extracted only for valid bodies. If the parse logic is inlined in the lambda, extract a small `internal fun parseIdentifyVersion(body: String): String` in `JetsonClient.kt` so the test can exercise it directly (mirrors the existing `isValidIdentifyBody` testability pattern).

## Validation
- `cd android && ./gradlew assembleDebug` builds the APK without errors (confirms `buildConfig = true` generates `BuildConfig`, the new code compiles, strings are well-formed).
- Manual: open the Settings tab on the Jetson hotspot → the "À propos" card shows "Application : 1.0" and "Companion Jetson : 2" (or current version); go off-range → shows "Hors ligne"; tap "Actualiser" → spinner then version or offline.
- If Task 8 is done: `cd android && ./gradlew test` passes the new unit test.

## Risks
- **`BuildConfig` not generated** if `buildConfig = true` is omitted → compile error referencing `BuildConfig.VERSION_NAME`. Mitigated by Task 1.
- **`isValidIdentifyBody` is `internal`** — the new client method is in the same module, so access is fine; no visibility change needed.
- **`getJson` parse lambda throwing** — by design, an invalid identify body throws inside the lambda so `getJson`'s `try/catch` maps it to `ApiResult.NetworkError`. This is consistent with the other typed getters' parse-failure handling.
- **Companion version field absent/empty** — `optString("version")` returns `""` on a missing key; the UI should treat an empty string as offline/unavailable rather than showing a blank. Task 5 handles this (blank → `about_companion_offline`).