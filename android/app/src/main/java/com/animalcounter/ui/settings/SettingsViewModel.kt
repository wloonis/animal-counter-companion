/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * animal-counter-companion — client/bridge layer (Android app + Jetson host companion HTTP bridge).
 * Copyright (C) 2026  LOONIS Wennaël
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.animalcounter.ui.settings

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.animalcounter.data.DEFAULT_BOX_TRACKING
import com.animalcounter.data.DEFAULT_CENTROID_TRACKING
import com.animalcounter.data.DEFAULT_COUNTING_LINE_ORIENTATION
import com.animalcounter.data.DEFAULT_DRAW_MASK_ZONES
import com.animalcounter.data.DEFAULT_DRAW_TRACKING
import com.animalcounter.data.DEFAULT_HOTSPOT_IP
import com.animalcounter.data.DEFAULT_JETSON_IP
import com.animalcounter.data.DEFAULT_LAN_IP
import com.animalcounter.data.DEFAULT_OFFSET_COUNTING_LINE
import com.animalcounter.data.OFFSET_SLIDER_MAX
import com.animalcounter.data.OFFSET_SLIDER_MIN
import com.animalcounter.data.SettingsRepository
import com.animalcounter.net.ClassCatalog
import com.animalcounter.net.JetsonConnectionManager
import com.animalcounter.net.JetsonSettings
import com.animalcounter.net.MaskZone
import com.animalcounter.net.PoweroffResponse
import com.animalcounter.net.SyncResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Debounce window (ms) before a typed IP is persisted to DataStore. */
private const val IP_PERSIST_DEBOUNCE_MS = 500L

/**
 * Debounce window (ms) before a tracking/offset setting change is pushed to
 * the Jetson via `PUT /api/settings`. Coalesces rapid slider/toggle flicker
 * into a single PATCH.
 */
private const val SETTINGS_PUSH_DEBOUNCE_MS = 600L

/**
 * State holder for the Settings screen (BL-73).
 *
 * Bridges the four configurable settings in [SettingsRepository] with the
 * composable fields, debouncing writes so the user can type freely. Each
 * field is backed by a local [MutableStateFlow] seeded once from DataStore
 * (one-shot [first]); subsequent edits update the local field immediately
 * and schedule a debounced persist.
 *
 * Behavior:
 *  - **Auto-select toggle**: flipping to `true` re-enables auto-select and
 *    triggers [JetsonConnectionManager.rescan] (the parallel probe picks up
 *    the candidate IPs again). Flipping to `false` just persists the flag
 *    (the manual IP field becomes the effective address).
 *  - **Manual IP field**: typing flips `autoSelect = false` (the manual
 *    override is now the active source) and persists the value; a debounced
 *    [JetsonConnectionManager.rescan] re-probes the manual IP.
 *  - **Candidate IP fields** (hotspot/lan): a debounced persist followed by
 *    [JetsonConnectionManager.rescan] so the parallel selection uses the new
 *    candidates on the next probe.
 *  - **On-demand clock sync** (BL-74): [syncTime] delegates to
 *    [JetsonConnectionManager.syncTime] and exposes the outcome via the
 *    [syncResult] state flow. Success auto-resets to `Idle` after ~5s so the
 *    green confirmation clears; Failure persists until the user retries or
 *    clears it with [clearSyncResult].
 *
 * Constructed with the default [AndroidViewModel] factory, which wires the
 * [Application] for the [SettingsRepository]'s DataStore.
 */
class SettingsViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = SettingsRepository(app)

    private val _autoSelect = MutableStateFlow(true)
    /** Whether auto-select is enabled (drives the toggle + manual field state). */
    val autoSelect: StateFlow<Boolean> = _autoSelect.asStateFlow()

    private val _manualIp = MutableStateFlow(DEFAULT_JETSON_IP)
    /** Manual-override Jetson IP (effective when [autoSelect] is `false`). */
    val manualIp: StateFlow<String> = _manualIp.asStateFlow()

    private val _hotspotIp = MutableStateFlow(DEFAULT_HOTSPOT_IP)
    /** Hotspot candidate IP probed by the auto-select parallel probe. */
    val hotspotIp: StateFlow<String> = _hotspotIp.asStateFlow()

    private val _lanIp = MutableStateFlow(DEFAULT_LAN_IP)
    /** LAN candidate IP probed by the auto-select parallel probe. */
    val lanIp: StateFlow<String> = _lanIp.asStateFlow()

    // ---- BL-76 runtime recording/tracking settings (cache + push) ----

    private val _drawTracking = MutableStateFlow(DEFAULT_DRAW_TRACKING)
    /**
     * Master "Track in recordings" toggle (`draw_tracking`). Drives the
     * enabled state of [boxTracking] / [centroidTracking] in the UI. The
     * last value pushed to the Jetson is cached here (DataStore is the
     * offline fallback; the on-device `runtime-settings.json` is the
     * source of truth at recording start).
     */
    val drawTracking: StateFlow<Boolean> = _drawTracking.asStateFlow()

    private val _boxTracking = MutableStateFlow(DEFAULT_BOX_TRACKING)
    /** "Boxes" sub-toggle (`box_tracking`). */
    val boxTracking: StateFlow<Boolean> = _boxTracking.asStateFlow()

    private val _centroidTracking = MutableStateFlow(DEFAULT_CENTROID_TRACKING)
    /** "Trails" sub-toggle (`centroid_tracking`). */
    val centroidTracking: StateFlow<Boolean> = _centroidTracking.asStateFlow()

    private val _offsetCountingLine = MutableStateFlow(DEFAULT_OFFSET_COUNTING_LINE)
    /**
     * Counting-line offset (`offset_counting_line`, BL-84: SIGNED, centered at 0;
     * practical UI range -50..50). Changing this affects the count; the UI warns
     * the user accordingly.
     */
    val offsetCountingLine: StateFlow<Int> = _offsetCountingLine.asStateFlow()

    private val _countingLineOrientation =
        MutableStateFlow(DEFAULT_COUNTING_LINE_ORIENTATION)
    /** (BL-84) Counting-line orientation: "vertical" | "horizontal". */
    val countingLineOrientation: StateFlow<String> =
        _countingLineOrientation.asStateFlow()

    // ---- BL-88 mask zones + snapshot ----

    private val _maskZones = MutableStateFlow<List<MaskZone>>(emptyList())
    /**
     * (BL-88) The in-memory mask-zone list (normalized axis-aligned rects
     * `{x,y,w,h} ∈ [0..1]`). Seeded from `GET /api/settings` on init; the
     * Jetson `runtime-settings.json` is the source of truth, but the draw UX
     * needs the live snapshot, so the list is held here (NOT cached in
     * DataStore). Edited locally via [addMaskZone]/[removeMaskZone]/
     * [updateMaskZone] and persisted to the Jetson via [saveMaskZones].
     */
    val maskZones: StateFlow<List<MaskZone>> = _maskZones.asStateFlow()

    private val _drawMaskZones = MutableStateFlow(DEFAULT_DRAW_MASK_ZONES)
    /**
     * (BL-88) Whether the countingapp draws the mask zones on screen
     * (`draw_mask_zones`). Seeded from `GET /api/settings` and cached in
     * DataStore (default `true`) for offline restore (mirrors the tracking
     * toggles). Edits schedule a `PUT` via [saveMaskZones].
     */
    val drawMaskZones: StateFlow<Boolean> = _drawMaskZones.asStateFlow()

    /**
     * (BL-88) UI-facing state of the camera snapshot fetch
     * (`GET /api/snapshot`). Surfaced to the Settings screen so the « Zones
     * de masquage » section can show the preview image, a spinner, an
     * « Aperçu pas encore disponible » placeholder (404), or an error.
     */
    sealed interface SnapshotState {
        /** No snapshot requested yet (default). */
        data object Idle : SnapshotState
        /** A snapshot fetch is in flight. */
        data object Loading : SnapshotState
        /** Snapshot decoded; [bitmap] holds the preview to draw zones on. */
        data class Loaded(val bitmap: Bitmap) : SnapshotState
        /** The countingapp has not written `snapshot.jpg` yet (HTTP 404) —
         *  transient; the user can retry. */
        data object Unavailable : SnapshotState
        /** No reachable Jetson, non-404 HTTP, network error, or decode failure. */
        data object Error : SnapshotState
    }

    private val _snapshot = MutableStateFlow<SnapshotState>(SnapshotState.Idle)
    /** Observable camera snapshot state for the « Zones de masquage » section. */
    val snapshot: StateFlow<SnapshotState> = _snapshot.asStateFlow()

    /**
     * (BL-88) UI-facing state of a `PUT /api/settings {mask_zones,
     * draw_mask_zones}` save. Surfaced to the Settings screen so the
     * « Enregistrer » button can show a spinner, a green confirmation, or
     * an error.
     */
    sealed interface MaskSaveState {
        /** No save requested yet (default). */
        data object Idle : MaskSaveState
        /** A save is in flight. */
        data object Saving : MaskSaveState
        /** Last save succeeded; the merged settings have been re-applied. */
        data object Saved : MaskSaveState
        /** No reachable Jetson, 400 on a validation error, or network error. */
        data object Error : MaskSaveState
    }

    private val _maskSave = MutableStateFlow<MaskSaveState>(MaskSaveState.Idle)
    /** Observable mask-zone save outcome for the « Enregistrer » button. */
    val maskSave: StateFlow<MaskSaveState> = _maskSave.asStateFlow()

    /**
     * UI-facing state of an on-demand Jetson poweroff (`POST /api/power`).
     * Surfaced to the Settings screen so the "Arrêter le Jetson" button can
     * show a spinner, a success, or an error message.
     */
    sealed interface PoweroffUiState {
        /** No poweroff requested yet (default). */
        data object Idle : PoweroffUiState
        /** A poweroff request is in flight (sentinel being written). */
        data object Loading : PoweroffUiState
        /** Sentinel written — the counting app will run the BL-62 poweroff. */
        data object Success : PoweroffUiState
        /** No reachable Jetson, non-2xx HTTP, or network error. */
        data class Error(val message: String?) : PoweroffUiState
    }

    private val _poweroffResult = MutableStateFlow<PoweroffUiState>(PoweroffUiState.Idle)
    /** Observable on-demand poweroff outcome for the "Arrêter le Jetson" button. */
    val poweroffResult: StateFlow<PoweroffUiState> = _poweroffResult.asStateFlow()

    /** State of an on-demand clock sync, surfaced to the Settings UI. */
    sealed interface SyncState {
        /** No sync pending/done (default + auto-cleared a few seconds after success). */
        data object Idle : SyncState
        /** A sync request is in flight. */
        data object Syncing : SyncState
        /** Last sync succeeded. */
        data object Success : SyncState
        /** Last sync failed; [message] carries a detail string when available. */
        data class Failure(val message: String?) : SyncState
    }

    private val _syncResult = MutableStateFlow<SyncState>(SyncState.Idle)
    /** Observable on-demand clock-sync outcome for the "Synchroniser l'heure" button. */
    val syncResult: StateFlow<SyncState> = _syncResult.asStateFlow()

    /** Auto-clear delay after a successful sync (ms). */
    private val syncSuccessClearDelayMs = 5000L

    /**
     * UI-facing state of the live Jetson companion version fetch
     * (`GET /api/identify`, BL-77 « À propos » card). Surfaced to the
     * Settings screen so the About card can show the version, a spinner,
     * or an offline state.
     */
    sealed interface CompanionVersionState {
        /** No fetch attempted yet (default before the init fetch resolves). */
        data object Idle : CompanionVersionState
        /** A version fetch is in flight. */
        data object Loading : CompanionVersionState
        /** The companion version was fetched successfully. */
        data class Loaded(val version: String) : CompanionVersionState
        /** No reachable Jetson, non-2xx HTTP, network error, or invalid body. */
        data object Error : CompanionVersionState
    }

    private val _companionVersion = MutableStateFlow<CompanionVersionState>(CompanionVersionState.Idle)
    /** Observable live Jetson companion version for the « À propos » card. */
    val companionVersion: StateFlow<CompanionVersionState> = _companionVersion.asStateFlow()

    /**
     * UI-facing state of the countable species catalog fetch (`GET /api/classes`,
     * BL-82). The catalog depends on the model deployed on the connected
     * Jetson; the selection is the live `counting_class_ids`.
     */
    sealed interface ClassCatalogState {
        /** No fetch attempted yet. */
        data object Idle : ClassCatalogState
        /** A fetch is in flight. */
        data object Loading : ClassCatalogState
        /** Catalog loaded; [catalog] holds the species + the current selection. */
        data class Loaded(val catalog: ClassCatalog) : ClassCatalogState
        /** The countingapp has not published `model-classes.json` yet (HTTP 404) —
         *  transient; the user can retry. */
        data object Unavailable : ClassCatalogState
        /** No reachable Jetson, non-404 HTTP, or network error. */
        data object Error : ClassCatalogState
    }

    private val _classCatalog = MutableStateFlow<ClassCatalogState>(ClassCatalogState.Idle)
    /** Observable countable-species catalog + selection for the Réglages section. */
    val classCatalog: StateFlow<ClassCatalogState> = _classCatalog.asStateFlow()

    /** Pending push job for a `counting_class_ids` change (debounced). */
    private var classIdsPushJob: Job? = null

    /** Whether the initial DataStore values have been loaded. */
    @Suppress("unused")
    private var loaded = false

    /** Pending debounced persist jobs, one per field (so cross-field edits don't cancel each other). */
    private var manualPersistJob: Job? = null
    private var hotspotPersistJob: Job? = null
    private var lanPersistJob: Job? = null

    /**
     * Pending debounced push job for the BL-76 tracking/offset settings. A
     * single shared job coalesces rapid toggle/slider flicker into one
     * `PUT /api/settings` PATCH carrying all four current values.
     */
    private var settingsPushJob: Job? = null

    init {
        // Seed the fields from DataStore (DEFAULT_* until first emit).
        viewModelScope.launch {
            _autoSelect.value = repo.autoSelect.first()
            _manualIp.value = repo.jetsonIp.first()
            _hotspotIp.value = repo.hotspotIp.first()
            _lanIp.value = repo.lanIp.first()
            _drawTracking.value = repo.drawTracking.first()
            _boxTracking.value = repo.boxTracking.first()
            _centroidTracking.value = repo.centroidTracking.first()
            _offsetCountingLine.value = repo.offsetCountingLine.first()
            _countingLineOrientation.value = repo.countingLineOrientation.first()
            _drawMaskZones.value = repo.drawMaskZones.first()
            loaded = true
            // Best-effort sync from the Jetson: if reachable, the on-device
            // runtime-settings.json overrides the local cache so the UI shows
            // the live values. Offline → keep the cached DataStore values.
            refreshSettingsFromJetson()
            // Best-effort fetch of the live companion version for the
            // « À propos » card (mirrors refreshSettingsFromJetson).
            refreshCompanionVersion()
            // BL-82: best-effort fetch of the countable species catalog +
            // current selection for the « Espèces comptées » section.
            refreshClasses()
        }
    }

    /**
     * Best-effort fetch of the live Jetson companion version
     * ([JetsonConnectionManager.identifyVersion]) for the « À propos »
     * card. Sets state to [CompanionVersionState.Loading], then maps a
     * non-blank success → [CompanionVersionState.Loaded], a blank success →
     * [CompanionVersionState.Error] (the field is treated as offline rather
     * than showing a blank), and any failure → [CompanionVersionState.Error].
     * Never throws.
     */
    fun refreshCompanionVersion() {
        _companionVersion.value = CompanionVersionState.Loading
        viewModelScope.launch {
            val result = JetsonConnectionManager.identifyVersion()
            _companionVersion.value = result.fold(
                onSuccess = { version ->
                    if (version.isNotBlank()) {
                        CompanionVersionState.Loaded(version)
                    } else {
                        CompanionVersionState.Error
                    }
                },
                onFailure = { CompanionVersionState.Error },
            )
        }
    }

    /**
     * Trigger an on-demand clock push ("Synchroniser l'heure"). Sets state
     * to [SyncState.Syncing], delegates to [JetsonConnectionManager.syncTime],
     * then sets [SyncState.Success] (auto-cleared after ~5s) or
     * [SyncState.Failure] (persists until the next user action).
     */
    fun syncTime() {
        _syncResult.value = SyncState.Syncing
        viewModelScope.launch {
            val outcome = JetsonConnectionManager.syncTime()
            when (outcome) {
                is SyncResult.Success -> {
                    _syncResult.value = SyncState.Success
                    // Auto-clear the green confirmation after a short delay.
                    viewModelScope.launch {
                        delay(syncSuccessClearDelayMs)
                        // Only reset if still Success (user may have triggered a retry).
                        if (_syncResult.value is SyncState.Success) {
                            _syncResult.value = SyncState.Idle
                        }
                    }
                }
                is SyncResult.Failure -> {
                    _syncResult.value = SyncState.Failure(outcome.message)
                }
            }
        }
    }

    /**
     * Manually reset [syncResult] to [SyncState.Idle] (e.g. before retrying
     * after a persistent Failure).
     */
    fun clearSyncResult() {
        _syncResult.value = SyncState.Idle
    }

    // ---- BL-76 runtime recording/tracking settings ----

    /**
     * Best-effort pull of the live runtime settings from the Jetson
     * (`GET /api/settings`). On success, the four fields are updated from
     * the response (only the keys present in the merged object; absent keys
     * keep the cached value) and persisted back to DataStore so the cache
     * stays fresh. Offline / non-2xx / parse failure → silently keep the
     * cached values (the UI already shows them).
     */
    fun refreshSettingsFromJetson() {
        viewModelScope.launch {
            val result = JetsonConnectionManager.getSettings()
            result.onSuccess { s ->
                s.drawTracking?.let {
                    _drawTracking.value = it
                    repo.setDrawTracking(it)
                }
                s.boxTracking?.let {
                    _boxTracking.value = it
                    repo.setBoxTracking(it)
                }
                s.centroidTracking?.let {
                    _centroidTracking.value = it
                    repo.setCentroidTracking(it)
                }
                s.offsetCountingLine?.let {
                    _offsetCountingLine.value = it
                    repo.setOffsetCountingLine(it)
                }
                s.countingLineOrientation?.let {
                    _countingLineOrientation.value = it
                    repo.setCountingLineOrientation(it)
                }
                // (BL-88) mask zones are kept in-memory only (not cached);
                // draw_mask_zones is mirrored to DataStore like the tracking
                // toggles.
                s.maskZones?.let { _maskZones.value = it }
                s.drawMaskZones?.let {
                    _drawMaskZones.value = it
                    repo.setDrawMaskZones(it)
                }
            }
        }
    }

    /**
     * (BL-88) Best-effort fetch of the camera snapshot (`GET /api/snapshot`).
     * Sets state to [SnapshotState.Loading], decodes the JPEG bytes into a
     * [Bitmap] off the main thread (`Dispatchers.Default`) via
     * `BitmapFactory.decodeByteArray`, then maps the result onto
     * [SnapshotState]: Loaded on 200 + a decodable image, Unavailable on 404
     * (the countingapp has not written `snapshot.jpg` yet), Error otherwise
     * (no reachable Jetson, non-404 HTTP, network error, or a null decode).
     * Never throws. Called by the « Capturer l'aperçu » button + the retry
     * affordances.
     */
    fun refreshSnapshot() {
        _snapshot.value = SnapshotState.Loading
        viewModelScope.launch {
            val result = JetsonConnectionManager.getSnapshot()
            _snapshot.value = result.fold(
                onSuccess = { bytes ->
                    val bmp = withContext(Dispatchers.Default) {
                        if (bytes.isEmpty()) null
                        else BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    }
                    if (bmp != null) SnapshotState.Loaded(bmp)
                    else SnapshotState.Error
                },
                onFailure = { e ->
                    val msg = e.message ?: ""
                    if (msg.contains("HTTP 404")) SnapshotState.Unavailable
                    else SnapshotState.Error
                },
            )
        }
    }

    /**
     * (BL-88) Add a mask zone (a normalized axis-aligned rect drawn by the
     * operator). Updates the in-memory list immediately (the PUT is explicit
     * via [saveMaskZones]). A drawn rect is always valid by construction, but
     * the caller should clamp each field to `[0..1]` against the displayed
     * image bounds before calling.
     */
    fun addMaskZone(zone: MaskZone) {
        _maskZones.value = _maskZones.value + zone
    }

    /**
     * (BL-88) Remove the mask zone at [index]. No-op when out of range.
     */
    fun removeMaskZone(index: Int) {
        val current = _maskZones.value
        if (index !in current.indices) return
        _maskZones.value = current.toMutableList().apply { removeAt(index) }
    }

    /**
     * (BL-88) Replace the mask zone at [index] with [zone]. No-op when out of
     * range (the draw overlay rebuilds the list from scratch on each drag, so
     * this is mainly for tests/future fine-edit).
     */
    fun updateMaskZone(index: Int, zone: MaskZone) {
        val current = _maskZones.value
        if (index !in current.indices) return
        _maskZones.value = current.toMutableList().apply { this[index] = zone }
    }
    /** Rename an existing mask zone (app-local label). */
    fun renameMaskZone(index: Int, name: String) {
        val current = _maskZones.value
        if (index !in current.indices) return
        _maskZones.value = current.toMutableList().apply {
            this[index] = this[index].copy(name = name)
        }
    }
    /** Move an existing mask zone to a new top-left (x, y) in normalized
     *  [0..1] coords, clamped so the whole rect stays inside the frame. */
    fun moveMaskZone(index: Int, x: Float, y: Float) {
        val current = _maskZones.value
        if (index !in current.indices) return
        val z = current[index]
        val nx = x.coerceIn(0f, 1f - z.w)
        val ny = y.coerceIn(0f, 1f - z.h)
        _maskZones.value = current.toMutableList().apply {
            this[index] = this[index].copy(x = nx, y = ny)
        }
    }
    /** Resize an existing mask zone by dragging one or more edges. The flags
     *  select which edges move (left/right/top/bottom); `dxN`/`dyN` are the
     *  incremental normalized deltas. Clamped to keep the rect inside [0..1]
     *  with a minimum size (anti-collapse). */
    fun resizeMaskZone(
        index: Int,
        left: Boolean,
        right: Boolean,
        top: Boolean,
        bottom: Boolean,
        dxN: Float,
        dyN: Float,
    ) {
        val current = _maskZones.value
        if (index !in current.indices) return
        val z = current[index]
        var nx = z.x
        var ny = z.y
        var nw = z.w
        var nh = z.h
        if (left)   { nx = z.x + dxN; nw = z.w - dxN }
        if (right)  { nw = z.w + dxN }
        if (top)    { ny = z.y + dyN; nh = z.h - dyN }
        if (bottom) { nh = z.h + dyN }
        val min = 0.02f
        // Enforce a minimum size, keeping the non-dragged edge fixed.
        if (nw < min) {
            if (left) nx = z.x + z.w - min
            nw = min
        }
        if (nh < min) {
            if (top) ny = z.y + z.h - min
            nh = min
        }
        // Clamp inside the frame (position first, then size).
        nx = nx.coerceIn(0f, 1f - nw)
        ny = ny.coerceIn(0f, 1f - nh)
        nw = nw.coerceIn(min, 1f - nx)
        nh = nh.coerceIn(min, 1f - ny)
        _maskZones.value = current.toMutableList().apply {
            this[index] = this[index].copy(x = nx, y = ny, w = nw, h = nh)
        }
    }

    /**
     * (BL-88) Toggle whether the countingapp draws the mask zones on screen
     * (`draw_mask_zones`). Updates the local flow + DataStore cache (mirrors
     * the tracking toggles); the Jetson push is explicit via [saveMaskZones].
     */
    fun setDrawMaskZones(value: Boolean) {
        _drawMaskZones.value = value
        viewModelScope.launch { repo.setDrawMaskZones(value) }
    }

    /**
     * (BL-88) Persist the current mask-zone list + the `draw_mask_zones`
     * toggle to the Jetson via `PUT /api/settings {mask_zones,
     * draw_mask_zones}`. Sets state to [MaskSaveState.Saving], then on success
     * re-applies the echoed merged settings (so the in-memory list reflects
     * the server-side truth) and sets [MaskSaveState.Saved]; on failure sets
     * [MaskSaveState.Error]. Never throws.
     */
    fun saveMaskZones() {
        _maskSave.value = MaskSaveState.Saving
        viewModelScope.launch {
            val body = JetsonSettings(
                maskZones = _maskZones.value,
                drawMaskZones = _drawMaskZones.value,
            )
            val result = JetsonConnectionManager.putSettings(body)
            _maskSave.value = result.fold(
                onSuccess = { s ->
                    s.maskZones?.let { _maskZones.value = it }
                    s.drawMaskZones?.let {
                        _drawMaskZones.value = it
                        repo.setDrawMaskZones(it)
                    }
                    MaskSaveState.Saved
                },
                onFailure = { MaskSaveState.Error },
            )
        }
    }

    /** (BL-88) Reset [maskSave] to [MaskSaveState.Idle] (e.g. before retrying). */
    fun clearMaskSaveState() {
        _maskSave.value = MaskSaveState.Idle
    }

    /**
     * (BL-82) Best-effort fetch of the countable species catalog + the current
     * `counting_class_ids` selection (`GET /api/classes`). Maps the result onto
     * [ClassCatalogState]: Loaded on 200, Unavailable on 404 (the countingapp
     * has not published `model-classes.json` yet), Error otherwise. Never
     * throws. Called at init and by the « Espèces comptées » section's
     * retry affordance.
     */
    fun refreshClasses() {
        _classCatalog.value = ClassCatalogState.Loading
        viewModelScope.launch {
            val result = JetsonConnectionManager.getClasses()
            _classCatalog.value = result.fold(
                onSuccess = { ClassCatalogState.Loaded(it) },
                onFailure = { e ->
                    val msg = e.message ?: ""
                    if (msg.contains("HTTP 404")) ClassCatalogState.Unavailable
                    else ClassCatalogState.Error
                },
            )
        }
    }

    /**
     * (BL-82) Toggle whether [id] is in the counting selection. Updates the
     * Loaded catalog state in place (so the switch flips instantly) and
     * schedules a debounced `PUT /api/settings {counting_class_ids}` push.
     * Hot-reloaded by the countingapp at the next recording start (no restart).
     * No-op when the catalog is not Loaded.
     */
    fun toggleClass(id: Int) {
        val current = (_classCatalog.value as? ClassCatalogState.Loaded)?.catalog ?: return
        val newSelection = if (id in current.countingClassIds) {
            current.countingClassIds - id
        } else {
            current.countingClassIds + id
        }
        // Update the state immediately for a responsive UI (the PUT is best-effort).
        _classCatalog.value = ClassCatalogState.Loaded(current.copy(countingClassIds = newSelection))
        classIdsPushJob?.cancel()
        classIdsPushJob = viewModelScope.launch {
            delay(SETTINGS_PUSH_DEBOUNCE_MS)
            JetsonConnectionManager.putSettings(JetsonSettings(countingClassIds = newSelection))
        }
    }

    /**
     * Master "Track in recordings" toggle change. Updates the local flow +
     * cache, then schedules a debounced `PUT /api/settings` push to the
     * Jetson carrying all four current values.
     */
    fun setDrawTracking(value: Boolean) {
        _drawTracking.value = value
        viewModelScope.launch { repo.setDrawTracking(value) }
        scheduleSettingsPush()
    }

    /** "Boxes" sub-toggle change. */
    fun setBoxTracking(value: Boolean) {
        _boxTracking.value = value
        viewModelScope.launch { repo.setBoxTracking(value) }
        scheduleSettingsPush()
    }

    /** "Trails" sub-toggle change. */
    fun setCentroidTracking(value: Boolean) {
        _centroidTracking.value = value
        viewModelScope.launch { repo.setCentroidTracking(value) }
        scheduleSettingsPush()
    }

    /**
     * Counting-line offset slider change (BL-84: SIGNED, centered at 0).
     * [value] is clamped to the practical UI range [OFFSET_SLIDER_MIN,
     * OFFSET_SLIDER_MAX] by the repository. Updates the local flow + cache,
     * then schedules a debounced push.
     */
    fun setOffsetCountingLine(value: Int) {
        _offsetCountingLine.value = value.coerceIn(OFFSET_SLIDER_MIN, OFFSET_SLIDER_MAX)
        viewModelScope.launch { repo.setOffsetCountingLine(value) }
        scheduleSettingsPush()
    }

    /**
     * (BL-84) Counting-line orientation change ("vertical" | "horizontal").
     * Updates the local flow + cache, then schedules a debounced push.
     */
    fun setCountingLineOrientation(value: String) {
        val canonical = if (value == "horizontal") "horizontal" else "vertical"
        _countingLineOrientation.value = canonical
        viewModelScope.launch { repo.setCountingLineOrientation(canonical) }
        scheduleSettingsPush()
    }

    /**
     * Coalesce tracking/offset edits into a single debounced `PUT
     * /api/settings` carrying all four current values (a PATCH that rewrites
     * the four UI-managed keys). Best-effort: a push failure does not reset
     * the UI (the cache is the offline source of truth; the next refresh or
     * edit retries implicitly).
     */
    private fun scheduleSettingsPush() {
        settingsPushJob?.cancel()
        settingsPushJob = viewModelScope.launch {
            delay(SETTINGS_PUSH_DEBOUNCE_MS)
            val body = JetsonSettings(
                drawTracking = _drawTracking.value,
                boxTracking = _boxTracking.value,
                centroidTracking = _centroidTracking.value,
                offsetCountingLine = _offsetCountingLine.value,
                countingLineOrientation = _countingLineOrientation.value,
            )
            JetsonConnectionManager.putSettings(body)
        }
    }

    // ---- BL-76 on-demand Jetson poweroff ----

    /**
     * Request a Jetson poweroff (`POST /api/power`). Sets state to
     * [PoweroffUiState.Loading], delegates to
     * [JetsonConnectionManager.poweroff] (which writes the
     * `.arret_requested` sentinel; the counting app consumes it and runs
     * the BL-62 poweroff sequence), then sets [PoweroffUiState.Success] or
     * [PoweroffUiState.Error]. Success persists (the Jetson is going down);
     * call [clearPoweroffResult] to reset.
     */
    fun poweroff() {
        _poweroffResult.value = PoweroffUiState.Loading
        viewModelScope.launch {
            val result: Result<PoweroffResponse> = JetsonConnectionManager.poweroff()
            _poweroffResult.value = result.fold(
                onSuccess = { PoweroffUiState.Success },
                onFailure = { PoweroffUiState.Error(it.message) },
            )
        }
    }

    /** Reset [poweroffResult] to [PoweroffUiState.Idle]. */
    fun clearPoweroffResult() {
        _poweroffResult.value = PoweroffUiState.Idle
    }

    /**
     * Toggle auto-select. Re-enabling auto triggers a fresh parallel
     * selection probe so the banner resolves quickly.
     */
    fun setAutoSelect(value: Boolean) {
        _autoSelect.value = value
        viewModelScope.launch {
            repo.setAutoSelect(value)
            if (value) JetsonConnectionManager.rescan()
        }
    }

    /**
     * Manual-override IP field edit. Typing flips [autoSelect] to `false`
     * (the manual override becomes the active source) and (after a
     * debounce) persists the value + re-probes the manual IP.
     */
    fun onManualIpChange(value: String) {
        _manualIp.value = value
        _autoSelect.value = false
        manualPersistJob?.cancel()
        manualPersistJob = viewModelScope.launch {
            delay(IP_PERSIST_DEBOUNCE_MS)
            repo.setAutoSelect(false)
            repo.setJetsonIp(value)
            JetsonConnectionManager.rescan()
        }
    }

    /**
     * Hotspot candidate IP field edit. Debounced persist + re-probe so the
     * next parallel selection uses the new candidate.
     */
    fun onHotspotIpChange(value: String) {
        _hotspotIp.value = value
        hotspotPersistJob?.cancel()
        hotspotPersistJob = viewModelScope.launch {
            delay(IP_PERSIST_DEBOUNCE_MS)
            repo.setHotspotIp(value)
            JetsonConnectionManager.rescan()
        }
    }

    /**
     * LAN candidate IP field edit. Debounced persist + re-probe so the
     * next parallel selection uses the new candidate.
     */
    fun onLanIpChange(value: String) {
        _lanIp.value = value
        lanPersistJob?.cancel()
        lanPersistJob = viewModelScope.launch {
            delay(IP_PERSIST_DEBOUNCE_MS)
            repo.setLanIp(value)
            JetsonConnectionManager.rescan()
        }
    }
}