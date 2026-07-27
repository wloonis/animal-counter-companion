package com.animalcounter.data

import java.time.Instant

/**
 * A single time-sync interaction with the Jetson companion (BL-64).
 *
 * Captures what happened (probe vs. push) and how it ended, plus a raw
 * detail string the UI may surface verbatim. UI labels for [Type] and
 * [Outcome] are localized via `stringResource(R.string.*)`.
 */
data class SyncEvent(
    val timestamp: Instant,
    val type: Type,
    val outcome: Outcome,
    val detail: String,
) {
    /** Kind of interaction with the companion. */
    sealed interface Type {
        /** A `GET /api/identify` reachability probe (foreground-only). */
        data object Probe : Type
        /** A `POST /api/time` clock push. */
        data object Sync : Type
    }

    /** Outcome of the interaction, mirroring the companion's HTTP contract. */
    sealed interface Outcome {
        /** HTTP 200 — success. */
        data object Success : Outcome
        /** HTTP 400 — bad request (malformed JSON, unparseable time, unknown tz). */
        data object BadRequest : Outcome
        /** HTTP 5xx — server error (e.g. `timedatectl` failure on the Jetson). */
        data object ServerError : Outcome
        /** Network failure — connect refused / timeout / DNS / no route. */
        data object Network : Outcome
    }
}