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

package com.animalcounter.ui.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.animalcounter.R
import com.animalcounter.net.DailyBucket
import com.animalcounter.ui.common.AppNavIcon
import com.animalcounter.ui.common.OfflineBanner
import com.animalcounter.net.ProbeState
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.util.Locale

/**
 * Tableau de bord tab — 7/30-day summary dashboard fed by
 * `GET /api/history/summary?days=N`.
 *
 * Visual language: Material 3 — a centered `TopAppBar`, a
 * `SingleChoiceSegmentedButton` period selector (7 / 30 days) at the top,
 * a Compose `Canvas`-drawn bar chart of `net_count` per day (M3
 * `colorScheme.primary` bars with value labels and a dashed grid baseline),
 * a 2-column row of summary `Card`s (total counted / sessions / guards /
 * avg per day), `PullToRefreshBox` for manual refresh, a M3
 * `LinearProgressIndicator` for loading, empty/error states in
 * `OutlinedCard`s, and a reachability banner keyed on [ProbeState].
 *
 * No chart-library dependency — the bar chart is hand-drawn on a Compose
 * `Canvas` so the feature adds zero deps.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(onSessionsClick: (days: Int) -> Unit = {}) {
    val vm: DashboardViewModel = viewModel()
    val state by vm.state.collectAsState()
    val probeState by vm.probeState.collectAsState()
    val period by vm.period.collectAsState()

    // Auto-refresh: fetch on tab enter + poll every 20s while foregrounded.
    // The composable leaves composition on tab switch (NavHost composes only
    // the current destination), so this restarts on return = tab-change refresh.
    LaunchedEffect(Unit) {
        vm.refresh()
        while (isActive) {
            delay(20_000)
            vm.refresh()
        }
    }

    val pullState = rememberPullToRefreshState()
    val isRefreshing = state is DashboardUiState.Loading

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.dashboard_title)) },
                navigationIcon = { AppNavIcon() },
            )
        },
    ) { innerPadding ->
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = vm::refresh,
            state = pullState,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Reachability banner (always present at the top).
                ReachabilityBanner(probeState = probeState)

                // Period selector — single-choice 7 / 30 days.
                PeriodSelector(
                    selected = period,
                    onSelect = vm::setPeriod,
                )

                when (val s = state) {
                    is DashboardUiState.Loading -> {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    is DashboardUiState.Loaded -> {
                    if (s.offline) OfflineBanner(cachedAt = s.cachedAt)
                    DashboardBody(s, onSessionsClick)
                }
                    is DashboardUiState.Empty -> EmptyCard()
                    is DashboardUiState.OutOfRange -> OutOfRangeCard()
                    is DashboardUiState.Error -> ErrorCard(message = s.message)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Period selector (M3 SingleChoiceSegmentedButton, 7 / 30 days)
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PeriodSelector(selected: DashboardPeriod, onSelect: (DashboardPeriod) -> Unit) {
    val options = DashboardPeriod.entries
    SingleChoiceSegmentedButtonRow(
        modifier = Modifier.fillMaxWidth(),
    ) {
        options.forEachIndexed { index, option ->
            SegmentedButton(
                selected = selected == option,
                onClick = { onSelect(option) },
                shape = MaterialTheme.shapes.medium,
                label = {
                    Text(
                        when (option) {
                            DashboardPeriod.DAYS_1 -> stringResource(R.string.dashboard_period_1)
                            DashboardPeriod.DAYS_7 -> stringResource(R.string.dashboard_period_7)
                            DashboardPeriod.DAYS_30 -> stringResource(R.string.dashboard_period_30)
                        }
                    )
                },
            )
            if (index < options.lastIndex) {
                Spacer(Modifier.width(8.dp))
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Loaded body — bar chart + summary cards
// ---------------------------------------------------------------------------

@Composable
private fun DashboardBody(loaded: DashboardUiState.Loaded, onSessionsClick: (days: Int) -> Unit) {
    // Summary cards in a 2-column row.
    SummaryCardsRow(loaded = loaded, onSessionsClick = onSessionsClick)

    Spacer(Modifier.height(4.dp))

    // Bar chart card.
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.BarChart,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.dashboard_total_counted),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            Spacer(Modifier.height(12.dp))
            NetCountBarChart(daily = loaded.daily)
        }
    }
}

// ---------------------------------------------------------------------------
// Summary cards — 2-column row
// ---------------------------------------------------------------------------

@Composable
private fun SummaryCardsRow(loaded: DashboardUiState.Loaded, onSessionsClick: (days: Int) -> Unit) {
    // Two rows of 2 cards each (no FlowRow needed — exactly 4 cards).
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SummaryCard(
            modifier = Modifier.weight(1f),
            value = loaded.totalCounted.toString(),
            label = stringResource(R.string.dashboard_total_counted),
        )
        SummaryCard(
            modifier = Modifier.weight(1f),
            value = loaded.totalSessions.toString(),
            label = stringResource(R.string.dashboard_total_sessions),
            onClick = { onSessionsClick(loaded.period.days) },
        )
    }
    Spacer(Modifier.height(12.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SummaryCard(
            modifier = Modifier.weight(1f),
            value = loaded.totalGuards.toString(),
            label = stringResource(R.string.dashboard_total_guards),
        )
        SummaryCard(
            modifier = Modifier.weight(1f),
            value = String.format(Locale.ROOT, "%.1f", loaded.avgPerDay),
            label = stringResource(R.string.dashboard_avg_per_day),
        )
    }
}

@Composable
private fun SummaryCard(modifier: Modifier = Modifier, value: String, label: String, onClick: (() -> Unit)? = null) {
    val content: @Composable () -> Unit = {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalAlignment = Alignment.Start,
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (onClick != null) {
        Card(modifier = modifier, shape = MaterialTheme.shapes.medium, onClick = onClick) { content() }
    } else {
        Card(modifier = modifier, shape = MaterialTheme.shapes.medium) { content() }
    }
}

// ---------------------------------------------------------------------------
// Bar chart — Compose Canvas (no chart library)
// ---------------------------------------------------------------------------

/**
 * Hand-drawn vertical bar chart of `net_count` per day.
 *
 * Layout:
 *  - Bars share the full width; each bar is `drawRoundRect` with
 *    `MaterialTheme.colorScheme.primary`.
 *  - Heights are scaled to the max `net_count` across the loaded days
 *    (a single-day dashboard still draws a full-height bar).
 *  - A dashed grid baseline + 2 light grid lines mark rough quartiles.
 *  - Each bar is labeled with its `net_count` value above it and its
 *    day label (MM-dd) below it, in `labelSmall`.
 *  - Empty days (`net_count == 0`) still get a baseline tick + label so
 *    the chart never collapses to zero-width bars.
 *
 * The chart is responsive — it fills the available width (parent `Card`
 * padding) and uses a fixed 180dp height (enough for labels + bars).
 */
@Composable
private fun NetCountBarChart(daily: List<DailyBucket>) {
    if (daily.isEmpty()) return

    val barColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val valueColor = MaterialTheme.colorScheme.onSurface
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = TextStyle(color = labelColor, fontSize = 11.sp)
    val valueStyle = TextStyle(color = valueColor, fontSize = 11.sp, fontWeight = FontWeight.Bold)

    val maxNet = (daily.maxOf { it.netCount }).coerceAtLeast(1)
    val dayLabels = daily.map { it.date.takeLast(5) } // MM-dd

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(180.dp),
    ) {
        val w = size.width
        val h = size.height
        val topPad = 28f     // room for value labels above bars
        val bottomPad = 32f  // room for day labels below bars
        val leftPad = 8f
        val rightPad = 8f
        val chartH = h - topPad - bottomPad
        val chartW = w - leftPad - rightPad
        val n = daily.size
        if (n <= 0 || chartW <= 0f || chartH <= 0f) return@Canvas

        val gap = 10f
        val barW = ((chartW - gap * (n - 1)) / n).coerceAtLeast(2f)

        // Grid baseline + 2 dashed quartile lines.
        val dash = PathEffect.dashPathEffect(floatArrayOf(6f, 6f), 0f)
        for (q in 0..2) {
            val y = topPad + chartH * (q / 3f)
            drawLine(
                color = gridColor,
                start = Offset(leftPad, y),
                end = Offset(w - rightPad, y),
                strokeWidth = 1f,
                pathEffect = dash,
                cap = StrokeCap.Round,
            )
        }

        // Bars + labels.
        for (i in 0 until n) {
            val x = leftPad + i * (barW + gap)
            val v = daily[i].netCount
            val barH = (chartH * (v.toFloat() / maxNet.toFloat())).coerceAtLeast(2f)
            val top = topPad + chartH - barH
            drawRoundRect(
                color = barColor,
                topLeft = Offset(x, top),
                size = Size(barW, barH),
                cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx()),
            )

            // Value label above the bar (or just above the baseline when v==0).
            val label = v.toString()
            val valueLayout = textMeasurer.measure(label, valueStyle)
            val valueX = x + barW / 2f - valueLayout.size.width / 2f
            val valueY = if (v == 0) topPad + chartH - valueLayout.size.height - 2f
                else top - valueLayout.size.height - 2f
            drawText(valueLayout, topLeft = Offset(valueX, valueY))

            // Day label below the baseline.
            val dayLayout = textMeasurer.measure(dayLabels[i], labelStyle)
            val dayX = x + barW / 2f - dayLayout.size.width / 2f
            val dayY = topPad + chartH + 6f
            drawText(dayLayout, topLeft = Offset(dayX, dayY))
        }
    }
}

// ---------------------------------------------------------------------------
// Empty / error / out-of-range cards + reachability banner
// ---------------------------------------------------------------------------

@Composable
private fun EmptyCard() {
    InfoCard(
        icon = Icons.Outlined.Inbox,
        title = stringResource(R.string.empty_dashboard),
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