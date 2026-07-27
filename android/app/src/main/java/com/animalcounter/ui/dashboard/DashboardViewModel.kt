package com.animalcounter.ui.dashboard

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.animalcounter.data.DEFAULT_JETSON_IP
import com.animalcounter.data.OfflineCache
import com.animalcounter.data.SettingsRepository
import com.animalcounter.net.ApiResult
import com.animalcounter.net.DailyBucket
import com.animalcounter.net.JetsonClient
import com.animalcounter.net.JetsonConnectionManager
import com.animalcounter.net.Summary
import com.animalcounter.net.activeWifiNetwork
import com.animalcounter.net.parseSummary
import com.animalcounter.net.ProbeState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Instant

/** Supported dashboard period windows (days). Default = 1 (today). */
enum class DashboardPeriod(val days: Int) {
    DAYS_1(1),
    DAYS_7(7),
    DAYS_30(30);

    companion object {
        fun fromDays(days: Int): DashboardPeriod =
            entries.firstOrNull { it.days == days } ?: DAYS_1
    }
}

/**
 * UI state for the Tableau de bord tab.
 *
 * - [Loading]: a summary fetch is in flight (no buckets yet) — show a
 *   `LinearProgressIndicator`.
 * - [Loaded]: a summary was fetched successfully; [summary] holds the
 *   daily buckets + period, and the derived totals ([totalCounted],
 *   [totalSessions], [totalGuards], [avgPerDay]) are precomputed for the
 *   cards so the screen never re-aggregates on recomposition.
 * - [Empty]: a summary was fetched but contained zero daily buckets —
 *   show the empty-dashboard card.
 * - [OutOfRange]: the Jetson is unreachable (probe failed AND no summary
 *   could be fetched) — show the out-of-range banner + empty card.
 * - [Error]: a fetch returned a non-recoverable HTTP error — show the
 *   error card.
 */
sealed interface DashboardUiState {
    /** Initial load in progress (no buckets yet). */
    data object Loading : DashboardUiState
    /** Summary loaded; derived totals precomputed for the cards. */
    data class Loaded(
        val period: DashboardPeriod,
        val summary: Summary,
        val daily: List<DailyBucket>,
        val totalCounted: Int,
        val totalSessions: Int,
        val totalGuards: Int,
        val avgPerDay: Double,
        val offline: Boolean = false,
        val cachedAt: Instant? = null,
    ) : DashboardUiState
    /** A summary was fetched but contained zero daily buckets. */
    data object Empty : DashboardUiState
    /** Jetson out of reach (probe + fetch both failed). */
    data object OutOfRange : DashboardUiState
    /** Recoverable or HTTP error while fetching the summary. */
    data class Error(val message: String) : DashboardUiState
}

/**
 * ViewModel backing the Tableau de bord tab.
 *
 * Drives the 7/30-day period selector and calls
 * `JetsonClient.getSummary(ip, days)`. The summary only carries
 * `sessions`/`net_count`/`guard_events`/`events` per day (no per-session
 * status split), so the dashboard cards surface sessions + net_count +
 * guard_events per day (no client-side clean-vs-power-loss split — there
 * is no per-session source here; that lives on the Historique tab).
 *
 * Exposes:
 *  - [state]: the current [DashboardUiState] (drives the screen body).
 *  - [probeState]: the reachability banner state (reuses the Time sync
 *    [ProbeState] so the banner style is identical).
 *  - [period]: the active 7/30-day window (drives the segmented button).
 */
class DashboardViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = SettingsRepository(app)

    /** Current Jetson IP (seeded from DataStore; source of truth = Time sync tab). */
    private val _ip = MutableStateFlow(DEFAULT_JETSON_IP)
    val ip: StateFlow<String> = _ip.asStateFlow()

    private val _state = MutableStateFlow<DashboardUiState>(DashboardUiState.Loading)
    val state: StateFlow<DashboardUiState> = _state.asStateFlow()

    /**
     * Reachability banner state — delegated to the app-wide
     * [JetsonConnectionManager] (the single canonical probe owner, BL-73).
     * Screens that read `vm.probeState` are unchanged.
     */
    val probeState: StateFlow<ProbeState>
        get() = JetsonConnectionManager.probeState

    /** Active 7/30-day window (drives the segmented button + the fetch). */
    private val _period = MutableStateFlow(DashboardPeriod.DAYS_1)
    val period: StateFlow<DashboardPeriod> = _period.asStateFlow()

    init {
        // Re-seed the IP + refetch whenever the manager resolves a new active
        // Jetson IP (hotspot/LAN/manual). The first emission is the hotspot
        // default; a second follows once the parallel probe resolves.
        viewModelScope.launch {
            repo.activeIp.collect { ip ->
                _ip.value = ip
                load(_period.value)
            }
        }
    }

    /**
     * Switch the period selector (7 / 30 days) and refetch. Called by the
     * `SingleChoiceSegmentedButton` on the screen. Preserves the previous
     * snapshot while loading so a transient failure doesn't blank the chart.
     */
    fun setPeriod(period: DashboardPeriod) {
        if (_period.value == period) return
        _period.value = period
        load(period)
    }

    /**
     * Refresh the current period — re-runs the summary fetch. Used by
     * pull-to-refresh and (optionally) a top-app-bar Refresh action.
     * Reachability probing is owned by [JetsonConnectionManager].
     */
    fun refresh() {
        load(_period.value)
    }

    /**
     * One `/api/summary?days=N` fetch mapped onto [state]. On a
     * network failure we transition to [DashboardUiState.OutOfRange];
     * on HTTP error to [DashboardUiState.Error]. A successful fetch with
     * zero daily buckets → [DashboardUiState.Empty]. A successful fetch
     * implies the Jetson is reachable (probe flipped to Reachable).
     */
    private fun load(period: DashboardPeriod) {
        viewModelScope.launch {
            // Preserve a Loaded snapshot so a transient refetch failure
            // doesn't blank the chart (only the banner flips to OutOfRange).
            val previous = _state.value
            if (previous !is DashboardUiState.Loaded) {
                _state.value = DashboardUiState.Loading
            }
            try {
                val cm = cm()
                val wifi = if (cm != null) activeWifiNetwork(cm) else null
                when (val result = JetsonClient.fetchRaw(
                    ip = _ip.value,
                    path = "/api/summary?days=${period.days}",
                    network = wifi,
                )) {
                    is ApiResult.Success -> {
                        OfflineCache.save(getApplication(), cacheKey(period.days), result.data)
                        val summary: Summary = parseSummary(result.data)
                        if (summary.daily.isEmpty()) {
                            _state.value = DashboardUiState.Empty
                        } else {
                            _state.value = aggregate(period, summary)
                        }
                        // A successful fetch implies the Jetson is reachable;
                        // the manager owns the banner so nothing to set here.
                    }
                    is ApiResult.HttpError -> {
                        _state.value =
                            if (previous is DashboardUiState.Loaded) previous
                            else loadCachedDashboard(period) ?: DashboardUiState.Error("HTTP ${result.code}")
                    }
                    is ApiResult.NetworkError -> {
                        _state.value =
                            if (previous is DashboardUiState.Loaded) previous
                            else loadCachedDashboard(period) ?: DashboardUiState.OutOfRange
                    }
                }
            } catch (t: Throwable) {
                _state.value =
                    if (previous is DashboardUiState.Loaded) previous
                    else loadCachedDashboard(period) ?: DashboardUiState.OutOfRange
            }
        }
    }

    /**
     * Aggregate a [Summary] into the card totals ([DashboardUiState.Loaded]).
     * Precomputed here so the screen never re-aggregates on recomposition.
     *
     * - [totalCounted]: sum of `net_count` across days.
     * - [totalSessions]: sum of `sessions` across days.
     * - [totalGuards]: sum of `guard_events` across days.
     * - [avgPerDay]: [totalCounted] / number of days in the window.
     */
    private fun aggregate(
        period: DashboardPeriod,
        summary: Summary,
        offline: Boolean = false,
        cachedAt: Instant? = null,
    ): DashboardUiState.Loaded {
        val daily = summary.daily
        val totalCounted = daily.sumOf { it.netCount }
        val totalSessions = daily.sumOf { it.sessions }
        val totalGuards = daily.sumOf { it.guardEvents }
        val avgPerDay = if (period.days > 0) totalCounted.toDouble() / period.days else 0.0
        return DashboardUiState.Loaded(
            period = period,
            summary = summary,
            daily = daily,
            totalCounted = totalCounted,
            totalSessions = totalSessions,
            totalGuards = totalGuards,
            avgPerDay = avgPerDay,
            offline = offline,
            cachedAt = cachedAt,
        )
    }

    /** Cache key per period (7 vs 30 days cached separately). */
    private fun cacheKey(days: Int) = "dashboard_$days"

    /** Offline fallback — serve the last cached `/api/summary` for the
     * active period so the dashboard stays consultable with no Jetson link.
     * Returns null when there is no cache (caller falls back to Error/OutOfRange). */
    private fun loadCachedDashboard(period: DashboardPeriod): DashboardUiState.Loaded? {
        val cached = OfflineCache.load(getApplication(), cacheKey(period.days)) ?: return null
        val summary = runCatching { parseSummary(cached.json) }.getOrNull() ?: return null
        if (summary.daily.isEmpty()) return null
        return aggregate(period, summary, offline = true, cachedAt = cached.savedAt)
    }

    /** Resolve the active WiFi network (null when not on the Jetson HotSpot). */
    private fun cm(): ConnectivityManager? = getApplication<Application>()
        .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
}