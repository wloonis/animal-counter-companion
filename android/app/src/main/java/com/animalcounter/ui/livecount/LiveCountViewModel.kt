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

package com.animalcounter.ui.livecount

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.animalcounter.data.DEFAULT_JETSON_IP
import com.animalcounter.data.SettingsRepository
import com.animalcounter.net.ApiResult
import com.animalcounter.net.JetsonClient
import com.animalcounter.net.JetsonConnectionManager
import com.animalcounter.net.LiveCount
import com.animalcounter.net.activeWifiNetwork
import com.animalcounter.net.ProbeState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Interval between automatic `GET /api/count` polls while the Live count
 * tab is in the foreground (resumed). Short enough to feel live, long
 * enough not to spam the Jetson companion.
 */
private const val POLL_INTERVAL_MS = 2_000L

/**
 * UI state for the Comptage live tab.
 *
 * - [Loading]: initial fetch in flight (no data yet) — shows a
 *   `LinearProgressIndicator`.
 * - [Loaded]: a [LiveCount] snapshot is available — render the big number,
 *   status pill + auto-mode chip.
 * - [OutOfRange]: the Jetson is unreachable (probe failed) AND no count
 *   could be fetched — show the out-of-range banner + empty card.
 * - [Error]: a fetch returned a non-recoverable error (HTTP/parse) — show
 *   the error card.
 */
sealed interface LiveCountUiState {
    /** Initial load in progress (no snapshot yet). */
    data object Loading : LiveCountUiState
    /** A count snapshot is available. */
    data class Loaded(val count: LiveCount) : LiveCountUiState
    /** Jetson out of reach (probe + fetch both failed). */
    data object OutOfRange : LiveCountUiState
    /** Recoverable or HTTP error while fetching the count. */
    data class Error(val message: String) : LiveCountUiState
}

/**
 * ViewModel backing the Comptage live tab.
 *
 * Seeds the Jetson IP from [SettingsRepository] (read-only here — the IP is
 * edited on the Time sync tab) and exposes:
 *  - [state]: the current [LiveCountUiState] (drives the screen body).
 *  - [probeState]: the reachability banner state (reuses the Time sync
 *    [ProbeState] so the banner style is identical).
 *
 * Polling is **lifecycle-aware**: the screen calls [startPolling] on
 * `ON_RESUME` and [stopPolling] on `ON_PAUSE`/`ON_STOP` (via a
 * `DisposableEffect` keyed on the compose lifecycle owner) so the
 * ~2s `/api/count` loop only runs while the tab is foregrounded — no
 * battery drain in the background. [refresh] is provided for the manual
 * pull-to-refresh action.
 */
class LiveCountViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = SettingsRepository(app)

    /** Current Jetson IP (seeded from DataStore; source of truth = Time sync tab). */
    private val _ip = MutableStateFlow(DEFAULT_JETSON_IP)
    val ip: StateFlow<String> = _ip.asStateFlow()

    private val _state = MutableStateFlow<LiveCountUiState>(LiveCountUiState.Loading)
    val state: StateFlow<LiveCountUiState> = _state.asStateFlow()

    /**
     * Reachability banner state — delegated to the app-wide
     * [JetsonConnectionManager] (the single canonical probe owner, BL-73).
     * Screens that read `vm.probeState` are unchanged.
     */
    val probeState: StateFlow<ProbeState>
        get() = JetsonConnectionManager.probeState

    /** Active polling job — cancelled by [stopPolling] and `onCleared`. */
    private var pollJob: Job? = null

    init {
        // Re-seed the IP + refetch whenever the manager resolves a new active
        // Jetson IP (hotspot/LAN/manual). The first emission is the hotspot
        // default; a second follows once the parallel probe resolves.
        viewModelScope.launch {
            repo.activeIp.collect { ip ->
                _ip.value = ip
                refresh()
            }
        }
    }

    /**
     * Start lifecycle-aware polling of `GET /api/count`. Idempotent — a
     * second call while polling is a no-op. The screen calls this on
     * `ON_RESUME`.
     */
    fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = viewModelScope.launch {
            while (true) {
                fetchOnce()
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    /**
     * Stop polling (cancels the loop). The screen calls this on `ON_PAUSE`
     * / `ON_STOP`. Safe to call when not polling.
     */
    fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    /**
     * Manual refresh (pull-to-refresh): one-shot fetch. Does not start the
     * polling loop — the screen controls that via lifecycle. Reachability
     * probing is owned by [JetsonConnectionManager].
     */
    fun refresh() {
        viewModelScope.launch {
            fetchOnce()
        }
    }

    /**
     * One `GET /api/count` fetch mapped onto [state]. On a network failure
     * we transition to [LiveCountUiState.OutOfRange] only when there is no
     * existing snapshot to keep showing (so a transient blip doesn't wipe
     * a perfectly good live number); otherwise the error is surfaced.
     */
    private suspend fun fetchOnce() {
        // Preserve any existing snapshot so a single failed poll doesn't
        // blank the big number (only the banner flips to OutOfRange).
        val previous = _state.value
        try {
            val cm = cm()
            val wifi = if (cm != null) activeWifiNetwork(cm) else null
            when (val result = JetsonClient.getCount(ip = _ip.value, network = wifi)) {
                is ApiResult.Success -> {
                    _state.value = LiveCountUiState.Loaded(result.data)
                    // A successful count implies the Jetson is reachable; the
                    // manager owns the banner so nothing to set here.
                }
                is ApiResult.HttpError -> {
                    _state.value = LiveCountUiState.Error("HTTP ${result.code}")
                }
                is ApiResult.NetworkError -> {
                    _state.value = if (previous is LiveCountUiState.Loaded) previous
                    else LiveCountUiState.OutOfRange
                }
            }
        } catch (t: Throwable) {
            _state.value = if (previous is LiveCountUiState.Loaded) previous
            else LiveCountUiState.OutOfRange
        }
    }

    /** Resolve the active WiFi network (null when not on the Jetson HotSpot). */
    private fun cm(): ConnectivityManager? = getApplication<Application>()
        .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    override fun onCleared() {
        super.onCleared()
        pollJob?.cancel()
        pollJob = null
    }
}