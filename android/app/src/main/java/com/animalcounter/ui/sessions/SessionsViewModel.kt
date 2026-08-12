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

package com.animalcounter.ui.sessions

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.animalcounter.data.DEFAULT_JETSON_IP
import com.animalcounter.data.OfflineCache
import com.animalcounter.data.SettingsRepository
import com.animalcounter.net.ApiResult
import com.animalcounter.net.SessionPage
import com.animalcounter.net.JetsonClient
import com.animalcounter.net.JetsonConnectionManager
import com.animalcounter.net.SessionSummary
import com.animalcounter.net.activeWifiNetwork
import com.animalcounter.net.parseSessions
import com.animalcounter.net.ProbeState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * UI state for the Sessions list (Dashboard -> "Sessions: N" -> list of
 * session-level entries). Reuses the `/api/sessions` payload (each session =
 * one video/counting run) but renders session-centric fields (session id,
 * start, end, end_reason, heartbeats, events) and navigates to the full
 * Session detail (`session/{sessionId}`).
 */
sealed interface SessionsUiState {
    data object Loading : SessionsUiState
    data class Loaded(
        val rows: List<SessionSummary>,
        val total: Int,
        val hasMore: Boolean,
        val loadingMore: Boolean,
        val offline: Boolean = false,
        val cachedAt: Instant? = null,
    ) : SessionsUiState
    data object Empty : SessionsUiState
    data object OutOfRange : SessionsUiState
    data class Error(val message: String) : SessionsUiState
}

private const val HISTORY_LIMIT = 50
private const val CACHE_KEY = "sessions"

/**
 * ViewModel backing the Sessions list. The period window (`days`) comes
 * from the `sessions?days={days}` nav arg; rows are filtered client-side
 * to `start_at >= now - days`. Pagination stops once a row falls outside
 * the window (the API returns newest-first), so a 1-day window fetches
 * only today's sessions.
 */
class SessionsViewModel(
    app: Application,
    private val handle: SavedStateHandle,
) : AndroidViewModel(app) {

    private val repo = SettingsRepository(app)

    private val _ip = MutableStateFlow(DEFAULT_JETSON_IP)
    val ip: StateFlow<String> = _ip.asStateFlow()

    /** Period window in days (from the nav arg; default 30 — the Sessions tab). */
    val days: Int = handle.get<String>("days")?.toIntOrNull()?.coerceAtLeast(1) ?: 30

    private val cache = ArrayList<SessionSummary>()
    private var offset = 0
    private var total = 0
    private var offlineMode = false
    private var lastCachedAt: Instant? = null

    private val _state = MutableStateFlow<SessionsUiState>(SessionsUiState.Loading)
    val state: StateFlow<SessionsUiState> = _state.asStateFlow()

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
                loadFirstPage()
            }
        }
    }

    fun loadFirstPage() {
        cache.clear(); offset = 0; total = 0
        offlineMode = false; lastCachedAt = null
        viewModelScope.launch { fetchPage(append = false) }
    }

    fun loadMore() {
        val s = _state.value
        if (s is SessionsUiState.Loaded && s.hasMore && !s.loadingMore) {
            viewModelScope.launch { fetchPage(append = true) }
        }
    }

    /** Re-fetch first page (auto-refresh polling + pull-to-refresh).
     * Reachability probing is owned by [JetsonConnectionManager]. */
    fun refresh() {
        viewModelScope.launch {
            fetchPage(append = false)
        }
    }

    private suspend fun fetchPage(append: Boolean) {
        val previous = _state.value
        if (previous !is SessionsUiState.Loaded && !append) {
            _state.value = SessionsUiState.Loading
        }
        try {
            val cm = cm()
            val wifi = if (cm != null) activeWifiNetwork(cm) else null
            when (val result = JetsonClient.fetchRaw(
                ip = _ip.value,
                path = "/api/sessions?limit=$HISTORY_LIMIT&offset=$offset",
                network = wifi,
            )) {
                is ApiResult.Success -> {
                    val page: SessionPage = parseSessions(result.data)
                    if (!append) OfflineCache.save(getApplication(), CACHE_KEY, result.data)
                    if (append) cache.addAll(page.sessions)
                    else { cache.clear(); cache.addAll(page.sessions) }
                    total = page.total.coerceAtLeast(cache.size)
                    offset = cache.size
                    offlineMode = false
                    lastCachedAt = null
                    publish()
                    // A successful fetch implies the Jetson is reachable;
                    // the manager owns the banner so nothing to set here.
                }
                is ApiResult.HttpError -> {
                    _state.value = if (previous is SessionsUiState.Loaded) previous
                    else loadCached() ?: SessionsUiState.Error("HTTP ${result.code}")
                }
                is ApiResult.NetworkError -> {
                    _state.value = if (previous is SessionsUiState.Loaded) previous
                    else loadCached() ?: SessionsUiState.OutOfRange
                }
            }
        } catch (t: Throwable) {
            _state.value = if (previous is SessionsUiState.Loaded) previous
            else loadCached() ?: SessionsUiState.OutOfRange
        }
    }

    /** Offline fallback — serve the last cached first page. */
    private fun loadCached(): SessionsUiState? {
        val cached = OfflineCache.load(getApplication(), CACHE_KEY) ?: return null
        val page = runCatching { parseSessions(cached.json) }.getOrNull() ?: return null
        cache.clear(); cache.addAll(page.sessions)
        total = page.total.coerceAtLeast(cache.size)
        offset = cache.size
        offlineMode = true
        lastCachedAt = cached.savedAt
        publish()
        return _state.value
    }

    /** Filter by the period window + publish. */
    private fun publish() {
        val cutoff = Instant.now().minusSeconds(days.toLong() * 86400)
        val rows = cache.filter { row ->
            row.startAt?.let { parseInstantTolerant(it) }?.let { it >= cutoff } ?: false
        }
        _state.value = when {
            cache.isEmpty() -> SessionsUiState.Empty
            else -> SessionsUiState.Loaded(
                rows = rows,
                total = rows.size,
                hasMore = offset < total, // still more pages that might fall in window
                loadingMore = false,
                offline = offlineMode,
                cachedAt = lastCachedAt,
            )
        }
    }

    private fun cm(): ConnectivityManager? = getApplication<Application>()
        .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    /** Tolerant ISO-8601 parse (offset or Z) -> Instant, null on failure. */
    private fun parseInstantTolerant(iso: String): Instant? = runCatching {
        try {
            java.time.OffsetDateTime.parse(iso, java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant()
        } catch (e: java.time.format.DateTimeParseException) {
            java.time.LocalDateTime.parse(iso.take(19)).atZone(java.time.ZoneId.systemDefault()).toInstant()
        }
    }.getOrNull()
}