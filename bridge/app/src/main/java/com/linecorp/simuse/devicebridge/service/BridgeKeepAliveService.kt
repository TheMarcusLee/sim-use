// SPDX-License-Identifier: Apache-2.0
package com.linecorp.simuse.devicebridge.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import com.linecorp.simuse.devicebridge.config.BridgeSettings
import com.linecorp.simuse.devicebridge.util.NetworkAddresses

/**
 * Foreground service that keeps the bridge process alive.
 *
 * Background: without a foreground notification, the OS — especially
 * Samsung One UI — aggressively kills accessibility service processes,
 * causing them to unbind and eventually become disabled. Raising the
 * process to FOREGROUND_SERVICE priority via this stub blocks that
 * behavior.
 *
 * Lifted from csat's `BridgeKeepAliveService` (csat learned this the
 * hard way on real devices).
 *
 * Fork addition — cable-free operation. With `adb forward` there is a
 * USB cable, which means the device is charging and `adb` itself keeps
 * the link warm. A Wi-Fi phone farm has neither: once the screen goes
 * off, Doze parks the CPU and the Wi-Fi driver drops into a power-save
 * mode that delays or discards inbound packets to a listening socket.
 * So when (and only when) LAN mode is enabled we additionally hold:
 *
 *   - a `PARTIAL_WAKE_LOCK`, so the accept loop and the handler pool
 *     keep getting scheduled with the screen off;
 *   - a Wi-Fi lock (`WIFI_MODE_FULL_LOW_LATENCY` where available,
 *     `WIFI_MODE_FULL_HIGH_PERF` otherwise), so the radio does not
 *     enter power-save and stall inbound connections.
 *
 * Both are skipped in the default loopback mode, so upstream USB
 * deployments keep exactly their current battery behaviour.
 *
 * LAN mode additionally registers a default-network callback that logs
 * the device's LAN IPv4 whenever it changes — see
 * [registerAddressWatcher].
 */
class BridgeKeepAliveService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var lastLoggedIpv4: String? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
        // Re-read on every start command:
        // `SimuseAccessibilityService.startServer()` re-issues
        // `startForegroundService` on every listener start — including
        // the `restartServer()` that a `set_bind_all` toggle triggers —
        // so the locks follow the current mode without a reboot.
        //
        // `intent` is null when the OS restarts us under START_STICKY;
        // re-reading the persisted flag (rather than trusting an extra)
        // is what makes that path correct too.
        if (BridgeSettings(this).bindAllInterfaces) {
            acquireLanLocks()
            registerAddressWatcher()
        } else {
            releaseLanLocks()
            unregisterAddressWatcher()
        }
        Log.i(TAG, "Keep-alive foreground service started")
        return START_STICKY
    }

    /**
     * Idempotent: `isHeld` guards mean repeated `onStartCommand`s do not
     * stack reference counts (both locks are created non-reference-
     * counted anyway, but the guard keeps the logs honest).
     */
    private fun acquireLanLocks() {
        try {
            if (wakeLock == null) {
                wakeLock = getSystemService(PowerManager::class.java)
                    ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
                    ?.apply { setReferenceCounted(false) }
            }
            wakeLock?.takeIf { !it.isHeld }?.acquire()

            if (wifiLock == null) {
                // LOW_LATENCY is the modern replacement for the
                // deprecated HIGH_PERF and additionally disables power
                // save; it only takes effect while the screen is on for
                // the *foreground* app, so we keep HIGH_PERF semantics
                // as the floor on anything older than API 29.
                val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                } else {
                    @Suppress("DEPRECATION")
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF
                }
                wifiLock = getSystemService(WifiManager::class.java)
                    ?.createWifiLock(mode, WIFI_LOCK_TAG)
                    ?.apply { setReferenceCounted(false) }
            }
            wifiLock?.takeIf { !it.isHeld }?.acquire()
            Log.i(TAG, "LAN mode: wake lock + Wi-Fi lock held")
        } catch (e: Exception) {
            // Never let a lock failure take down the keep-alive service:
            // a bridge that works only while the screen is on still
            // beats no bridge at all.
            Log.w(TAG, "Failed to acquire LAN locks: ${e.message}")
        }
    }

    /**
     * Logs the device's LAN IPv4 whenever the network underneath the
     * bridge changes.
     *
     * Nothing in the bridge advertises its address, so a phone whose
     * DHCP lease moves silently drops off the farm — the controller
     * just starts seeing connection refusals against a stale
     * `bridgeUrl`. We cannot fix that from the device side, but we can
     * leave a timestamped breadcrumb in logcat so the operator who
     * eventually plugs a cable in can see exactly when and to what the
     * address changed. Only registered in LAN mode; loopback
     * deployments keep upstream's behaviour of touching no networking
     * APIs at all.
     */
    private fun registerAddressWatcher() {
        if (networkCallback != null) return
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = report("available")
            override fun onLost(network: Network) = report("lost")
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) =
                report("capabilities-changed")

            private fun report(reason: String) {
                val current = NetworkAddresses.lanIpv4()
                if (current == lastLoggedIpv4) return
                Log.w(
                    TAG,
                    "LAN address changed ($reason): ${lastLoggedIpv4 ?: "<none>"} -> " +
                        "${current ?: "<none>"} — the farm's bridgeUrl for this device " +
                        "is now stale unless it has a DHCP reservation",
                )
                lastLoggedIpv4 = current
            }
        }
        try {
            cm.registerDefaultNetworkCallback(callback)
            networkCallback = callback
            lastLoggedIpv4 = NetworkAddresses.lanIpv4()
            Log.i(TAG, "Watching for LAN address changes (current=${lastLoggedIpv4 ?: "<none>"})")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register network callback: ${e.message}")
        }
    }

    private fun unregisterAddressWatcher() {
        val callback = networkCallback ?: return
        networkCallback = null
        try {
            getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(callback)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to unregister network callback: ${e.message}")
        }
    }

    private fun releaseLanLocks() {
        try {
            wakeLock?.takeIf { it.isHeld }?.release()
            wifiLock?.takeIf { it.isHeld }?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to release LAN locks: ${e.message}")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        unregisterAddressWatcher()
        releaseLanLocks()
        Log.i(TAG, "Keep-alive foreground service destroyed")
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "sim-use bridge",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Keeps sim-use accessibility service alive"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("sim-use bridge")
            .setContentText("Accessibility service active")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .build()

    companion object {
        private const val TAG = "SimuseKeepAlive"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "sim_use_bridge_keepalive"
        private const val WAKE_LOCK_TAG = "simuse:bridge-lan"
        private const val WIFI_LOCK_TAG = "simuse:bridge-lan-wifi"
    }
}