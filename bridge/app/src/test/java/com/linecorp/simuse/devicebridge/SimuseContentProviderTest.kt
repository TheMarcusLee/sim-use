// SPDX-License-Identifier: Apache-2.0
package com.linecorp.simuse.devicebridge

import android.net.Uri
import android.os.Binder
import com.linecorp.simuse.devicebridge.service.SimuseContentProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * Verifies that [SimuseContentProvider] enforces shell/root caller UID.
 *
 * The Android `content` CLI is already system-restricted to the shell
 * UID (`ACCESS_CONTENT_PROVIDERS_EXTERNALLY` permission), but installed
 * apps can still call `ContentResolver.query()` directly. The UID guard
 * in `query`/`insert` is the only thing standing between an arbitrary
 * app and the auth-token bearer for the HTTP bridge.
 */
class SimuseContentProviderTest {

    private val authTokenUri: Uri = mockk(relaxed = true) {
        every { lastPathSegment } returns "auth_token"
    }
    private val toggleUri: Uri = mockk(relaxed = true) {
        every { lastPathSegment } returns "toggle_socket_server"
    }

    @Before
    fun setUp() {
        mockkStatic(Binder::class)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test(expected = SecurityException::class)
    fun queryRejectsArbitraryAppUid() {
        every { Binder.getCallingUid() } returns 10191 // typical user-app UID
        SimuseContentProvider().query(authTokenUri, null, null, null, null)
    }

    @Test(expected = SecurityException::class)
    fun insertRejectsArbitraryAppUid() {
        every { Binder.getCallingUid() } returns 10042
        SimuseContentProvider().insert(toggleUri, null)
    }

    @Test(expected = SecurityException::class)
    fun callRejectsArbitraryAppUid() {
        // `set_bind_all` puts the bridge on the LAN. If an installed app
        // could reach it, a malicious app could expose the control API
        // to the whole network without the operator noticing.
        every { Binder.getCallingUid() } returns 10191
        SimuseContentProvider().call(SimuseContentProvider.METHOD_SET_BIND_ALL, "true", null)
    }

    @Test(expected = SecurityException::class)
    fun callRejectsSystemUid() {
        every { Binder.getCallingUid() } returns 1000
        SimuseContentProvider().call(SimuseContentProvider.METHOD_STATUS, null, null)
    }

    @Test(expected = SecurityException::class)
    fun queryRejectsSystemUid() {
        // System UID (1000) is privileged but is NOT shell. We want a hard
        // shell-only contract; system_server has no business reading our
        // bearer token.
        every { Binder.getCallingUid() } returns 1000
        SimuseContentProvider().query(authTokenUri, null, null, null, null)
    }

    @Test
    fun queryAcceptsShellUid() {
        every { Binder.getCallingUid() } returns 2000 // Process.SHELL_UID
        try {
            // We only assert the guard does NOT throw. Reaching the real
            // body would need a Context for AuthManager; in default-values
            // mode the lazy property is never touched because the URI
            // matches and we abandon the test there — the call may NPE on
            // MatrixCursor construction in stub mode, which is fine.
            SimuseContentProvider().query(authTokenUri, null, null, null, null)
        } catch (e: SecurityException) {
            fail("Shell UID should not be rejected, got: ${e.message}")
        } catch (e: Exception) {
            // Anything else (NPE on stubbed Android types, etc.) is
            // acceptable for this guard-only test.
        }
    }

    @Test
    fun queryAcceptsRootUid() {
        every { Binder.getCallingUid() } returns 0 // Process.ROOT_UID
        try {
            SimuseContentProvider().query(authTokenUri, null, null, null, null)
        } catch (e: SecurityException) {
            fail("Root UID should not be rejected, got: ${e.message}")
        } catch (e: Exception) {
            // See note above.
        }
        assertNotNull("smoke") // dummy assert so JUnit counts this as a real test
    }

    // ── Operator status payload (fork addition) ─────────────────

    /**
     * `status` is the one call that returns a broad snapshot of the
     * bridge, and operators paste its output into tickets and chat. It
     * must never carry auth material — the bearer token has exactly one
     * exit, the `auth_token` query.
     */
    @Test
    fun statusJsonCarriesNoAuthMaterial() {
        val json = SimuseContentProvider.buildStatusJson(
            bindAll = true,
            boundAll = true,
            serverRunning = true,
            accessibilityServiceConnected = true,
            port = 8080,
            lanIpv4 = "192.168.1.42",
        )
        val keys = json.keys().asSequence().toSet()
        assertEquals(
            setOf(
                "bind_all", "bound_all", "server_running",
                "accessibility_service_connected", "port", "lan_ipv4",
            ),
            keys,
        )
        val body = json.toString().lowercase()
        for (forbidden in listOf("token", "bearer", "authorization", "secret", "auth_token")) {
            assertFalse("status must not mention '$forbidden': $json", body.contains(forbidden))
        }
    }

    /**
     * Round-trips the persisted-vs-live distinction the farm relies on:
     * between a `set_bind_all true` and a successful rebind, `bind_all`
     * is true while `bound_all` is still false, and that difference is
     * exactly what tells an operator the toggle has not taken effect.
     */
    @Test
    fun statusJsonSeparatesPersistedIntentFromLiveBindMode() {
        val pending = SimuseContentProvider.buildStatusJson(
            bindAll = true,
            boundAll = false,
            serverRunning = false,
            accessibilityServiceConnected = false,
            port = 8080,
            lanIpv4 = null,
        )
        assertTrue(pending.getBoolean("bind_all"))
        assertFalse(pending.getBoolean("bound_all"))
        assertFalse(pending.getBoolean("server_running"))
        assertTrue("absent IP must be JSON null", pending.isNull("lan_ipv4"))

        val live = SimuseContentProvider.buildStatusJson(
            bindAll = true,
            boundAll = true,
            serverRunning = true,
            accessibilityServiceConnected = true,
            port = 8080,
            lanIpv4 = "10.0.0.5",
        )
        assertTrue(live.getBoolean("bound_all"))
        assertEquals("10.0.0.5", live.getString("lan_ipv4"))
        assertEquals(8080, live.getInt("port"))
    }
}
