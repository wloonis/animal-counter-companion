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

@file:JvmName("JetsonModels")

package com.animalcounter.net

import org.json.JSONArray
import org.json.JSONObject

/**
 * BL-68 / BL-69 read-only history API data classes + JSON parsers.
 *
 * The Jetson companion (port 8090) exposes five read-only history/count
 * endpoints (frozen by BL-68, PR #72). This file holds the typed Kotlin
 * mirrors of their response shapes and the defensive (`optX`-based) JSON
 * decoders. The decoders are `internal` so the unit tests in
 * `app/src/test/java/com/animalcounter/net/` (same package) can feed mock
 * fixtures straight into them without HTTP.
 *
 * Conventions:
 *  - Every field that the API documents as optional/best-effort (thermal,
 *    video meta, system, per-counter values, `end` for a running session)
 *    is nullable and decodes to `null`/default on a missing key — never
 *    throws. This matters because the Jetson may run an older image whose
 *    JSONL lines lack the newest perf/thermal fields.
 *  - `status` is `"ended"` | `"running"`; the clean/power-loss/unknown
 *    classification lives in the **separate** `end_reason` field (verified
 *    against `tests/companion_history_reader.py`). UI pill color logic must
 *    branch on `end_reason`, not `status`.
 *  - For a **running** session `/api/sessions/<id>` returns `end == null`;
 *    counters/system then fall back to the last `heartbeats[]` entry (handled
 *    by the detail ViewModel/Screen, not here — the parser just preserves
 *    `end == null`).
 */

// ---------------------------------------------------------------------------
// Typed result wrapper (history tabs have no SyncEvent shape)
// ---------------------------------------------------------------------------

/**
 * Outcome of a history-endpoint call. History/count endpoints don't fit the
 * existing [com.animalcounter.data.SyncEvent] shape (they return structured
 * data, not a log line), so they use this sealed result instead.
 */
sealed interface ApiResult<out T> {
    /** HTTP 200 — body parsed into [data]. */
    data class Success<T>(val data: T) : ApiResult<T>
    /** Non-2xx HTTP response (e.g. 404 for an unknown session id). */
    data class HttpError(val code: Int) : ApiResult<Nothing>
    /** Connect/read timeout, DNS, no route, JSON parse failure, etc. */
    data class NetworkError(val message: String) : ApiResult<Nothing>
}

// ---------------------------------------------------------------------------
// /api/count  →  LiveCount
// ---------------------------------------------------------------------------

/**
 * `GET /api/count` → `{count, status, auto_mode, timestamp, session_id}`.
 *
 * Parsed defensively: a missing field degrades to a default (`count=0`,
 * `status="unknown"`, `autoMode=false`) rather than throwing, because the
 * exact shape is not pinned by a fixture in this repo (only by the brief).
 */
data class LiveCount(
    val count: Int,
    val status: String,
    val autoMode: Boolean,
    val timestamp: String?,
    val sessionId: String?,
)

// ---------------------------------------------------------------------------
// /api/sessions  →  SessionPage (list of SessionSummary)
// ---------------------------------------------------------------------------
//
// BL-72: the companion renamed `/api/history` → `/api/sessions` (response
// shape unchanged: `{sessions[], limit, offset, total}`). The Kotlin mirror
// is renamed in lockstep: `HistoryPage` → `SessionPage`, `parseHistory` →
// `parseSessions`.

/** One row of `/api/history` (`session_summaries` in the reader). */
data class SessionSummary(
    val sessionId: String?,
    val startAt: String?,
    val endAt: String?,
    val endReason: String?,
    val status: String,          // "ended" | "running"
    val netCount: Int?,
    val events: Int,
    val heartbeats: Int,
    val lastEventTs: String?,
    val imageTag: String?,
    val videoPath: String?,          // last_segment: the video filename (BL-69 video-info)
    val videoDuration: Double?,     // video.duration seconds (BL-69); null for old sessions
)

/** `/api/sessions` → `{sessions[], limit, offset, total}`. */
data class SessionPage(
    val sessions: List<SessionSummary>,
    val limit: Int,
    val offset: Int,
    val total: Int,
)

// ---------------------------------------------------------------------------
// /api/videos  →  VideoPage (list of VideoRow)
// ---------------------------------------------------------------------------
//
// BL-72: the companion exposes `/api/videos?limit=&offset=` listing every
// compressed clip plus a synthetic index-0 "running" row while a recording
// is in progress. Each row carries the full video facts the UI needs (no
// separate detail endpoint). Field names mirror the deployed BL-71 backend:
//   {video_id, session_id, filename, duration, count_delta, ts, status}
// `video_id` is `counting-{YYYYMMDD-HHMMSS}` (no `#N`); the running row has
// `status:"running"` and `duration:null`.

/** One row of `/api/videos`. */
data class VideoRow(
    val videoId: String?,
    val sessionId: String?,
    val filename: String?,
    val duration: Double?,
    val fileDuration: Double?,   // BL-71: actual compressed+trimmed file length (ffprobe)
    val countDelta: Int?,
    val ts: String?,
    val status: String,          // "ready" | "running" | "unknown" | …
)

/** `/api/videos` → `{videos[], limit, offset, total}`. */
data class VideoPage(
    val videos: List<VideoRow>,
    val limit: Int,
    val offset: Int,
    val total: Int,
)

/** `/api/videos/<id>` → full video detail with per-video counting metadata
 * (directional counts, guard interventions, track_lost, events timeline) +
 * perf/thermal attributed by timespan (BL-71). */
data class VideoDetail(
    val videoId: String?,
    val filename: String?,
    val duration: Double?,
    val fileDuration: Double?,   // BL-71: actual compressed+trimmed file length
    val countDelta: Int?,
    val sessionId: String?,
    val ts: String?,
    val status: String,
    val countLeftToRight: Int,
    val countRightToLeft: Int,
    /** (BL-85) Horizontal-line directional counts (0 for vertical-line
     *  sessions). [countingLineOrientation] tells the UI which pair to show. */
    val countDownToUp: Int,
    val countUpToDown: Int,
    /** (BL-85) Session's counting-line orientation ("vertical" | "horizontal").
     *  Defaults to "vertical" for pre-BL-83 sessions. Drives which directional
     *  pair + labels the UI shows. */
    val countingLineOrientation: String,
    val guardInterventions: JSONObject,   // {event_type: count}
    val trackLost: Int,
    val events: List<CountingEvent>,
    val perf: VideoPerf,
)

data class VideoPerf(
    val thermalAvg: Double?,
    val thermalPeak: Double?,
    val cpuLoadAvg: Double?,
    val memUsedAvg: Double?,
    val diskFreeAvg: Double?,
    val heartbeatCount: Int,
)

// ---------------------------------------------------------------------------
// /api/sessions/<id>  →  SessionDetail (A–G groups)
// ---------------------------------------------------------------------------

/** B — final counters (in `session_end.counters`). */
data class GuardInterventions(
    val lostBufferExpired: Int,
    val mirrorGuard: Int,
    val resurrection: Int,
    val reidRebind: Int,
)

/** B — counting/tracking health (in `session_end.counters`). */
data class Counters(
    val countLeftToRight: Int?,
    val countRightToLeft: Int?,
    val guardInterventions: GuardInterventions,
    val idSwitchRecoveries: Int?,
    val uniqueTrackIds: Int?,
    val maxConcurrentTracks: Int?,
)

/** E — video metadata (in `session_end.video`). Best-effort. */
data class VideoMeta(
    val path: String?,
    val sizeBytes: Long?,
    val duration: Double?,
    val resolution: String?,
    val codec: String?,
    val complete: Boolean?,
)

/** F — system health (in `heartbeat.system` and `session_end.system`). */
data class SystemHealth(
    val diskFree: Double?,              // GB free on /files
    val cpuLoadAvg: List<Double>,       // /proc/loadavg (1/5/15 min)
    val memUsed: Double?,               // GB used (from /proc/meminfo)
)

/**
 * C/F — one heartbeat line. `thermal` is best-effort and its inner shape is
 * not frozen by BL-68, so it is preserved as the raw [JSONObject] for the
 * detail screen to probe with `optInt`/`optDouble` ("N/A" on missing keys).
 */
data class Heartbeat(
    val ts: String?,
    val count: Int?,
    val lastSegment: String?,
    val system: SystemHealth?,
    val thermal: JSONObject?,
)

/** G — one event line from the per-session timeline. */
data class CountingEvent(
    val ts: String?,
    val eventType: String?,
    val detail: JSONObject?,
)

/**
 * D — config snapshot (in `session_start.config`). `notable` is the curated
 * `config_notable` subset (confidence threshold, guard buffer length, …) and
 * `raw` keeps the full config dict for best-effort extraction of any other
 * threshold/guard param the detail screen wants to surface.
 */
data class ConfigSnapshot(
    val imageTag: String?,
    val gitCommit: String?,
    val mode: String?,
    val notable: JSONObject?,
    val raw: JSONObject?,
)

/** A — `session_start` lifecycle + D config. */
data class SessionStart(
    val startAt: String?,
    val startReason: String?,
    val status: String?,
    val prevSessionId: String?,
    val config: ConfigSnapshot?,
)

/**
 * A — `session_end`. `null` for a running session (the detail screen falls
 * back to the last `heartbeats[]` entry for counters/system in that case).
 */
data class SessionEnd(
    val endAt: String?,
    val endReason: String?,     // clean | power-loss | unknown | sigterm | …
    val status: String?,
    val synthetic: Boolean?,
    val counters: Counters?,
    val video: VideoMeta?,
    val system: SystemHealth?,
)

/**
 * `/api/sessions/<id>` detail (A–G). Mirrors the reader's `session_detail`
 * output. `end` is `null` for a running session; `heartbeats` is the raw
 * (ordered) list for the detail screen to aggregate perf/thermal.
 */
data class SessionDetail(
    val sessionId: String,
    val start: SessionStart?,
    val end: SessionEnd?,
    val endAt: String?,          // end.end_at OR last heartbeat ts (reader fills)
    val endReason: String?,
    val status: String,          // "ended" | "running"
    val netCount: Int?,
    val config: ConfigSnapshot?, // reader-level: start.config
    val heartbeats: List<Heartbeat>,
    // BL-71: per-video counting (events, guards, directional) now lives on
    // the VIDEO entity (/api/videos/<id>). The session keeps only global
    // facts + the list of its video_ids.
    val videos: List<String>,
)

// ---------------------------------------------------------------------------
// /api/history/summary?days=N  →  Summary
// ---------------------------------------------------------------------------

/** One day bucket of `/api/history/summary`. */
data class DailyBucket(
    val date: String,            // YYYY-MM-DD
    val sessions: Int,
    val netCount: Int,
    val guardEvents: Int,
    val events: Int,
)

/** `/api/history/summary?days=N` → `{days, daily[{date,sessions,net_count,guard_events,events}]}`. */
data class Summary(
    val days: Int,
    val daily: List<DailyBucket>,
)

// ---------------------------------------------------------------------------
// /api/startups  →  StartupList
// ---------------------------------------------------------------------------

/** One startup line (`type == "startup"`). */
data class Startup(
    val bootAt: String?,
    val imageTag: String?,
    val gitCommit: String?,
    val mode: String?,
    val configNotable: JSONObject?,
)

/** `/api/startups` → `{startups[]}`. */
data class StartupList(
    val startups: List<Startup>,
)

// ---------------------------------------------------------------------------
// BL-76 runtime settings + poweroff (mutable, PATCH-like)
// ---------------------------------------------------------------------------
//
// The Jetson companion (port 8090, SERVICE_VERSION "5") exposes three new
// endpoints consumed by the Android Réglages tab:
//   GET  /api/settings → the merged runtime-settings.json ({} if absent)
//   PUT  /api/settings → PATCH-like merge of the keys present in the body
//                        (only non-null Kotlin fields are written) → the full
//                        merged object echoed back
//   POST /api/power    → writes the .arret_requested sentinel (200 before the
//                        counting app executes the actual poweroff)
//
// `JetsonSettings` mirrors `runtime-settings.json`:
//   {"draw_tracking": bool, "box_tracking": bool,
//    "centroid_tracking": bool, "offset_counting_line": int 0-100}
// Every field is nullable so a PUT body carrying only a subset of the keys
// is modelled faithfully (`null` = "do not modify this key" → not serialized).
// The companion validates types/ranges server-side; the Kotlin side only
// needs to skip `null` fields when serializing and use the defensive `optX`
// helpers when parsing.

/**
 * PATCH-like body for `PUT /api/settings` and the parsed shape of
 * `GET /api/settings`. `null` fields are omitted on serialization (not
 * written) so a PUT only touches the keys the caller wants to change.
 */
data class JetsonSettings(
    val drawTracking: Boolean? = null,
    val boxTracking: Boolean? = null,
    val centroidTracking: Boolean? = null,
    val offsetCountingLine: Int? = null,
    /** (BL-84) Counting-line orientation: "vertical" | "horizontal". `null` =
     *  do not modify (PATCH semantics). Hot-reloaded at the next recording. */
    val countingLineOrientation: String? = null,
    /** (BL-82) Which class ids the countingapp counts; hot-reloaded at each
     *  recording start. `null` = do not modify (PATCH semantics). */
    val countingClassIds: List<Int>? = null,
)

/** `POST /api/power` response → `{"status":"poweroff_requested"}`. */
data class PoweroffResponse(val status: String)

/** (BL-82) One countable species in the deployed model's class catalog. */
data class ClassEntry(val id: Int, val name: String)

/** (BL-82) `GET /api/classes` — the countable species catalog published by the
 *  countingapp (`model-classes.json`) plus the current `counting_class_ids`
 *  selection (resolved the same way the countingapp will at the next recording
 *  start). `classes` is empty only transiently before the countingapp publishes
 *  the catalog; the endpoint itself returns 404 in that case (mapped to
 *  [ApiResult.HttpError](404) by the getter). */
data class ClassCatalog(
    val modelVersion: String?,
    val nc: Int,
    val classes: List<ClassEntry>,
    val defaultCountingClass: Int?,
    val countingClassIds: List<Int>,
)

/**
 * Serialize [JetsonSettings] to a JSON object, omitting `null` fields so a
 * PUT body is a true PATCH (only the present keys are sent). Field names use
 * the snake_case contract of `runtime-settings.json`.
 */
internal fun JetsonSettings.toJson(): JSONObject = JSONObject().also { o ->
    drawTracking?.let { o.put("draw_tracking", it) }
    boxTracking?.let { o.put("box_tracking", it) }
    centroidTracking?.let { o.put("centroid_tracking", it) }
    offsetCountingLine?.let { o.put("offset_counting_line", it) }
    countingLineOrientation?.let { o.put("counting_line_orientation", it) }
    countingClassIds?.let { o.put("counting_class_ids", JSONArray(it)) }
}

/** Parse `GET /api/settings` (or the merged echo of `PUT /api/settings`) into
 *  [JetsonSettings]. Defensive: a missing/invalid key degrades to `null`. */
internal fun parseJetsonSettings(json: String): JetsonSettings {
    val o = JSONObject(json)
    return JetsonSettings(
        drawTracking = o.optBooleanOrNull("draw_tracking"),
        boxTracking = o.optBooleanOrNull("box_tracking"),
        centroidTracking = o.optBooleanOrNull("centroid_tracking"),
        offsetCountingLine = o.optIntOrNull("offset_counting_line"),
        countingLineOrientation = o.optStringOrNull("counting_line_orientation"),
        countingClassIds = o.optIntArrayOrNull("counting_class_ids"),
    )
}

/** Parse `POST /api/power` body into [PoweroffResponse]. Defaults to an empty
 *  status if the key is absent (the endpoint always echoes `status`). */
internal fun parsePoweroffResponse(json: String): PoweroffResponse =
    PoweroffResponse(JSONObject(json).optStringOrNull("status") ?: "")

/** (BL-82) Parse `GET /api/classes` into [ClassCatalog]. Defensive: a missing
 *  `classes` array degrades to an empty list; a missing `counting_class_ids`
 *  degrades to an empty selection (the caller should then show the model
 *  default). Never throws. */
internal fun parseClassCatalog(json: String): ClassCatalog {
    val o = JSONObject(json)
    val arr = o.optJSONArray("classes") ?: JSONArray()
    val classes = ArrayList<ClassEntry>(arr.length())
    for (i in 0 until arr.length()) {
        if (arr.isNull(i)) continue
        val c = arr.optJSONObject(i) ?: continue
        classes.add(ClassEntry(id = c.optInt("id"), name = c.optStringOrNull("name") ?: c.optInt("id").toString()))
    }
    return ClassCatalog(
        modelVersion = o.optStringOrNull("model_version"),
        nc = o.optInt("nc", classes.size),
        classes = classes,
        defaultCountingClass = o.optIntOrNull("default_counting_class"),
        countingClassIds = o.optIntArrayOrNull("counting_class_ids") ?: emptyList(),
    )
}

// ---------------------------------------------------------------------------
// Defensive opt-X helpers (never throw on missing/null keys)
// ---------------------------------------------------------------------------

internal fun JSONObject.optIntOrNull(key: String): Int? =
    if (has(key) && !isNull(key)) optInt(key) else null

internal fun JSONObject.optLongOrNull(key: String): Long? =
    if (has(key) && !isNull(key)) optLong(key) else null

internal fun JSONObject.optDoubleOrNull(key: String): Double? =
    if (has(key) && !isNull(key)) optDouble(key) else null

internal fun JSONObject.optBooleanOrNull(key: String): Boolean? =
    if (has(key) && !isNull(key)) optBoolean(key) else null

/** (BL-82) Read a `counting_class_ids`-style `int[]` key; null when absent or
 *  not an array. Booleans inside are dropped (bool is an int subclass in JSON
 *  clients, but the companion rejects them server-side). */
internal fun JSONObject.optIntArrayOrNull(key: String): List<Int>? {
    if (!has(key) || isNull(key)) return null
    val arr = optJSONArray(key) ?: return null
    val out = ArrayList<Int>(arr.length())
    for (i in 0 until arr.length()) {
        if (arr.isNull(i)) continue
        val v = arr.optInt(i, Int.MIN_VALUE)
        if (v != Int.MIN_VALUE && !arr.optBoolean(i, false)) out.add(v)
    }
    return out
}

/** `optString` that maps missing/`""`/`null` to `null` rather than `""`. */
internal fun JSONObject.optStringOrNull(key: String): String? {
    if (!has(key) || isNull(key)) return null
    val s = optString(key)
    return s.ifBlank { null }
}

internal fun JSONArray.optDoubleOrNull(index: Int): Double? {
    if (index < 0 || index >= length() || isNull(index)) return null
    val d = optDouble(index, Double.NaN)
    return if (d.isNaN()) null else d
}

internal fun JSONArray.optStringOrNull(index: Int): String? {
    if (index < 0 || index >= length() || isNull(index)) return null
    val s = optString(index)
    return s.ifBlank { null }
}

// ---------------------------------------------------------------------------
// Parsers (pure: String → data class). Internal so unit tests can call them.
// ---------------------------------------------------------------------------

/** Parse `GET /api/count` body into [LiveCount]. */
internal fun parseLiveCount(json: String): LiveCount {
    val o = JSONObject(json)
    return LiveCount(
        count = o.optInt("count", 0),
        status = o.optStringOrNull("status") ?: "unknown",
        autoMode = o.optBoolean("auto_mode", false),
        timestamp = o.optStringOrNull("timestamp"),
        sessionId = o.optStringOrNull("session_id"),
    )
}

/** Parse one `sessions[]` element of `/api/history` into [SessionSummary]. */
internal fun parseSessionSummary(o: JSONObject): SessionSummary = SessionSummary(
    sessionId = o.optStringOrNull("session_id"),
    startAt = o.optStringOrNull("start_at"),
    endAt = o.optStringOrNull("end_at"),
    endReason = o.optStringOrNull("end_reason"),
    status = o.optStringOrNull("status") ?: "unknown",
    netCount = o.optIntOrNull("net_count"),
    events = o.optInt("events", 0),
    heartbeats = o.optInt("heartbeats", 0),
    lastEventTs = o.optStringOrNull("last_event_ts"),
    imageTag = o.optStringOrNull("image_tag"),
    videoPath = o.optStringOrNull("last_segment"),
    videoDuration = o.optDoubleOrNull("video_duration"),
)

/** Parse `GET /api/sessions` body into [SessionPage] (shape identical to the old `/api/history`). */
internal fun parseSessions(json: String): SessionPage {
    val o = JSONObject(json)
    val arr = o.optJSONArray("sessions") ?: JSONArray()
    val sessions = (0 until arr.length()).map { parseSessionSummary(arr.getJSONObject(it)) }
    return SessionPage(
        sessions = sessions,
        limit = o.optInt("limit", 0),
        offset = o.optInt("offset", 0),
        total = o.optInt("total", sessions.size),
    )
}

/** Parse one `videos[]` element of `/api/videos` into [VideoRow] (defensive `optX`). */
internal fun parseVideoRow(o: JSONObject): VideoRow = VideoRow(
    videoId = o.optStringOrNull("video_id"),
    sessionId = o.optStringOrNull("session_id"),
    filename = o.optStringOrNull("filename"),
    duration = o.optDoubleOrNull("duration"),
    fileDuration = o.optDoubleOrNull("file_duration"),
    countDelta = o.optIntOrNull("count_delta"),
    ts = o.optStringOrNull("ts"),
    status = o.optStringOrNull("status") ?: "unknown",
)

/** Parse `GET /api/videos?limit=&offset=` body into [VideoPage] (mirrors [parseSessions]). */
internal fun parseVideos(json: String): VideoPage {
    val o = JSONObject(json)
    val arr = o.optJSONArray("videos") ?: JSONArray()
    val videos = (0 until arr.length()).map { parseVideoRow(arr.getJSONObject(it)) }
    return VideoPage(
        videos = videos,
        limit = o.optInt("limit", 0),
        offset = o.optInt("offset", 0),
        total = o.optInt("total", videos.size),
    )
}

/** Parse `GET /api/videos/<id>` body into [VideoDetail] (per-video counting
 * metadata + perf/thermal attributed by timespan — BL-71). */
internal fun parseVideoDetail(json: String): VideoDetail {
    val o = JSONObject(json)
    val evArr = o.optJSONArray("events") ?: JSONArray()
    val events = (0 until evArr.length()).map { parseCountingEvent(evArr.getJSONObject(it)) }
    val perfObj = o.optJSONObject("perf")
    val perf = VideoPerf(
        thermalAvg = perfObj?.optDoubleOrNull("thermal_avg"),
        thermalPeak = perfObj?.optDoubleOrNull("thermal_peak"),
        cpuLoadAvg = perfObj?.optDoubleOrNull("cpu_load_avg"),
        memUsedAvg = perfObj?.optDoubleOrNull("mem_used_avg"),
        diskFreeAvg = perfObj?.optDoubleOrNull("disk_free_avg"),
        heartbeatCount = perfObj?.optInt("heartbeat_count", 0) ?: 0,
    )
    return VideoDetail(
        videoId = o.optStringOrNull("video_id"),
        filename = o.optStringOrNull("filename"),
        duration = o.optDoubleOrNull("duration"),
        fileDuration = o.optDoubleOrNull("file_duration"),
        countDelta = o.optIntOrNull("count_delta"),
        sessionId = o.optStringOrNull("session_id"),
        ts = o.optStringOrNull("ts"),
        status = o.optStringOrNull("status") ?: "unknown",
        countLeftToRight = o.optInt("count_left_to_right", 0),
        countRightToLeft = o.optInt("count_right_to_left", 0),
        countDownToUp = o.optInt("count_down_to_up", 0),
        countUpToDown = o.optInt("count_up_to_down", 0),
        countingLineOrientation = o.optStringOrNull("counting_line_orientation") ?: "vertical",
        guardInterventions = o.optJSONObject("guard_interventions") ?: JSONObject(),
        trackLost = o.optInt("track_lost", 0),
        events = events,
        perf = perf,
    )
}

internal fun parseGuardInterventions(o: JSONObject?): GuardInterventions = GuardInterventions(
    lostBufferExpired = o?.optIntOrNull("lost_buffer_expired") ?: 0,
    mirrorGuard = o?.optIntOrNull("mirror_guard") ?: 0,
    resurrection = o?.optIntOrNull("resurrection") ?: 0,
    reidRebind = o?.optIntOrNull("reid_rebind") ?: 0,
)

/** Parse `session_end.counters` (B) into [Counters]. */
internal fun parseCounters(o: JSONObject): Counters = Counters(
    countLeftToRight = o.optIntOrNull("count_left_to_right"),
    countRightToLeft = o.optIntOrNull("count_right_to_left"),
    guardInterventions = parseGuardInterventions(o.optJSONObject("guard_interventions")),
    idSwitchRecoveries = o.optIntOrNull("id_switch_recoveries"),
    uniqueTrackIds = o.optIntOrNull("unique_track_ids"),
    maxConcurrentTracks = o.optIntOrNull("max_concurrent_tracks"),
)

/** Parse `session_end.video` (E) into [VideoMeta]. Best-effort. */
internal fun parseVideoMeta(o: JSONObject): VideoMeta = VideoMeta(
    path = o.optStringOrNull("path") ?: o.optStringOrNull("filename") ?: o.optStringOrNull("last_segment"),
    sizeBytes = o.optLongOrNull("size") ?: o.optLongOrNull("size_bytes"),
    duration = o.optDoubleOrNull("duration"),
    resolution = o.optStringOrNull("resolution"),
    codec = o.optStringOrNull("codec"),
    complete = o.optBooleanOrNull("complete"),
)

/** Parse `system` (F) into [SystemHealth]. */
internal fun parseSystemHealth(o: JSONObject): SystemHealth {
    val loadArr = o.optJSONArray("cpu_load_avg")
    val load = if (loadArr != null) {
        (0 until loadArr.length()).mapNotNull { loadArr.optDoubleOrNull(it) }
    } else {
        emptyList()
    }
    return SystemHealth(
        diskFree = o.optDoubleOrNull("disk_free"),
        cpuLoadAvg = load,
        memUsed = o.optDoubleOrNull("mem_used"),
    )
}

/** Parse one `heartbeats[]` element (C/F) into [Heartbeat]. */
internal fun parseHeartbeat(o: JSONObject): Heartbeat = Heartbeat(
    ts = o.optStringOrNull("ts"),
    count = o.optIntOrNull("count"),
    // The writer emits `last_segment` (BL-68 doc) but older lines used
    // `last_video`; accept both so a running session still shows a filename.
    lastSegment = o.optStringOrNull("last_segment") ?: o.optStringOrNull("last_video"),
    system = o.optJSONObject("system")?.let { parseSystemHealth(it) },
    thermal = o.optJSONObject("thermal"),
)

/** Parse one `events[]` element (G) into [CountingEvent]. */
internal fun parseCountingEvent(o: JSONObject): CountingEvent = CountingEvent(
    ts = o.optStringOrNull("ts"),
    eventType = o.optStringOrNull("event_type"),
    detail = o.optJSONObject("detail"),
)

/** Parse a config dict (D) into [ConfigSnapshot]. */
internal fun parseConfigSnapshot(o: JSONObject): ConfigSnapshot = ConfigSnapshot(
    imageTag = o.optStringOrNull("image_tag"),
    gitCommit = o.optStringOrNull("git_commit"),
    mode = o.optStringOrNull("mode"),
    notable = o.optJSONObject("config_notable"),
    raw = o,
)

/** Parse `session_start` (A + D) into [SessionStart]. */
internal fun parseSessionStart(o: JSONObject): SessionStart = SessionStart(
    startAt = o.optStringOrNull("start_at"),
    startReason = o.optStringOrNull("start_reason"),
    status = o.optStringOrNull("status"),
    prevSessionId = o.optStringOrNull("prev_session_id"),
    config = o.optJSONObject("config")?.let { parseConfigSnapshot(it) },
)

/** Parse `session_end` (A + B + E + F) into [SessionEnd]. */
internal fun parseSessionEnd(o: JSONObject): SessionEnd = SessionEnd(
    endAt = o.optStringOrNull("end_at"),
    endReason = o.optStringOrNull("end_reason"),
    status = o.optStringOrNull("status"),
    synthetic = o.optBooleanOrNull("synthetic"),
    counters = o.optJSONObject("counters")?.let { parseCounters(it) },
    video = o.optJSONObject("video")?.let { parseVideoMeta(it) },
    system = o.optJSONObject("system")?.let { parseSystemHealth(it) },
)

/**
 * Parse `GET /api/sessions/<id>` body into [SessionDetail].
 * Handles `end == null` (running session) gracefully.
 */
internal fun parseSessionDetail(json: String): SessionDetail {
    val o = JSONObject(json)
    val startObj = o.optJSONObject("start")
    val endObj = o.optJSONObject("end")
    val cfgObj = o.optJSONObject("config")
    val hbArr = o.optJSONArray("heartbeats") ?: JSONArray()
    val vidArr = o.optJSONArray("videos") ?: JSONArray()
    val videos = (0 until vidArr.length()).map { vidArr.getString(it) }
    return SessionDetail(
        sessionId = o.optStringOrNull("session_id") ?: "",
        start = startObj?.let { parseSessionStart(it) },
        end = endObj?.let { parseSessionEnd(it) },
        endAt = o.optStringOrNull("end_at"),
        endReason = o.optStringOrNull("end_reason"),
        status = o.optStringOrNull("status") ?: "unknown",
        netCount = o.optIntOrNull("net_count"),
        config = cfgObj?.let { parseConfigSnapshot(it) },
        heartbeats = (0 until hbArr.length()).map { parseHeartbeat(hbArr.getJSONObject(it)) },
        videos = videos,
    )
}

/** Parse one `daily[]` bucket of `/api/history/summary` into [DailyBucket]. */
internal fun parseDailyBucket(o: JSONObject): DailyBucket = DailyBucket(
    date = o.optStringOrNull("date") ?: "",
    sessions = o.optInt("sessions", 0),
    netCount = o.optInt("net_count", 0),
    guardEvents = o.optInt("guard_events", 0),
    events = o.optInt("events", 0),
)

/** Parse `GET /api/history/summary?days=N` body into [Summary]. */
internal fun parseSummary(json: String): Summary {
    val o = JSONObject(json)
    val arr = o.optJSONArray("daily") ?: JSONArray()
    return Summary(
        days = o.optInt("days", 0),
        daily = (0 until arr.length()).map { parseDailyBucket(arr.getJSONObject(it)) },
    )
}

/** Parse one `startups[]` element into [Startup]. */
internal fun parseStartup(o: JSONObject): Startup = Startup(
    bootAt = o.optStringOrNull("boot_at"),
    imageTag = o.optStringOrNull("image_tag"),
    gitCommit = o.optStringOrNull("git_commit"),
    mode = o.optStringOrNull("mode"),
    configNotable = o.optJSONObject("config_notable"),
)

/** Parse `GET /api/startups` body into [StartupList]. */
internal fun parseStartups(json: String): StartupList {
    val o = JSONObject(json)
    val arr = o.optJSONArray("startups") ?: JSONArray()
    return StartupList(
        startups = (0 until arr.length()).map { parseStartup(arr.getJSONObject(it)) },
    )
}