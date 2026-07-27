package com.animalcounter.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Process-wide, bounded ring buffer of recent [SyncEvent]s.
 *
 * A single `object` singleton so both the foreground UI (the log view +
 * out-of-range banner) and the app-lifecycle-scoped
 * [com.animalcounter.net.JetsonConnectionManager] push into the same log.
 * Thread-safe via a [MutableStateFlow] updated with
 * `update { }`; consumers observe [events] as a Compose-collectable
 * [StateFlow]. The buffer is capped at [CAP] entries; oldest entries are
 * dropped once the cap is reached (FIFO eviction).
 */
object SyncLog {

    /** Maximum retained entries (reverse-chronological list). */
    private const val CAP = 200

    private val _events = MutableStateFlow<List<SyncEvent>>(emptyList())

    /** Recent events, newest first. */
    val events: StateFlow<List<SyncEvent>> = _events.asStateFlow()

    /**
     * Whether the phone is currently connected to the Jetson HotSpot WiFi.
     *
     * Updated by [com.animalcounter.net.JetsonConnectionManager]'s `NetworkCallback`
     * (`onAvailable`/`onLost`); observed by the UI to show the out-of-range
     * message when the companion services are unreachable. Defaults to `false`.
     */
    private val _hotspotConnected = MutableStateFlow(false)
    val hotspotConnected: StateFlow<Boolean> = _hotspotConnected.asStateFlow()

    /** Update the shared HotSpot connectivity state (service-side). */
    fun setConnected(connected: Boolean) {
        _hotspotConnected.value = connected
    }

    /**
     * Append [event] to the log, evicting the oldest entry when the cap
     * is exceeded. The most-recent event is always at index 0.
     */
    fun add(event: SyncEvent) {
        _events.update { current ->
            val next = ArrayList<SyncEvent>(current.size + 1)
            next.add(event)
            next.addAll(current)
            if (next.size > CAP) {
                // Drop the oldest (tail) entries beyond the cap. subList(CAP, ...)
                // is a view; .clear() removes those backing elements in place.
                next.subList(CAP, next.size).clear()
            }
            next
        }
    }

    /** Remove all entries. Exposed for tests / future "clear log" UI. */
    fun clear() {
        _events.value = emptyList()
    }
}