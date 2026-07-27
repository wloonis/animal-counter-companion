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