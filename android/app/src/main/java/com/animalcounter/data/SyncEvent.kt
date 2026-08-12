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