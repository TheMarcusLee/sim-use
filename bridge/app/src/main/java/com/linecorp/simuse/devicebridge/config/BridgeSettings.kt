// SPDX-License-Identifier: Apache-2.0
package com.linecorp.simuse.devicebridge.config

import android.content.Context

/**
 * Persisted operator-tunable bridge settings.
 *
 * Currently a single flag: `bind_all_interfaces`. Upstream sim-use
 * reaches the bridge through `adb forward`, which lands on 127.0.0.1,
 * so loopback-only is the correct (and safest) default and stays the
 * default here. Phone-farm deployments want the opposite: no USB cable
 * attached, the controller talks to the device over Wi-Fi, so the
 * listener has to sit on 0.0.0.0.
 *
 * Stored in the same SharedPreferences file as [AuthManager] so a
 * single `pm clear` resets both the token and the bind mode — an
 * operator who wipes the app gets upstream behaviour back.
 *
 * The flag is written only from [com.linecorp.simuse.devicebridge.service.SimuseContentProvider],
 * which is gated to the `adb shell` / root UID. There is no in-app UI
 * (the bridge ships no Activity), so adb is the only path.
 */
class BridgeSettings(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * True when the HTTP listener should bind `0.0.0.0` instead of
     * `127.0.0.1`. Defaults to false = upstream loopback behaviour.
     */
    var bindAllInterfaces: Boolean
        get() = prefs.getBoolean(KEY_BIND_ALL, false)
        set(value) {
            // `commit()` rather than `apply()`: the caller is an adb
            // `content call` that returns to a shell script which may
            // immediately kill/restart the app or read the value back.
            // We want the write durable before we answer.
            prefs.edit().putBoolean(KEY_BIND_ALL, value).commit()
        }

    companion object {
        // Shared with AuthManager on purpose — see class docs.
        internal const val PREFS_NAME = "sim_use_device_bridge"
        internal const val KEY_BIND_ALL = "bind_all_interfaces"

        /**
         * Parses the string an operator typed into
         * `adb shell content call ... --arg <value>`.
         *
         * Deliberately strict-ish: we accept the handful of spellings a
         * human or shell script realistically produces, and return null
         * for anything else so the provider can answer with an explicit
         * error rather than silently defaulting to "false" (which would
         * look like the toggle worked while the bridge stayed on
         * loopback). A null/absent arg is also an error — `set_bind_all`
         * with no value is a typo, not a request to disable.
         */
        fun parseBooleanArg(raw: String?): Boolean? = when (raw?.trim()?.lowercase()) {
            "true", "1", "yes", "y", "on", "enable", "enabled" -> true
            "false", "0", "no", "n", "off", "disable", "disabled" -> false
            else -> null
        }
    }
}
