// SPDX-License-Identifier: Apache-2.0
package com.linecorp.simuse.devicebridge

import android.content.Context
import android.content.SharedPreferences
import com.linecorp.simuse.devicebridge.config.BridgeSettings
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the "listen on all interfaces" setting: how the adb
 * `--arg` string is parsed, and that the value round-trips through
 * SharedPreferences under the expected key with the expected default.
 *
 * No emulator needed — SharedPreferences is mocked, which is enough
 * because the class holds no logic beyond key/default/durability
 * choices, and those are exactly what a regression would break.
 */
class BridgeSettingsTest {

    // ── adb --arg parsing ───────────────────────────────────────

    @Test
    fun parsesTruthySpellings() {
        for (raw in listOf("true", "TRUE", " True ", "1", "yes", "y", "on", "enable", "enabled")) {
            assertEquals("expected true for '$raw'", true, BridgeSettings.parseBooleanArg(raw))
        }
    }

    @Test
    fun parsesFalsySpellings() {
        for (raw in listOf("false", "FALSE", " off ", "0", "no", "n", "disable", "disabled")) {
            assertEquals("expected false for '$raw'", false, BridgeSettings.parseBooleanArg(raw))
        }
    }

    @Test
    fun rejectsGarbageRatherThanDefaultingToFalse() {
        // The failure mode we are guarding: a typo silently reading as
        // "false" would leave the bridge on loopback while the operator
        // believes Wi-Fi mode is on. Null makes the provider answer with
        // an explicit error instead.
        assertNull(BridgeSettings.parseBooleanArg("ture"))
        assertNull(BridgeSettings.parseBooleanArg(""))
        assertNull(BridgeSettings.parseBooleanArg("2"))
    }

    @Test
    fun rejectsMissingArg() {
        assertNull(BridgeSettings.parseBooleanArg(null))
    }

    // ── Persistence ─────────────────────────────────────────────

    private class Fixture {
        val editor: SharedPreferences.Editor = mockk(relaxed = true)
        val prefs: SharedPreferences = mockk(relaxed = true)
        val context: Context = mockk(relaxed = true)

        init {
            every { context.getSharedPreferences(any(), any()) } returns prefs
            every { prefs.edit() } returns editor
            every { editor.putBoolean(any(), any()) } returns editor
            every { editor.commit() } returns true
        }
    }

    @Test
    fun defaultsToLoopbackWhenUnset() {
        val f = Fixture()
        // Simulate a fresh install: the key is absent, so the store must
        // hand back the caller-supplied default.
        every { f.prefs.getBoolean(BridgeSettings.KEY_BIND_ALL, any()) } answers { secondArg() }
        assertFalse(
            "upstream loopback behaviour must be the default",
            BridgeSettings(f.context).bindAllInterfaces,
        )
    }

    @Test
    fun readsPersistedTrue() {
        val f = Fixture()
        every { f.prefs.getBoolean(BridgeSettings.KEY_BIND_ALL, any()) } returns true
        assertTrue(BridgeSettings(f.context).bindAllInterfaces)
    }

    @Test
    fun writesUnderExpectedKeyAndCommitsSynchronously() {
        val f = Fixture()
        val key = slot<String>()
        val value = slot<Boolean>()
        every { f.editor.putBoolean(capture(key), capture(value)) } returns f.editor

        BridgeSettings(f.context).bindAllInterfaces = true

        assertEquals(BridgeSettings.KEY_BIND_ALL, key.captured)
        assertTrue(value.captured)
        // commit(), not apply(): the adb caller may read the value back
        // (or kill the process) immediately after the call returns.
        verify(exactly = 1) { f.editor.commit() }
    }

    @Test
    fun sharesThePrefsFileWithTheAuthToken() {
        val f = Fixture()
        val name = slot<String>()
        every { f.context.getSharedPreferences(capture(name), any()) } returns f.prefs

        BridgeSettings(f.context)

        // Same file as AuthManager so one `pm clear` resets token and
        // bind mode together, restoring upstream behaviour wholesale.
        assertEquals("sim_use_device_bridge", name.captured)
    }

    /**
     * Full toggle round trip against a stateful store, which the
     * write-only and read-only tests above cannot catch between them: an
     * operator's `set_bind_all true` must be readable as true by the
     * very next `get_bind_all` / listener start, and `false` must take
     * the device back to upstream loopback rather than sticking on.
     *
     * This is the exact sequence FARM-NOTES documents for putting a
     * phone on, and later off, the farm network.
     */
    @Test
    fun bindAllTogglesRoundTripThroughTheStore() {
        val f = Fixture()
        val stored = HashMap<String, Boolean>()
        every { f.editor.putBoolean(any(), any()) } answers {
            stored[firstArg()] = secondArg()
            f.editor
        }
        every { f.prefs.getBoolean(any(), any()) } answers {
            stored[firstArg<String>()] ?: secondArg()
        }

        val settings = BridgeSettings(f.context)
        assertFalse("fresh install is loopback", settings.bindAllInterfaces)

        settings.bindAllInterfaces = true
        assertTrue("set_bind_all true must be visible immediately", settings.bindAllInterfaces)
        // A second BridgeSettings over the same store — what the
        // accessibility service constructs on restart — sees it too.
        assertTrue(BridgeSettings(f.context).bindAllInterfaces)

        settings.bindAllInterfaces = false
        assertFalse("set_bind_all false must return the device to loopback", settings.bindAllInterfaces)
        assertFalse(BridgeSettings(f.context).bindAllInterfaces)
    }
}
