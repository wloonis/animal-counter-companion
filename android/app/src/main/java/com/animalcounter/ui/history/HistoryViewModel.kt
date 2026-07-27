package com.animalcounter.ui.history

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.animalcounter.data.DEFAULT_JETSON_IP
import com.animalcounter.data.OfflineCache
import com.animalcounter.data.SettingsRepository
import com.animalcounter.net.ApiResult
import com.animalcounter.net.VideoPage
import com.animalcounter.net.VideoRow
import com.animalcounter.net.JetsonClient
import com.animalcounter.net.JetsonConnectionManager
import com.animalcounter.net.activeWifiNetwork
import com.animalcounter.net.parseVideos
import com.animalcounter.net.ProbeState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/** Page size for `/api/videos` (matches the brief's `limit=50`). */
private const val HISTORY_LIMIT = 50
private const val CACHE_KEY = "videos"

/**
 * UI state for the Historique (videos) tab.
 *
 * - [Loading]: initial page fetch in flight (no rows yet) — show a
 *   `LinearProgressIndicator`.
 * - [Loaded]: one or more pages loaded; [rows] holds the accumulated
 *   (filter-applied) [VideoRow]s, [hasMore] is true when `offset < total`.
 * - [Empty]: a page was fetched successfully but it contained zero rows
 *   (and no filter is active) — show the empty-history card.
 * - [OutOfRange]: the Jetson is unreachable (probe failed) AND no page
 *   could be fetched — show the out-of-range banner + empty card.
 * - [Error]: a fetch returned a non-recoverable HTTP error — show the
 *   error card.
 */
sealed interface HistoryUiState {
    /** Initial load in progress (no rows yet). */
    data object Loading : HistoryUiState
    /** Rows available; [hasMore] true when more pages can be appended. */
    data class Loaded(
        val rows: List<VideoRow>,
        val total: Int,
        val hasMore: Boolean,
        val loadingMore: Boolean,
        val offline: Boolean = false,
        val cachedAt: Instant? = null,
    ) : HistoryUiState
    /** A page was fetched but contained zero videos (no filter active). */
    data object Empty : HistoryUiState
    /** Jetson out of reach (probe + fetch both failed). */
    data object OutOfRange : HistoryUiState
    /** Recoverable or HTTP error while fetching a page. */
    data class Error(val message: String) : HistoryUiState
}

/**
 * Status filter values exposed by the History screen's `FilterChip` group.
 *
 * BL-72: the History tab now lists `/api/videos` rows. A [VideoRow] carries
 * only the `status` field (`"ready"` | `"running"` | …) — it does NOT carry
 * the session `end_reason` that the old clean/power-loss/unknown chips
 * branched on. The chip group is therefore collapsed to All / Running /
 * Ready (the three meaningful states for a video row).
 */
enum class HistoryStatusFilter(val key: String) {
    ALL("all"),
    RUNNING("running"),
    READY("ready");

    companion object {
        fun fromKey(key: String?): HistoryStatusFilter =
            entries.firstOrNull { it.key == key } ?: ALL
    }
}

/**
 * ViewModel backing the Historique (videos) tab.
 *
 * Maintains an accumulated, paginated view of `/api/videos` with a light
 * in-memory cache (the accumulated [VideoRow] list). Filtering is
 * client-side (the API has no server-side filter params): the selected
 * date ([LocalDate]) compares against a row's `ts` (parsed to a local
 * date), and the selected status filter branches on the row's `status`
 * (`ready` / `running`). Running rows are kept first (the companion emits
 * the synthetic running row at index 0); within the rest, newest `ts`
 * first.
 *
 * The old `matchesFilters` video-only hack (excluding rows with no video
 * path / zero count) is gone — every `/api/videos` row is already a video.
 *
 * Exposes:
 *  - [state]: the current [HistoryUiState] (drives the screen body).
 *  - [probeState]: the reachability banner state (reuses the Time sync
 *    [ProbeState] so the banner style is identical).
 *  - [filterDate] / [filterStatus]: the active client-side filters.
 */
class HistoryViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = SettingsRepository(app)

    /** Current Jetson IP (seeded from DataStore; source of truth = Time sync tab). */
    private val _ip = MutableStateFlow(DEFAULT_JETSON_IP)
    val ip: StateFlow<String> = _ip.asStateFlow()

    private val _state = MutableStateFlow<HistoryUiState>(HistoryUiState.Loading)
    val state: StateFlow<HistoryUiState> = _state.asStateFlow()

    /**
     * Reachability banner state — delegated to the app-wide
     * [JetsonConnectionManager] (the single canonical probe owner, BL-73).
     * Screens that read `vm.probeState` are unchanged.
     */
    val probeState: StateFlow<ProbeState>
        get() = JetsonConnectionManager.probeState

    /** Selected date filter (null = no date filter). */
    private val _filterDate = MutableStateFlow<LocalDate?>(null)
    val filterDate: StateFlow<LocalDate?> = _filterDate.asStateFlow()

    /** Selected status filter (ALL = no status filter). */
    private val _filterStatus = MutableStateFlow(HistoryStatusFilter.ALL)
    val filterStatus: StateFlow<HistoryStatusFilter> = _filterStatus.asStateFlow()

    /** Accumulated raw (unfiltered) videos — the light in-memory cache. */
    private val cache = ArrayList<VideoRow>()

    /** Offline-cache flags — true when the current Loaded state is served from
     * the on-device cache (no Jetson connection). Reset to false on every
     * successful online fetch; set by [loadCachedVideos]. [publishFiltered]
     * reads them so a filter change keeps the offline banner. */
    private var offlineMode = false
    private var lastCachedAt: Instant? = null

    /** Next offset to fetch (== cache.size while loading the first page). */
    private var offset = 0

    /** Total video count reported by the API (drives [HistoryUiState.Loaded.hasMore]). */
    private var total = 0

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

    /**
     * Refresh — clears the cache and re-fetches the first page (preserves
     * the active filters). Used by the top-app-bar Refresh action and by
     * pull-to-refresh.
     */
    fun loadFirstPage() {
        viewModelScope.launch {
            // Preserve a Loaded snapshot so a transient refresh failure doesn't
            // blank the list (only the banner flips to OutOfRange).
            val previous = _state.value
            if (previous !is HistoryUiState.Loaded) _state.value = HistoryUiState.Loading
            cache.clear()
            offset = 0
            total = 0
            fetchPage(append = false, previous = previous)
        }
    }

    /**
     * Append the next page (when the user scrolls near the end). No-op
     * when there is no more data, a page is already being fetched, or the
     * Jetson is known out of range.
     */
    fun loadNextPage() {
        val current = _state.value
        if (current !is HistoryUiState.Loaded) return
        if (current.loadingMore) return
        if (!current.hasMore) return
        viewModelScope.launch {
            _state.value = current.copy(loadingMore = true)
            fetchPage(append = true, previous = current)
        }
    }

    /** Set the date filter (null clears it) and re-apply the filter client-side. */
    /** Re-fetch first page (auto-refresh polling + pull-to-refresh).
     * Reachability probing is owned by [JetsonConnectionManager]. */
    fun refresh() {
        loadFirstPage()
    }

    fun setFilterDate(date: LocalDate?) {
        _filterDate.value = date
        reapplyFilter()
    }

    /** Set the status filter and re-apply the filter client-side. */
    fun setFilterStatus(filter: HistoryStatusFilter) {
        _filterStatus.value = filter
        reapplyFilter()
    }

    /**
     * One `/api/videos?limit=&offset=` fetch mapped onto [state]. On a
     * network failure we transition to [HistoryUiState.OutOfRange] only
     * when there is no cached snapshot to keep showing (so a transient
     * blip doesn't wipe a perfectly good list); on append failure we keep
     * the existing [HistoryUiState.Loaded] (just clear `loadingMore`).
     */
    private suspend fun fetchPage(append: Boolean, previous: HistoryUiState) {
        try {
            val cm = cm()
            val wifi = if (cm != null) activeWifiNetwork(cm) else null
            when (val result = JetsonClient.fetchRaw(
                ip = _ip.value,
                path = "/api/videos?limit=$HISTORY_LIMIT&offset=$offset",
                network = wifi,
            )) {
                is ApiResult.Success -> {
                    val page: VideoPage = parseVideos(result.data)
                    // Cache only the first (non-append) page for offline consult.
                    if (!append) OfflineCache.save(getApplication(), CACHE_KEY, result.data)
                    if (append) {
                        cache.addAll(page.videos)
                    } else {
                        cache.clear()
                        cache.addAll(page.videos)
                    }
                    total = page.total.coerceAtLeast(cache.size)
                    offset = cache.size
                    offlineMode = false
                    lastCachedAt = null
                    publishFiltered(hasMore = offset < total, loadingMore = false)
                    // A successful fetch implies the Jetson is reachable;
                    // the manager owns the banner so nothing to set here.
                }
                is ApiResult.HttpError -> {
                    _state.value = if (previous is HistoryUiState.Loaded) {
                        previous.copy(loadingMore = false)
                    } else {
                        loadCachedVideos() ?: HistoryUiState.Error("HTTP ${result.code}")
                    }
                }
                is ApiResult.NetworkError -> {
                    _state.value = if (previous is HistoryUiState.Loaded) {
                        previous.copy(loadingMore = false)
                    } else {
                        loadCachedVideos() ?: HistoryUiState.OutOfRange
                    }
                }
            }
        } catch (t: Throwable) {
            _state.value = if (previous is HistoryUiState.Loaded) {
                previous.copy(loadingMore = false)
            } else {
                loadCachedVideos() ?: HistoryUiState.OutOfRange
            }
        }
    }

    /**
     * Offline fallback — serve the last cached first page of `/api/videos`
     * so the history tab stays consultable with no Jetson connection. Fills
     * [cache]/[total]/[offset], sets [offlineMode]/[lastCachedAt], then
     * publishes via [publishFiltered]. Returns the resulting [HistoryUiState]
     * (Loaded or Empty), or null when there is no cache (caller falls back to
     * Error/OutOfRange).
     */
    private fun loadCachedVideos(): HistoryUiState? {
        val cached = OfflineCache.load(getApplication(), CACHE_KEY) ?: return null
        val page = runCatching { parseVideos(cached.json) }.getOrNull() ?: return null
        cache.clear()
        cache.addAll(page.videos)
        total = page.total.coerceAtLeast(cache.size)
        offset = cache.size
        offlineMode = true
        lastCachedAt = cached.savedAt
        publishFiltered(hasMore = false, loadingMore = false)
        return _state.value
    }

    /**
     * Re-apply the active date + status filters to the in-memory cache and
     * republish [state]. Called after a filter change (no network).
     */
    private fun reapplyFilter() {
        if (cache.isEmpty()) {
            // No rows cached yet — keep the current load/empty/error state.
            if (_state.value is HistoryUiState.Loaded) {
                _state.value = HistoryUiState.Empty
            }
            return
        }
        publishFiltered(hasMore = offset < total, loadingMore = false)
    }

    /**
     * Apply the active filters to [cache] and publish the resulting
     * [HistoryUiState.Loaded] (or [HistoryUiState.Empty] when the filtered
     * set is empty AND no filter is active — a filter producing zero rows
     * is still [Loaded] so the user sees their filter took effect).
     *
     * Running rows are kept first (the companion emits the synthetic running
     * row at index 0), then newest `ts` first.
     */
    private fun publishFiltered(hasMore: Boolean, loadingMore: Boolean) {
        val date = _filterDate.value
        val status = _filterStatus.value
        val rows = cache.filter { matchesFilters(it, date, status) }
            .sortedWith(
                compareByDescending<VideoRow> { it.status == "running" }
                    .thenByDescending { it.ts ?: "" }
            )
        _state.value = when {
            rows.isEmpty() && date == null && status == HistoryStatusFilter.ALL ->
                HistoryUiState.Empty
            else -> HistoryUiState.Loaded(
                rows = rows,
                total = if (date == null && status == HistoryStatusFilter.ALL) total else rows.size,
                hasMore = hasMore && date == null && status == HistoryStatusFilter.ALL,
                loadingMore = loadingMore,
                offline = offlineMode,
                cachedAt = lastCachedAt,
            )
        }
    }

    /**
     * Client-side filter predicate.
     *
     * Date: compares the row's `ts` (parsed to a local date) to [date].
     * Rows whose `ts` is absent or unparseable are excluded when a date
     * filter is active.
     *
     * Status: branches on the row's `status` field — `READY` →
     * `status == "ready"`, `RUNNING` → `status == "running"`.
     *
     * The old video-only hack (excluding rows with no `last_segment` /
     * zero `count_delta`) is gone — every `/api/videos` row is already a
     * video.
     */
    private fun matchesFilters(
        v: VideoRow,
        date: LocalDate?,
        status: HistoryStatusFilter,
    ): Boolean {
        if (date != null) {
            val rowDate = parseLocalDate(v.ts)
            if (rowDate != date) return false
        }
        if (status != HistoryStatusFilter.ALL) {
            if (!matchesStatusFilter(v, status)) return false
        }
        return true
    }

    /** Branch on the row's `status` field (the only status a [VideoRow] carries). */
    private fun matchesStatusFilter(v: VideoRow, filter: HistoryStatusFilter): Boolean =
        when (filter) {
            HistoryStatusFilter.ALL -> true
            HistoryStatusFilter.RUNNING -> v.status == "running"
            HistoryStatusFilter.READY -> v.status == "ready"
        }

    /** Parse an ISO-8601 datetime (or bare date) into a [LocalDate], null on failure. */
    private fun parseLocalDate(iso: String?): LocalDate? {
        if (iso.isNullOrBlank()) return null
        return runCatching {
            // Prefer OffsetDateTime (the companion emits offset datetimes);
            // fall back to a bare LocalDate (YYYY-MM-DD) for robustness.
            try {
                OffsetDateTime.parse(iso, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toLocalDate()
            } catch (e: DateTimeParseException) {
                LocalDate.parse(iso.take(10))
            }
        }.getOrNull()
    }

    /** Resolve the active WiFi network (null when not on the Jetson HotSpot). */
    private fun cm(): ConnectivityManager? = getApplication<Application>()
        .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
}