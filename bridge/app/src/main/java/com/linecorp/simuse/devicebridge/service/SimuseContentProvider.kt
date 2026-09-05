// SPDX-License-Identifier: Apache-2.0
package com.linecorp.simuse.devicebridge.service

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import com.linecorp.simuse.devicebridge.config.AuthManager
import com.linecorp.simuse.devicebridge.config.BridgeSettings
import com.linecorp.simuse.devicebridge.util.NetworkAddresses
import org.json.JSONObject

/**
 * Surfaces auth-token retrieval and server-toggle to `adb shell content
 * query / insert` so `sim-use android init` can bootstrap without an
 * Activity.
 *
 * URIs:
 *   - `content://com.linecorp.simuse.devicebridge/auth_token`         query
 *   - `content://com.linecorp.simuse.devicebridge/toggle_socket_server` insert with `enabled:b:true|false`
 *   - `content://com.linecorp.simuse.devicebridge/bridge_status`      query (fork addition)
 *
 * `call` methods (fork addition — `adb shell content call --uri
 * content://com.linecorp.simuse.devicebridge --method <m> [--arg <v>]`):
 *   - `set_bind_all` with `--arg true|false` — persist the LAN-listen
 *     flag and restart the listener so it takes effect at once.
 *   - `get_bind_all` — read the persisted flag back.
 *   - `status` — persisted flag, live bind mode, port and LAN IPv4, so
 *     one adb round-trip over USB tells the operator the exact
 *     `http://<ip>:<port>` to configure.
 *
 * `call` is used rather than another `insert` URI because `content
 * call` prints the returned Bundle to stdout, which is what makes a
 * one-shot USB bootstrap script readable; `content insert` returns
 * nothing.
 *
 * Same shape and same query-cursor encoding as csat's
 * `CsatContentProvider`. The returned token row format
 * (`result={"status":"success","result":"<uuid>"}`) is what the Swift
 * `AuthTokenFetcher` already expects, so no client change is needed.
 *
 * Access is gated to `adb shell` / root only by [assertShellCaller]. The
 * provider is `exported="true"` in the manifest because adb-shell calls
 * arrive as cross-process IPC; signature-permission would also block adb
 * shell (the shell UID does not share our app signature). The UID check
 * is what keeps installed apps from harvesting the bearer token.
 */
class SimuseContentProvider : ContentProvider() {

    private val authManager by lazy { AuthManager(context!!) }
    private val settings by lazy { BridgeSettings(context!!) }

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        assertShellCaller()
        val json = when (uri.lastPathSegment) {
            PATH_AUTH_TOKEN -> JSONObject().apply {
                put("status", "success")
                put("result", authManager.getOrCreateToken())
            }
            // Same cursor encoding as auth_token so the existing Swift
            // `AuthTokenFetcher`-style parser shape is reusable.
            PATH_BRIDGE_STATUS -> JSONObject().apply {
                put("status", "success")
                put("result", statusJson())
            }
            else -> return null
        }.toString()
        return MatrixCursor(arrayOf(COLUMN_RESULT)).apply {
            addRow(arrayOf(json))
        }
    }

    /**
     * Handles `adb shell content call`. Every branch is behind the same
     * shell/root UID guard as `query`/`insert` — an installed app must
     * not be able to flip the bridge onto the LAN.
     */
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        assertShellCaller()
        return when (method) {
            METHOD_SET_BIND_ALL -> {
                val parsed = BridgeSettings.parseBooleanArg(arg)
                    ?: return errorBundle(
                        "invalid_arg",
                        "expected --arg true|false, got: ${arg ?: "<missing>"}",
                    )
                settings.bindAllInterfaces = parsed
                // Rebind now rather than waiting for the next a11y
                // service connect, so the operator's very next request
                // over Wi-Fi succeeds. Null instance = service not
                // enabled yet; the flag is persisted and will be picked
                // up by `onServiceConnected`.
                SimuseAccessibilityService.instance?.restartServer()
                successBundle(statusJson())
            }
            METHOD_GET_BIND_ALL -> successBundle(settings.bindAllInterfaces)
            METHOD_STATUS -> successBundle(statusJson())
            else -> errorBundle("unknown_method", method)
        }
    }

    /**
     * Descriptive snapshot for operators. Carries no auth material —
     * the token is only ever handed out through the `auth_token` query.
     */
    private fun statusJson(): JSONObject {
        val service = SimuseAccessibilityService.instance
        return buildStatusJson(
            bindAll = settings.bindAllInterfaces,
            boundAll = service?.boundAllInterfaces ?: false,
            serverRunning = service?.isServerRunning ?: false,
            accessibilityServiceConnected = service != null,
            port = SimuseAccessibilityService.SERVER_PORT,
            lanIpv4 = NetworkAddresses.lanIpv4(),
        )
    }

    private fun successBundle(result: Any?): Bundle = Bundle().apply {
        putString(
            COLUMN_RESULT,
            JSONObject().apply {
                put("status", "success")
                put("result", result)
            }.toString(),
        )
    }

    private fun errorBundle(code: String, message: String): Bundle = Bundle().apply {
        putString(
            COLUMN_RESULT,
            JSONObject().apply {
                put("status", "error")
                put("code", code)
                put("error", message)
            }.toString(),
        )
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        assertShellCaller()
        if (uri.lastPathSegment != PATH_TOGGLE_SERVER) return null
        val enabled = values?.getAsBoolean("enabled") ?: true
        val service = SimuseAccessibilityService.instance
        if (enabled) service?.startServer() else service?.stopServer()
        return uri
    }

    override fun getType(uri: Uri): String? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    /**
     * Rejects any caller other than `adb shell` (UID 2000) or root (UID 0).
     * Without this guard, any installed app can `ContentResolver.query` the
     * auth_token URI and obtain the bearer token used to talk to the
     * on-device HTTP bridge.
     */
    private fun assertShellCaller() {
        val uid = Binder.getCallingUid()
        if (uid != Process.SHELL_UID && uid != Process.ROOT_UID) {
            throw SecurityException(
                "SimuseContentProvider is reachable only from adb shell (uid=$uid)"
            )
        }
    }

    companion object {
        private const val PATH_AUTH_TOKEN = "auth_token"
        private const val PATH_TOGGLE_SERVER = "toggle_socket_server"
        private const val PATH_BRIDGE_STATUS = "bridge_status"
        private const val COLUMN_RESULT = "result"

        internal const val METHOD_SET_BIND_ALL = "set_bind_all"
        internal const val METHOD_GET_BIND_ALL = "get_bind_all"
        internal const val METHOD_STATUS = "status"

        /**
         * Pure builder for the operator status payload. Split out from
         * the instance method so a JVM test can assert the exact field
         * set — in particular that no auth material ever creeps in.
         * The bearer token is handed out by exactly one path, the
         * `auth_token` query, and must stay that way.
         */
        internal fun buildStatusJson(
            bindAll: Boolean,
            boundAll: Boolean,
            serverRunning: Boolean,
            accessibilityServiceConnected: Boolean,
            port: Int,
            lanIpv4: String?,
        ): JSONObject = JSONObject().apply {
            // What is persisted…
            put("bind_all", bindAll)
            // …versus what the live listener is actually bound to.
            put("bound_all", boundAll)
            put("server_running", serverRunning)
            put("accessibility_service_connected", accessibilityServiceConnected)
            put("port", port)
            put("lan_ipv4", lanIpv4 ?: JSONObject.NULL)
        }
    }
}