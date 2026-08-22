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

package com.animalcounter.ui.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import androidx.lifecycle.viewmodel.compose.viewModel
import com.animalcounter.BuildConfig
import com.animalcounter.R
import com.animalcounter.data.OFFSET_SLIDER_MAX
import com.animalcounter.data.OFFSET_SLIDER_MIN
import com.animalcounter.net.MaskZone
import com.animalcounter.ui.common.AppNavIcon

/**
 * Settings tab (BL-73 / BL-76) — operator configuration, restructured into
 * five clear sections:
 *
 *  1. **Horloge** — on-demand clock sync (BL-74, "Synchroniser l'heure").
 *  2. **Connexion au Jetson** — auto-select toggle, manual-override IP and
 *     the two candidate IPs (hotspot + lan) used by the parallel probe
 *     (BL-73).
 *  3. **Alimentation** — on-demand Jetson poweroff (BL-76) with a
 *     destructive button + confirmation dialog.
 *  4. **Enregistrement & tracking** — master "Track in recordings" toggle
 *     (`draw_tracking`) + two sub-toggles "Boxes" (`box_tracking`) and
 *     "Trails" (`centroid_tracking`), the latter two disabled while the
 *     master is OFF.
 *  5. **Ligne de comptage** — slider 0-100 for `offset_counting_line` with
 *     a live value readout and a warning that it affects the count.
 *
 * Every edit is persisted to DataStore (debounced in [SettingsViewModel])
 * and, for the tracking/offset settings, pushed to the Jetson via
 * `PUT /api/settings` so the next recording picks them up at hot-reload
 * time (BL-76).
 *
 * All user-facing text is localized via `stringResource(R.string.*)`
 * (no hard-coded strings).
 */

/** Which handle of a mask zone a drag grabbed: [NONE] = draw a new zone,
 *  [MOVE] = drag inside to translate, [L]/[R]/[T]/[B] = stretch one edge,
 *  [TL]/[TR]/[BL]/[BR] = stretch a corner (two edges at once). */
private enum class MaskHandle { NONE, MOVE, L, R, T, B, TL, TR, BL, BR }

/** Hit-test a pointer [offset] (px, relative to the canvas) against a [zone]
 *  to decide which handle it grabbed. [cw]/[ch] are the canvas size in px.
 *  Edges are only grabbable when the zone is large enough in that dimension
 *  (anti-ambiguity for tiny zones); corners win over single edges. */
private fun hitTestHandle(offset: Offset, z: MaskZone, cw: Float, ch: Float): MaskHandle {
    val th = 28f
    val lx = z.x * cw
    val rx = (z.x + z.w) * cw
    val ty = z.y * ch
    val by = (z.y + z.h) * ch
    val bigW = z.w * cw > th * 2
    val bigH = z.h * ch > th * 2
    val nearL = bigW && abs(offset.x - lx) <= th && offset.y in (ty - th)..(by + th)
    val nearR = bigW && abs(offset.x - rx) <= th && offset.y in (ty - th)..(by + th)
    val nearT = bigH && abs(offset.y - ty) <= th && offset.x in (lx - th)..(rx + th)
    val nearB = bigH && abs(offset.y - by) <= th && offset.x in (lx - th)..(rx + th)
    return when {
        nearL && nearT -> MaskHandle.TL
        nearL && nearB -> MaskHandle.BL
        nearR && nearT -> MaskHandle.TR
        nearR && nearB -> MaskHandle.BR
        nearL -> MaskHandle.L
        nearR -> MaskHandle.R
        nearT -> MaskHandle.T
        nearB -> MaskHandle.B
        offset.x in lx..rx && offset.y in ty..by -> MaskHandle.MOVE
        else -> MaskHandle.NONE
    }
}

/**
 * A reusable settings [Section] — a titled group of controls rendered inside
 * a [Card]. Each of the five BL-76 sections uses this wrapper so the screen
 * has a consistent visual rhythm (title + content column).
 *
 * @param title the section heading (already localized).
 * @param content the section body composable(s).
 */
@Composable
private fun Section(
    title: String,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            content()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen() {
    val vm: SettingsViewModel = viewModel()
    val autoSelect by vm.autoSelect.collectAsState()
    val manualIp by vm.manualIp.collectAsState()
    val hotspotIp by vm.hotspotIp.collectAsState()
    val lanIp by vm.lanIp.collectAsState()
    val syncState by vm.syncResult.collectAsState()
    val poweroffState by vm.poweroffResult.collectAsState()
    val drawTracking by vm.drawTracking.collectAsState()
    val boxTracking by vm.boxTracking.collectAsState()
    val centroidTracking by vm.centroidTracking.collectAsState()
    val offsetCountingLine by vm.offsetCountingLine.collectAsState()
    val countingLineOrientation by vm.countingLineOrientation.collectAsState()
    val companionVersion by vm.companionVersion.collectAsState()
    val classCatalog by vm.classCatalog.collectAsState()
    val maskZones by vm.maskZones.collectAsState()
    val drawMaskZones by vm.drawMaskZones.collectAsState()
    val snapshot by vm.snapshot.collectAsState()
    val maskSave by vm.maskSave.collectAsState()

    // Confirmation dialog for the destructive Jetson poweroff. Hidden by
    // default; the "Arrêter le Jetson" button opens it; confirming dismisses
    // the dialog and fires [SettingsViewModel.poweroff].
    var showPoweroffDialog by remember { mutableStateOf(false) }

    if (showPoweroffDialog) {
        AlertDialog(
            onDismissRequest = {
                // Only the explicit "Confirm" triggers the poweroff; cancel
                // closes the dialog without action.
                showPoweroffDialog = false
            },
            title = { Text(stringResource(R.string.power_confirm_title)) },
            text = { Text(stringResource(R.string.power_confirm_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showPoweroffDialog = false
                        vm.poweroff()
                    },
                ) {
                    Text(
                        text = stringResource(R.string.power_button),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            // No explicit dismiss button: tapping outside / back cancels via
            // onDismissRequest (kept out of the BL-76 string set).
        )
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = { AppNavIcon() },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // ---- 1. Horloge ----
            Section(title = stringResource(R.string.section_clock)) {
                // On-demand clock sync (BL-74). Pushes the current device
                // time to the Jetson (POST /api/time) via
                // JetsonConnectionManager.syncTime. The button is disabled
                // while a sync is in flight; the inline status line reflects
                // the outcome (green success auto-clears via the VM after
                // ~5s, red failure persists until the next action).
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Button(
                        onClick = vm::syncTime,
                        enabled = syncState !is SettingsViewModel.SyncState.Syncing,
                    ) {
                        if (syncState is SettingsViewModel.SyncState.Syncing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.size(8.dp))
                        }
                        Text(stringResource(R.string.settings_sync_time))
                    }

                    when (syncState) {
                        is SettingsViewModel.SyncState.Idle -> { /* no inline status */ }
                        is SettingsViewModel.SyncState.Syncing -> {
                            Text(
                                text = stringResource(R.string.settings_syncing),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        is SettingsViewModel.SyncState.Success -> {
                            Text(
                                text = stringResource(R.string.settings_sync_success),
                                color = Color(0xFF2E7D32),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        is SettingsViewModel.SyncState.Failure -> {
                            Text(
                                text = stringResource(R.string.settings_sync_failure),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }

            // ---- 2. Connexion au Jetson ----
            Section(title = stringResource(R.string.section_connection)) {
                // Auto-select toggle. When on, the manager polls both
                // candidate IPs in parallel and picks the first reachable
                // one.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = stringResource(R.string.settings_auto_select),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Switch(
                        checked = autoSelect,
                        onCheckedChange = vm::setAutoSelect,
                    )
                }

                // Manual-override IP. Typing here flips auto-select off; the
                // field stays editable so the operator can type even with
                // auto-select on (the toggle then flips off automatically).
                OutlinedTextField(
                    value = manualIp,
                    onValueChange = vm::onManualIpChange,
                    label = { Text(stringResource(R.string.settings_manual_ip)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    modifier = Modifier.fillMaxWidth(),
                )

                // Candidate IPs probed by the auto-select parallel
                // selection.
                OutlinedTextField(
                    value = hotspotIp,
                    onValueChange = vm::onHotspotIpChange,
                    label = { Text(stringResource(R.string.settings_hotspot_ip)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = lanIp,
                    onValueChange = vm::onLanIpChange,
                    label = { Text(stringResource(R.string.settings_lan_ip)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // ---- 3. Alimentation ----
            Section(title = stringResource(R.string.section_power)) {
                // Destructive "Arrêter le Jetson" button (BL-76). Opens a
                // confirmation dialog; on confirm, the VM writes the
                // `.arret_requested` sentinel via POST /api/power and the
                // counting app runs the BL-62 poweroff sequence. The
                // button is disabled while a request is in flight; the
                // inline status reflects the outcome (spinner + "Arrêt en
                // cours" while loading, then success/error).
                val powerLoading =
                    poweroffState is SettingsViewModel.PoweroffUiState.Loading

                Button(
                    onClick = { showPoweroffDialog = true },
                    enabled = !powerLoading,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (powerLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onError,
                        )
                        Spacer(Modifier.size(8.dp))
                    }
                    Text(stringResource(R.string.power_button))
                }

                when (poweroffState) {
                    is SettingsViewModel.PoweroffUiState.Idle -> { /* no inline status */ }
                    is SettingsViewModel.PoweroffUiState.Loading -> {
                        Text(
                            text = stringResource(R.string.power_in_progress),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    is SettingsViewModel.PoweroffUiState.Success -> {
                        Text(
                            text = stringResource(R.string.power_success),
                            color = Color(0xFF2E7D32),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    is SettingsViewModel.PoweroffUiState.Error -> {
                        Text(
                            text = stringResource(R.string.power_error),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }

            // ---- 4. Enregistrement & tracking ----
            Section(title = stringResource(R.string.section_tracking)) {
                // Master "Track in recordings" toggle (`draw_tracking`).
                // When OFF, the two sub-toggles below are disabled/grayed.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.draw_tracking_title),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            text = stringResource(R.string.draw_tracking_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = drawTracking,
                        onCheckedChange = vm::setDrawTracking,
                    )
                }

                // "Boxes" sub-toggle — disabled (grayed) while the master is
                // OFF.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = stringResource(R.string.box_tracking_title),
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (drawTracking)
                            MaterialTheme.colorScheme.onSurface
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Switch(
                        checked = boxTracking,
                        onCheckedChange = vm::setBoxTracking,
                        enabled = drawTracking,
                    )
                }

                // "Trails" sub-toggle — disabled (grayed) while the master
                // is OFF.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = stringResource(R.string.centroid_tracking_title),
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (drawTracking)
                            MaterialTheme.colorScheme.onSurface
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Switch(
                        checked = centroidTracking,
                        onCheckedChange = vm::setCentroidTracking,
                        enabled = drawTracking,
                    )
                }
            }

            // ---- 5. Ligne de comptage (BL-84: orientation H/V + offset signé) ----
            Section(title = stringResource(R.string.section_counting_line)) {
                // Orientation selector: two buttons (Verticale / Horizontale).
                Text(
                    text = stringResource(R.string.offset_orientation_label),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = countingLineOrientation == "vertical",
                        onClick = { vm.setCountingLineOrientation("vertical") },
                        label = { Text(stringResource(R.string.offset_orientation_vertical)) },
                        modifier = Modifier.weight(1f),
                    )
                    FilterChip(
                        selected = countingLineOrientation == "horizontal",
                        onClick = { vm.setCountingLineOrientation("horizontal") },
                        label = { Text(stringResource(R.string.offset_orientation_horizontal)) },
                        modifier = Modifier.weight(1f),
                    )
                }
                Text(
                    text = stringResource(R.string.offset_slider_title),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Slider(
                    value = offsetCountingLine.toFloat(),
                    onValueChange = { vm.setOffsetCountingLine(it.toInt()) },
                    // BL-84: signed range, centered at 0.
                    valueRange = OFFSET_SLIDER_MIN.toFloat()..OFFSET_SLIDER_MAX.toFloat(),
                    steps = 0,
                    modifier = Modifier.fillMaxWidth(),
                )
                // Live value readout (signed, e.g. "-10" / "0" / "+12").
                Text(
                    text = if (offsetCountingLine > 0) "+${offsetCountingLine}" else offsetCountingLine.toString(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // Warning: changing the counting line affects the count.
                Text(
                    text = stringResource(R.string.offset_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            // ---- 5b. Espèces comptées (BL-82) ----
            Section(title = stringResource(R.string.section_counted_species)) {
                when (val state = classCatalog) {
                    is SettingsViewModel.ClassCatalogState.Loading -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                            )
                            Text(
                                text = stringResource(R.string.species_loading),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    is SettingsViewModel.ClassCatalogState.Unavailable -> {
                        Text(
                            text = stringResource(R.string.species_unavailable),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = { vm.refreshClasses() }) {
                            Text(stringResource(R.string.species_retry))
                        }
                    }
                    is SettingsViewModel.ClassCatalogState.Error -> {
                        Text(
                            text = stringResource(R.string.species_error),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = { vm.refreshClasses() }) {
                            Text(stringResource(R.string.species_retry))
                        }
                    }
                    is SettingsViewModel.ClassCatalogState.Idle -> {
                        Text(
                            text = stringResource(R.string.species_error),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    is SettingsViewModel.ClassCatalogState.Loaded -> {
                        val catalog = state.catalog
                        if (catalog.classes.isEmpty()) {
                            Text(
                                text = stringResource(R.string.species_empty),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            catalog.classes.forEach { entry ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = entry.name,
                                            style = MaterialTheme.typography.bodyLarge,
                                        )
                                        Text(
                                            text = "id ${entry.id}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    Switch(
                                        checked = entry.id in catalog.countingClassIds,
                                        onCheckedChange = { vm.toggleClass(entry.id) },
                                    )
                                }
                            }
                            Text(
                                text = stringResource(R.string.species_hot_reload_note),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            // ---- 5c. Zones de masquage (BL-88) ----
            Section(title = stringResource(R.string.section_mask_zones)) {
                // « Capturer l'aperçu » button — fetches the camera snapshot
                // (GET /api/snapshot) served by the companion from
                // /files/snapshot.jpg (written by the countingapp). Disabled
                // while a fetch is in flight.
                OutlinedButton(
                    onClick = vm::refreshSnapshot,
                    enabled = snapshot !is SettingsViewModel.SnapshotState.Loading,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (snapshot is SettingsViewModel.SnapshotState.Loading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.size(8.dp))
                    }
                    Text(stringResource(R.string.mask_capture_preview))
                }

                // Snapshot area + drag-to-draw overlay. The image is shown in
                // a Box whose aspect ratio matches the bitmap so the image
                // fills the box exactly (no letterboxing); drag coordinates
                // are then normalized directly against the box size and clamped
                // to [0..1] before being added as a mask zone.
                when (val s = snapshot) {
                    is SettingsViewModel.SnapshotState.Idle -> {
                        Text(
                            text = stringResource(R.string.mask_drag_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    is SettingsViewModel.SnapshotState.Loading -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                            )
                            Text(
                                text = stringResource(R.string.mask_snapshot_loading),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    is SettingsViewModel.SnapshotState.Unavailable -> {
                        Text(
                            text = stringResource(R.string.mask_snapshot_unavailable),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = { vm.refreshSnapshot() }) {
                            Text(stringResource(R.string.mask_capture_preview))
                        }
                    }
                    is SettingsViewModel.SnapshotState.Error -> {
                        Text(
                            text = stringResource(R.string.mask_snapshot_error),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                        TextButton(onClick = { vm.refreshSnapshot() }) {
                            Text(stringResource(R.string.mask_capture_preview))
                        }
                    }
                    is SettingsViewModel.SnapshotState.Loaded -> {
                        val bmp = s.bitmap
                        val aspect = if (bmp.height > 0)
                            bmp.width.toFloat() / bmp.height.toFloat()
                        else 1f
                        // Drag state (px, relative to the box).
                        var boxSize by remember { mutableStateOf(IntSize.Zero) }
                        var dragStart by remember { mutableStateOf<Offset?>(null) }
                        var dragCur by remember { mutableStateOf<Offset?>(null) }
                        // Index of the zone being moved (null = not moving).
                        var movingIndex by remember { mutableStateOf<Int?>(null) }
                        // Zone being resized + which handle (null/NONE = not resizing).
                        var resizeIndex by remember { mutableStateOf<Int?>(null) }
                        var resizeHandle by remember { mutableStateOf(MaskHandle.NONE) }

                        // Text paint for the zone name labels drawn on the canvas.
                        val labelPaint = remember {
                            android.graphics.Paint().apply {
                                color = android.graphics.Color.WHITE
                                textSize = 36f
                                isAntiAlias = true
                                typeface = android.graphics.Typeface.DEFAULT_BOLD
                                setShadowLayer(6f, 1f, 1f, 0xDD000000.toInt())
                            }
                        }

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(aspect)
                                .onSizeChanged { boxSize = it }
                                .pointerInput(Unit) {
                                    detectDragGestures(
                                        onDragStart = { offset ->
                                            val w = boxSize.width
                                            val h = boxSize.height
                                            // Hit-test: grab a resize handle (edge/corner)
                                            // > move (inside) > draw a new zone (empty).
                                            var handle = MaskHandle.NONE
                                            var hitIdx = -1
                                            if (w > 0 && h > 0) {
                                                for (i in maskZones.indices.reversed()) {
                                                    val hd = hitTestHandle(offset, maskZones[i], w.toFloat(), h.toFloat())
                                                    if (hd != MaskHandle.NONE) { handle = hd; hitIdx = i; break }
                                                }
                                            }
                                            when (handle) {
                                                MaskHandle.NONE -> {
                                                    movingIndex = null
                                                    resizeIndex = null
                                                    resizeHandle = MaskHandle.NONE
                                                    dragStart = offset
                                                    dragCur = offset
                                                }
                                                MaskHandle.MOVE -> {
                                                    movingIndex = hitIdx
                                                    resizeIndex = null
                                                    resizeHandle = MaskHandle.NONE
                                                    dragStart = null
                                                    dragCur = null
                                                }
                                                else -> {
                                                    movingIndex = null
                                                    resizeIndex = hitIdx
                                                    resizeHandle = handle
                                                    dragStart = null
                                                    dragCur = null
                                                }
                                            }
                                        },
                                        onDrag = { change, delta ->
                                            change.consume()
                                            val w = boxSize.width
                                            val h = boxSize.height
                                            if (w > 0 && h > 0) {
                                                val ri = resizeIndex
                                                if (ri != null && resizeHandle != MaskHandle.NONE && resizeHandle != MaskHandle.MOVE) {
                                                    val hd = resizeHandle
                                                    vm.resizeMaskZone(
                                                        ri,
                                                        left = hd == MaskHandle.L || hd == MaskHandle.TL || hd == MaskHandle.BL,
                                                        right = hd == MaskHandle.R || hd == MaskHandle.TR || hd == MaskHandle.BR,
                                                        top = hd == MaskHandle.T || hd == MaskHandle.TL || hd == MaskHandle.TR,
                                                        bottom = hd == MaskHandle.B || hd == MaskHandle.BL || hd == MaskHandle.BR,
                                                        dxN = delta.x / w.toFloat(),
                                                        dyN = delta.y / h.toFloat(),
                                                    )
                                                } else {
                                                    val mi = movingIndex
                                                    if (mi != null) {
                                                        val z = maskZones[mi]
                                                        vm.moveMaskZone(
                                                            mi,
                                                            z.x + delta.x / w.toFloat(),
                                                            z.y + delta.y / h.toFloat(),
                                                        )
                                                    } else {
                                                        dragCur = (dragCur ?: Offset.Zero) + delta
                                                    }
                                                }
                                            }
                                        },
                                        onDragEnd = {
                                            if (movingIndex == null) {
                                                val start = dragStart
                                                val cur = dragCur
                                                val w = boxSize.width
                                                val h = boxSize.height
                                                if (start != null && cur != null && w > 0 && h > 0) {
                                                    val left = minOf(start.x, cur.x)
                                                        .coerceIn(0f, w.toFloat())
                                                    val right = maxOf(start.x, cur.x)
                                                        .coerceIn(0f, w.toFloat())
                                                    val top = minOf(start.y, cur.y)
                                                        .coerceIn(0f, h.toFloat())
                                                    val bottom = maxOf(start.y, cur.y)
                                                        .coerceIn(0f, h.toFloat())
                                                    val rw = right - left
                                                    val rh = bottom - top
                                                    if (rw > 0f && rh > 0f) {
                                                        vm.addMaskZone(
                                                            MaskZone(
                                                                x = left / w.toFloat(),
                                                                y = top / h.toFloat(),
                                                                w = rw / w.toFloat(),
                                                                h = rh / h.toFloat(),
                                                                name = "Zone ${maskZones.size + 1}",
                                                            ),
                                                        )
                                                    }
                                                }
                                            }
                                            movingIndex = null
                                            resizeIndex = null
                                            resizeHandle = MaskHandle.NONE
                                            dragStart = null
                                            dragCur = null
                                        },
                                        onDragCancel = {
                                            movingIndex = null
                                            resizeIndex = null
                                            resizeHandle = MaskHandle.NONE
                                            dragStart = null
                                            dragCur = null
                                        },
                                    )
                                },
                        ) {
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = null,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize(),
                            )
                            Canvas(modifier = Modifier.fillMaxSize()) {
                                val cw = size.width
                                val ch = size.height
                                // Existing zones (translucent fill + stroke).
                                maskZones.forEach { z ->
                                    drawRect(
                                        color = Color(0x662196F3),
                                        topLeft = Offset(z.x * cw, z.y * ch),
                                        size = Size(z.w * cw, z.h * ch),
                                    )
                                    drawRect(
                                        color = Color(0xFF2196F3),
                                        topLeft = Offset(z.x * cw, z.y * ch),
                                        size = Size(z.w * cw, z.h * ch),
                                        style = Stroke(width = 3.dp.toPx()),
                                    )
                                }
                                // Resize handles (4 corners) — visual affordance
                                // that a zone can be stretched by its edges/corners.
                                val hs = 9.dp.toPx()
                                maskZones.forEach { z ->
                                    val corners = listOf(
                                        Offset(z.x * cw, z.y * ch),
                                        Offset((z.x + z.w) * cw, z.y * ch),
                                        Offset(z.x * cw, (z.y + z.h) * ch),
                                        Offset((z.x + z.w) * cw, (z.y + z.h) * ch),
                                    )
                                    corners.forEach { c ->
                                        drawRect(
                                            color = Color.White,
                                            topLeft = Offset(c.x - hs / 2, c.y - hs / 2),
                                            size = Size(hs, hs),
                                        )
                                        drawRect(
                                            color = Color(0xFF2196F3),
                                            topLeft = Offset(c.x - hs / 2, c.y - hs / 2),
                                            size = Size(hs, hs),
                                            style = Stroke(width = 1.5.dp.toPx()),
                                        )
                                    }
                                }
                                // In-progress drag rectangle (create only — not
                                // shown while moving/resizing an existing zone).
                                if (movingIndex == null && resizeIndex == null) {
                                    val st = dragStart
                                    val cu = dragCur
                                    if (st != null && cu != null) {
                                        val l = minOf(st.x, cu.x)
                                        val r = maxOf(st.x, cu.x)
                                        val t = minOf(st.y, cu.y)
                                        val b = maxOf(st.y, cu.y)
                                        drawRect(
                                            color = Color(0x66FF9800),
                                            topLeft = Offset(l, t),
                                            size = Size(r - l, b - t),
                                        )
                                        drawRect(
                                            color = Color(0xFFFF9800),
                                            topLeft = Offset(l, t),
                                            size = Size(r - l, b - t),
                                            style = Stroke(width = 3.dp.toPx()),
                                        )
                                    }
                                }
                                // Name labels (zone name, or "Zone N" fallback).
                                drawIntoCanvas { canvas ->
                                    maskZones.forEachIndexed { i, z ->
                                        val label = z.name.ifBlank { "Zone ${i + 1}" }
                                        canvas.nativeCanvas.drawText(
                                            label,
                                            z.x * cw + 6f,
                                            z.y * ch + 30f,
                                            labelPaint,
                                        )
                                    }
                                }
                            }
                        }
                        Text(
                            text = stringResource(R.string.mask_drag_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                // Zone list with a delete affordance per row.
                if (maskZones.isEmpty()) {
                    Text(
                        text = stringResource(R.string.mask_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    maskZones.forEachIndexed { index, z ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedTextField(
                                value = z.name,
                                onValueChange = { vm.renameMaskZone(index, it) },
                                placeholder = {
                                    Text(stringResource(R.string.mask_zone_label, index + 1))
                                },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = { vm.removeMaskZone(index) }) {
                                Icon(
                                    imageVector = Icons.Filled.Delete,
                                    contentDescription = null,
                                )
                            }
                        }
                    }
                }

                // « Afficher les zones à l'écran » overlay toggle
                // (draw_mask_zones).
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = stringResource(R.string.mask_draw_overlay_title),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Switch(
                        checked = drawMaskZones,
                        onCheckedChange = vm::setDrawMaskZones,
                    )
                }

                // « Enregistrer » button — PUT {mask_zones, draw_mask_zones}.
                // Disabled while a save is in flight; inline feedback below.
                Button(
                    onClick = vm::saveMaskZones,
                    enabled = maskSave !is SettingsViewModel.MaskSaveState.Saving,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (maskSave is SettingsViewModel.MaskSaveState.Saving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.size(8.dp))
                    }
                    Text(stringResource(R.string.mask_save))
                }
                when (maskSave) {
                    is SettingsViewModel.MaskSaveState.Idle -> { /* no inline status */ }
                    is SettingsViewModel.MaskSaveState.Saving -> {
                        Text(
                            text = stringResource(R.string.mask_saving),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    is SettingsViewModel.MaskSaveState.Saved -> {
                        Text(
                            text = stringResource(R.string.mask_saved),
                            color = Color(0xFF2E7D32),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    is SettingsViewModel.MaskSaveState.Error -> {
                        Text(
                            text = stringResource(R.string.mask_save_error),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }

            // ---- 6. À propos (BL-77) ----
            Section(title = stringResource(R.string.section_about)) {
                // App version row — static, from BuildConfig.VERSION_NAME
                // ("1.0" by default in defaultConfig).
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = stringResource(R.string.about_app_version),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = BuildConfig.VERSION_NAME,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // Companion Jetson version row — live fetch
                // (GET /api/identify). Loaded → version, Loading → spinner +
                // "Récupération…", Error/Idle (offline) → "Hors ligne".
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = stringResource(R.string.about_companion_version),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (companionVersion is SettingsViewModel.CompanionVersionState.Loading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                            )
                            Text(
                                text = stringResource(R.string.about_companion_loading),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else if (companionVersion is SettingsViewModel.CompanionVersionState.Loaded) {
                            Text(
                                text = (companionVersion as SettingsViewModel.CompanionVersionState.Loaded).version,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            // Error / Idle → offline / unavailable.
                            Text(
                                text = stringResource(R.string.about_companion_offline),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                // Small refresh button — retries the live fetch. Disabled
                // while a fetch is in flight.
                OutlinedButton(
                    onClick = vm::refreshCompanionVersion,
                    enabled = companionVersion !is SettingsViewModel.CompanionVersionState.Loading,
                ) {
                    Text(stringResource(R.string.about_refresh))
                }
            }
        }
    }
}