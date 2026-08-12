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

package com.animalcounter.ui.sessiondetail

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.animalcounter.data.DEFAULT_JETSON_IP
import com.animalcounter.data.SettingsRepository
import com.animalcounter.net.ApiResult
import com.animalcounter.net.JetsonClient
import com.animalcounter.net.JetsonConnectionManager
import com.animalcounter.net.SessionDetail
import com.animalcounter.net.activeWifiNetwork
import com.animalcounter.net.ProbeState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * UI state for the Détail de session screen.
 *
 * - [Loading]: the `GET /api/sessions/<id>` fetch is in flight (no detail
 *   yet) — show a `LinearProgressIndicator`.
 * - [Loaded]: a [SessionDetail] snapshot is available — render the A–G
 *   group cards.
 * - [OutOfRange]: the Jetson is unreachable (probe failed) AND no detail
 *   could be fetched — show the out-of-range banner + empty card.
 * - [Error]: the fetch returned a non-recoverable error (e.g. HTTP 404 for
 *   an unknown session id, or a parse failure) — show the error card.
 */
sealed interface SessionDetailUiState {
    /** Initial load in progress (no snapshot yet). */
    data object Loading : SessionDetailUiState
    /** A session detail snapshot is available. */
    data class Loaded(val detail: SessionDetail) : SessionDetailUiState
    /** Jetson out of reach (probe + fetch both failed). */
    data object OutOfRange : SessionDetailUiState
    /** Recoverable or HTTP error while fetching the session (e.g. 404). */
    data class Error(val message: String) : SessionDetailUiState
}

/**
 * ViewModel backing the Détail de session screen.
 *
 * The session id is supplied either via the Navigation Compose back-stack
 * argument (`session/{sessionId}` → [SavedStateHandle] key `"sessionId"`)
 * or by an explicit [load] call. Seeds the Jetson IP from
 * [SettingsRepository.activeIp] (resolved by [JetsonConnectionManager];
 * read-only here — the IP is edited on the Settings tab) and exposes:
 *  - [state]: the current [SessionDetailUiState] (drives the screen body).
 *  - [probeState]: the reachability banner state (delegated to the
 *    app-wide [JetsonConnectionManager] so the banner style is identical).
 *  - [sessionId]: the session id currently being displayed.
 *
 * Reachability probing is owned by [JetsonConnectionManager]; [load] is
 * called with the seeded session id on init and re-run whenever the
 * resolved active IP changes. [refresh] re-fetches the same id (used by
 * pull-to-refresh).
 */
class SessionDetailViewModel(
    app: Application,
    private val handle: SavedStateHandle,
) : AndroidViewModel(app) {

    private val repo = SettingsRepository(app)

    /** Current Jetson IP (resolved by the connection manager; source of truth = Settings tab). */
    private val _ip = MutableStateFlow(DEFAULT_JETSON_IP)
    val ip: StateFlow<String> = _ip.asStateFlow()

    /** Session id under display (seeded from the nav arg in [SavedStateHandle]). */
    private val _sessionId = MutableStateFlow(handle.get<String>(KEY_SESSION_ID).orEmpty())
    val sessionId: StateFlow<String> = _sessionId.asStateFlow()

    private val _state = MutableStateFlow<SessionDetailUiState>(SessionDetailUiState.Loading)
    val state: StateFlow<SessionDetailUiState> = _state.asStateFlow()

    /**
     * Reachability banner state — delegated to the app-wide
     * [JetsonConnectionManager] (the single canonical probe owner, BL-73).
     * Screens that read `vm.probeState` are unchanged.
     */
    val probeState: StateFlow<ProbeState>
        get() = JetsonConnectionManager.probeState

    init {
        // Re-seed the IP + refetch whenever the manager resolves a new active
        // Jetson IP (hotspot/LAN/manual). The first emission is the hotspot
        // default; a second follows once the parallel probe resolves.
        viewModelScope.launch {
            repo.activeIp.collect { ip ->
                _ip.value = ip
                val id = _sessionId.value
                if (id.isNotBlank()) load(id) else _state.value = SessionDetailUiState.Error("no id")
            }
        }
    }

    /**
     * Load (or reload) a session by id — `GET /api/sessions/<id>`. Maps the
     * result onto [state]. On a network failure we transition to
     * [SessionDetailUiState.OutOfRange] only when there is no existing
     * snapshot to keep showing (so a transient blip doesn't wipe a
     * perfectly good detail); otherwise the error is surfaced.
     *
     * Also updates [sessionId] so subsequent [refresh] calls reuse it.
     */
    fun load(sessionId: String) {
        _sessionId.value = sessionId
        viewModelScope.launch { fetch(sessionId) }
    }

    /**
     * Re-fetch the current session (pull-to-refresh). No-op when no id is
     * set. Reachability probing is owned by [JetsonConnectionManager].
     */
    fun refresh() {
        val id = _sessionId.value
        if (id.isBlank()) return
        viewModelScope.launch { fetch(id) }
    }

    /**
     * One `GET /api/sessions/<id>` fetch mapped onto [state]. On a network
     * failure we keep any existing [SessionDetailUiState.Loaded] snapshot
     * so a transient blip doesn't blank the cards (only the banner flips
     * to OutOfRange); on HTTP error (e.g. 404) we surface the error unless a
     * snapshot already exists.
     */
    private suspend fun fetch(sessionId: String) {
        val previous = _state.value
        // Only show the full-screen Loading spinner on the very first load.
        if (previous !is SessionDetailUiState.Loaded) {
            _state.value = SessionDetailUiState.Loading
        }
        try {
            val cm = cm()
            val wifi = if (cm != null) activeWifiNetwork(cm) else null
            when (val result = JetsonClient.getSession(ip = _ip.value, id = sessionId, network = wifi)) {
                is ApiResult.Success -> {
                    _state.value = SessionDetailUiState.Loaded(result.data)
                    // A successful fetch implies the Jetson is reachable; the
                    // manager owns the banner so nothing to set here.
                }
                is ApiResult.HttpError -> {
                    _state.value = if (previous is SessionDetailUiState.Loaded) previous
                    else SessionDetailUiState.Error("HTTP ${result.code}")
                }
                is ApiResult.NetworkError -> {
                    _state.value = if (previous is SessionDetailUiState.Loaded) previous
                    else SessionDetailUiState.OutOfRange
                }
            }
        } catch (t: Throwable) {
            _state.value = if (previous is SessionDetailUiState.Loaded) previous
            else SessionDetailUiState.OutOfRange
        }
    }

    /** Resolve the active WiFi network (null when not on the Jetson HotSpot). */
    private fun cm(): ConnectivityManager? = getApplication<Application>()
        .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    companion object {
        /** [SavedStateHandle] key holding the `session/{sessionId}` nav arg. */
        const val KEY_SESSION_ID = "sessionId"
    }
}