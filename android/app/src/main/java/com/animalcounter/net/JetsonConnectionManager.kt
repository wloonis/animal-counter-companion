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

package com.animalcounter.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.animalcounter.data.DEFAULT_HOTSPOT_IP
import com.animalcounter.data.DEFAULT_JETSON_IP
import com.animalcounter.data.DEFAULT_LAN_IP
import com.animalcounter.data.SettingsRepository
import com.animalcounter.data.SyncEvent
import com.animalcounter.data.SyncLog
import com.animalcounter.net.ApiResult
import com.animalcounter.net.JetsonClient
import com.animalcounter.net.JetsonSettings
import com.animalcounter.net.PoweroffResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.time.ZoneId

/**
 * Per-probe deadline for the parallel candidate race. The first strict-valid
 * `GET /api/identify` wins (see [parallelProbe] — a true `select` race, so a
 * failing/unreachable candidate does NOT block the reachable one). Set above
 * the 5s per-request connect timeout in [JetsonClient] so that when ALL
 * candidates are unreachable the race resolves at ~5s via the connect timeout
 * (not the probe deadline); when a candidate IS reachable it wins in ~200ms
 * regardless of any unreachable candidate still hanging.
 */
private const val PROBE_TIMEOUT_MS = 6_000L

/**
 * Outcome of an on-demand clock push ([syncTime]). Surfaced to the Settings
 * screen so the "Synchroniser l'heure" button can show an inline result.
 */
sealed interface SyncResult {
    /** `POST /api/time` returned 200. */
    data object Success : SyncResult
    /** No reachable Jetson, a non-2xx HTTP response, or a network error. */
    data class Failure(val message: String?) : SyncResult
}

/**
 * App-lifecycle-scoped connection manager for the Jetson companion (BL-73).
 *
 * Owns the *single canonical* reachability state for the app and resolves
 * which Jetson IP the rest of the app should talk to. It replaces both the
 * per-ViewModel `probe()` methods and the old background foreground
 * time-sync service: there is **no** boot or background time-sync anymore —
 * everything here runs ONLY while the app is in the foreground (the activity
 * `ON_START`→[start], `ON_STOP`→[stop]).
 *
 * Responsibilities:
 *  - Expose [probeState] (the app-wide « Jetson connecté / hors de portée »
 *    banner) and [activeIp] (delegated to [SettingsRepository], the single
 *    IP ViewModels use for `GET /api/...`).
 *  - Register a `TRANSPORT_WIFI` [ConnectivityManager.NetworkCallback]:
 *    `onAvailable` → [rescan] (re-select the active IP, **no** automatic
 *    time push); `onLost` → OutOfRange banner.
 *  - [rescan]: a **parallel** strict probe of both candidate IPs (hotspot +
 *    lan) when [SettingsRepository.autoSelect] is on, or a single probe of
 *    the manual-override IP when it is off. Bound to the active WiFi
 *    [Network] via [activeWifiNetwork] so the request reaches the Jetson
 *    HotSpot even with mobile data (5G) as the default internet uplink.
 *    First strict-valid [JetsonClient.identify] hit wins → the IP is written
 *    into [SettingsRepository.activeIp] (via [setActiveIp]); no hit →
 *    OutOfRange. **No clock push here** anymore.
 *  - [syncTime]: the on-demand, user-triggered clock push ("Synchroniser
 *    l'heure" button in Settings). If [SettingsRepository.activeIp] is
 *    already set, it posts directly to it; otherwise it runs a fresh
 *    selection probe first. Returns a [SyncResult] for the Settings UI.
 *
 * There is **no keep-alive loop** anymore (BL-74): the ~30s re-probe and its
 * automatic `POST /api/time` were removed. The `NetworkCallback.onAvailable`
 * still fires on WiFi changes (so IP selection re-runs), and the on-demand
 * [syncTime] covers the time needs. The Jetson now keeps its own time via a
 * DS3231 hardware RTC ([docs/13_rtc_install.md]).
 *
 * Everything is cancelled by [stop] (the activity `ON_STOP`): the
 * NetworkCallback is unregistered and the coroutine scope is cancelled.
 * Nothing here ever runs in the background or at boot.
 */
object JetsonConnectionManager {

    /** App-wide reachability banner state. Driven solely by this manager. */
    private val _probeState = MutableStateFlow(ProbeState.Idle)
    val probeState: StateFlow<ProbeState> = _probeState.asStateFlow()

    /**
     * The resolved active Jetson IP. Delegated to [SettingsRepository] so
     * ViewModels that read `repo.activeIp` and the manager stay in sync with
     * a single source of truth. Defaults to the hotspot candidate until a
     * probe resolves a reachable IP shortly after app open. Returns the
     * hotspot default when the manager has not been [start]ed yet.
     */
    val activeIp: StateFlow<String>
        get() = repo?.activeIp ?: MutableStateFlow(DEFAULT_HOTSPOT_IP)

    /** App-scoped coroutine scope — created on [start], cancelled on [stop]. */
    private var scope: CoroutineScope? = null

    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile
    private var onWifi: Boolean = false

    /** Lazily-initialized settings repo; bound to the app context on [start]. */
    private var repo: SettingsRepository? = null

    /**
     * Begin app-foreground connection management. Idempotent — a second call
     * without an intervening [stop] is a no-op. The activity calls this on
     * `ON_START`.
     */
    fun start(context: Context) {
        if (scope != null) return
        val appContext = context.applicationContext
        repo = SettingsRepository(appContext)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        connectivityManager = appContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        registerNetworkCallback()
        // Best-effort immediate probe: if the phone is already on the Jetson
        // WiFi when the app opens, onAvailable will not fire again, so probe
        // now to resolve the active IP ASAP.
        rescan()
    }

    /**
     * Stop app-foreground connection management. Unregisters the WiFi
     * callback and cancels the coroutine scope (any in-flight probe). The
     * activity calls this on `ON_STOP`.
     */
    fun stop() {
        unregisterNetworkCallback()
        scope?.cancel()
        scope = null
        onWifi = false
        _probeState.value = ProbeState.Idle
    }

    /**
     * Run a fresh selection probe.
     *
     * - `autoSelect = true` (default): probe BOTH candidate IPs in parallel
     *   (hotspot + lan); the first strict-valid [JetsonClient.identify] hit
     *   wins (race). On a hit → [SettingsRepository.setActiveIp] +
     *   `probeState = Reachable`. No hit → `probeState = OutOfRange`.
     * - `autoSelect = false`: probe only the manual-override IP
     *   ([SettingsRepository.jetsonIp]); same hit/none handling.
     *
     * This is **IP selection only** — no automatic clock push. The clock is
     * pushed on demand via [syncTime].
     *
     * Safe to call from the UI thread (offloads work to the manager scope).
     */
    fun rescan() {
        val s = scope ?: return
        val r = repo ?: return
        s.launch {
            _probeState.value = ProbeState.Probing
            val network = activeWifiNetworkSafe()
            val auto = runCatching { r.autoSelect.first() }.getOrDefault(true)
            val resolved = if (auto) {
                val hotspot = runCatching { r.hotspotIp.first() }.getOrDefault(DEFAULT_HOTSPOT_IP)
                val lan = runCatching { r.lanIp.first() }.getOrDefault(DEFAULT_LAN_IP)
                parallelProbe(setOf(hotspot, lan), network)
            } else {
                val manual = runCatching { r.jetsonIp.first() }.getOrDefault(DEFAULT_JETSON_IP)
                singleProbe(manual, network)
            }
            if (resolved != null) {
                r.setActiveIp(resolved)
                _probeState.value = ProbeState.Reachable
            } else {
                _probeState.value = ProbeState.OutOfRange
            }
        }
    }

    /**
     * On-demand clock push — "Synchroniser l'heure" (BL-74 replacement for the
     * removed keep-alive time loop).
     *
     * If [SettingsRepository.activeIp] is already set (non-blank), posts
     * directly to it. Otherwise runs a fresh selection probe first (mirrors
     * [rescan]) so the button works even before the auto-probe has resolved
     * an IP. The probe result is *not* logged to [SyncLog] (it's a pure IP
     * selection); only the final `POST /api/time` outcome is logged.
     *
     * @return [SyncResult.Success] on HTTP 200, otherwise
     *   [SyncResult.Failure] (no reachable Jetson, non-2xx, or network error).
     */
    suspend fun syncTime(): SyncResult {
        val r = repo ?: return SyncResult.Failure("Manager not started")
        val network = activeWifiNetworkSafe()
        val active = r.activeIp.value
        val ip = if (!active.isNullOrBlank()) {
            active
        } else {
            val auto = runCatching { r.autoSelect.first() }.getOrDefault(true)
            val resolved = if (auto) {
                val hotspot = runCatching { r.hotspotIp.first() }.getOrDefault(DEFAULT_HOTSPOT_IP)
                val lan = runCatching { r.lanIp.first() }.getOrDefault(DEFAULT_LAN_IP)
                parallelProbe(setOf(hotspot, lan), network)
            } else {
                val manual = runCatching { r.jetsonIp.first() }.getOrDefault(DEFAULT_JETSON_IP)
                singleProbe(manual, network)
            } ?: return SyncResult.Failure("Jetson introuvable")
            r.setActiveIp(resolved)
            resolved
        }
        val event = postTime(ip, network)
        return if (event.outcome == SyncEvent.Outcome.Success) {
            SyncResult.Success
        } else {
            SyncResult.Failure(event.detail)
        }
    }


    /**
     * Resolve the active Jetson IP for an on-demand call, mirroring [syncTime]:
     * if [SettingsRepository.activeIp] is already set (non-blank) it is used
     * directly, otherwise a fresh selection probe is run (parallel when
     * `autoSelect`, single otherwise) and its result is persisted via
     * [SettingsRepository.setActiveIp]. Returns `null` when no Jetson is
     * reachable (the caller surfaces a failure to the UI).
     */
    private suspend fun resolveActiveIp(): String? {
        val r = repo ?: return null
        val network = activeWifiNetworkSafe()
        val active = r.activeIp.value
        if (!active.isNullOrBlank()) return active
        val auto = runCatching { r.autoSelect.first() }.getOrDefault(true)
        val resolved = if (auto) {
            val hotspot = runCatching { r.hotspotIp.first() }.getOrDefault(DEFAULT_HOTSPOT_IP)
            val lan = runCatching { r.lanIp.first() }.getOrDefault(DEFAULT_LAN_IP)
            parallelProbe(setOf(hotspot, lan), network)
        } else {
            val manual = runCatching { r.jetsonIp.first() }.getOrDefault(DEFAULT_JETSON_IP)
            singleProbe(manual, network)
        } ?: return null
        r.setActiveIp(resolved)
        return resolved
    }

    /**
     * On-demand `POST /api/power` (BL-76) — writes the `.arret_requested`
     * sentinel on the Jetson; the counting app consumes it and runs the BL-62
     * poweroff sequence. Reuses the same IP resolution + WiFi-bound transport
     * as [syncTime]: if [SettingsRepository.activeIp] is already set it posts
     * directly to it, otherwise a fresh selection probe runs first.
     *
     * @return [Result.success] with the [PoweroffResponse] on HTTP 200, or
     *   [Result.failure] (no reachable Jetson, non-2xx HTTP, or network
     *   error). Never throws.
     */
    suspend fun poweroff(): Result<PoweroffResponse> {
        val ip = resolveActiveIp() ?: return Result.failure(IllegalStateException("Jetson introuvable"))
        val network = activeWifiNetworkSafe()
        return when (val res = JetsonClient.postPower(ip = ip, network = network)) {
            is ApiResult.Success -> Result.success(res.data)
            is ApiResult.HttpError -> Result.failure(IllegalStateException("HTTP ${res.code}"))
            is ApiResult.NetworkError -> Result.failure(IllegalStateException(res.message))
        }
    }

    /**
     * On-demand `GET /api/identify` (BL-77 About card) — a lightweight
     * fetch that returns just the Jetson companion `version` string.
     * Reuses the same IP resolution + WiFi-bound transport as [syncTime]
     * and the dedicated [JetsonClient.identifyVersion] method (it does
     * NOT touch the probe/`SyncLog` path, so it has zero counting/core
     * impact). The returned version may be blank when the field is absent
     * on the companion; the caller (Settings « À propos » card) treats a
     * blank as offline/unavailable.
     *
     * @return [Result.success] with the version string on HTTP 200, or
     *   [Result.failure] (no reachable Jetson, non-2xx HTTP, network error,
     *   or an invalid identify body). Never throws.
     */
    suspend fun identifyVersion(): Result<String> {
        val ip = resolveActiveIp() ?: return Result.failure(IllegalStateException("Jetson introuvable"))
        val network = activeWifiNetworkSafe()
        return when (val res = JetsonClient.identifyVersion(ip = ip, network = network)) {
            is ApiResult.Success -> Result.success(res.data)
            is ApiResult.HttpError -> Result.failure(IllegalStateException("HTTP ${res.code}"))
            is ApiResult.NetworkError -> Result.failure(IllegalStateException(res.message))
        }
    }

    /**
     * On-demand `GET /api/settings` (BL-76) — fetches the merged
     * `runtime-settings.json` from the Jetson (an empty object → an
     * all-`null` [JetsonSettings] when the file is absent). Reuses the same
     * IP resolution + WiFi-bound transport as [syncTime].
     *
     * @return [Result.success] with the [JetsonSettings] on HTTP 200, or
     *   [Result.failure] (no reachable Jetson, non-2xx HTTP, or network
     *   error). Never throws.
     */
    suspend fun getSettings(): Result<JetsonSettings> {
        val ip = resolveActiveIp() ?: return Result.failure(IllegalStateException("Jetson introuvable"))
        val network = activeWifiNetworkSafe()
        return when (val res = JetsonClient.getSettings(ip = ip, network = network)) {
            is ApiResult.Success -> Result.success(res.data)
            is ApiResult.HttpError -> Result.failure(IllegalStateException("HTTP ${res.code}"))
            is ApiResult.NetworkError -> Result.failure(IllegalStateException(res.message))
        }
    }

    /**
     * (BL-88) On-demand `GET /api/snapshot` — the camera preview JPEG bytes
     * served by the companion from `/files/snapshot.jpg`. Reuses the same
     * IP resolution + WiFi-bound transport as [getSettings] (the binary
     * [JetsonClient.getSnapshot] twin of the JSON getters). The caller
     * decodes the bytes into a [android.graphics.Bitmap] off the main thread.
     *
     * @return [Result.success] with the raw JPEG [ByteArray] on HTTP 200, or
     *   [Result.failure] (no reachable Jetson, non-2xx HTTP — including 404
     *   when no snapshot has been written yet — or network error). Never
     *   throws.
     */
    suspend fun getSnapshot(): Result<ByteArray> {
        val ip = resolveActiveIp() ?: return Result.failure(IllegalStateException("Jetson introuvable"))
        val network = activeWifiNetworkSafe()
        return when (val res = JetsonClient.getSnapshot(ip = ip, network = network)) {
            is ApiResult.Success -> Result.success(res.data)
            is ApiResult.HttpError -> Result.failure(IllegalStateException("HTTP ${res.code}"))
            is ApiResult.NetworkError -> Result.failure(IllegalStateException(res.message))
        }
    }

    /** (BL-82) On-demand `GET /api/classes` — the countable species catalog
     *  + the current `counting_class_ids` selection. Reuses the same IP
     *  resolution + WiFi-bound transport as [getSettings]. Returns
     *  [Result.failure] with `HTTP 404` when the countingapp has not yet
     *  published `model-classes.json` (caller surfaces "catalog unavailable"). */
    suspend fun getClasses(): Result<ClassCatalog> {
        val ip = resolveActiveIp() ?: return Result.failure(IllegalStateException("Jetson introuvable"))
        val network = activeWifiNetworkSafe()
        return when (val res = JetsonClient.getClasses(ip = ip, network = network)) {
            is ApiResult.Success -> Result.success(res.data)
            is ApiResult.HttpError -> Result.failure(IllegalStateException("HTTP ${res.code}"))
            is ApiResult.NetworkError -> Result.failure(IllegalStateException(res.message))
        }
    }

    /**
     * On-demand `PUT /api/settings` (BL-76) — PATCH-like merge: only the
     * non-`null` fields of [settings] are serialized by [JetsonSettings.toJson];
     * the companion merges them atomically and echoes the full merged object.
     * Reuses the same IP resolution + WiFi-bound transport as [syncTime].
     *
     * @return [Result.success] with the merged [JetsonSettings] on HTTP 200,
     *   or [Result.failure] (no reachable Jetson, 400 on a validation error,
     *   or network error). Never throws.
     */
    suspend fun putSettings(settings: JetsonSettings): Result<JetsonSettings> {
        val ip = resolveActiveIp() ?: return Result.failure(IllegalStateException("Jetson introuvable"))
        val network = activeWifiNetworkSafe()
        return when (val res = JetsonClient.putSettings(ip = ip, body = settings, network = network)) {
            is ApiResult.Success -> Result.success(res.data)
            is ApiResult.HttpError -> Result.failure(IllegalStateException("HTTP ${res.code}"))
            is ApiResult.NetworkError -> Result.failure(IllegalStateException(res.message))
        }
    }

    /**
     * Register the `TRANSPORT_WIFI` [ConnectivityManager.NetworkCallback].
     * `onAvailable` → mark on-WiFi, [rescan] (re-select the active IP);
     * `onLost` → mark off-WiFi, OutOfRange banner.
     */
    private fun registerNetworkCallback() {
        if (networkCallback != null) return
        val cm = connectivityManager ?: return
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                onWifi = true
                SyncLog.setConnected(true)
                SyncLog.add(
                    SyncEvent(
                        timestamp = Instant.now(),
                        type = SyncEvent.Type.Sync,
                        outcome = SyncEvent.Outcome.Success,
                        detail = "WiFi joined — re-selecting Jetson",
                    ),
                )
                rescan()
            }

            override fun onLost(network: Network) {
                onWifi = false
                SyncLog.setConnected(false)
                _probeState.value = ProbeState.OutOfRange
                SyncLog.add(
                    SyncEvent(
                        timestamp = Instant.now(),
                        type = SyncEvent.Type.Sync,
                        outcome = SyncEvent.Outcome.Network,
                        detail = "WiFi lost — out of Jetson range",
                    ),
                )
            }
        }
        runCatching { cm.registerNetworkCallback(request, callback) }
        networkCallback = callback
    }

    private fun unregisterNetworkCallback() {
        val cm = connectivityManager ?: return
        val cb = networkCallback ?: return
        runCatching { cm.unregisterNetworkCallback(cb) }
        networkCallback = null
    }

    /**
     * Parallel strict probe of [candidates] (deduped, non-blank). Each
     * candidate is probed via [JetsonClient.identify] (bound to [network]);
     * the first to return a strict-valid `Success` outcome wins. The whole
     * race is bounded by [PROBE_TIMEOUT_MS] — if no candidate succeeds
     * within the deadline, returns `null`.
     *
     * This is a TRUE `select` race (BL-74 fix): the first SUCCESSFUL probe
     * resolves the result immediately, and a failing/unreachable candidate
     * does NOT short-circuit the race — we keep waiting for the others. The
     * previous implementation awaited the candidates sequentially, so on the
     * LAN the unreachable hotspot candidate (192.168.100.1, whose TCP connect
     * hangs until the 5s connect timeout) was awaited first and let the short
     * probe deadline expire before the reachable LAN candidate (192.168.0.180,
     * done in ~200ms) was ever checked — the app reported "hors de portée" on
     * the LAN even though the Jetson was reachable. The select race fixes that:
     * the reachable candidate wins in ~200ms regardless of the unreachable one.
     */
    private suspend fun parallelProbe(
        candidates: Set<String>,
        network: Network?,
    ): String? {
        val ips = candidates.mapNotNull { it.trim().ifBlank { null } }.distinct()
        if (ips.isEmpty()) return null
        val s = scope ?: return null
        return withTimeoutOrNull(PROBE_TIMEOUT_MS) {
            // TRUE race (BL-74 fix): every candidate is probed in parallel and
            // reports its result into a buffered channel; the first SUCCESS
            // (non-null) wins and we return immediately. A failing/unreachable
            // candidate does NOT short-circuit the race — we keep receiving
            // until a success arrives or every candidate has reported (all
            // failures -> null). The previous implementation awaited the
            // candidates sequentially, so on the LAN the unreachable hotspot
            // candidate (192.168.100.1, whose TCP connect hangs until the 5s
            // connect timeout) was awaited first and let the short probe
            // deadline expire before the reachable LAN candidate
            // (192.168.0.180, done in ~200ms) was ever checked — the app
            // reported "hors de portée" on the LAN even though the Jetson was
            // reachable. The channel race fixes that: the reachable candidate
            // wins in ~200ms regardless of the unreachable one.
            val results = Channel<String?>(ips.size)
            ips.forEach { ip ->
                s.launch {
                    val event = JetsonClient.identify(ip = ip, network = network)
                    results.send(if (event.outcome == SyncEvent.Outcome.Success) ip else null)
                }
            }
            var received = 0
            while (received < ips.size) {
                val res = results.receive()
                received++
                if (res != null) return@withTimeoutOrNull res
            }
            null
        }
    }

    /** Single strict probe of [ip]; returns [ip] on a strict-valid hit, else null. */
    private suspend fun singleProbe(ip: String, network: Network?): String? {
        return withTimeoutOrNull(PROBE_TIMEOUT_MS) {
            val event = JetsonClient.identify(ip = ip, network = network)
            if (event.outcome == SyncEvent.Outcome.Success) ip else null
        }
    }

    /**
     * `POST /api/time` to [ip] (bound to [network]); logs the result to
     * [SyncLog]. Failures never throw — they surface as a [SyncEvent].
     */
    private suspend fun postTime(ip: String, network: Network?): SyncEvent {
        val event = JetsonClient.postTime(
            ip = ip,
            timeIso = nowIsoForCompanion(),
            tz = ZoneId.systemDefault().id,
            network = network,
        )
        SyncLog.add(event)
        return event
    }

    /** Resolve the active WiFi [Network] (null when not on the Jetson HotSpot). */
    private fun activeWifiNetworkSafe(): Network? {
        val cm = connectivityManager ?: return null
        return activeWifiNetwork(cm)
    }
}