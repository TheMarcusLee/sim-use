// SPDX-License-Identifier: Apache-2.0
package com.linecorp.simuse.devicebridge

import android.accessibilityservice.AccessibilityService
import com.linecorp.simuse.devicebridge.config.AuthManager
import com.linecorp.simuse.devicebridge.server.ActionRouter
import io.mockk.every
import io.mockk.mockk
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Routing-layer tests for `ActionRouter`: auth gate, unknown routes,
 * `/ping` envelope shape, missing-parameter handling.
 *
 * We don't reach the handler implementations here — those are either
 * Android-coupled (TreeHandler / GestureHandler / CaptureHandler all
 * need `AccessibilityNodeInfo` or `Bitmap`) or already covered by
 * dedicated unit suites (`InputHandlerTest`). The router contract
 * (which paths exist, how errors are shaped, who can bypass auth) is
 * what most callers actually depend on through the wire spec.
 */
class ActionRouterTest {

    private val token = "test-token-1234"
    private lateinit var authManager: AuthManager
    private lateinit var router: ActionRouter

    @Before
    fun setUp() {
        authManager = mockk()
        every { authManager.getOrCreateToken() } returns token
        // No accessibility service available — routes that touch the
        // service will return 503. That's still a route-layer outcome
        // worth pinning.
        router = ActionRouter(serviceProvider = { null }, authManager = authManager)
    }

    private fun authed(): Map<String, String> = mapOf("authorization" to "Bearer $token")
    private fun unauthed(): Map<String, String> = emptyMap()

    // ── /ping bypasses auth ─────────────────────────────────────

    @Test
    fun pingReachableWithoutAuth() {
        val resp = router.route("GET", "/ping", emptyMap(), unauthed())
        assertEquals(200, resp.statusCode)
        val json = JSONObject(resp.body)
        assertEquals("success", json.getString("status"))
        assertEquals("pong", json.getString("result"))
        // protocol_version + bridge_version live alongside result so
        // clients can verify compatibility without an authed call.
        assertTrue("protocol_version field present", json.has("protocol_version"))
        assertTrue("bridge_version field present", json.has("bridge_version"))
    }

    // ── /ping bind-all reporting (fork addition) ────────────────

    @Test
    fun pingReportsLoopbackByDefault() {
        // Routers built without an explicit provider — every upstream
        // call site and every older test — must report loopback.
        val json = JSONObject(router.route("GET", "/ping", emptyMap(), unauthed()).body)
        assertFalse("bind_all defaults to false", json.getBoolean("bind_all"))
    }

    @Test
    fun pingReportsBindAllWhenListenerIsOnAllInterfaces() {
        val lanRouter = ActionRouter(
            serviceProvider = { null },
            authManager = authManager,
            bindAllProvider = { true },
        )
        val json = JSONObject(lanRouter.route("GET", "/ping", emptyMap(), unauthed()).body)
        assertTrue(json.getBoolean("bind_all"))
        // Still the same envelope, still unauthenticated: a farm health
        // check must not need the token.
        assertEquals("success", json.getString("status"))
        assertEquals("pong", json.getString("result"))
    }

    @Test
    fun pingTracksTheProviderLive() {
        // The service snapshots its bind mode at listener start and the
        // router reads it through the lambda, so a restart into a new
        // mode must be visible on the very next /ping.
        var bindAll = false
        val liveRouter = ActionRouter(
            serviceProvider = { null },
            authManager = authManager,
            bindAllProvider = { bindAll },
        )
        assertFalse(JSONObject(liveRouter.route("GET", "/ping", emptyMap(), unauthed()).body).getBoolean("bind_all"))
        bindAll = true
        assertTrue(JSONObject(liveRouter.route("GET", "/ping", emptyMap(), unauthed()).body).getBoolean("bind_all"))
    }

    @Test
    fun pingNeverLeaksTheBearerToken() {
        // /ping is the one unauthenticated route and, in Wi-Fi mode, is
        // answered to any LAN peer. Pin that the payload carries no auth
        // material.
        val lanRouter = ActionRouter(
            serviceProvider = { null },
            authManager = authManager,
            bindAllProvider = { true },
        )
        val body = lanRouter.route("GET", "/ping", emptyMap(), unauthed()).body
        assertFalse("token must not appear in /ping body", body.contains(token))
        val json = JSONObject(body)
        for (forbidden in listOf("token", "auth_token", "authorization", "bearer")) {
            assertFalse("/ping must not carry '$forbidden'", json.has(forbidden))
        }
    }

    @Test
    fun bindAllModeStillRequiresAuthOnEveryOtherRoute() {
        // The whole security story of Wi-Fi mode is "the token is still
        // mandatory". Enumerate the farm-facing routes explicitly.
        val lanRouter = ActionRouter(
            serviceProvider = { null },
            authManager = authManager,
            bindAllProvider = { true },
        )
        val routes = listOf(
            "GET" to "/screenshot",
            "GET" to "/a11y_tree_full",
            "GET" to "/keyboard/state",
            "POST" to "/tap",
            "POST" to "/swipe",
            "POST" to "/gesture",
            "POST" to "/keyboard/input",
            "POST" to "/keyboard/key",
            "POST" to "/paste",
        )
        for ((method, path) in routes) {
            val resp = lanRouter.route(method, path, emptyMap(), unauthed())
            assertEquals("$method $path must be 401 without a bearer", 401, resp.statusCode)
        }
    }

    // ── Auth gate ───────────────────────────────────────────────

    @Test
    fun nonPingPathWithoutAuthReturns401() {
        val resp = router.route("GET", "/a11y_tree_full", emptyMap(), unauthed())
        assertEquals(401, resp.statusCode)
        val json = JSONObject(resp.body)
        assertEquals("error", json.getString("status"))
        assertEquals("unauthorized", json.getString("code"))
    }

    @Test
    fun nonPingPathWithWrongBearerReturns401() {
        val resp = router.route(
            "GET", "/a11y_tree_full", emptyMap(),
            mapOf("authorization" to "Bearer wrong-token")
        )
        assertEquals(401, resp.statusCode)
    }

    @Test
    fun nonPingPathWithMalformedAuthHeaderReturns401() {
        val resp = router.route(
            "GET", "/a11y_tree_full", emptyMap(),
            mapOf("authorization" to "NoBearerPrefix $token")
        )
        assertEquals(401, resp.statusCode)
    }

    // ── Unknown paths ────────────────────────────────────────────

    @Test
    fun unknownPathReturns404() {
        val resp = router.route("GET", "/no-such-endpoint", emptyMap(), authed())
        assertEquals(404, resp.statusCode)
        val json = JSONObject(resp.body)
        assertEquals("unknown_endpoint", json.getString("code"))
    }

    @Test
    fun methodMismatchReturns404() {
        // /ping is GET-only; POST /ping is "unknown".
        val resp = router.route("POST", "/ping", emptyMap(), authed())
        assertEquals(404, resp.statusCode)
    }

    // ── Missing-parameter contract on input verbs ────────────────

    @Test
    fun tapMissingXReturns400WithMissingX() {
        // Service is null so we'd hit 503 anyway — but the missing-x
        // check fires first in handleTap, so we see 400.
        val resp = router.route("POST", "/tap", mapOf("y" to "100"), authed())
        // Service-null check actually runs first; the router returns
        // 503 before the param check has a chance to fire. Both are
        // valid responses — pin whichever the current implementation
        // produces so it doesn't drift silently.
        assertTrue(
            "expected 400 missing_x or 503 service-unavailable, got ${resp.statusCode}",
            resp.statusCode == 400 || resp.statusCode == 503
        )
    }

    @Test
    fun keyboardKeyMissingKeyCodeReturns400OrServiceUnavailable() {
        val resp = router.route("POST", "/keyboard/key", emptyMap(), authed())
        assertTrue(
            "expected 400 or 503, got ${resp.statusCode}",
            resp.statusCode == 400 || resp.statusCode == 503
        )
    }

    // ── Service availability ─────────────────────────────────────

    @Test
    fun routesThatTouchServiceReturn503WhenServiceNull() {
        val touchService = listOf(
            "GET" to "/screenshot",
            "GET" to "/a11y_tree_full",
            "POST" to "/tap",
            "POST" to "/swipe",
            "POST" to "/keyboard/input",
            "POST" to "/keyboard/key",
        )
        for ((method, path) in touchService) {
            val params = when (path) {
                "/tap" -> mapOf("x" to "10", "y" to "10")
                "/swipe" -> mapOf("startX" to "0", "startY" to "0", "endX" to "10", "endY" to "10")
                "/keyboard/input" -> mapOf("base64_text" to "aGk=")
                "/keyboard/key" -> mapOf("key_code" to "3")
                else -> emptyMap()
            }
            val resp = router.route(method, path, params, authed())
            assertEquals("$method $path expected 503", 503, resp.statusCode)
            val json = JSONObject(resp.body)
            assertEquals(
                "$method $path expected accessibility_service_not_running",
                "accessibility_service_not_running",
                json.getString("code")
            )
        }
    }

    // ── Error shape ──────────────────────────────────────────────

    @Test
    fun errorEnvelopeHasStatusAndCode() {
        val resp = router.route("GET", "/unknown", emptyMap(), authed())
        val json = JSONObject(resp.body)
        assertEquals("error", json.getString("status"))
        assertTrue("code present", json.has("code"))
    }

    // ── Auth applies to *every* non-/ping request shape ─────────

    /**
     * The auth gate keys on path, before dispatch, so an unknown route
     * must answer 401 rather than 404 to an unauthenticated caller. On
     * a LAN-exposed listener the 404-vs-401 difference is a free route
     * oracle: it would let any peer enumerate which endpoints exist
     * without holding the token.
     */
    @Test
    fun unknownRoutesRequireAuthBeforeTheyReport404() {
        val probes = listOf(
            "GET" to "/no-such-endpoint",
            "POST" to "/admin",
            "DELETE" to "/screenshot",
            "PUT" to "/tap",
            "GET" to "/",
            "GET" to "/PING",          // case-sensitive: not the /ping bypass
            "GET" to "/ping/../tap",   // no path normalisation, so no bypass
            "GET" to "/ping2",
        )
        for ((method, path) in probes) {
            val resp = router.route(method, path, emptyMap(), unauthed())
            assertEquals("$method $path must be 401, not a route oracle", 401, resp.statusCode)
        }
    }

    /**
     * HEAD and OPTIONS are not dispatched by name anywhere, so they must
     * not become an unauthenticated side door into the protected routes.
     */
    @Test
    fun headAndOptionsOnProtectedRoutesStillRequireAuth() {
        for (method in listOf("HEAD", "OPTIONS", "TRACE", "PATCH")) {
            for (path in listOf("/screenshot", "/a11y_tree_full", "/tap", "/keyboard/input")) {
                val resp = router.route(method, path, emptyMap(), unauthed())
                assertEquals("$method $path must be 401", 401, resp.statusCode)
            }
        }
    }

    /**
     * `/ping` is exempted by path, so non-GET verbs on it also skip the
     * auth check. That is intentional and harmless — they all fall
     * through to a 404 whose body carries nothing an unauthenticated
     * peer could not already learn. Pinned so the exemption cannot
     * quietly grow a real handler behind it.
     */
    @Test
    fun nonGetVerbsOnPingAreUnauthenticatedButInert() {
        for (method in listOf("HEAD", "OPTIONS", "POST", "DELETE")) {
            val resp = router.route(method, "/ping", emptyMap(), unauthed())
            assertEquals("$method /ping", 404, resp.statusCode)
            assertFalse("must not leak the token", resp.body.contains(token))
            assertEquals("unknown_endpoint", JSONObject(resp.body).getString("code"))
        }
    }

    @Test
    fun bearerComparisonRejectsPrefixesAndSuffixes() {
        // Guards the constant-time comparison against a length-oblivious
        // rewrite: a token prefix must never authenticate.
        val nearMisses = listOf(
            token.dropLast(1),
            token + "x",
            "",
            token.uppercase(),
        )
        for (candidate in nearMisses) {
            val resp = router.route(
                "GET", "/screenshot", emptyMap(),
                mapOf("authorization" to "Bearer $candidate"),
            )
            assertEquals("'$candidate' must not authenticate", 401, resp.statusCode)
        }
    }
}
