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

package com.animalcounter.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map

/**
 * Default Jetson companion IP (the Jetson HotSpot gateway address).
 *
 * Two roles:
 *  - The default value of the manual-override IP (`jetson_ip`), so a user
 *    who clears the manual field still has a usable address.
 *  - The default value of the auto-select hotspot candidate
 *    (`jetson_ip_hotspot`), and thus the initial value of [activeIp].
 */
const val DEFAULT_HOTSPOT_IP: String = "192.168.100.1"

/** Default LAN candidate IP (Jetson when joined to the home/work WiFi). */
const val DEFAULT_LAN_IP: String = "192.168.0.180"

/**
 * Legacy alias kept for any code that still references the "Jetson IP" name.
 * Same value as [DEFAULT_HOTSPOT_IP] (the hotspot default).
 */
const val DEFAULT_JETSON_IP: String = DEFAULT_HOTSPOT_IP

/** Default value of the "Track in recordings" master toggle. */
const val DEFAULT_DRAW_TRACKING: Boolean = false

/** Default value of the "Boxes" sub-toggle. */
const val DEFAULT_BOX_TRACKING: Boolean = true

/** Default value of the "Trails" sub-toggle. */
const val DEFAULT_CENTROID_TRACKING: Boolean = true

/** Default value of the counting-line offset (BL-84: SIGNED, centered at 0).
 *  Range -50..50 (practical UI range; the companion accepts -300..300 and
 *  the counting app clamps the computed line position inside the image with a
 *  200px margin at use-time). Was 10 (unsigned 0-100) before BL-84. */
const val DEFAULT_OFFSET_COUNTING_LINE: Int = 0

/** Default counting-line orientation (BL-84): "vertical" (a vertical line at
 *  x = W/2 + W*off/100) | "horizontal" (a horizontal line at
 *  y = H/2 + H*off/100). Mirrors the counting app default. */
const val DEFAULT_COUNTING_LINE_ORIENTATION: String = "vertical"

/** Practical UI range for the signed offset slider. The companion accepts
 *  the loose -300..300 sanity range; we cap the slider at +/-50 for usable
 *  fine control (the counting app clamps to image bounds anyway). */
const val OFFSET_SLIDER_MIN: Int = -50
const val OFFSET_SLIDER_MAX: Int = 50

/** Process-wide [DataStore] delegate (single instance per [Context]). */
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
    name = "animal_counter_settings",
)

/**
 * Persistence layer for the few user-configurable settings, backed by
 * Jetpack DataStore Preferences (coroutine-friendly, lifecycle-safe).
 *
 * Stores:
 *  - `jetson_ip_hotspot` / `jetson_ip_lan`: the two candidate IPs the
 *    auto-select probe polls (defaults [DEFAULT_HOTSPOT_IP] /
 *    [DEFAULT_LAN_IP]).
 *  - `auto_select`: whether auto-select is on (default `true`).
 *  - `jetson_ip`: the manual-override IP used when auto-select is off
 *    (default [DEFAULT_JETSON_IP]).
 *  - `draw_tracking` / `box_tracking` / `centroid_tracking` /
 *    `offset_counting_line`: the offline cache of the runtime
 *    recording/tracking settings (defaults `false` / `true` / `true` /
 *    `10`). The last known value pushed to the Jetson
 *    (see [JetsonClient][com.animalcounter.net.JetsonClient]
 *    `putSettings`) is kept here so the UI can restore its state while
 *    offline.
 *
 * Also owns the resolved `activeIp`: the single IP the rest of the app
 * (ViewModels) should talk to. [JetsonConnectionManager] is the only
 * writer of [activeIp] via [setActiveIp]; everyone else reads
 * [activeIp].
 */
class SettingsRepository(private val context: Context) {

    private val jetsonIpKey = stringPreferencesKey(JETSON_IP_KEY)
    private val hotspotIpKey = stringPreferencesKey(HOTSPOT_IP_KEY)
    private val lanIpKey = stringPreferencesKey(LAN_IP_KEY)
    private val autoSelectKey = booleanPreferencesKey(AUTO_SELECT_KEY)
    private val drawTrackingKey = booleanPreferencesKey(DRAW_TRACKING_KEY)
    private val boxTrackingKey = booleanPreferencesKey(BOX_TRACKING_KEY)
    private val centroidTrackingKey =
        booleanPreferencesKey(CENTROID_TRACKING_KEY)
    private val offsetCountingLineKey =
        intPreferencesKey(OFFSET_COUNTING_LINE_KEY)
    private val countingLineOrientationKey =
        stringPreferencesKey(COUNTING_LINE_ORIENTATION_KEY)

    /**
     * The manual-override Jetson IP. Emits [DEFAULT_JETSON_IP] when no
     * value has been written yet.
     */
    val jetsonIp: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[jetsonIpKey] ?: DEFAULT_JETSON_IP
    }

    /**
     * The hotspot candidate IP. Emits [DEFAULT_HOTSPOT_IP] when unset.
     */
    val hotspotIp: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[hotspotIpKey] ?: DEFAULT_HOTSPOT_IP
    }

    /**
     * The LAN candidate IP. Emits [DEFAULT_LAN_IP] when unset.
     */
    val lanIp: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[lanIpKey] ?: DEFAULT_LAN_IP
    }

    /**
     * Whether auto-select is enabled. Emits `true` when unset.
     */
    val autoSelect: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[autoSelectKey] ?: true
    }

    /**
     * Offline cache of the "Track in recordings" master toggle
     * (`draw_tracking`). Emits `false` when unset. This is the last
     * value pushed to the Jetson; the on-device runtime-settings.json is
     * the source of truth at recording start.
     */
    val drawTracking: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[drawTrackingKey] ?: DEFAULT_DRAW_TRACKING
    }

    /**
     * Offline cache of the "Boxes" sub-toggle (`box_tracking`). Emits
     * `true` when unset.
     */
    val boxTracking: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[boxTrackingKey] ?: DEFAULT_BOX_TRACKING
    }

    /**
     * Offline cache of the "Trails" sub-toggle
     * (`centroid_tracking`). Emits `true` when unset.
     */
    val centroidTracking: Flow<Boolean> =
        context.dataStore.data.map { prefs ->
            prefs[centroidTrackingKey] ?: DEFAULT_CENTROID_TRACKING
        }

    /**
     * Offline cache of the counting-line offset
     * (`offset_counting_line`, BL-84: SIGNED, centered at 0; practical UI
     * range -50..50). Emits [DEFAULT_OFFSET_COUNTING_LINE] when unset.
     * Changing this value affects the counting line position and therefore
     * the count; the UI warns the user accordingly.
     */
    val offsetCountingLine: Flow<Int> =
        context.dataStore.data.map { prefs ->
            prefs[offsetCountingLineKey] ?: DEFAULT_OFFSET_COUNTING_LINE
        }

    /**
     * Offline cache of the counting-line orientation
     * (`counting_line_orientation`, BL-84): "vertical" | "horizontal".
     * Emits [DEFAULT_COUNTING_LINE_ORIENTATION] when unset.
     */
    val countingLineOrientation: Flow<String> =
        context.dataStore.data.map { prefs ->
            prefs[countingLineOrientationKey] ?: DEFAULT_COUNTING_LINE_ORIENTATION
        }

    /**
     * The resolved active Jetson IP the app should talk to. Defaults to
     * the hotspot default ([DEFAULT_HOTSPOT_IP]) until
     * [JetsonConnectionManager][com.animalcounter.net.JetsonConnectionManager]
     * resolves a reachable IP shortly after the app opens.
     *
     * **Process-shared** (held in the [companion object][Shared]):
     * [SettingsRepository] is instantiated separately by
     * [JetsonConnectionManager] (the sole writer, via [setActiveIp]) AND by
     * every screen ViewModel (readers). If `activeIp` were a per-instance
     * field, the manager would resolve the LAN IP (e.g. `192.168.0.180`)
     * into ITS instance while each ViewModel's instance stayed stuck at the
     * hotspot default (`192.168.100.1`) — on the home WiFi that default is
     * unreachable, so every `/api/...` data fetch failed and the screens fell
     * back to the offline cache even though the reachability banner said
     * « Jetson connecté » (BL-74 follow-up). Sharing the StateFlow across
     * instances via the companion makes the manager's resolution visible to
     * every ViewModel immediately.
     */
    val activeIp: StateFlow<String> = sharedActiveIp.asStateFlow()

    /**
     * Persist [ip] as the manual-override Jetson IP. Empty/blank values
     * are coerced to [DEFAULT_JETSON_IP] so the store never holds an
     * unusable address.
     */
    suspend fun setJetsonIp(ip: String) {
        val normalized = ip.trim().ifBlank { DEFAULT_JETSON_IP }
        context.dataStore.edit { prefs ->
            prefs[jetsonIpKey] = normalized
        }
    }

    /**
     * Persist [ip] as the hotspot candidate IP. Blank coerced to
     * [DEFAULT_HOTSPOT_IP].
     */
    suspend fun setHotspotIp(ip: String) {
        val normalized = ip.trim().ifBlank { DEFAULT_HOTSPOT_IP }
        context.dataStore.edit { prefs ->
            prefs[hotspotIpKey] = normalized
        }
    }

    /**
     * Persist [ip] as the LAN candidate IP. Blank coerced to
     * [DEFAULT_LAN_IP].
     */
    suspend fun setLanIp(ip: String) {
        val normalized = ip.trim().ifBlank { DEFAULT_LAN_IP }
        context.dataStore.edit { prefs ->
            prefs[lanIpKey] = normalized
        }
    }

    /**
     * Persist [value] as the auto-select flag.
     */
    suspend fun setAutoSelect(value: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[autoSelectKey] = value
        }
    }

    /**
     * Persist [value] as the master "Track in recordings" toggle
     * (`draw_tracking`). [value] is the new offline-cached value (the
     * caller is responsible for pushing it to the Jetson via
     * `putSettings`).
     */
    suspend fun setDrawTracking(value: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[drawTrackingKey] = value
        }
    }

    /**
     * Persist [value] as the "Boxes" sub-toggle (`box_tracking`).
     */
    suspend fun setBoxTracking(value: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[boxTrackingKey] = value
        }
    }

    /**
     * Persist [value] as the "Trails" sub-toggle
     * (`centroid_tracking`).
     */
    suspend fun setCentroidTracking(value: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[centroidTrackingKey] = value
        }
    }

    /**
     * Persist [value] as the counting-line offset
     * (`offset_counting_line`, BL-84: SIGNED). [value] is clamped to the
     * practical UI range [OFFSET_SLIDER_MIN, OFFSET_SLIDER_MAX] before being
     * stored (the companion accepts -300..300; the counting app clamps the
     * computed line position inside the image at use-time).
     */
    suspend fun setOffsetCountingLine(value: Int) {
        val clamped = value.coerceIn(OFFSET_SLIDER_MIN, OFFSET_SLIDER_MAX)
        context.dataStore.edit { prefs ->
            prefs[offsetCountingLineKey] = clamped
        }
    }

    /**
     * Persist [value] as the counting-line orientation
     * (`counting_line_orientation`, BL-84). [value] is coerced to the
     * canonical "vertical" | "horizontal" (defaults to "vertical" on any
     * other string, mirroring the counting app's resolve fallback).
     */
    suspend fun setCountingLineOrientation(value: String) {
        val canonical = if (value == "horizontal") "horizontal" else "vertical"
        context.dataStore.edit { prefs ->
            prefs[countingLineOrientationKey] = canonical
        }
    }

    /**
     * Update the resolved active IP. Intended to be called only by
     * [JetsonConnectionManager][com.animalcounter.net.JetsonConnectionManager];
     * ViewModels read [activeIp].
     */
    suspend fun setActiveIp(ip: String) {
        sharedActiveIp.value = ip
    }

    private companion object {
        /** Process-wide holder for the resolved active Jetson IP (BL-74 fix). */
        private val sharedActiveIp = MutableStateFlow(DEFAULT_HOTSPOT_IP)
        const val JETSON_IP_KEY = "jetson_ip"
        const val HOTSPOT_IP_KEY = "jetson_ip_hotspot"
        const val LAN_IP_KEY = "jetson_ip_lan"
        const val AUTO_SELECT_KEY = "auto_select"
        const val DRAW_TRACKING_KEY = "draw_tracking"
        const val BOX_TRACKING_KEY = "box_tracking"
        const val CENTROID_TRACKING_KEY = "centroid_tracking"
        const val OFFSET_COUNTING_LINE_KEY = "offset_counting_line"
        const val COUNTING_LINE_ORIENTATION_KEY = "counting_line_orientation"
    }
}