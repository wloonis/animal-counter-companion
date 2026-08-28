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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.animalcounter.R
import com.animalcounter.net.Counters
import com.animalcounter.net.CountingEvent
import com.animalcounter.net.Heartbeat
import com.animalcounter.net.SessionDetail
import com.animalcounter.net.SessionEnd
import com.animalcounter.net.SessionStart
import com.animalcounter.net.SystemHealth
import com.animalcounter.net.VideoMeta
import com.animalcounter.net.optStringOrNull
import com.animalcounter.net.ProbeState
import org.json.JSONObject
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Détail de session screen — pushed `composable` route `session/{sessionId}`.
 *
 * Renders the A–G groups from `GET /api/sessions/<id>` as a vertical scroll
 * of Material 3 `ElevatedCard`s (depth contrast vs. the flat list rows on
 * the History tab), each with an `Icon` + `titleMedium` header and a
 * `HorizontalDivider`:
 *  - **En-tête** (A): video filename, start/end (locale), duration, status
 *    pill (M3 tonal `Chip` + colored dot per the verified `end_reason`
 *    mapping), end_reason label.
 *  - **Comptage** (B): net (`headlineSmall`) + directional counts with
 *    arrow icons + tracking health counters; for a running session
 *    (`end == null`) counters fall back to the last heartbeat count.
 *  - **Guards** (B): per-type `guard_interventions` counters as rows.
 *  - **Performance/thermique** (C/F): aggregated from `heartbeats[]` —
 *    fps avg/min, frames dropped, SoC temp avg/peak, inference ms, gpu
 *    util. Every field renders "N/A" gracefully when absent.
 *  - **Configuration** (D): model, thresholds, guard params, git_commit
 *    (monospace), image_tag, mode.
 *  - **Vidéo** (E): size, duration, resolution, codec, complete, path;
 *    "running / N/A" when absent.
 *  - **Système** (F): disk_free start (last heartbeat) / end (`end.system`),
 *    cpu_load_avg, mem_used.
 *  - **Timeline d'événements** (G): chronological `events[]` rows.
 *
 * Loading = `LinearProgressIndicator`; empty/error in `OutlinedCard`;
 * reachability banner at the top (reuses the Time sync banner style).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionDetailScreen(
    sessionId: String?,
    onBack: () -> Unit = {},
) {
    val vm: SessionDetailViewModel = viewModel()
    val state by vm.state.collectAsState()
    val probeState by vm.probeState.collectAsState()

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.detail_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.detail_back),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { ReachabilityBanner(probeState = probeState) }

            when (val s = state) {
                is SessionDetailUiState.Loading -> item {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                is SessionDetailUiState.Loaded -> {
                    val d = s.detail
                    item { HeaderCard(d) }
                    item { ComptageCard(d) }
                    item { VideosCard(d) }
                    item { PerfCard(d) }
                    item { ConfigCard(d) }
                    item { SystemCard(d) }
                }
                is SessionDetailUiState.OutOfRange -> item { OutOfRangeCard() }
                is SessionDetailUiState.Error -> item { ErrorCard(message = s.message) }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Group cards
// ---------------------------------------------------------------------------

/** A — En-tête. */
@Composable
private fun HeaderCard(d: SessionDetail) {
    val start = d.start
    val end = d.end
    val startStr = formatIso(start?.startAt)
    // A running session's end_at is the companion's last-heartbeat fill
    // (~ now), not a real end — show "—" instead of a misleading end date,
    // and skip the duration row (it would be a moving last-heartbeat span).
    val isRunning = d.status == "running"
    val endStr = if (isRunning) "—" else formatIso(d.endAt ?: end?.endAt)
    val dur = if (isRunning) null else durationBetween(start?.startAt, d.endAt ?: end?.endAt)
    GroupCard(
        icon = Icons.Filled.PlayCircle,
        title = stringResource(R.string.group_header),
    ) {
        KeyValueRow(R.string.detail_session_id, d.sessionId.ifBlank { "—" })
        KeyValueRow(R.string.detail_start, startStr)
        KeyValueRow(R.string.detail_end, endStr)
        if (dur != null) KeyValueRow(R.string.detail_duration, dur)
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            KeyValueLabel(R.string.detail_status)
            StatusPill(status = d.status, endReason = d.endReason ?: end?.endReason)
        }
        KeyValueRow(R.string.detail_end_reason, endReasonLabel(d.endReason ?: end?.endReason))
    }
}

/** C/F — Performance & thermique, aggregated from `heartbeats[]`. */
@Composable
private fun PerfCard(d: SessionDetail) {
    val agg = aggregatePerf(d.heartbeats)
    GroupCard(
        icon = Icons.Filled.MonitorHeart,
        title = stringResource(R.string.group_perf),
    ) {
        KeyValueRow(R.string.perf_fps_avg, agg.fpsAvg?.format(1), unit = null)
        KeyValueRow(R.string.perf_fps_min, agg.fpsMin?.format(1), unit = null)
        KeyValueRow(R.string.perf_frames_dropped, agg.framesDropped?.toString())
        KeyValueRow(R.string.perf_temp_avg, agg.tempAvg?.format(1), unit = R.string.unit_celsius)
        KeyValueRow(R.string.perf_temp_peak, agg.tempPeak?.format(1), unit = R.string.unit_celsius)
        KeyValueRow(R.string.perf_inference_ms, agg.inferenceMs?.format(1))
        KeyValueRow(R.string.perf_gpu_util, agg.gpuUtil?.format(0), unit = null)
    }
}

/** D — Configuration snapshot. */
@Composable
private fun ConfigCard(d: SessionDetail) {
    val cfg = d.config ?: d.start?.config
    val notable = cfg?.notable
    GroupCard(
        icon = Icons.Filled.Settings,
        title = stringResource(R.string.group_config),
    ) {
        KeyValueRow(R.string.config_image_tag, cfg?.imageTag)
        KeyValueRow(R.string.config_mode, cfg?.mode)
        KeyValueRow(R.string.config_model, notable?.optStringOrNull("model"))
        KeyValueRow(R.string.config_thresholds, notable?.pretty("thresholds"))
        KeyValueRow(R.string.config_guard_params, notable?.pretty("guard"))
        KeyValueRow(R.string.config_git_commit, cfg?.gitCommit, monospace = true)
    }
}

/** F — Système. */
@Composable
private fun SystemCard(d: SessionDetail) {
    val lastHb = d.heartbeats.lastOrNull()
    val startSys = lastHb?.system
    val endSys = d.end?.system ?: if (d.end == null) lastHb?.system else null
    GroupCard(
        icon = Icons.Filled.Devices,
        title = stringResource(R.string.group_system),
    ) {
        KeyValueRow(R.string.sys_disk_free_start, startSys?.diskFree?.let { formatGb(it) })
        KeyValueRow(R.string.sys_disk_free_end, endSys?.diskFree?.let { formatGb(it) })
        KeyValueRow(R.string.sys_cpu_load, (endSys ?: startSys)?.cpuLoadAvg?.joinToString(", ") { it.format(2) })
        KeyValueRow(R.string.sys_mem_used, (endSys ?: startSys)?.memUsed?.let { formatGb(it) })
    }
}

/** BL-71 — Vidéos de la session. Per-video counting (directional, guards,
 *  REID, track_lost, events, perf) lives on the video detail
 *  (/api/videos/<id>); the session lists its video_ids. */
@Composable
private fun VideosCard(d: SessionDetail) {
    val videos = d.videos
    GroupCard(
        icon = Icons.Filled.VideoFile,
        title = stringResource(R.string.group_video),
    ) {
        if (videos.isEmpty()) {
            Text(
                text = stringResource(R.string.timeline_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@GroupCard
        }
        videos.forEach { vid ->
            KeyValueRow(R.string.video_path, vid)
        }
    }
}

// ---------------------------------------------------------------------------
// Small building blocks
// ---------------------------------------------------------------------------

/** One ElevatedCard group with an icon + title header + divider + body. */
@Composable
internal fun GroupCard(
    icon: ImageVector,
    title: String,
    content: @Composable () -> Unit,
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

/** Label/value row inside a group card. `value` renders "N/A" when null/blank. */
@Composable
internal fun KeyValueRow(
    labelRes: Int,
    value: String?,
    unit: Int? = null,
    monospace: Boolean = false,
) {
    val display = value?.takeIf { it.isNotBlank() } ?: na()
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val text = if (unit != null) "$display ${stringResource(unit)}" else display
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = if (monospace) FontFamily.Monospace else null,
            fontWeight = if (monospace) FontWeight.Normal else FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Label-only leading text (used before an inline pill). */
@Composable
internal fun KeyValueLabel(labelRes: Int) {
    Text(
        text = stringResource(labelRes),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** String-label overload (for dynamic labels like guard event_type keys
 *  or non-resource strings — BL-71 per-video metadata). */
@Composable
internal fun KeyValueRow(label: String, value: String?) {
    val display = value?.takeIf { it.isNotBlank() } ?: na()
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = display,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/** Directional count row (left→right / right→left / down→up / up→down)
 *  with an orientation-appropriate arrow icon. */
@Composable
internal fun DirectionalRow(labelRes: Int, value: Int?, arrow: ImageVector) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = arrow,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value?.toString() ?: na(),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
    }
}

/** B — Comptage (BL-85). Net headline + directional rows driven by the
 *  session's `counting_line_orientation` (vertical = LEFT/RIGHT, horizontal
 *  = UP/DOWN). The counts come from the companion's top-level aggregated
 *  fields (not `end.counters`); they are available for both running and
 *  ended sessions. */
@Composable
private fun ComptageCard(d: SessionDetail) {
    val isHorizontal = d.countingLineOrientation == "horizontal"
    GroupCard(
        icon = Icons.Filled.Timeline,
        title = stringResource(R.string.group_counting),
    ) {
        Text(
            text = "${d.netCount ?: 0}",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(8.dp))
        if (isHorizontal) {
            DirectionalRow(R.string.detail_count_dtu, d.countDownToUp, Icons.Filled.ArrowUpward)
            DirectionalRow(R.string.detail_count_utd, d.countUpToDown, Icons.Filled.ArrowDownward)
        } else {
            DirectionalRow(R.string.detail_count_ltr, d.countLeftToRight, Icons.AutoMirrored.Filled.ArrowBack)
            DirectionalRow(R.string.detail_count_rtl, d.countRightToLeft, Icons.Filled.ArrowForward)
        }
    }
}

/** One guard counter row with a leading bolt icon + trailing count. */
@Composable
private fun GuardRow(labelRes: Int, count: Int) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.Bolt,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** One event line in the timeline. */
@Composable
private fun EventRow(ev: CountingEvent) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = formatIso(ev.ts),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = eventLabel(ev.eventType),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
        }
        ev.detail?.toString()?.takeIf { it.isNotBlank() && it != "{}" }?.let { detail ->
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Status pill (reuses the verified end_reason mapping)
// ---------------------------------------------------------------------------

/**
 * Status pill = small tonal `Surface` with a leading colored dot. Color
 * logic branches on `end_reason` (+ `running` via `status`):
 *  - `status == "running"` → blue (primary)
 *  - else `end_reason == "clean"` → green (tertiary)
 *  - else `end_reason == "power-loss"` → orange (error)
 *  - else (`"unknown"`, `"sigterm"`, null) → gray (outline)
 */
@Composable
private fun StatusPill(status: String, endReason: String?) {
    val (dotColor, label) = when {
        status == "running" ->
            MaterialTheme.colorScheme.primary to stringResource(R.string.status_running)
        endReason == "clean" ->
            MaterialTheme.colorScheme.tertiary to stringResource(R.string.status_clean)
        endReason == "power-loss" ->
            MaterialTheme.colorScheme.error to stringResource(R.string.status_power_loss)
        else ->
            MaterialTheme.colorScheme.outline to stringResource(R.string.status_unknown)
    }
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
private fun endReasonLabel(endReason: String?): String = when (endReason) {
    "clean" -> stringResource(R.string.status_clean)
    "power-loss" -> stringResource(R.string.status_power_loss)
    "running" -> stringResource(R.string.status_running)
    null, "" -> na()
    else -> endReason
}

// ---------------------------------------------------------------------------
// Reachability banner + empty/error cards (mirror the History screen style)
// ---------------------------------------------------------------------------

@Composable
internal fun ReachabilityBanner(probeState: ProbeState) {
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

@Composable
internal fun OutOfRangeCard() {
    InfoCard(
        icon = Icons.Filled.WifiOff,
        title = stringResource(R.string.error_out_of_range),
        body = stringResource(R.string.jetson_out_of_range),
    )
}

@Composable
internal fun ErrorCard(message: String) {
    InfoCard(
        icon = Icons.Outlined.ErrorOutline,
        title = stringResource(R.string.error_load),
        body = message.ifBlank { stringResource(R.string.detail_empty) },
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
// Perf aggregation from heartbeats[] (best-effort thermal)
// ---------------------------------------------------------------------------

private data class PerfAgg(
    val fpsAvg: Double?,
    val fpsMin: Double?,
    val framesDropped: Int?,
    val tempAvg: Double?,
    val tempPeak: Double?,
    val inferenceMs: Double?,
    val gpuUtil: Double?,
)

/**
 * Aggregate perf/thermal samples across `heartbeats[]`. Thermal shape is
 * not frozen by BL-68, so each field is probed defensively under several
 * common key aliases; missing keys degrade to `null` (rendered "N/A").
 */
private fun aggregatePerf(heartbeats: List<Heartbeat>): PerfAgg {
    if (heartbeats.isEmpty()) return PerfAgg(null, null, null, null, null, null, null)
    val fpsList = mutableListOf<Double>()
    var droppedSum = 0
    var droppedSeen = false
    val tempList = mutableListOf<Double>()
    val infList = mutableListOf<Double>()
    val gpuList = mutableListOf<Double>()
    heartbeats.forEach { hb ->
        val t = hb.thermal ?: return@forEach
        t.optDoubleOrNullAlias(listOf("fps", "fps_avg", "avg_fps"))?.let { fpsList.add(it) }
        t.optIntOrNullAlias(listOf("frames_dropped", "dropped", "dropped_frames"))?.let {
            droppedSum += it; droppedSeen = true
        }
        t.optDoubleOrNullAlias(listOf("soc_temp", "soc_temp_c", "temp", "temp_c", "soc_temp_avg"))?.let {
            tempList.add(it)
        }
        t.optDoubleOrNullAlias(listOf("inference_ms", "inference"))?.let { infList.add(it) }
        t.optDoubleOrNullAlias(listOf("gpu_util", "gpu"))?.let { gpuList.add(it) }
    }
    return PerfAgg(
        fpsAvg = fpsList.takeIf { it.isNotEmpty() }?.average(),
        fpsMin = fpsList.minOrNull(),
        framesDropped = if (droppedSeen) droppedSum else null,
        tempAvg = tempList.takeIf { it.isNotEmpty() }?.average(),
        tempPeak = tempList.maxOrNull(),
        inferenceMs = infList.takeIf { it.isNotEmpty() }?.average(),
        gpuUtil = gpuList.takeIf { it.isNotEmpty() }?.average(),
    )
}

private fun JSONObject.optDoubleOrNullAlias(keys: List<String>): Double? {
    for (k in keys) {
        if (has(k) && !isNull(k)) {
            val d = optDouble(k, Double.NaN)
            if (!d.isNaN()) return d
        }
    }
    return null
}

private fun JSONObject.optIntOrNullAlias(keys: List<String>): Int? {
    for (k in keys) {
        if (has(k) && !isNull(k)) return optInt(k)
    }
    return null
}

/** Pretty-print a nested JSON object/array value under [key], else null. */
private fun JSONObject.pretty(key: String): String? {
    val v = if (has(key) && !isNull(key)) opt(key) else null
    return when {
        v == null -> null
        v is JSONObject && v.length() == 0 -> null
        v.toString() == "null" -> null
        else -> v.toString()
    }
}

// ---------------------------------------------------------------------------
// Event-type label (localized for the known set, raw fallback otherwise)
// ---------------------------------------------------------------------------

@Composable
private fun eventLabel(type: String?): String {
    val res = when (type) {
        "crossed_left" -> R.string.event_crossed_left
        "crossed_right" -> R.string.event_crossed_right
        "id_switch_recovery" -> R.string.event_id_switch_recovery
        "mirror_guard" -> R.string.event_mirror_guard
        "resurrection" -> R.string.event_resurrection
        "reid_suppress" -> R.string.event_reid_suppress
        "track_lost" -> R.string.event_track_lost
        "lost_buffer_expired" -> R.string.event_lost_buffer_expired
        "mirror_guard_enforce" -> R.string.event_mirror_guard_enforce
        "mirror_suppress" -> R.string.event_mirror_suppress
        else -> return type ?: na()
    }
    return stringResource(res)
}

// ---------------------------------------------------------------------------
// Formatting helpers
// ---------------------------------------------------------------------------

@Composable
internal fun na(): String = stringResource(R.string.na_label)

/** Render an ISO-8601 timestamp as a short locale date-time. */
internal fun formatIso(iso: String?): String {
    if (iso.isNullOrBlank()) return "—"
    return runCatching {
        val instant = parseInstant(iso) ?: return iso.take(19)
        DateTimeFormatter
            .ofPattern("dd MMM yyyy HH:mm:ss", Locale.getDefault())
            .withZone(ZoneId.systemDefault())
            .format(instant)
    }.getOrNull() ?: iso.take(19)
}

private fun parseInstant(iso: String): Instant? = runCatching {
    try {
        OffsetDateTime.parse(iso, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant()
    } catch (e: DateTimeParseException) {
        java.time.LocalDateTime.parse(iso.take(19)).atZone(ZoneId.systemDefault()).toInstant()
    }
}.getOrNull()

/** Whole-second duration between two ISO timestamps, null when either absent. */
internal fun durationBetween(startIso: String?, endIso: String?): String? {
    if (startIso.isNullOrBlank() || endIso.isNullOrBlank()) return null
    return runCatching {
        val start = parseInstant(startIso) ?: return null
        val end = parseInstant(endIso) ?: return null
        val secs = ChronoUnit.SECONDS.between(start, end)
        if (secs < 0) return null
        val h = secs / 3600
        val m = (secs % 3600) / 60
        val s = secs % 60
        if (h > 0) String.format(Locale.ROOT, "%dh %02dm %02ds", h, m, s)
        else if (m > 0) String.format(Locale.ROOT, "%dm %02ds", m, s)
        else String.format(Locale.ROOT, "%ds", s)
    }.getOrNull()
}

/** Format a byte count as MB or GB. */
private fun formatBytes(bytes: Long): String {
    val mb = bytes / 1_048_576.0
    return if (mb >= 1024) String.format(Locale.ROOT, "%.2f GB", mb / 1024)
    else String.format(Locale.ROOT, "%.1f MB", mb)
}

/** Format seconds (Double) as a short duration string. */
private fun formatSeconds(seconds: Double): String {
    val total = seconds.toLong()
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format(Locale.ROOT, "%dh %02dm %02ds", h, m, s)
    else if (m > 0) String.format(Locale.ROOT, "%dm %02ds", m, s)
    else String.format(Locale.ROOT, "%.1fs", seconds)
}

/** Format a GB value (Double, already in GB). */
private fun formatGb(gb: Double): String =
    if (gb >= 1.0) String.format(Locale.ROOT, "%.2f GB", gb)
    else String.format(Locale.ROOT, "%.0f MB", gb * 1024)

private fun Double.format(digits: Int): String =
    String.format(Locale.ROOT, "%.${digits}f", this)