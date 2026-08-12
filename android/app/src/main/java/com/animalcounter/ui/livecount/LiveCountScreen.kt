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

import androidx.compose.animation.animateContentSize
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.animalcounter.R
import com.animalcounter.net.LiveCount
import com.animalcounter.net.ProbeState
import com.animalcounter.ui.common.AppNavIcon

/**
 * Comptage live tab.
 *
 * Shows the current `GET /api/count` snapshot: a large animated number,
 * a status pill (colored dot + label per the mapping in the plan), and an
 * auto-mode chip. Polls every ~2s while foregrounded (lifecycle-aware —
 * the loop is started on `ON_RESUME` and cancelled on `ON_PAUSE`), and
 * supports manual pull-to-refresh.
 *
 * Visual language: Material 3 — `LargeTopAppBar` with collapsing scroll
 * behavior, M3 `Card` for the big number, M3 tonal `Chip` for the status
 * pill (leading colored dot), `AssistChip` for auto-mode, `OutlinedCard`
 * for empty/error states, `LinearProgressIndicator` for loading, and
 * `PullToRefreshBox` for manual refresh. The big number uses
 * `animateContentSize()` so it ticks smoothly on change.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveCountScreen() {
    val vm: LiveCountViewModel = viewModel()
    val state by vm.state.collectAsState()
    val probeState by vm.probeState.collectAsState()

    // Lifecycle-aware polling: start on resume, stop on pause/stop so the
    // ~2s /api/count loop only runs while the tab is foregrounded.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> vm.startPolling()
                Lifecycle.Event.ON_PAUSE -> vm.stopPolling()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val pullState = rememberPullToRefreshState()
    val isRefreshing = state is LiveCountUiState.Loading

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(R.string.tab_live_count)) },
                navigationIcon = { AppNavIcon() },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { innerPadding ->
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = vm::refresh,
            state = pullState,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .nestedScroll(scrollBehavior.nestedScrollConnection),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ReachabilityBanner(probeState = probeState)

                when (val s = state) {
                    is LiveCountUiState.Loading -> LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                    )
                    is LiveCountUiState.Loaded -> LiveCountBody(count = s.count)
                    is LiveCountUiState.OutOfRange -> OutOfRangeCard()
                    is LiveCountUiState.Error -> ErrorCard(message = s.message)
                }
            }
        }
    }
}

/** Big-number card + status pill + auto-mode chip. */
@Composable
private fun LiveCountBody(count: LiveCount) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.live_count_label),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = count.count.toString(),
                modifier = Modifier.animateContentSize(),
                style = MaterialTheme.typography.displayLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
            )
            LivePill(timestamp = count.timestamp)
            AssistChip(
                onClick = {},
                enabled = false,
                label = { Text(stringResource(R.string.live_auto_mode)) },
                leadingIcon = {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(
                                color = if (count.autoMode)
                                    MaterialTheme.colorScheme.tertiary
                                else MaterialTheme.colorScheme.outline,
                                shape = CircleShape,
                            ),
                    )
                },
                colors = AssistChipDefaults.assistChipColors(
                    disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            )
        }
    }
}

/**
 * Live freshness pill — derives the live state from the **heartbeat
 * timestamp** (not the `status` field, which is an int from SharedState
 * and is absent on older images). "En direct" if the last heartbeat is
 * recent (< 15s, ~3x the 5s heartbeat interval), "En pause" if stale,
 * "Hors ligne" if no timestamp. Works on old + new images alike.
 */
@Composable
private fun LivePill(timestamp: String?) {
    val (dotColor, label) = liveVisual(timestamp)
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = MaterialTheme.shapes.small,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(color = dotColor, shape = CircleShape),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

private fun parseInstant(ts: String): java.time.Instant? =
    runCatching { java.time.Instant.parse(ts) }
        .getOrElse {
            runCatching { java.time.OffsetDateTime.parse(ts).toInstant() }.getOrNull()
        }

@Composable
private fun liveVisual(timestamp: String?): Pair<Color, String> {
    if (timestamp == null) {
        return MaterialTheme.colorScheme.outline to stringResource(R.string.live_offline)
    }
    val ageMs = parseInstant(timestamp)
        ?.let { java.time.Duration.between(it, java.time.Instant.now()).toMillis() }
        ?: return MaterialTheme.colorScheme.outline to stringResource(R.string.live_offline)
    return if (ageMs < 15_000) {
        MaterialTheme.colorScheme.primary to stringResource(R.string.live_direct)
    } else {
        MaterialTheme.colorScheme.outline to stringResource(R.string.live_idle)
    }
}

/** Reachability banner — reuses the Time sync banner styling keyed on ProbeState. */
@Composable
private fun ReachabilityBanner(probeState: ProbeState) {
    val container: Color
    val onContainer: Color
    val message: String
    val icon: androidx.compose.ui.graphics.vector.ImageVector
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
        title = stringResource(R.string.error_network),
        body = message,
    )
}

@Composable
private fun InfoCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    body: String,
) {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                )
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