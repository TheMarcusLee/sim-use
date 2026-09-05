// SPDX-License-Identifier: Apache-2.0
package com.linecorp.simuse.devicebridge.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.linecorp.simuse.devicebridge.config.AuthManager
import com.linecorp.simuse.devicebridge.config.BridgeSettings
import com.linecorp.simuse.devicebridge.server.ActionRouter
import com.linecorp.simuse.devicebridge.server.HttpServer
import com.linecorp.simuse.devicebridge.util.NetworkAddresses

/**
 * sim-use bridge accessibility service.
 *
 * Owns:
 *   - the HTTP server lifecycle (started on `onServiceConnected`,
 *     stopped on `onUnbind` / `onDestroy`)
 *   - the foreground keep-alive child service that prevents Samsung et
 *     al from killing the process
 *   - a static `instance` reference so the ContentProvider can start /
 *     stop the server without an Activity
 *
 * Direct copy of csat's `CsatAccessibilityService` shape, just renamed.
 */
class SimuseAccessibilityService : AccessibilityService() {

    // `@Volatile` so writes from `onServiceConnected` / `onUnbind`
    // are immediately visible to ContentProvider callers (which
    // dereference `instance.startServer()` / `.stopServer()` from
    // arbitrary Binder threads). Without volatile a stale-cached
    // `httpServer` reference could survive a stop-then-start
    // cycle on the wrong thread.
    @Volatile
    private var httpServer: HttpServer? = null
    @Volatile
    private var router: ActionRouter? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        // `startServer()` starts the keep-alive service itself, so the
        // locks always match the mode the listener actually came up in.
        startServer()
        Log.i(TAG, "onServiceConnected (pid=${android.os.Process.myPid()})")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // No event-driven state. We pull rootInActiveWindow on demand.
    }

    override fun onInterrupt() {
        Log.w(TAG, "onInterrupt")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.w(TAG, "onUnbind (pid=${android.os.Process.myPid()})")
        stopServer()
        stopKeepAliveService()
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        Log.w(TAG, "onDestroy (pid=${android.os.Process.myPid()})")
        stopServer()
        instance = null
        super.onDestroy()
    }

    fun startServer() {
        if (httpServer?.isRunning == true) return
        val authManager = AuthManager(this)
        val settings = BridgeSettings(this)
        // Snapshot the flag at bind time: the listener's bind address is
        // fixed for the life of the ServerSocket, so a mid-flight
        // preference change must go through `restartServer()` (which the
        // ContentProvider calls) rather than being re-read per request.
        val bindAll = settings.bindAllInterfaces
        // Provisional: corrected below once we know the bind succeeded.
        boundAllInterfaces = bindAll
        val newRouter = ActionRouter(
            serviceProvider = { instance },
            authManager = authManager,
            bindAllProvider = { boundAllInterfaces },
        )
        router = newRouter
        val server = HttpServer(SERVER_PORT, newRouter, bindAll = bindAll).also { it.start() }
        httpServer = server
        // `start()` binds synchronously, so a false `isRunning` here
        // means the bind failed (port held, permission). Report that on
        // `/ping` rather than claiming LAN reachability the farm will
        // then fail to use.
        boundAllInterfaces = bindAll && server.isRunning
        if (!server.isRunning) {
            Log.e(TAG, "HTTP server failed to bind port $SERVER_PORT (bindAll=$bindAll)")
        }
        // Re-issue the keep-alive start command on every listener start,
        // not just on `onServiceConnected`. `restartServer()` (the
        // `set_bind_all` path) goes through here, and the keep-alive
        // service re-reads the flag in `onStartCommand` — without this
        // call, toggling LAN mode ON never acquires the wake/Wi-Fi locks
        // on an already-connected service (the farm's normal bootstrap
        // order), and toggling it OFF leaves a PARTIAL_WAKE_LOCK held
        // for the life of the process.
        startKeepAliveService()
        if (boundAllInterfaces) {
            Log.i(
                TAG,
                "HTTP server started on 0.0.0.0:$SERVER_PORT — point the farm at " +
                    "http://${NetworkAddresses.lanIpv4() ?: "<no-lan-ipv4>"}:$SERVER_PORT",
            )
        } else {
            Log.i(TAG, "HTTP server started on 127.0.0.1:$SERVER_PORT")
        }
    }

    /**
     * Stops and re-starts the listener so a changed bind mode takes
     * effect immediately. Called from the ContentProvider after
     * `set_bind_all`; a no-op start when the service is not connected
     * is fine because `onServiceConnected` will start with the newly
     * persisted flag anyway.
     */
    fun restartServer() {
        stopServer()
        startServer()
    }

    fun stopServer() {
        httpServer?.stop()
        httpServer = null
        boundAllInterfaces = false
        // Shut down the router's `rootWindowExecutor` (cached
        // thread pool) so service-unbind doesn't leave a forest
        // of "AwaitedTreeWorker" threads against a dead service
        // instance. Done after `httpServer.stop()` so any in-
        // flight handlers have already returned.
        router?.close()
        router = null
        Log.i(TAG, "HTTP server stopped")
    }

    val isServerRunning: Boolean get() = httpServer?.isRunning == true

    /**
     * Bind mode of the *currently running* listener, which can lag the
     * persisted preference between a `set_bind_all` write and the
     * restart. Reported on `/ping` so the farm sees reality, not intent.
     */
    @Volatile
    var boundAllInterfaces: Boolean = false
        private set

    private fun startKeepAliveService() {
        try {
            val intent = Intent(this, BridgeKeepAliveService::class.java)
            startForegroundService(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to start keep-alive service: ${e.message}")
        }
    }

    private fun stopKeepAliveService() {
        try {
            stopService(Intent(this, BridgeKeepAliveService::class.java))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to stop keep-alive service: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "SimuseA11yService"
        const val SERVER_PORT = 8080

        @Volatile
        var instance: SimuseAccessibilityService? = null
            private set
    }
}