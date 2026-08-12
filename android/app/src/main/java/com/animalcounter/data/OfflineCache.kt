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
import java.io.File
import java.time.Instant

/**
 * Simple, stdlib-only, file-based read-only cache for offline consultation of
 * the Jetson companion responses (history, dashboard, startups).
 *
 * Stores the raw JSON body + a saved-at timestamp in the app's internal
 * files dir (`filesDir/bl69-cache/`). The ViewModels save on every successful
 * online fetch and fall back to the cache when the Jetson is unreachable, so
 * the user can consult the last-known data with no connection to the Jetson
 * (e.g. at home, away from the HotSpot).
 *
 * No eviction/size cap: the payloads are small (a few KB per tab) and the
 * cache is overwritten on each successful fetch, so it stays bounded by
 * nature. A [Context.getFilesDir] file is private to the app and cleared on
 * uninstall.
 */
object OfflineCache {

    private const val DIR_NAME = "bl69-cache"

    private fun dir(ctx: Context): File =
        File(ctx.filesDir, DIR_NAME).apply { mkdirs() }

    /** Persist [json] under [key] with the current instant as the saved-at
     *  timestamp. Best-effort: swallows IO errors (cache is best-effort). */
    fun save(ctx: Context, key: String, json: String) {
        runCatching {
            File(dir(ctx), "$key.json").writeText(json)
            File(dir(ctx), "$key.meta").writeText(Instant.now().toString())
        }
    }

    /** A cached entry: the raw JSON + the instant it was saved (null if the
     *  meta file is missing/unparseable). Null if no cache exists for [key]. */
    data class Cached(val json: String, val savedAt: Instant?)

    /** Load the cached entry for [key], or null if none exists (or IO fails). */
    fun load(ctx: Context, key: String): Cached? =
        runCatching {
            val f = File(dir(ctx), "$key.json")
            if (!f.exists()) null
            else Cached(
                json = f.readText(),
                savedAt = File(dir(ctx), "$key.meta")
                    .takeIf { it.exists() }
                    ?.readText()
                    ?.let { runCatching { Instant.parse(it) }.getOrNull() },
            )
        }.getOrNull()
}