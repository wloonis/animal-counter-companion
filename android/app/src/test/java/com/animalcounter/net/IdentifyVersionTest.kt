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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * BL-77 Task 8 — unit tests for [parseIdentifyVersion], the pure `internal`
 * decoder backing `JetsonClient.identifyVersion` (the About card's live
 * companion-version fetch).
 *
 * No HTTP is exercised — only the JSON extraction logic, so the tests are
 * deterministic and network-free (mirrors the existing
 * [JetsonClientParsingTest] pattern for the history parsers).
 */
class IdentifyVersionTest {

    @Test
    fun `extracts version from a valid jetson-companion body`() {
        val body = """{"service":"jetson-companion","version":"2"}"""
        assertEquals("2", parseIdentifyVersion(body))
    }

    @Test
    fun `extracts an arbitrary version string`() {
        val body = """{"service":"jetson-companion","version":"1.4.0-rc3"}"""
        assertEquals("1.4.0-rc3", parseIdentifyVersion(body))
    }

    @Test
    fun `missing version field returns an empty string, not a throw`() {
        // optString("version") returns "" for an absent key — the About UI
        // treats a blank as offline. The body is still a valid Jetson
        // identify response (service matches), so no throw here.
        val body = """{"service":"jetson-companion"}"""
        assertEquals("", parseIdentifyVersion(body))
    }

    @Test
    fun `empty version value returns an empty string`() {
        val body = """{"service":"jetson-companion","version":""}"""
        assertEquals("", parseIdentifyVersion(body))
    }

    @Test
    fun `non-jetson service is rejected`() {
        val body = """{"service":"nginx","version":"2"}"""
        assertThrows(IllegalArgumentException::class.java) {
            parseIdentifyVersion(body)
        }
    }

    @Test
    fun `missing service field is rejected`() {
        val body = """{"version":"2"}"""
        assertThrows(IllegalArgumentException::class.java) {
            parseIdentifyVersion(body)
        }
    }

    @Test
    fun `non-JSON body is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            parseIdentifyVersion("not-json")
        }
    }
}