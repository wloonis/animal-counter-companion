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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.animalcounter.R
import com.animalcounter.net.SessionSummary
import com.animalcounter.ui.common.AppNavIcon
import com.animalcounter.ui.common.OfflineBanner
import com.animalcounter.ui.sessiondetail.ReachabilityBanner
import com.animalcounter.ui.sessiondetail.formatIso
import com.animalcounter.net.ProbeState

/**
 * Sessions list — reached from the Dashboard "Sessions: N" entry. Lists
 * session-level entries (session id, start, end, end_reason, heartbeats,
 * events) scoped to the Dashboard's active period window. Tapping a row
 * navigates to the full Session detail (`session/{sessionId}`).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionsScreen(
    navController: NavController,
    onBack: (() -> Unit)? = null,
) {
    val vm: SessionsViewModel = viewModel()
    val state by vm.state.collectAsState()
    val probeState by vm.probeState.collectAsState()

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.sessions_title)) },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.detail_back),
                            )
                        }
                    } else {
                        AppNavIcon()
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { ReachabilityBanner(probeState = probeState) }

            when (val s = state) {
                is SessionsUiState.Loading -> item {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                is SessionsUiState.Loaded -> {
                    if (s.offline) item { OfflineBanner(cachedAt = s.cachedAt) }
                    if (s.rows.isEmpty()) {
                        item { EmptyText() }
                    } else {
                        items(s.rows, key = { it.sessionId ?: it.startAt ?: it.hashCode() }) { row ->
                            SessionListRow(
                                session = row,
                                onClick = {
                                    row.sessionId?.let { navController.navigate("session/$it") }
                                },
                            )
                        }
                        if (s.hasMore && !s.loadingMore) {
                            item {
                                LaunchedEffect(Unit) { vm.loadMore() }
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            }
                        }
                    }
                }
                is SessionsUiState.Empty -> item { EmptyText() }
                is SessionsUiState.OutOfRange -> item { OutOfRangeText() }
                is SessionsUiState.Error -> item {
                    Text(
                        text = s.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun SessionListRow(session: SessionSummary, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        onClick = onClick,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.History,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = session.sessionId?.take(8) ?: "—",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (session.status == "running")
                        stringResource(R.string.filter_status_running)
                    else stringResource(R.string.status_ended),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (session.status == "running")
                        MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.detail_start) + ": " + formatIso(session.startAt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // A running session's end_at is the companion's last-heartbeat
            // fill (~ now), not a real end — don't show it as a "Fin:" date.
            val endStr =
                if (session.status == "running") null else formatIso(session.endAt)
            if (endStr != null) {
                Text(
                    text = stringResource(R.string.detail_end) + ": " + endStr,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Secondary line: end reason + heartbeats + events.
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val reason = session.endReason ?: "—"
                Text(
                    text = stringResource(R.string.detail_end_reason) + ": " + reason,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.detail_heartbeats) + ": " + session.heartbeats,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.history_events_label) + ": " + session.events,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun EmptyText() {
    Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(
            text = "—",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun OutOfRangeText() {
    Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(
            text = "Jetson —",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}