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

import android.app.Application
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.animalcounter.data.DEFAULT_JETSON_IP
import com.animalcounter.data.SettingsRepository
import com.animalcounter.net.JetsonClient
import com.animalcounter.net.ApiResult
import com.animalcounter.net.VideoRow
import com.animalcounter.net.VideoDetail
import com.animalcounter.net.VideoStreamResult
import com.animalcounter.net.activeWifiNetwork
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream

/**
 * The video facts the detail screen renders, mirrored from the tapped
 * History row's nav args (no re-fetch — the `/api/videos` row carries
 * everything the screen needs).
 */
data class VideoDetailUi(
    val row: VideoRow,
)

/**
 * State of the download/open coroutine driving the detail screen button +
 * progress bar.
 *
 * - [Idle]: nothing has been requested yet (initial state).
 * - [Probing]: the gallery is being queried for an existing copy (short,
 *   local only — no network).
 * - [Downloading]: the clip is streaming from `/api/video/<id>` into
 *   `MediaStore` (`Movies/Films`); [percent] is `0..100` (best-effort from
 *   `Content-Length`, `0` when unknown).
 * - [Done]: the clip is available in the gallery at [uri] — the screen
 *   fires `ACTION_VIEW` on it.
 * - [Error]: the download could not complete (404 = compression in
 *   progress / cleaned up; other HTTP / network / IO failure).
 */
sealed interface DownloadState {
    data object Idle : DownloadState
    data object Probing : DownloadState
    data class Downloading(val percent: Int) : DownloadState
    data class Done(val uri: Uri) : DownloadState
    data class Error(val message: String) : DownloadState
}

/**
 * ViewModel backing the Détail vidéo screen.
 *
 * The [VideoRow] facts come straight from the Navigation Compose back-stack
 * arguments (see [com.animalcounter.ui.nav.AnimalCounterApp] — the
 * `video/{videoId}?...` route passes `videoId`, `filename`, `countDelta`,
 * `duration`, `status`, `sessionId`, `ts` as `StringType` args). No
 * `/api/sessions/<id>` or video-detail fetch is performed — the list row is
 * the source of truth. Numeric fields (`countDelta`, `duration`) are parsed
 * defensively (null/blank → `null`) so a malformed arg never crashes the
 * screen.
 *
 * [downloadState] drives the download/open button + progress bar. The
 * download coroutine is launched on [Dispatchers.IO]:
 *  1. **Probe** `MediaStore.Video` by `DISPLAY_NAME == <filename>` (newest
 *     first) — on hit, emit [DownloadState.Done] with the existing
 *     `contentUri` (short-circuits the network round-trip).
 *  2. **Download** `GET /api/video/<videoId>` bound to the active WiFi
 *     [android.net.Network] (the Jetson HotSpot — same `openBound`/`
 *     activeWifiNetwork` pattern as the rest of `JetsonClient`), stream the
 *     body in 64 KiB chunks into a `MediaStore` insert under
 *     `Movies/Films` (`video/mp4`, `IS_PENDING=1` → `0` on success),
 *     updating [DownloadState.Downloading] from `Content-Length`.
 *  3. **404** → [DownloadState.Error] "video no longer available
 *     (compression in progress or cleaned up)"; other HTTP / network /
 *     IO errors → a short message. A `status == "running"` row is never
 *     downloaded here — the screen disables the button for it.
 */
class VideoDetailViewModel(
    app: Application,
    private val handle: SavedStateHandle,
) : AndroidViewModel(app) {

    private val repo = SettingsRepository(app)

    /** Current Jetson IP (seeded from DataStore; source of truth = Time sync tab). */
    private val _ip = MutableStateFlow(DEFAULT_JETSON_IP)
    val ip: StateFlow<String> = _ip.asStateFlow()

    /** The video facts (parsed once from the nav args). */
    private val _ui = MutableStateFlow(parseUi(handle))
    val ui: StateFlow<VideoDetailUi> = _ui.asStateFlow()

    private val _downloadState = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val downloadState: StateFlow<DownloadState> = _downloadState.asStateFlow()

    /** Per-video counting metadata + perf/thermal (BL-71), fetched from
     *  `/api/videos/<videoId>`. Null until the first fetch completes. */
    private val _detail = MutableStateFlow<VideoDetail?>(null)
    val detail: StateFlow<VideoDetail?> = _detail.asStateFlow()

    /** Fetch the per-video metadata (directional, guards, track_lost, events,
     *  perf/thermal) from `/api/videos/<videoId>`. No-op when there is no
     *  videoId. Best-effort: a failure leaves the previous detail (or null). */
    fun loadDetail() {
        val videoId = _ui.value.row.videoId ?: return
        viewModelScope.launch {
            // Use the resolved active IP (managed by JetsonConnectionManager);
            // the hotspot default may be wrong when the Jetson is on internet
            // WiFi (192.168.0.180), so reading the current value avoids hitting
            // the wrong host before the parallel probe resolves.
            val ip = repo.activeIp.value
            val cm = cm()
            val wifi = if (cm != null) activeWifiNetwork(cm) else null
            try {
                when (val r = JetsonClient.getVideoDetail(
                    ip = ip, videoId = videoId, network = wifi,
                )) {
                    is ApiResult.Success -> _detail.value = r.data
                    is ApiResult.HttpError, is ApiResult.NetworkError -> {
                        // Leave the previous detail (or null); the screen
                        // renders the nav-arg VideoRow facts regardless.
                    }
                }
            } catch (t: Throwable) {
                // best-effort
            }
        }
    }

    init {
        // Track the resolved active Jetson IP (owned by
        // JetsonConnectionManager) so a download routes to the operator's
        // Jetson regardless of hotspot vs LAN.
        viewModelScope.launch {
            repo.activeIp.collect { ip -> _ip.value = ip }
        }
    }

    /**
     * Probe the gallery for an existing copy of [VideoRow.filename]; on hit
     * emit [DownloadState.Done] (no network). Otherwise stream
     * `GET /api/video/<videoId>` into `MediaStore` (`Movies/Films`) with
     * progress updates, then emit [DownloadState.Done] with the new `uri`.
     *
     * No-op when a download/probe is already in flight, or when there is no
     * `videoId`/`filename` to work with. A `status == "running"` row is
     * rejected here too (defensive — the screen also disables the button).
     */
    fun downloadOrOpen(context: Context) {
        val row = _ui.value.row
        if (_downloadState.value is DownloadState.Probing ||
            _downloadState.value is DownloadState.Downloading
        ) return
        val videoId = row.videoId
        val filename = row.filename
        if (videoId.isNullOrBlank() || filename.isNullOrBlank()) {
            _downloadState.value = DownloadState.Error("missing video id or filename")
            return
        }
        if (row.status == "running") {
            _downloadState.value = DownloadState.Error("still recording")
            return
        }
        _downloadState.value = DownloadState.Probing
        viewModelScope.launch {
            try {
                val existing = probeGallery(context, filename)
                if (existing != null) {
                    _downloadState.value = DownloadState.Done(existing)
                    return@launch
                }
                download(context, row)
            } catch (t: Throwable) {
                _downloadState.value = DownloadState.Error(
                    t.message ?: t.javaClass.simpleName,
                )
            }
        }
    }

    /**
     * Probe-only entry point (no download): query `MediaStore.Video` for an
     * existing copy of [VideoRow.filename] and emit [DownloadState.Done]
     * with its `contentUri` on hit, or [DownloadState.Idle] on miss. Used by
     * the detail screen on enter to decide the button label ("Open" when
     * the clip is already in the gallery, "Download" otherwise). No-op when
     * a download/probe is already in flight or the row has no filename.
     */
    fun probe(context: Context) {
        if (_downloadState.value is DownloadState.Probing ||
            _downloadState.value is DownloadState.Downloading
        ) return
        val filename = _ui.value.row.filename
        if (filename.isNullOrBlank()) return
        _downloadState.value = DownloadState.Probing
        viewModelScope.launch {
            try {
                val existing = probeGallery(context, filename)
                _downloadState.value =
                    if (existing != null) DownloadState.Done(existing)
                    else DownloadState.Idle
            } catch (t: Throwable) {
                // A probe failure is non-fatal — fall back to the Download path.
                _downloadState.value = DownloadState.Idle
            }
        }
    }

    /** Reset back to [DownloadState.Idle] (e.g. on screen re-entry). */
    fun reset() {
        _downloadState.value = DownloadState.Idle
    }

    /**
     * Stream `GET /api/video/<videoId>` into a `MediaStore` insert. Binds
     * to the active WiFi network so the request reaches the Jetson HotSpot
     * even with mobile data as the default uplink. Updates
     * [DownloadState.Downloading] from `Content-Length` as bytes flow.
     */
    private suspend fun download(context: Context, row: VideoRow) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val wifi = if (cm != null) activeWifiNetwork(cm) else null
        val result = JetsonClient.openVideoStream(
            ip = _ip.value,
            videoId = row.videoId ?: return,
            network = wifi,
        )
        when (result) {
            is VideoStreamResult.Success -> streamIntoMediaStore(context, row, result)
            is VideoStreamResult.HttpError -> {
                _downloadState.value = if (result.code == 404) {
                    DownloadState.Error(
                        "video no longer available (compression in progress or cleaned up)",
                    )
                } else {
                    DownloadState.Error("HTTP ${result.code}")
                }
            }
            is VideoStreamResult.NetworkError ->
                _downloadState.value = DownloadState.Error(result.message)
        }
    }

    /**
     * Drain [VideoStreamResult.Success.stream] into a freshly inserted
     * `MediaStore.Video` row under `Movies/Films`, copying 64 KiB at a time
     * and emitting progress from `Content-Length`. Owns the connection
     * (calls `disconnect()` + closes both streams in a `finally`), then
     * flips `IS_PENDING` to `0` so the clip is visible in the gallery and
     * emits [DownloadState.Done] with the new `uri`.
     */
    private suspend fun streamIntoMediaStore(
        context: Context,
        row: VideoRow,
        result: VideoStreamResult.Success,
    ) = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val filename = row.filename ?: return@withContext
        val total = result.contentLength

        // Insert a pending row under Movies/Films.
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, filename)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Films")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            @Suppress("DEPRECATION")
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }
        val uri = resolver.insert(collection, values)
            ?: run {
                _downloadState.value = DownloadState.Error("could not create gallery entry")
                result.connection.disconnect()
                return@withContext
            }

        var input: InputStream? = null
        var output: OutputStream? = null
        var written = 0L
        try {
            input = result.stream
            output = resolver.openOutputStream(uri, "w")
                ?: throw java.io.IOException("could not open output stream")
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                output.write(buf, 0, n)
                written += n
                if (total > 0) {
                    val pct = ((written * 100) / total).toInt().coerceIn(0, 100)
                    _downloadState.value = DownloadState.Downloading(pct)
                } else {
                    // Unknown length — show indeterminate-but-active state.
                    _downloadState.value = DownloadState.Downloading(0)
                }
            }
            output.flush()
            // Publish the row (clear IS_PENDING).
            val done = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
            _downloadState.value = DownloadState.Done(uri)
        } catch (t: Throwable) {
            // Best-effort cleanup of the half-written pending row.
            runCatching { resolver.delete(uri, null, null) }
            throw t
        } finally {
            runCatching { output?.close() }
            runCatching { input?.close() }
            result.connection.disconnect()
        }
    }

    /**
     * Query `MediaStore.Video` for the newest row whose `DISPLAY_NAME`
     * matches [filename]; returns its `contentUri` or `null` on miss. Local
     * only (no network) — a hit short-circuits the download round-trip.
     */
    private suspend fun probeGallery(context: Context, filename: String): Uri? =
        withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            val projection = arrayOf(MediaStore.Video.Media._ID)
            val selection = "${MediaStore.Video.Media.DISPLAY_NAME} = ?"
            val args = arrayOf(filename)
            val sort = "${MediaStore.Video.Media.DATE_ADDED} DESC"
            resolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                args,
                sort,
            )?.use { c ->
                if (c.moveToFirst()) {
                    val id = c.getLong(0)
                    ContentUris.withAppendedId(
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id,
                    )
                } else null
            }
        }

    /** Resolve the active WiFi network (null when not on the Jetson HotSpot). */
    @Suppress("unused") // kept for symmetry with the sibling SessionDetailViewModel
    private fun cm(): ConnectivityManager? = getApplication<Application>()
        .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    companion object {
        /** [SavedStateHandle] keys holding the `video/{videoId}?...` nav args. */
        const val KEY_VIDEO_ID = "videoId"
        const val KEY_FILENAME = "filename"
        const val KEY_COUNT_DELTA = "countDelta"
        const val KEY_DURATION = "duration"
        const val KEY_FILE_DURATION = "fileDuration"
        const val KEY_STATUS = "status"
        const val KEY_SESSION_ID = "sessionId"
        const val KEY_TS = "ts"

        /**
         * Parse the nav-arg strings (defensively) into a [VideoDetailUi].
         * Numeric fields map null/blank → `null`; missing `status` defaults
         * to `"unknown"` so a malformed arg never crashes the screen.
         */
        internal fun parseUi(handle: SavedStateHandle): VideoDetailUi {
            val row = VideoRow(
                videoId = handle.get<String>(KEY_VIDEO_ID)?.takeIf { it.isNotBlank() },
                sessionId = handle.get<String>(KEY_SESSION_ID)?.takeIf { it.isNotBlank() },
                filename = handle.get<String>(KEY_FILENAME)?.takeIf { it.isNotBlank() },
                duration = handle.get<String>(KEY_DURATION)?.toNullableDouble(),
                fileDuration = handle.get<String>(KEY_FILE_DURATION)?.toNullableDouble(),
                countDelta = handle.get<String>(KEY_COUNT_DELTA)?.toNullableInt(),
                ts = handle.get<String>(KEY_TS)?.takeIf { it.isNotBlank() },
                status = handle.get<String>(KEY_STATUS)?.takeIf { it.isNotBlank() } ?: "unknown",
            )
            return VideoDetailUi(row = row)
        }

        private fun String.toNullableInt(): Int? =
            trim().takeIf { it.isNotBlank() }?.toIntOrNull()

        private fun String.toNullableDouble(): Double? =
            trim().takeIf { it.isNotBlank() }?.toDoubleOrNull()
    }
}