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

package com.animalcounter.net

import com.animalcounter.data.SyncEvent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.io.InputStream
import java.net.URLEncoder
import java.net.URL

/**
 * Companion-side HTTP contract (BL-64 `companion.py`):
 *  - `GET  /api/identify` → `{"service":"jetson-companion","version":"<v>"}`
 *  - `POST /api/time`     → body `{"time":"<ISO8601>","tz":"<IANA>"}` → 200 on success,
 *    400 on a malformed/unparseable time/tz, 5xx if the Jetson cannot apply it.
 *
 * All requests target `http://<ip>:8090/...` (cleartext; the Jetson HotSpot is an
 * isolated network — see `AndroidManifest.xml` `usesCleartextTraffic`).
 */
/**
 * Find a network that carries WIFI transport (and is NOT a VPN), or null.
 *
 * Why this matters: when the phone has mobile data (5G) AND is joined to the
 * Jetson HotSpot WiFi (which has no internet), Android's *active/default*
 * network is the mobile one (it has internet), so [ConnectivityManager.activeNetwork]
 * returns the carrier network — routing `http://192.168.100.1:8090/...` over 5G
 * where it never reaches the Jetson. We must therefore NOT rely on the active
 * network: scan ALL networks and pick the one with WIFI transport, then bind
 * the [HttpURLConnection] to it via [Network.openConnection] so the request
 * goes onto the HotSpot regardless of mobile data being the default uplink.
 * Callers that already have a [Network] from a
 * [ConnectivityManager.NetworkCallback] (the foreground service) pass it
 * directly; foreground/UI callers use this helper.
 *
 * Why VPN networks are EXCLUDED (BL-81): a VPN that rides on WiFi (e.g.
 * Tailscale, always-on VPN, per-app VPN) exposes `TRANSPORT_WIFI` *inherited*
 * from its underlying network, in addition to `TRANSPORT_VPN`. There are two
 * distinct failure modes when a VPN is active, both fixed by returning null
 * (i.e. using the system default network, unbound) whenever ANY VPN network
 * is present:
 *
 *  1. Selection: without the VPN exclusion this helper could return the VPN
 *     network first (the order of [ConnectivityManager.allNetworks] is
 *     unspecified); binding the probe to it routes `http://192.168.0.180:8090/...`
 *     through the VPN tunnel (tun0), which only routes the VPN's own subnets
 *     (e.g. Tailscale's 100.x / fd7a:), NOT the Jetson LAN `192.168.0.0/24` —
 *     the probe is silently dropped.
 *  2. EPERM bind: a *non-bypassable* VPN (Tailscale `bypassable=false`)
 *     forbids the app from pinning a socket to a non-VPN network, so
 *     `Network.openConnection` then raises `SocketException: Binding socket to
 *     network N failed: EPERM (Operation not permitted)` — the probe never
 *     connects even when the right WiFi network is selected.
 *
 * Returning null when a VPN is present makes [openBound] use the default
 * network; a split-tunnel VPN (Tailscale's default) lets the LAN traffic fall
 * through to the VPN's underlying WiFi, which is exactly what reaches the
 * Jetson (same path the shell uses). When NO VPN is active we still bind to the
 * (non-VPN) WiFi so the probe routes over the Jetson HotSpot even when mobile
 * data is the default internet uplink. The per-network `!VPN` guard is kept as
 * defense-in-depth. Fallback to null (no WIFI network at all) also uses the
 * default network, correct when the WiFi is the default uplink.
 */
fun activeWifiNetwork(cm: ConnectivityManager): Network? {
    @Suppress("DEPRECATION")
    val all = cm.allNetworks
    // BL-81: when a VPN is active, do NOT bind the request to a specific
    // network. A non-bypassable VPN (e.g. Tailscale with bypassable=false)
    // makes Network.openConnection bind the socket with EPERM ("Binding socket
    // to network N failed: EPERM"), so any pinned, non-VPN network is
    // unreachable from the app. Returning null makes [openBound] use the
    // system default network instead — and a split-tunnel VPN (Tailscale's
    // default: it only routes its own 100.x/fd7a: subnets) lets LAN traffic
    // (e.g. the Jetson at 192.168.0.180) fall through to the VPN's underlying
    // WiFi, which is exactly what reaches the Jetson. When NO VPN is active we
    // still bind to the (non-VPN) WiFi so the probe routes over the Jetson
    // HotSpot even when mobile data is the default internet uplink.
    val hasVpn = all.any { nm ->
        cm.getNetworkCapabilities(nm)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
    }
    if (hasVpn) return null
    for (network in all) {
        val caps = cm.getNetworkCapabilities(network) ?: continue
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            && !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
            return network
        }
    }
    return null
}

/**
 * Current instant formatted as an ISO-8601 LOCAL offset datetime truncated to
 * microseconds, e.g. `2025-07-15T16:30:00.123456+02:00`.
 *
 * Why not `Instant.now().toString()` (UTC `...Z` with nanoseconds):
 *  1. The Jetson companion parses with Python `datetime.fromisoformat`, which
 *     on Python 3.10 (Jetson JetPack) rejects the `Z` suffix and >6 fractional
 *     digits → HTTP 400 "invalid ISO8601 time".
 *  2. The companion strips the offset and uses the wall-clock value as the
 *     LOCAL time (it sets the timezone separately via `set-timezone`), so a
 *     UTC instant would set the clock off by the UTC offset.
 * A local [java.time.OffsetDateTime] truncated to microseconds is accepted by
 * `fromisoformat` and carries the correct local wall time.
 */
fun nowIsoForCompanion(): String {
    val odt = java.time.OffsetDateTime.now(java.time.ZoneId.systemDefault())
        .truncatedTo(java.time.temporal.ChronoUnit.MICROS)
    return odt.format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME)
}

/**
 * Strict validation of a `GET /api/identify` response body (BL-73).
 *
 * Returns `true` only when [body] parses as JSON and its top-level `service`
 * field equals `"jetson-companion"` exactly. **No version check** is performed
 * (deliberately dropped — the companion's `version` string is informational).
 * Anything else (non-JSON, missing `service`, a wrong `service` value) is
 * rejected so a stale/foreign HTTP 200 response is never mistaken for the
 * Jetson companion. Extracted as a pure top-level `internal` function so the
 * unit tests can exercise it without HTTP.
 */
internal fun isValidIdentifyBody(body: String): Boolean = runCatching {
    JSONObject(body).optString("service") == "jetson-companion"
}.getOrDefault(false)

/**
 * Extract the companion `version` string from a `GET /api/identify` body
 * (BL-77 About card). The body MUST already pass [isValidIdentifyBody] (a
 * strict `service == "jetson-companion"` check); this then reads the
 * `version` field. Throws [IllegalArgumentException] on an invalid body so
 * the shared [JetsonClient.getJson] parse lambda maps it to
 * [ApiResult.NetworkError] (consistent with the other typed getters). The
 * `version` field may legitimately be empty (`optString` returns `""` for a
 * missing key); the About UI treats a blank as offline. Extracted as a pure
 * top-level `internal` function so the unit tests can exercise it without HTTP.
 */
internal fun parseIdentifyVersion(body: String): String {
    require(isValidIdentifyBody(body)) {
        "Not a valid Jetson identify body"
    }
    return JSONObject(body).optString("version")
}

private const val JETSON_PORT = 8090

/** Read/connect timeout for the companion probe/push. */
private const val CONNECT_TIMEOUT_MS = 5_000

/** Read timeout for the companion probe/push. */
private const val READ_TIMEOUT_MS = 5_000

/**
 * Minimal HTTP client for the Jetson companion, built on stdlib
 * [HttpURLConnection] only (no OkHttp) so the build stays self-contained
 * for the offline field workflow.
 *
 * Both entry points run on [Dispatchers.IO] and map the raw HTTP result
 * onto [SyncEvent] so the caller can feed it straight into the shared
 * [com.animalcounter.data.SyncLog]. Failures never throw — they surface
 * as [SyncEvent.Outcome.Network] events.
 */
object JetsonClient {

    /**
     * Reachability probe — `GET /api/identify`.
     *
     * @return a [SyncEvent] typed [SyncEvent.Type.Probe] capturing the
     *   companion's `service`/`version` on success, or a failure outcome.
     */
    suspend fun identify(ip: String, network: Network? = null): SyncEvent = withContext(Dispatchers.IO) {
        val now = java.time.Instant.now()
        try {
            val url = URL("http://${sanitizeIp(ip)}:$JETSON_PORT/api/identify")
            val conn = (openBound(url, network) as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = false
                useCaches = false
            }
            try {
                val code = conn.responseCode
                val body = conn.readBody(code)
                if (code == 200 && isValidIdentifyBody(body)) {
                    // Strict identify success: HTTP 200 AND a body whose
                    // `service` field is exactly `"jetson-companion"`
                    // (validated by [isValidIdentifyBody]). Surface the
                    // `service`/`version` pair as the detail line.
                    val parsed = runCatching {
                        val json = JSONObject(body)
                        "${json.optString("service")} ${json.optString("version")}".trim()
                    }.getOrNull()
                    val detail = parsed?.ifBlank { body } ?: body
                    SyncEvent(now, SyncEvent.Type.Probe, SyncEvent.Outcome.Success, detail)
                } else if (code == 200) {
                    // HTTP 200 but the body is NOT a valid Jetson identify
                    // response (wrong/missing `service`, or non-JSON). Treat
                    // it as a reachability failure and surface the raw body so
                    // the operator can see what answered instead of the Jetson.
                    SyncEvent(
                        now, SyncEvent.Type.Probe, SyncEvent.Outcome.Network,
                        body,
                    )
                } else {
                    SyncEvent(
                        now, SyncEvent.Type.Probe,
                        outcomeFor(code),
                        "HTTP $code: $body",
                    )
                }
            } finally {
                conn.disconnect()
            }
        } catch (t: Throwable) {
            SyncEvent(
                now, SyncEvent.Type.Probe, SyncEvent.Outcome.Network,
                t.message ?: t.javaClass.simpleName,
            )
        }
    }

    /**
     * Clock push — `POST /api/time`.
     *
     * @param timeIso an ISO-8601 instant, e.g. `Instant.now().toString()`.
     * @param tz an IANA zone id, e.g. `ZoneId.systemDefault().id`.
     * @return a [SyncEvent] typed [SyncEvent.Type.Sync] capturing the
     *   companion's response on success, or a failure outcome.
     */
    suspend fun postTime(
        ip: String,
        timeIso: String,
        tz: String,
        network: Network? = null,
    ): SyncEvent = withContext(Dispatchers.IO) {
        val now = java.time.Instant.now()
        try {
            val url = URL("http://${sanitizeIp(ip)}:$JETSON_PORT/api/time")
            val payload = JSONObject()
                .put("time", timeIso)
                .put("tz", tz)
                .toString()
            val conn = (openBound(url, network) as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                doOutput = true
                instanceFollowRedirects = false
                useCaches = false
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
            }
            try {
                conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                val body = conn.readBody(code)
                if (code == 200) {
                    SyncEvent(
                        now, SyncEvent.Type.Sync, SyncEvent.Outcome.Success,
                        body.ifBlank { "200 OK" },
                    )
                } else {
                    SyncEvent(
                        now, SyncEvent.Type.Sync,
                        outcomeFor(code),
                        "HTTP $code: $body",
                    )
                }
            } finally {
                conn.disconnect()
            }
        } catch (t: Throwable) {
            SyncEvent(
                now, SyncEvent.Type.Sync, SyncEvent.Outcome.Network,
                t.message ?: t.javaClass.simpleName,
            )
        }
    }

    /** Open a connection bound to [network] when non-null (routes over the WiFi
     *  HotSpot even when mobile data is the default internet uplink), else the
     *  default network. */
    private fun openBound(url: URL, network: Network?): java.net.URLConnection =
        network?.openConnection(url) ?: url.openConnection()

    /** Map an HTTP status code to the matching [SyncEvent.Outcome]. */
    private fun outcomeFor(code: Int): SyncEvent.Outcome = when (code) {
        in 200..299 -> SyncEvent.Outcome.Success
        400 -> SyncEvent.Outcome.BadRequest
        in 500..599 -> SyncEvent.Outcome.ServerError
        else -> SyncEvent.Outcome.Network
    }

    /** Read the response body, draining the error stream when code >= 400. */
    private fun HttpURLConnection.readBody(code: Int): String {
        val stream = if (code in 200..299) inputStream else errorStream ?: inputStream
        return runCatching { stream?.bufferedReader()?.use { it.readText() } }.getOrNull()
            ?.trim()
            .orEmpty()
    }

    /** Strip any scheme/port the user may have pasted; keep a bare host. */
    private fun sanitizeIp(ip: String): String =
        ip.trim()
            .removePrefix("http://")
            .removePrefix("https://")
            .substringBefore(':')
            .ifBlank { "192.168.100.1" }

    // -----------------------------------------------------------------------
    // BL-68 / BL-69 read-only history + count endpoints
    // -----------------------------------------------------------------------
    //
    // The five methods below all reuse the transport pattern of
    // [identify]/[postTime] (stdlib [HttpURLConnection], bound to the active
    // WiFi [Network] via [openBound], 5s connect/read timeouts,
    // `finally { conn.disconnect() }`, typed failures). They return an
    // [ApiResult] (Success/HttpError/NetworkError) instead of a [SyncEvent]
    // because the history tabs render structured data, not a log line.
    //
    // The JSON → data-class decoders live in [Models.kt] and are `internal`
    // so the unit tests can feed mock fixtures straight into them.

    /** `GET /api/count` → [LiveCount]. */
    suspend fun getCount(ip: String, network: Network? = null): ApiResult<LiveCount> =
        getJson(ip, "/api/count", network) { parseLiveCount(it) }

    /** `GET /api/sessions?limit=&offset=` → [SessionPage] (BL-72: renamed from `/api/history`). */
    suspend fun getSessions(
        ip: String,
        limit: Int = 50,
        offset: Int = 0,
        network: Network? = null,
    ): ApiResult<SessionPage> =
        getJson(ip, "/api/sessions?limit=$limit&offset=$offset", network) { parseSessions(it) }

    /** `GET /api/videos?limit=&offset=` → [VideoPage] (BL-72: compressed clips + synthetic running row). */
    suspend fun getVideos(
        ip: String,
        limit: Int = 50,
        offset: Int = 0,
        network: Network? = null,
    ): ApiResult<VideoPage> =
        getJson(ip, "/api/videos?limit=$limit&offset=$offset", network) { parseVideos(it) }

    /** `GET /api/videos/<id>` → [VideoDetail] (per-video counting metadata +
     * perf/thermal attributed by timespan — BL-71). */
    suspend fun getVideoDetail(
        ip: String,
        videoId: String,
        network: Network? = null,
    ): ApiResult<VideoDetail> =
        getJson(ip, "/api/videos/" + URLEncoder.encode(videoId, "UTF-8"), network) { parseVideoDetail(it) }

    /** `GET /api/sessions/<id>` → [SessionDetail] (A–G groups, `end` may be null). */
    suspend fun getSession(
        ip: String,
        id: String,
        network: Network? = null,
    ): ApiResult<SessionDetail> =
        getJson(
            ip,
            "/api/sessions/" + URLEncoder.encode(id, "UTF-8"),
            network,
        ) { parseSessionDetail(it) }

    /** `GET /api/summary?days=N` → [Summary] (daily buckets; BL-72: renamed from `/api/history/summary`). */
    suspend fun getSummary(
        ip: String,
        days: Int = 7,
        network: Network? = null,
    ): ApiResult<Summary> =
        getJson(ip, "/api/summary?days=$days", network) { parseSummary(it) }

    /** `GET /api/startups?limit=` → [StartupList] (newest boot first). */
    suspend fun getStartups(
        ip: String,
        limit: Int = 50,
        network: Network? = null,
    ): ApiResult<StartupList> =
        getJson(ip, "/api/startups?limit=$limit", network) { parseStartups(it) }

    /** `GET <path>` → raw response body string (for offline caching of the
     * history/dashboard/startups tabs). Same transport as the typed getters
     * (WiFi-bound, 5s timeouts, never throws); returns [ApiResult.Success]
     * with the body, or HttpError/NetworkError. The caller caches the body on
     * success and parses it with the `internal` parsers in [Models.kt]. */
    suspend fun fetchRaw(
        ip: String,
        path: String,
        network: Network? = null,
    ): ApiResult<String> = getJson(ip, path, network) { it }

    // -----------------------------------------------------------------------
    // BL-76 runtime settings + poweroff (mutable endpoints)
    // -----------------------------------------------------------------------
    //
    // Three endpoints backing the Android Réglages tab (companion
    // SERVICE_VERSION "5"). They reuse the same WiFi-bound [HttpURLConnection]
    // transport as the read-only history getters, but the latter two are
    // write operations (PUT/POST) so they go through [sendJson] instead of
    // [getJson]. `null` fields on a [JetsonSettings] PUT body are omitted by
    // [JetsonSettings.toJson] so the body is a true PATCH (only the keys the
    // caller wants to change are sent). The companion validates types/ranges
    // server-side and returns 400 on a bad payload; that surfaces as
    // [ApiResult.HttpError](400). All three never throw — failures map to
    // [ApiResult.HttpError] / [ApiResult.NetworkError].

    /** `GET /api/settings` → [JetsonSettings] (merged `runtime-settings.json`,
     *  empty object → all-`null` [JetsonSettings] if the file is absent). */
    suspend fun getSettings(
        ip: String,
        network: Network? = null,
    ): ApiResult<JetsonSettings> =
        getJson(ip, "/api/settings", network) { parseJetsonSettings(it) }

    /**
     * `PUT /api/settings` — PATCH-like merge: only the non-`null` fields of
     * [body] are serialized (see [JetsonSettings.toJson]); the companion
     * merges them into `runtime-settings.json` atomically and echoes the full
     * merged object. Returns the parsed merged settings on 200, 400 on a
     * validation error, [ApiResult.NetworkError] on connect/read failure.
     */
    suspend fun putSettings(
        ip: String,
        body: JetsonSettings,
        network: Network? = null,
    ): ApiResult<JetsonSettings> =
        sendJson("PUT", ip, "/api/settings", body.toJson().toString(), network) {
            parseJetsonSettings(it)
        }

    /**
     * `POST /api/power` — writes the `.arret_requested` sentinel on the
     * Jetson (the counting app consumes it and runs the BL-62 poweroff
     * sequence). The request body is optional and ignored server-side; an
     * empty JSON object is sent. Returns [PoweroffResponse] on 200
     * (`{"status":"poweroff_requested"}`), [ApiResult.HttpError] on a non-2xx,
     * [ApiResult.NetworkError] on connect/read failure.
     */
    suspend fun postPower(
        ip: String,
        network: Network? = null,
    ): ApiResult<PoweroffResponse> =
        sendJson("POST", ip, "/api/power", "{}", network) { parsePoweroffResponse(it) }

    /**
     * `GET /api/identify` → companion `version` string (BL-77 About card).
     *
     * A lightweight, off-the-probe-path variant of [identify]: it reuses the
     * shared WiFi-bound [getJson] transport and [isValidIdentifyBody] validator
     * but returns just the `version` string wrapped in [ApiResult] (not a
     * [SyncEvent]), so the About fetch never touches the probe/log path → zero
     * counting/core impact. On a valid 200 body the `version` field is
     * extracted via [parseIdentifyVersion]; an invalid/non-Jetson body throws
     * inside the parse lambda so [getJson] maps it to
     * [ApiResult.NetworkError] (consistent with the other typed getters'
     * parse-failure handling).
     */
    suspend fun identifyVersion(
        ip: String,
        network: Network? = null,
    ): ApiResult<String> =
        getJson(ip, "/api/identify", network) { parseIdentifyVersion(it) }

    /**
     * Shared transport for the BL-76 write endpoints: binds to [network]
     * (the WiFi HotSpot) when non-null, applies the 5s connect/read timeouts,
     * sends [payload] as the request body with `Content-Type: application/json`,
     * drains the response, and maps HTTP 200 → [ApiResult.Success] (parsed via
     * [parse]), non-2xx → [ApiResult.HttpError], thrown/parse failure →
     * [ApiResult.NetworkError]. Never throws.
     */
    private suspend fun <T> sendJson(
        method: String,
        ip: String,
        path: String,
        payload: String,
        network: Network?,
        parse: (String) -> T,
    ): ApiResult<T> = withContext(Dispatchers.IO) {
        try {
            val url = URL("http://${sanitizeIp(ip)}:$JETSON_PORT$path")
            val conn = (openBound(url, network) as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                doOutput = true
                instanceFollowRedirects = false
                useCaches = false
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
            }
            try {
                conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                val body = conn.readBody(code)
                if (code == 200) {
                    ApiResult.Success(parse(body))
                } else {
                    ApiResult.HttpError(code)
                }
            } finally {
                conn.disconnect()
            }
        } catch (t: Throwable) {
            ApiResult.NetworkError(t.message ?: t.javaClass.simpleName)
        }
    }

    /**
     * Shared GET transport for the read-only history endpoints: binds to
     * [network] (the WiFi HotSpot) when non-null, applies the 5s timeouts,
     * drains the body, and maps HTTP 200 → [ApiResult.Success] (parsed via
     * [parse]), non-2xx → [ApiResult.HttpError], thrown/parse failure →
     * [ApiResult.NetworkError]. Never throws.
     */
    private suspend fun <T> getJson(
        ip: String,
        path: String,
        network: Network?,
        parse: (String) -> T,
    ): ApiResult<T> = withContext(Dispatchers.IO) {
        try {
            val url = URL("http://${sanitizeIp(ip)}:$JETSON_PORT$path")
            val conn = (openBound(url, network) as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = false
                useCaches = false
                setRequestProperty("Accept", "application/json")
            }
            try {
                val code = conn.responseCode
                val body = conn.readBody(code)
                if (code == 200) {
                    ApiResult.Success(parse(body))
                } else {
                    ApiResult.HttpError(code)
                }
            } finally {
                conn.disconnect()
            }
        } catch (t: Throwable) {
            ApiResult.NetworkError(t.message ?: t.javaClass.simpleName)
        }
    }

    // -----------------------------------------------------------------------
    // BL-72 video download stream — Range-capable MP4
    // -----------------------------------------------------------------------
    //
    // Unlike the JSON getters above, [openVideoStream] does NOT drain the
    // body or call `conn.disconnect()` — it hands the live [InputStream] to
    // the caller (the VideoDetailViewModel coroutine) so a multi-hundred-MB
    // clip streams in chunks into a `MediaStore` sink without buffering the
    // whole file in memory. The caller owns `conn.disconnect()` and the
    // stream's lifecycle (close on completion / cancellation / error).

    /**
     * Open `GET /api/video/<videoId>` bound to the WiFi [Network], requesting
     * `video/mp4`. The stream stays open for the caller to drain.
     *
     * - 200 / 206 → [VideoStreamResult.Success] with the response code, the
     *   live [InputStream] (drain in chunks), and `Content-Length` (`-1` if
     *   absent, e.g. a chunked 206).
     * - 404 → [VideoStreamResult.HttpError] (the clip is not yet compressed
     *   or has been cleaned up).
     * - any other non-2xx → [VideoStreamResult.HttpError].
     * - connect/read failure → [VideoStreamResult.NetworkError].
     *
     * The caller MUST [HttpURLConnection.disconnect] the connection bound to
     * the returned stream when done (success or failure). On a non-success
     * result the connection is already disconnected here.
     */
    suspend fun openVideoStream(
        ip: String,
        videoId: String,
        network: Network? = null,
    ): VideoStreamResult = withContext(Dispatchers.IO) {
        val url = URL("http://${sanitizeIp(ip)}:$JETSON_PORT/api/video/" +
            URLEncoder.encode(videoId, "UTF-8"))
        try {
            val conn = (openBound(url, network) as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                // A long clip can take minutes to drain; use 0 (infinite) so
                // the read isn't killed mid-stream by the 5s probe timeout.
                readTimeout = 0
                instanceFollowRedirects = false
                useCaches = false
                setRequestProperty("Accept", "video/mp4")
            }
            try {
                val code = conn.responseCode
                if (code == 200 || code == 206) {
                    val len = conn.contentLengthLong
                    VideoStreamResult.Success(code, conn.inputStream, len, conn)
                } else {
                    conn.disconnect()
                    VideoStreamResult.HttpError(code)
                }
            } catch (t: Throwable) {
                conn.disconnect()
                VideoStreamResult.NetworkError(t.message ?: t.javaClass.simpleName)
            }
        } catch (t: Throwable) {
            VideoStreamResult.NetworkError(t.message ?: t.javaClass.simpleName)
        }
    }
}

/**
 * Outcome of [JetsonClient.openVideoStream]. The stream is left open on
 * [VideoStreamResult.Success] for the caller to drain into a `MediaStore`
 * sink; the caller owns `connection.disconnect()` and closing the [stream].
 */
sealed interface VideoStreamResult {
    /** 200/206 — the [stream] is live; drain it in chunks. [contentLength] is `-1` if absent. */
    data class Success(
        val code: Int,
        val stream: InputStream,
        val contentLength: Long,
        val connection: HttpURLConnection,
    ) : VideoStreamResult
    /** Non-2xx (404 = clip not available / compression in progress / cleaned up). */
    data class HttpError(val code: Int) : VideoStreamResult
    /** Connect/read failure (timeout, no route, …). */
    data class NetworkError(val message: String) : VideoStreamResult
}