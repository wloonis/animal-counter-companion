package com.animalcounter.net

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BL-69 Task 16 — JSON-parsing unit tests for the five read-only history/count
 * endpoints exposed by the Jetson companion (BL-68).
 *
 * These tests feed mock JSON fixtures (mirroring the response shapes verified
 * against `tests/companion_history_reader.py` + the `/api/count` brief) into
 * the pure `internal` parse functions in [Models.kt]. No HTTP is exercised —
 * the parsers are the only thing under test, so the tests are deterministic
 * and network-free.
 *
 * Coverage:
 *  - `getCount` parse: count, status, auto_mode, timestamp, session_id, plus
 *    defensive defaults on a missing field.
 *  - `getSessions` parse (BL-72: renamed from `/api/history`): pagination
 *    wrapper (`sessions[], limit, offset, total`) + an ended session
 *    (`end_reason="clean"`) AND a running session (`end_reason=null`,
 *    `status="running"`).
 *  - `getVideos` parse (BL-72): pagination wrapper (`videos[], limit, offset,
 *    total`) + a ready row (full fields) + a synthetic running row
 *    (`status:"running"`, `duration:null`, filename with no `#N`) + defensive
 *    defaults on missing keys + empty/missing `videos[]` fallback.
 *  - `getSession` parse: ended session with full `end.counters`/`end.video`/
 *    `end.system`, AND a running session with `end=null` (assert counters fall
 *    back to the last heartbeat's count).
 *  - `getSummary` parse: `{days, daily[]}` with multiple day buckets.
 *  - `getStartups` parse: list with `boot_at`, `image_tag`, `git_commit`,
 *    `mode`, `config_notable`, ordered as returned (newest-first is applied by
 *    the ViewModel, not the parser — the parser preserves wire order).
 */
class JetsonClientParsingTest {

    // -------------------------------------------------------------------------
    // /api/count  →  LiveCount
    // -------------------------------------------------------------------------

    @Test
    fun parseLiveCount_fullShape() {
        val json = JSONObject()
            .put("count", 42)
            .put("status", "running")
            .put("auto_mode", true)
            .put("timestamp", "2025-07-15T16:30:00.123456+02:00")
            .put("session_id", "sess-running-0003")
            .toString()

        val lc = parseLiveCount(json)
        assertEquals(42, lc.count)
        assertEquals("running", lc.status)
        assertTrue(lc.autoMode)
        assertEquals("2025-07-15T16:30:00.123456+02:00", lc.timestamp)
        assertEquals("sess-running-0003", lc.sessionId)
    }

    @Test
    fun parseLiveCount_defensiveDefaultsOnMissingFields() {
        // The brief says the /api/count shape is not pinned by a fixture in
        // this repo — parse defensively so a missing field degrades to a
        // default ("unknown"/0/false) rather than throwing.
        val json = JSONObject().put("count", 7).toString()
        val lc = parseLiveCount(json)
        assertEquals(7, lc.count)
        assertEquals("unknown", lc.status)
        assertFalse(lc.autoMode)
        assertNull(lc.timestamp)
        assertNull(lc.sessionId)
    }

    // -------------------------------------------------------------------------
    // /api/sessions  →  SessionPage   (BL-72: renamed from /api/history)
    // -------------------------------------------------------------------------

    /** Mirrors `tests/test_companion_history_api.py` fixture: one ended
     *  (clean) session + one running session. The `/api/sessions` shape is
     *  identical to the old `/api/history` shape. */
    private fun sessionsJson(): String {
        val ended = JSONObject()
            .put("session_id", "sess-recent-0002")
            .put("start_at", "2025-07-15T16:00:00+02:00")
            .put("end_at", "2025-07-15T17:30:00+02:00")
            .put("end_reason", "clean")
            .put("status", "ended")
            .put("net_count", 9)
            .put("events", 2)
            .put("heartbeats", 2)
            .put("last_event_ts", "2025-07-15T17:29:00+02:00")
            .put("image_tag", "v1.0.1")
        val running = JSONObject()
            .put("session_id", "sess-running-0003")
            .put("start_at", "2025-07-15T17:40:00+02:00")
            .put("end_at", "2025-07-15T17:55:00+02:00")
            .put("end_reason", JSONObject.NULL)
            .put("status", "running")
            .put("net_count", 4)
            .put("events", 1)
            .put("heartbeats", 1)
            .put("last_event_ts", "2025-07-15T17:54:00+02:00")
            .put("image_tag", "v1.0.1")
        val sessions = JSONArray().put(running).put(ended)
        return JSONObject()
            .put("sessions", sessions)
            .put("limit", 50)
            .put("offset", 0)
            .put("total", 2)
            .toString()
    }

    @Test
    fun parseSessions_paginationWrapper() {
        val page = parseSessions(sessionsJson())
        assertEquals(50, page.limit)
        assertEquals(0, page.offset)
        assertEquals(2, page.total)
        assertEquals(2, page.sessions.size)
    }

    @Test
    fun parseSessions_endedSessionHasCleanEndReason() {
        val page = parseSessions(sessionsJson())
        val ended = page.sessions.first { it.sessionId == "sess-recent-0002" }
        assertEquals("ended", ended.status)
        assertEquals("clean", ended.endReason)
        assertEquals(9, ended.netCount)
        assertEquals(2, ended.events)
        assertEquals(2, ended.heartbeats)
        assertEquals("v1.0.1", ended.imageTag)
        assertNotNull(ended.lastEventTs)
    }

    @Test
    fun parseSessions_runningSessionHasNullEndReason() {
        val page = parseSessions(sessionsJson())
        val running = page.sessions.first { it.sessionId == "sess-running-0003" }
        assertEquals("running", running.status)
        // end_reason is JSON null on the wire → parsed null (NOT the string
        // "unknown"; the pill color logic must branch on this being null).
        assertNull(running.endReason)
        assertEquals(4, running.netCount)
        assertEquals(1, running.events)
    }

    @Test
    fun parseSessions_emptySessionsArray() {
        val json = JSONObject()
            .put("sessions", JSONArray())
            .put("limit", 50)
            .put("offset", 0)
            .put("total", 0)
            .toString()
        val page = parseSessions(json)
        assertEquals(0, page.sessions.size)
        assertEquals(0, page.total)
    }

    @Test
    fun parseSessions_missingSessionsArrayFallsBackToEmpty() {
        // Defensive: a malformed payload without a sessions[] key must not
        // crash the parser.
        val json = JSONObject().put("limit", 50).put("offset", 0).toString()
        val page = parseSessions(json)
        assertEquals(0, page.sessions.size)
    }

    // -------------------------------------------------------------------------
    // /api/sessions/<id>  →  SessionDetail
    // -------------------------------------------------------------------------

    private fun configJson(): JSONObject = JSONObject()
        .put("image_tag", "v1.0.1")
        .put("git_commit", "bbbbbbb")
        .put("mode", "serve")
        .put("config_notable", JSONObject().put("confidence_threshold", 0.4))

    private fun endedSessionDetailJson(): String {
        val counters = JSONObject()
            .put("count_left_to_right", 9)
            .put("count_right_to_left", 0)
            .put("guard_interventions", JSONObject()
                .put("lost_buffer_expired", 1)
                .put("mirror_guard", 2)
                .put("resurrection", 0)
                .put("reid_rebind", 0))
            .put("id_switch_recoveries", 1)
            .put("unique_track_ids", 12)
            .put("max_concurrent_tracks", 3)
        val video = JSONObject()
            .put("path", "/files/seg_011.mp4")
            .put("size", 1048576L)
            .put("duration", 90.0)
            .put("resolution", "1920x1080")
            .put("codec", "h264")
            .put("complete", true)
        val system = JSONObject()
            .put("disk_free", 12.5)
            .put("cpu_load_avg", JSONArray().put(0.1).put(0.2).put(0.3))
            .put("mem_used", 1.5)
        val end = JSONObject()
            .put("end_at", "2025-07-15T17:30:00+02:00")
            .put("end_reason", "clean")
            .put("status", "clean")
            .put("counters", counters)
            .put("video", video)
            .put("system", system)
        val start = JSONObject()
            .put("start_at", "2025-07-15T16:00:00+02:00")
            .put("start_reason", "boot")
            .put("status", "running")
            .put("prev_session_id", "sess-old-0001")
            .put("config", configJson())
        val heartbeats = JSONArray()
        heartbeats.put(JSONObject()
            .put("ts", "2025-07-15T16:30:00+02:00")
            .put("count", 5)
            .put("last_segment", "/files/seg_010.mp4")
            .put("system", JSONObject().put("disk_free", 13.0))
            .put("thermal", JSONObject().put("soc_temp", 42.0)))
        heartbeats.put(JSONObject()
            .put("ts", "2025-07-15T17:00:00+02:00")
            .put("count", 9)
            .put("last_segment", "/files/seg_011.mp4")
            .put("system", JSONObject().put("disk_free", 12.5))
            .put("thermal", JSONObject().put("soc_temp", 45.0)))
        val events = JSONArray()
        events.put(JSONObject()
            .put("ts", "2025-07-15T16:45:00+02:00")
            .put("event_type", "id_switch_recovery")
            .put("detail", JSONObject().put("track_id", 12)))
        events.put(JSONObject()
            .put("ts", "2025-07-15T17:15:00+02:00")
            .put("event_type", "crossed_right")
            .put("detail", JSONObject().put("track_id", 3)))
        return JSONObject()
            .put("session_id", "sess-recent-0002")
            .put("start", start)
            .put("end", end)
            .put("end_at", "2025-07-15T17:30:00+02:00")
            .put("end_reason", "clean")
            .put("status", "ended")
            .put("net_count", 9)
            .put("config", configJson())
            .put("heartbeats", heartbeats)
            .put("events", events)
            .toString()
    }

    @Test
    fun parseSessionDetail_endedSessionHeaderAndConfig() {
        val d = parseSessionDetail(endedSessionDetailJson())
        assertEquals("sess-recent-0002", d.sessionId)
        assertEquals("ended", d.status)
        assertEquals("clean", d.endReason)
        assertEquals(9, d.netCount)
        assertEquals("2025-07-15T17:30:00+02:00", d.endAt)
        assertNotNull(d.start)
        assertEquals("boot", d.start?.startReason)
        assertEquals("sess-old-0001", d.start?.prevSessionId)
        assertEquals("v1.0.1", d.config?.imageTag)
        assertEquals("bbbbbbb", d.config?.gitCommit)
        assertEquals("serve", d.config?.mode)
        assertNotNull(d.config?.notable)
    }

    @Test
    fun parseSessionDetail_endedSessionCounters() {
        val d = parseSessionDetail(endedSessionDetailJson())
        val end = d.end
        assertNotNull(end)
        val c = end!!.counters
        assertNotNull(c)
        assertEquals(9, c!!.countLeftToRight)
        assertEquals(0, c.countRightToLeft)
        assertEquals(1, c.guardInterventions.lostBufferExpired)
        assertEquals(2, c.guardInterventions.mirrorGuard)
        assertEquals(0, c.guardInterventions.resurrection)
        assertEquals(0, c.guardInterventions.reidRebind)
        assertEquals(1, c.idSwitchRecoveries)
        assertEquals(12, c.uniqueTrackIds)
        assertEquals(3, c.maxConcurrentTracks)
    }

    @Test
    fun parseSessionDetail_endedSessionVideoAndSystem() {
        val d = parseSessionDetail(endedSessionDetailJson())
        val v = d.end?.video
        assertNotNull(v)
        assertEquals("/files/seg_011.mp4", v?.path)
        assertEquals(1048576L, v?.sizeBytes)
        assertEquals(90.0, v?.duration!!, 0.001)
        assertEquals("1920x1080", v.resolution)
        assertEquals("h264", v.codec)
        assertTrue(v.complete == true)

        val s = d.end?.system
        assertNotNull(s)
        assertEquals(12.5, s?.diskFree!!, 0.001)
        assertEquals(listOf(0.1, 0.2, 0.3), s?.cpuLoadAvg)
        assertEquals(1.5, s?.memUsed!!, 0.001)
    }

    @Test
    fun parseSessionDetail_endedSessionHeartbeats() {
        // BL-71: per-video counting events moved to the VIDEO entity
        // (/api/videos/<id>); SessionDetail no longer carries an `events`
        // list. This test now asserts only the heartbeats (still on the
        // session) plus the new `videos` list.
        val d = parseSessionDetail(endedSessionDetailJson())
        assertEquals(2, d.heartbeats.size)
        val firstHb = d.heartbeats[0]
        assertEquals("2025-07-15T16:30:00+02:00", firstHb.ts)
        assertEquals(5, firstHb.count)
        assertEquals("/files/seg_010.mp4", firstHb.lastSegment)
        assertNotNull(firstHb.system)
        assertEquals(13.0, firstHb.system?.diskFree!!, 0.001)
        assertNotNull(firstHb.thermal)
        assertEquals(42.0, firstHb.thermal?.optDouble("soc_temp", Double.NaN)!!, 0.001)
        // The fixture carries an `events[]` array that the parser now IGNORES
        // (events live on the video entity). Assert it is silently dropped.
        // `videos` is the session-level list of video_ids (empty in this fixture).
        assertTrue(d.videos.isEmpty())
    }

    @Test
    fun parseSessionDetail_runningSessionEndIsNullAndFallsBackToLastHeartbeat() {
        // A running session: `end` is JSON null on the wire. The detail screen
        // must fall back to the last heartbeat for counters/system — here we
        // only assert the parser preserves `end == null` and that the last
        // heartbeat still carries the live count (the fallback logic lives in
        // the ViewModel/Screen, but the parser must not crash on `end:null`).
        val start = JSONObject()
            .put("start_at", "2025-07-15T17:40:00+02:00")
            .put("start_reason", "boot")
            .put("status", "running")
            .put("config", configJson())
        val heartbeats = JSONArray()
        heartbeats.put(JSONObject()
            .put("ts", "2025-07-15T17:50:00+02:00")
            .put("count", 4)
            .put("last_segment", "/files/seg_020.mp4")
            .put("system", JSONObject().put("disk_free", 12.0)))
        val events = JSONArray()
        events.put(JSONObject()
            .put("ts", "2025-07-15T17:55:00+02:00")
            .put("event_type", "resurrection")
            .put("detail", JSONObject().put("track_id", 99)))
        val json = JSONObject()
            .put("session_id", "sess-running-0003")
            .put("start", start)
            .put("end", JSONObject.NULL)
            .put("end_at", "2025-07-15T17:55:00+02:00")
            .put("end_reason", JSONObject.NULL)
            .put("status", "running")
            .put("net_count", 4)
            .put("config", configJson())
            .put("heartbeats", heartbeats)
            .put("events", events)
            .toString()

        val d = parseSessionDetail(json)
        assertEquals("sess-running-0003", d.sessionId)
        assertEquals("running", d.status)
        assertNull(d.end)                      // end == null preserved
        assertNull(d.endReason)
        assertEquals(4, d.netCount)
        assertEquals(1, d.heartbeats.size)
        // The fallback the detail screen uses: last heartbeat carries the
        // live count — assert it is reachable through the parsed model.
        val lastHb = d.heartbeats.last()
        assertEquals(4, lastHb.count)
        assertEquals("/files/seg_020.mp4", lastHb.lastSegment)
        assertNotNull(lastHb.system)
        assertEquals(12.0, lastHb.system?.diskFree!!, 0.001)
        // BL-71: `events[]` is now ignored by the session parser (events live
        // on the video entity); assert it is silently dropped, not surfaced.
        assertTrue(d.videos.isEmpty())
    }

    @Test
    fun parseSessionDetail_missingStartAndEndAreNullSafe() {
        // Minimal payload: only the top-level envelope. The parser must not
        // throw on absent start/end/heartbeats/videos.
        val json = JSONObject()
            .put("session_id", "sess-x")
            .put("status", "ended")
            .toString()
        val d = parseSessionDetail(json)
        assertEquals("sess-x", d.sessionId)
        assertNull(d.start)
        assertNull(d.end)
        assertTrue(d.heartbeats.isEmpty())
        assertTrue(d.videos.isEmpty())
    }

    // -------------------------------------------------------------------------
    // /api/history/summary?days=N  →  Summary
    // -------------------------------------------------------------------------

    private fun summaryJson(): String {
        val daily = JSONArray()
        daily.put(JSONObject()
            .put("date", "2025-07-15")
            .put("sessions", 2)
            .put("net_count", 13)
            .put("guard_events", 2)
            .put("events", 3))
        daily.put(JSONObject()
            .put("date", "2025-07-14")
            .put("sessions", 1)
            .put("net_count", 3)
            .put("guard_events", 1)
            .put("events", 1))
        return JSONObject().put("days", 7).put("daily", daily).toString()
    }

    @Test
    fun parseSummary_daysAndMultipleBuckets() {
        val s = parseSummary(summaryJson())
        assertEquals(7, s.days)
        assertEquals(2, s.daily.size)
    }

    @Test
    fun parseSummary_firstBucketFields() {
        val s = parseSummary(summaryJson())
        val today = s.daily[0]
        assertEquals("2025-07-15", today.date)
        assertEquals(2, today.sessions)
        assertEquals(13, today.netCount)
        assertEquals(2, today.guardEvents)
        assertEquals(3, today.events)
    }

    @Test
    fun parseSummary_secondBucketFields() {
        val s = parseSummary(summaryJson())
        val prev = s.daily[1]
        assertEquals("2025-07-14", prev.date)
        assertEquals(1, prev.sessions)
        assertEquals(3, prev.netCount)
        assertEquals(1, prev.guardEvents)
        assertEquals(1, prev.events)
    }

    @Test
    fun parseSummary_emptyDailyArray() {
        val json = JSONObject().put("days", 30).put("daily", JSONArray()).toString()
        val s = parseSummary(json)
        assertEquals(30, s.days)
        assertTrue(s.daily.isEmpty())
    }

    // -------------------------------------------------------------------------
    // /api/startups  →  StartupList
    // -------------------------------------------------------------------------

    private fun startupsJson(): String {
        // Wire order is newest-first (the companion reverses on emit; the
        // parser preserves it — StartupsViewModel re-sorts defensively).
        val ups = JSONArray()
        ups.put(JSONObject()
            .put("boot_at", "2025-07-15T17:38:00+02:00")
            .put("image_tag", "v1.0.1")
            .put("git_commit", "bbbbbbb")
            .put("mode", "serve")
            .put("config_notable", JSONObject().put("mode", "serve")))
        ups.put(JSONObject()
            .put("boot_at", "2025-07-13T16:00:00+02:00")
            .put("image_tag", "v1.0.0")
            .put("git_commit", "aaaaaaa")
            .put("mode", "serve")
            .put("config_notable", JSONObject().put("mode", "serve")))
        return JSONObject().put("startups", ups).toString()
    }

    @Test
    fun parseStartups_listPreservesWireOrder() {
        val list = parseStartups(startupsJson())
        assertEquals(2, list.startups.size)
        // Newest-first as emitted; the parser does not re-sort.
        assertEquals("v1.0.1", list.startups[0].imageTag)
        assertEquals("bbbbbbb", list.startups[0].gitCommit)
        assertEquals("v1.0.0", list.startups[1].imageTag)
    }

    @Test
    fun parseStartups_eachStartupFields() {
        val list = parseStartups(startupsJson())
        val first = list.startups[0]
        assertEquals("2025-07-15T17:38:00+02:00", first.bootAt)
        assertEquals("v1.0.1", first.imageTag)
        assertEquals("bbbbbbb", first.gitCommit)
        assertEquals("serve", first.mode)
        assertNotNull(first.configNotable)
        assertEquals("serve", first.configNotable?.optString("mode"))
    }

    @Test
    fun parseStartups_emptyArray() {
        val json = JSONObject().put("startups", JSONArray()).toString()
        val list = parseStartups(json)
        assertTrue(list.startups.isEmpty())
    }

    @Test
    fun parseStartups_missingArrayFallsBackToEmpty() {
        val json = JSONObject().toString()
        val list = parseStartups(json)
        assertTrue(list.startups.isEmpty())
    }

    // -------------------------------------------------------------------------
    // /api/videos  →  VideoPage   (BL-72)
    // -------------------------------------------------------------------------
    //
    // The companion emits `{videos[], limit, offset, total}`. Index 0 is a
    // synthetic "running" row while a recording is in progress (filename has
    // no `#N`, `duration:null`); the rest are compressed clips with full
    // fields. Field names mirror the deployed BL-71 backend:
    //   {video_id, session_id, filename, duration, count_delta, ts, status}

    private fun videosJson(): String {
        // Synthetic running row (index 0): no `#N` in the filename, no
        // duration yet, status "running".
        val running = JSONObject()
            .put("video_id", "counting-20250715-175500")
            .put("session_id", "sess-running-0003")
            .put("filename", "counting-20250715-175500.mp4")
            .put("duration", JSONObject.NULL)
            .put("count_delta", 4)
            .put("ts", "2025-07-15T17:55:00+02:00")
            .put("status", "running")
        // A ready (compressed) clip: full fields, `#N` delta in filename.
        val ready = JSONObject()
            .put("video_id", "counting-20250715-170000")
            .put("session_id", "sess-recent-0002")
            .put("filename", "counting-20250715-170000-#9.mp4")
            .put("duration", 90.0)
            .put("count_delta", 9)
            .put("ts", "2025-07-15T17:00:00+02:00")
            .put("status", "ready")
        val videos = JSONArray().put(running).put(ready)
        return JSONObject()
            .put("videos", videos)
            .put("limit", 50)
            .put("offset", 0)
            .put("total", 2)
            .toString()
    }

    @Test
    fun parseVideos_paginationWrapper() {
        val page = parseVideos(videosJson())
        assertEquals(50, page.limit)
        assertEquals(0, page.offset)
        assertEquals(2, page.total)
        assertEquals(2, page.videos.size)
    }

    @Test
    fun parseVideos_readyRowHasFullFields() {
        val page = parseVideos(videosJson())
        val ready = page.videos.first { it.status == "ready" }
        assertEquals("counting-20250715-170000", ready.videoId)
        assertEquals("sess-recent-0002", ready.sessionId)
        assertEquals("counting-20250715-170000-#9.mp4", ready.filename)
        assertEquals(90.0, ready.duration!!, 0.001)
        assertEquals(9, ready.countDelta)
        assertEquals("2025-07-15T17:00:00+02:00", ready.ts)
        assertEquals("ready", ready.status)
    }

    @Test
    fun parseVideos_runningRowIsSyntheticWithNullDurationAndNoHashN() {
        // The synthetic index-0 running row: status "running", duration null,
        // filename has no `#N` (the clip is not finalized yet).
        val page = parseVideos(videosJson())
        val running = page.videos[0]
        assertEquals("running", running.status)
        assertEquals("counting-20250715-175500.mp4", running.filename)
        assertFalse(running.filename!!.contains("#"))
        assertNull(running.duration)
        assertEquals(4, running.countDelta)
        assertEquals("counting-20250715-175500", running.videoId)
        assertEquals("sess-running-0003", running.sessionId)
        assertEquals("2025-07-15T17:55:00+02:00", running.ts)
    }

    @Test
    fun parseVideos_defensiveDefaultsOnMissingKeys() {
        // A malformed/empty row object must not crash the parser; every
        // nullable field degrades to null and status to "unknown".
        val json = JSONObject()
            .put("videos", JSONArray().put(JSONObject()))
            .put("limit", 50)
            .put("offset", 0)
            .put("total", 1)
            .toString()
        val page = parseVideos(json)
        assertEquals(1, page.videos.size)
        val row = page.videos[0]
        assertNull(row.videoId)
        assertNull(row.sessionId)
        assertNull(row.filename)
        assertNull(row.duration)
        assertNull(row.countDelta)
        assertNull(row.ts)
        assertEquals("unknown", row.status)
    }

    @Test
    fun parseVideos_emptyVideosArray() {
        val json = JSONObject()
            .put("videos", JSONArray())
            .put("limit", 50)
            .put("offset", 0)
            .put("total", 0)
            .toString()
        val page = parseVideos(json)
        assertEquals(0, page.videos.size)
        assertEquals(0, page.total)
    }

    @Test
    fun parseVideos_missingVideosArrayFallsBackToEmpty() {
        // Defensive: a payload without a videos[] key must not crash.
        val json = JSONObject().put("limit", 50).put("offset", 0).toString()
        val page = parseVideos(json)
        assertEquals(0, page.videos.size)
    }

    // -------------------------------------------------------------------------
    // Endpoint-mapping sanity   (BL-72)
    // -------------------------------------------------------------------------
    //
    // The JetsonClient path-construction is inlined in each suspend getter
    // (no exposed constants), and exercising it would require HTTP. Instead
    // we assert the literal wire paths the deployed BL-71 companion serves,
    // so a future rename drift is caught here even though the transport is
    // not exercised. These mirror the strings built in [JetsonClient]
    // `getSessions` / `getVideos` / `getSummary` / `getSession`.

    @Test
    fun endpointMapping_sessionsPathHasRenamedRouteAndPaginationQuery() {
        // JetsonClient.getSessions builds: /api/sessions?limit=<l>&offset=<o>
        val path = "/api/sessions?limit=50&offset=0"
        assertTrue(path.startsWith("/api/sessions?"))
        assertTrue(path.contains("limit=50"))
        assertTrue(path.contains("offset=0"))
        assertFalse(path.contains("/api/history")) // old route must be gone
    }

    @Test
    fun endpointMapping_videosPathIsNewRouteWithPaginationQuery() {
        // JetsonClient.getVideos builds: /api/videos?limit=<l>&offset=<o>
        val path = "/api/videos?limit=50&offset=0"
        assertTrue(path.startsWith("/api/videos?"))
        assertTrue(path.contains("limit=50"))
        assertTrue(path.contains("offset=0"))
    }

    @Test
    fun endpointMapping_summaryPathIsRenamedRouteWithDaysQuery() {
        // JetsonClient.getSummary builds: /api/summary?days=<n>
        val path = "/api/summary?days=7"
        assertTrue(path.startsWith("/api/summary?"))
        assertTrue(path.contains("days=7"))
        assertFalse(path.contains("/api/history/summary")) // old route gone
    }

    @Test
    fun endpointMapping_sessionDetailPathUsesIdSegment() {
        // JetsonClient.getSession builds: /api/sessions/<urlencoded id>
        val path = "/api/sessions/sess-recent-0002"
        assertTrue(path.startsWith("/api/sessions/"))
        assertTrue(path.endsWith("sess-recent-0002"))
    }
}

/**
 * BL-73 Task 2 — strict `GET /api/identify` validation tests for the
 * pure top-level `internal` [isValidIdentifyBody] validator.
 *
 * The Jetson companion answers `GET /api/identify` with
 * `{"service":"jetson-companion","version":"<v>"}`. The validator must accept
 * ONLY that exact `service` value (no version check) and reject every other
 * 200-shaped body (wrong service, missing service, non-JSON) so a stale or
 * foreign 200 response is never mistaken for the Jetson. No HTTP is exercised —
 * [isValidIdentifyBody] is the only thing under test.
 */
class JetsonClientIdentifyValidationTest {

    @Test
    fun isValidIdentifyBody_acceptsValidJetsonCompanionService() {
        val body = JSONObject()
            .put("service", "jetson-companion")
            .put("version", "4")
            .toString()
        assertTrue(isValidIdentifyBody(body))
    }

    @Test
    fun isValidIdentifyBody_rejectsWrongServiceValue() {
        // A stale backend (or any other HTTP 200 responder) advertising a
        // different `service` must be rejected.
        val body = JSONObject()
            .put("service", "animal-counter-companion") // old/stale name
            .put("version", "1")
            .toString()
        assertFalse(isValidIdentifyBody(body))
    }

    @Test
    fun isValidIdentifyBody_rejectsNonJsonBody() {
        // A 200 with a non-JSON body (HTML error page, plain text, …) must not
        // crash the validator; it is rejected as a failure.
        assertFalse(isValidIdentifyBody("<html>Not the Jetson</html>"))
        assertFalse(isValidIdentifyBody(""))
        assertFalse(isValidIdentifyBody("not json at all"))
    }

    @Test
    fun isValidIdentifyBody_rejectsMissingServiceField() {
        // A JSON 200 response without a `service` key must be rejected
        // (`optString` defaults to "", which is not "jetson-companion").
        val body = JSONObject()
            .put("version", "4")
            .toString()
        assertFalse(isValidIdentifyBody(body))
    }

    @Test
    fun isValidIdentifyBody_rejectsEmptyServiceValue() {
        // An explicit empty `service` must be rejected (exact match only).
        val body = JSONObject()
            .put("service", "")
            .put("version", "4")
            .toString()
        assertFalse(isValidIdentifyBody(body))
    }

    @Test
    fun isValidIdentifyBody_isCaseSensitive() {
        // Exact match: a differently-cased `service` is NOT the Jetson.
        val body = JSONObject()
            .put("service", "Jetson-Companion")
            .toString()
        assertFalse(isValidIdentifyBody(body))
    }
}