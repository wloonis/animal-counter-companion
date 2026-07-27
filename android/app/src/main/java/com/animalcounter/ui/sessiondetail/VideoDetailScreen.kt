package com.animalcounter.ui.sessiondetail

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.animalcounter.R
import com.animalcounter.net.VideoRow
import com.animalcounter.net.VideoDetail
import com.animalcounter.net.VideoPerf
import com.animalcounter.net.CountingEvent
import com.animalcounter.net.optIntOrNull
import com.animalcounter.net.optStringOrNull
import com.animalcounter.net.optDoubleOrNull
import java.util.Locale

/**
 * Détail vidéo — video-centric detail screen reached from the History tab
 * via the `video/{videoId}?...` nav route.
 *
 * BL-72: the screen no longer fetches `/api/sessions/<id>` (the old
 * `SessionDetailViewModel` diagnostics dump). Instead it reads the
 * [VideoRow] facts straight from the Navigation Compose back-stack args
 * via [VideoDetailViewModel] (no re-fetch — the `/api/videos` row carries
 * everything). It renders a [VideoHeaderCard] (filename, start/ts,
 * duration, count_delta, status pill) and a download/open button:
 *  - On enter the gallery is probed for an existing copy of `filename`;
 *    a hit → the button reads "Open" and fires `ACTION_VIEW` on the
 *    existing `contentUri`.
 *  - On miss → the button reads "Download" and streams
 *    `GET /api/video/<videoId>` into `MediaStore` (`Movies/Films`) with a
 *    [LinearProgressIndicator] driven by `DownloadState.Downloading(percent)`.
 *  - 404 → the "video no longer available" message.
 *  - A `status == "running"` row disables the button with a "still
 *    recording" hint (the compressed file does not exist yet).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoDetailScreen(
    onBack: () -> Unit = {},
) {
    val vm: VideoDetailViewModel = viewModel()
    val ui by vm.ui.collectAsState()
    val downloadState by vm.downloadState.collectAsState()
    val detail by vm.detail.collectAsState()
    val context = LocalContext.current
    val row = ui.row

    // Probe the gallery on enter — decides "Open" vs "Download" and
    // short-circuits the network round-trip when the clip is already saved.
    LaunchedEffect(row.filename) {
        vm.probe(context)
    }

    // Fetch the per-video metadata (directional, guards, track_lost, events,
    // perf/thermal) from /api/videos/<videoId> (BL-71).
    LaunchedEffect(row.videoId) {
        vm.loadDetail()
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.video_detail_title)) },
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
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { VideoHeaderCard(row) }
            item { DownloadCard(row, downloadState, onDownload = { vm.downloadOrOpen(context) }) }
            item { VideoMetadataCard(detail) }
        }
    }
}

// ---------------------------------------------------------------------------
// Header card
// ---------------------------------------------------------------------------

/** En-tête vidéo: filename, start/ts, duration, count_delta, status pill. */
@Composable
private fun VideoHeaderCard(row: VideoRow) {
    GroupCard(
        icon = Icons.Filled.PlayCircle,
        title = stringResource(R.string.detail_video),
    ) {
        // Filename (straight from the API — no tmp- logic).
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
                text = row.filename ?: row.videoId ?: "—",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(8.dp))

        KeyValueRow(R.string.detail_start, formatIso(row.ts))
        val dur = formatSeconds(row.fileDuration ?: row.duration)
        if (dur != null) KeyValueRow(R.string.detail_duration, dur)
        KeyValueRow(R.string.video_count_delta, row.countDelta?.toString())

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            KeyValueLabel(R.string.detail_status)
            VideoStatusPill(status = row.status)
        }
    }
}

/** BL-71 - Per-video counting metadata + perf/thermal (from /api/videos/<id>):
 *  directional counts, guard interventions (REID/mirror/resurrection/...),
 *  track_lost, events count, + perf/thermal (SoC temp, cpu). Rendered only
 *  when the detail fetch succeeded; the header + download card always render
 *  the essential facts from the nav-arg VideoRow. */
@Composable
private fun VideoMetadataCard(detail: VideoDetail?) {
    if (detail == null) return
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.group_counting),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))
            // Net (per-video delta) - headline emphasis.
            Text(
                text = stringResource(R.string.detail_net),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = detail.countDelta?.toString() ?: "-",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(8.dp))
            KeyValueRow(R.string.detail_count_ltr, detail.countLeftToRight.toString())
            KeyValueRow(R.string.detail_count_rtl, detail.countRightToLeft.toString())
            KeyValueRow(R.string.detail_id_switch, detail.trackLost.toString())
            // Guard interventions (REID/mirror/resurrection/lost_buffer/...).
            val g = detail.guardInterventions
            if (g.length() > 0) {
                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.group_guards),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(6.dp))
                for (k in g.keys()) {
                    KeyValueRow(k, g.optInt(k).toString())
                }
            }
            // Perf/thermal.
            val p = detail.perf
            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.group_perf),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(6.dp))
            KeyValueRow("SoC avg C", p.thermalAvg?.let { "%.1f".format(it) } ?: "-")
            KeyValueRow("SoC peak C", p.thermalPeak?.let { "%.1f".format(it) } ?: "-")
            KeyValueRow(R.string.sys_cpu_load, p.cpuLoadAvg?.let { "%.2f".format(it) } ?: "-")
            // Events timeline (per-video, BL-71).
            if (detail.events.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.history_events_label),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "${detail.events.size}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                for (ev in detail.events) {
                    EventRow(ev)
                }
            }
        }
    }
}

@Composable
private fun EventRow(ev: CountingEvent) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                text = formatTimeOnly(ev.ts),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = videoEventLabel(ev),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
        }
        val d = ev.detail
        if (d != null && d.length() > 0) {
            val parts = mutableListOf<String>()
            d.optIntOrNull("track_id")?.let { parts.add("ID=$it") }
            d.optIntOrNull("count")?.let { parts.add("count=$it") }
            d.optStringOrNull("side")?.let { parts.add("cote=$it") }
            val cx = d.optDoubleOrNull("cx")
            val cy = d.optDoubleOrNull("cy")
            if (cx != null && cy != null) parts.add("pos=%.0f,%.0f".format(cx, cy))
            if (parts.isNotEmpty()) {
                Text(
                    text = parts.joinToString("  "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun videoEventLabel(ev: CountingEvent): String {
    if (ev.eventType == "crossed") {
        return when (ev.detail?.optStringOrNull("direction")) {
            "LEFT" -> stringResource(R.string.event_crossed_left)
            "RIGHT" -> stringResource(R.string.event_crossed_right)
            else -> "Traversee"
        }
    }
    return when (ev.eventType) {
        "track_lost" -> stringResource(R.string.event_track_lost)
        "lost_buffer_expired" -> stringResource(R.string.event_lost_buffer_expired)
        "resurrection" -> stringResource(R.string.event_resurrection)
        "reid_suppress" -> stringResource(R.string.event_reid_suppress)
        "mirror_suppress" -> stringResource(R.string.event_mirror_suppress)
        "id_switch_recovery" -> stringResource(R.string.event_id_switch_recovery)
        "mirror_guard_enforce" -> stringResource(R.string.event_mirror_guard_enforce)
        "mirror_candidate" -> "Candidat miroir"
        else -> ev.eventType ?: "—"
    }
}

private fun formatTimeOnly(iso: String?): String {
    if (iso.isNullOrBlank()) return "—"
    return runCatching {
        val instant = try {
            java.time.OffsetDateTime.parse(iso, java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant()
        } catch (e: java.time.format.DateTimeParseException) {
            java.time.LocalDateTime.parse(iso.take(19)).atZone(java.time.ZoneId.systemDefault()).toInstant()
        }
        java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss", Locale.getDefault())
            .withZone(java.time.ZoneId.systemDefault()).format(instant)
    }.getOrNull() ?: iso.take(19).let { if (it.length >= 19) it.substring(11) else it }
}

/** Running/Ready/Unknown pill keyed on the [VideoRow] `status` field. */
@Composable
private fun VideoStatusPill(status: String) {
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
            Spacer(
                modifier = Modifier
                    .size(8.dp)
                    .background(color = dotColor, shape = CircleShape),
            )
            Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium)
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
// Download / open card
// ---------------------------------------------------------------------------

/**
 * Download/open button + progress + status messages.
 *
 * - `status == "running"` → button disabled with a "still recording" hint.
 * - [DownloadState.Done] → "Open" button; tap fires `ACTION_VIEW` on the uri.
 * - [DownloadState.Downloading] → indeterminate/percent progress bar; button disabled.
 * - [DownloadState.Probing] → small progress bar; button disabled.
 * - [DownloadState.Error] → error message (404 → "video no longer available").
 * - [DownloadState.Idle] → "Download" button.
 */
@Composable
private fun DownloadCard(
    row: VideoRow,
    state: DownloadState,
    onDownload: () -> Unit,
) {
    val running = row.status == "running"
    val context = LocalContext.current

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            when (val s = state) {
                is DownloadState.Downloading -> {
                    val percent = s.percent
                    if (percent > 0) {
                        LinearProgressIndicator(
                            progress = { percent / 100f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        // Unknown length — indeterminate.
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.video_download) +
                            if (percent > 0) " $percent%" else "",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                is DownloadState.Probing -> {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                is DownloadState.Done -> {
                    DownloadButton(
                        label = stringResource(R.string.video_open),
                        icon = Icons.AutoMirrored.Filled.OpenInNew,
                        enabled = !running,
                        hint = if (running) stringResource(R.string.video_still_recording) else null,
                        onClick = { openVideo(context, s.uri) },
                    )
                }
                is DownloadState.Error -> {
                    val msg = if (running) stringResource(R.string.video_still_recording) else s.message
                    OutlinedErrorCard(message = msg)
                    if (!running) {
                        Spacer(Modifier.height(10.dp))
                        DownloadButton(
                            label = stringResource(R.string.video_download),
                            icon = Icons.Filled.Download,
                            enabled = true,
                            hint = null,
                            onClick = onDownload,
                        )
                    }
                }
                DownloadState.Idle -> {
                    if (running) {
                        OutlinedErrorCard(message = stringResource(R.string.video_still_recording))
                    }
                    DownloadButton(
                        label = stringResource(R.string.video_download),
                        icon = Icons.Filled.Download,
                        enabled = !running,
                        hint = if (running) stringResource(R.string.video_still_recording) else null,
                        onClick = onDownload,
                    )
                }
            }
        }
    }
}

/** Primary download/open button with an optional disabled hint line. */
@Composable
private fun DownloadButton(
    label: String,
    icon: ImageVector,
    enabled: Boolean,
    hint: String?,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        colors = if (enabled) ButtonDefaults.buttonColors()
        else ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.size(8.dp))
        Text(label)
    }
    if (hint != null) {
        Spacer(Modifier.height(6.dp))
        Text(
            text = hint,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Small tonal error / info card used for the 404 + still-recording messages. */
@Composable
private fun OutlinedErrorCard(message: String) {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(12.dp),
        )
    }
}

/** Fire `ACTION_VIEW` on a gallery `contentUri` (grants read to the chooser). */
private fun openVideo(context: android.content.Context, uri: Uri) {
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "video/mp4")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching {
        context.startActivity(Intent.createChooser(intent, null))
    }
}

// ---------------------------------------------------------------------------
// Formatting helpers
// ---------------------------------------------------------------------------

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