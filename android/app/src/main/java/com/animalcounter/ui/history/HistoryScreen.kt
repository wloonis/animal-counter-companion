package com.animalcounter.ui.history

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material3.Card
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.animalcounter.R
import com.animalcounter.net.VideoRow
import com.animalcounter.ui.common.AppNavIcon
import com.animalcounter.ui.common.OfflineBanner
import com.animalcounter.net.ProbeState
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

/**
 * Historique tab — paginated, filterable list of videos (past + running)
 * from `GET /api/videos`.
 *
 * BL-72: the History tab now lists `/api/videos` rows (each row is already
 * a video — the old `matchesFilters` video-only hack is gone). Each card
 * shows the final filename (`counting-{ts}-#N.mp4`, straight from the API),
 * the per-video `count_delta` (big number + direction arrow), the `duration`,
 * and a status pill (Running = primary, Ready = tertiary). The status chip
 * group is collapsed to All / Running / Ready (a [VideoRow] carries no
 * `end_reason`). Tapping a row navigates to `video/{videoId}` passing the
 * full [VideoRow] as nav args (no re-fetch on the detail screen).
 *
 * Visual language: Material 3 — `LargeTopAppBar` with collapsing scroll
 * behavior + a top-app-bar Refresh action; `LazyColumn` of flat M3 `Card`
 * rows; collapsible filter row with a `DatePickerDialog`-backed
 * `OutlinedButton` for the date and a single-choice `FilterChip` group for
 * the status; `PullToRefreshBox` for manual refresh;
 * `LinearProgressIndicator` for loading; empty/error states in
 * `OutlinedCard`s; reachability banner.
 *
 * Infinite-scroll: when the last visible item is near the end of the
 * loaded list and more pages are available, [HistoryViewModel.loadNextPage]
 * is invoked.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun HistoryScreen(navController: NavController) {
    val vm: HistoryViewModel = viewModel()
    val state by vm.state.collectAsState()
    val probeState by vm.probeState.collectAsState()
    val filterDate by vm.filterDate.collectAsState()
    val filterStatus by vm.filterStatus.collectAsState()

    // Auto-refresh: fetch first page on tab enter + poll every 20s while
    // foregrounded. Restarted on tab return (NavHost composes only the
    // current destination) = tab-change refresh.
    LaunchedEffect(Unit) {
        vm.refresh()
        while (isActive) {
            delay(20_000)
            vm.refresh()
        }
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val pullState = rememberPullToRefreshState()
    val listState = rememberLazyListState()

    // Infinite-scroll trigger: when the last visible item is within a
    // screenful of the end and more pages are available, load the next page.
    val uiState = state
    val hasMore = uiState is HistoryUiState.Loaded && uiState.hasMore
    LaunchedEffect(listState, hasMore) {
        snapshotFlow {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            last >= info.totalItemsCount - 4
        }
            .distinctUntilChanged()
            .collect { nearEnd ->
                if (nearEnd && hasMore) vm.loadNextPage()
            }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(R.string.history_title)) },
                navigationIcon = { AppNavIcon() },
                actions = {
                    IconButton(onClick = vm::loadFirstPage) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.refresh))
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { innerPadding ->
        PullToRefreshBox(
            isRefreshing = state is HistoryUiState.Loading,
            onRefresh = vm::loadFirstPage,
            state = pullState,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .nestedScroll(scrollBehavior.nestedScrollConnection),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // Reachability banner (always present, pinned at the top).
                item { ReachabilityBanner(probeState = probeState) }

                // Filter controls (date + status single-choice chips).
                item {
                    FilterRow(
                        filterDate = filterDate,
                        filterStatus = filterStatus,
                        onDateChange = vm::setFilterDate,
                        onStatusChange = vm::setFilterStatus,
                        onClear = {
                            vm.setFilterDate(null)
                            vm.setFilterStatus(HistoryStatusFilter.ALL)
                        },
                    )
                }

                when (val s = state) {
                    is HistoryUiState.Loading -> item {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    is HistoryUiState.Loaded -> {
                        if (s.offline) item { OfflineBanner(cachedAt = s.cachedAt) }
                        if (s.rows.isEmpty()) {
                            item { EmptyCard() }
                        } else {
                            items(s.rows, key = { it.videoId ?: it.filename ?: it.hashCode() }) { row ->
                                VideoRowCard(
                                    row = row,
                                    onClick = {
                                        val videoId = row.videoId
                                        if (!videoId.isNullOrBlank()) {
                                            navController.navigate(videoDetailRoute(row))
                                        }
                                    },
                                )
                            }
                            if (s.loadingMore) {
                                item {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(8.dp),
                                        horizontalArrangement = Arrangement.Center,
                                    ) {
                                        LinearProgressIndicator()
                                    }
                                }
                            }
                        }
                    }
                    is HistoryUiState.Empty -> item { EmptyCard() }
                    is HistoryUiState.OutOfRange -> item { OutOfRangeCard() }
                    is HistoryUiState.Error -> item { ErrorCard(message = s.message) }
                }
            }
        }
    }
}

/**
 * Build the `video/{videoId}?...` navigation route carrying the full
 * [VideoRow] as nav args (so the detail screen needs no re-fetch). Every
 * value is URL-encoded; null/blank fields encode to the empty string and
 * are parsed defensively on the detail side.
 *
 * The route placeholder names (`videoId`, `filename`, `countDelta`,
 * `duration`, `status`, `sessionId`, `ts`) match the NavHost registration
 * (see [com.animalcounter.ui.nav.AnimalCounterApp]).
 */
internal fun videoDetailRoute(row: VideoRow): String = buildString {
    append("video/")
    append(Uri.encode(row.videoId ?: ""))
    append("?filename="); append(Uri.encode(row.filename ?: ""))
    append("&countDelta="); append(Uri.encode(row.countDelta?.toString() ?: ""))
    append("&duration="); append(Uri.encode(row.duration?.toString() ?: ""))
    append("&fileDuration="); append(Uri.encode(row.fileDuration?.toString() ?: ""))
    append("&status="); append(Uri.encode(row.status))
    append("&sessionId="); append(Uri.encode(row.sessionId ?: ""))
    append("&ts="); append(Uri.encode(row.ts ?: ""))
}

// ---------------------------------------------------------------------------
// Filter row
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun FilterRow(
    filterDate: LocalDate?,
    filterStatus: HistoryStatusFilter,
    onDateChange: (LocalDate?) -> Unit,
    onStatusChange: (HistoryStatusFilter) -> Unit,
    onClear: () -> Unit,
) {
    var showDatePicker by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                onClick = { showDatePicker = true },
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Filled.CalendarMonth, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text(
                    text = filterDate?.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
                        ?: stringResource(R.string.filter_date_none),
                )
            }
            if (filterDate != null || filterStatus != HistoryStatusFilter.ALL) {
                TextButton(onClick = onClear) {
                    Icon(Icons.Filled.Clear, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(4.dp))
                    Text(stringResource(R.string.filter_clear))
                }
            }
        }

        // Single-choice status FilterChip group (horizontal, wraps when narrow).
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StatusChipRow(filterStatus, onStatusChange)
        }
    }

    if (showDatePicker) {
        val dateState = rememberDatePickerState(
            initialSelectedDateMillis = filterDate?.atStartOfDay(ZoneId.systemDefault())
                ?.toInstant()?.toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        dateState.selectedDateMillis?.let { ms ->
                            onDateChange(
                                Instant.ofEpochMilli(ms)
                                    .atZone(ZoneId.systemDefault())
                                    .toLocalDate(),
                            )
                        }
                        showDatePicker = false
                    },
                ) {
                    Text(stringResource(R.string.refresh))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text(stringResource(R.string.filter_clear))
                }
            },
        ) {
            DatePicker(state = dateState)
        }
    }
}

@Composable
private fun StatusChipRow(
    selected: HistoryStatusFilter,
    onSelect: (HistoryStatusFilter) -> Unit,
) {
    // BL-72: a VideoRow carries only `status` (`ready` | `running`) — no
    // `end_reason` — so the chip group is All / Running / Ready.
    val options = listOf(
        HistoryStatusFilter.ALL to R.string.filter_status_all,
        HistoryStatusFilter.RUNNING to R.string.filter_status_running,
        HistoryStatusFilter.READY to R.string.filter_status_ready,
    )
    options.forEach { (filter, labelRes) ->
        FilterChip(
            selected = selected == filter,
            onClick = { onSelect(filter) },
            label = { Text(stringResource(labelRes)) },
        )
    }
}

// ---------------------------------------------------------------------------
// Video row
// ---------------------------------------------------------------------------

@Composable
private fun VideoRowCard(row: VideoRow, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        onClick = onClick,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
            // Hero line: count_delta (big number) + direction arrow + status
            // pill (secondary, trailing). The count is the primary info.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val delta = row.countDelta ?: 0
                val arrow = if (delta >= 0) Icons.Filled.ArrowUpward else Icons.Filled.ArrowDownward
                val arrowTint = if (delta >= 0)
                    MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.tertiary
                Icon(arrow, contentDescription = null, tint = arrowTint, modifier = Modifier.size(28.dp))
                Text(
                    text = delta.toString(),
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                StatusPill(status = row.status)
            }

            Spacer(Modifier.height(8.dp))

            // Video filename (secondary) — straight from the API, no tmp- logic.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.VideoFile,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = row.filename ?: row.videoId?.take(20) ?: "—",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // Start date/time (locale).
            Text(
                text = formatStart(row.ts),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Duration + ready-complete trailing icon (secondary line).
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val dur = formatSeconds(row.fileDuration ?: row.duration)
                if (dur != null) {
                    Text(
                        text = stringResource(R.string.history_duration_label) + ": " + dur,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (row.status == "ready") {
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }
    }
}

/**
 * Status pill = small tonal `Surface` with a leading colored dot. Color
 * logic branches on the [VideoRow] `status` field (the only status a
 * video row carries):
 *  - `status == "running"` → blue (primary)
 *  - `status == "ready"` → green (tertiary)
 *  - else → gray (outline)
 */
@Composable
private fun StatusPill(status: String) {
    val (dotColor, label) = statusVisual(status)
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = MaterialTheme.shapes.small,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(color = dotColor, shape = CircleShape),
            )
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun statusVisual(status: String): Pair<Color, String> = when (status) {
    "running" ->
        MaterialTheme.colorScheme.primary to stringResource(R.string.filter_status_running)
    "ready" ->
        MaterialTheme.colorScheme.tertiary to stringResource(R.string.filter_status_ready)
    else ->
        MaterialTheme.colorScheme.outline to stringResource(R.string.filter_status_unknown)
}

// ---------------------------------------------------------------------------
// Reachability banner (reuses the Time sync banner style keyed on ProbeState)
// ---------------------------------------------------------------------------

@Composable
private fun ReachabilityBanner(probeState: ProbeState) {
    val container: Color
    val onContainer: Color
    val message: String
    val icon: ImageVector
    when (probeState) {
        ProbeState.Reachable -> {
            container = MaterialTheme.colorScheme.primaryContainer
            onContainer = MaterialTheme.colorScheme.onPrimaryContainer
            message = stringResource(R.string.jetson_connected)
            icon = Icons.Filled.Wifi
        }
        ProbeState.OutOfRange -> {
            container = MaterialTheme.colorScheme.errorContainer
            onContainer = MaterialTheme.colorScheme.onErrorContainer
            message = stringResource(R.string.jetson_out_of_range)
            icon = Icons.Filled.WifiOff
        }
        ProbeState.Probing, ProbeState.Idle -> {
            container = MaterialTheme.colorScheme.surfaceVariant
            onContainer = MaterialTheme.colorScheme.onSurfaceVariant
            message = stringResource(R.string.jetson_checking)
            icon = Icons.Filled.Wifi
        }
    }
    Surface(
        color = container,
        contentColor = onContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(icon, contentDescription = null)
            Text(message, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

// ---------------------------------------------------------------------------
// Empty / error / out-of-range cards
// ---------------------------------------------------------------------------

@Composable
private fun EmptyCard() {
    InfoCard(
        icon = Icons.Outlined.Inbox,
        title = stringResource(R.string.empty_history),
        body = "",
    )
}

@Composable
private fun OutOfRangeCard() {
    InfoCard(
        icon = Icons.Filled.WifiOff,
        title = stringResource(R.string.error_out_of_range),
        body = stringResource(R.string.jetson_out_of_range),
    )
}

@Composable
private fun ErrorCard(message: String) {
    InfoCard(
        icon = Icons.Outlined.ErrorOutline,
        title = stringResource(R.string.error_load),
        body = message,
    )
}

@Composable
private fun InfoCard(icon: ImageVector, title: String, body: String) {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Column {
                Text(title, style = MaterialTheme.typography.titleSmall)
                if (body.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = body,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Date/time helpers
// ---------------------------------------------------------------------------

/** Render a video's `ts` as a short locale date-time (e.g. "12 Aug 14:30"). */
private fun formatStart(iso: String?): String {
    if (iso.isNullOrBlank()) return "—"
    return runCatching {
        val instant = try {
            OffsetDateTime.parse(iso, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant()
        } catch (e: DateTimeParseException) {
            // Fall back to a bare local date-time (no offset).
            java.time.LocalDateTime.parse(iso.take(19)).atZone(ZoneId.systemDefault()).toInstant()
        }
        DateTimeFormatter
            .ofPattern("dd MMM HH:mm", Locale.getDefault())
            .withZone(ZoneId.systemDefault())
            .format(instant)
    }.getOrNull() ?: iso.take(19)
}

/** Format a duration in seconds as `H:MM:SS` / `MM:SS` / `SSs`, null when null. */
private fun formatSeconds(seconds: Double?): String? {
    if (seconds == null || seconds < 0 || seconds.isNaN()) return null
    val total = seconds.toLong()
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return when {
        h > 0 -> String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s)
        m > 0 -> String.format(Locale.ROOT, "%d:%02d", m, s)
        else -> String.format(Locale.ROOT, "%ds", s)
    }
}