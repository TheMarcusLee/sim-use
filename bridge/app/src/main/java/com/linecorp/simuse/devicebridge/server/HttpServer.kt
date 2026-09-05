// SPDX-License-Identifier: Apache-2.0
package com.linecorp.simuse.devicebridge.server

import android.util.Log
import com.linecorp.simuse.devicebridge.util.NetworkAddresses
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Minimal HTTP/1.1 server backed by a raw `ServerSocket`.
 *
 * Replicates csat's `HttpServer` shape (same pool layout, same parser,
 * same status-text table — including 504 which NanoHTTPD's enum can't
 * express natively). Form-urlencoded bodies and `application/json`
 * (for `/gesture`'s `strokes` array) both flow into a single `params`
 * map that the router consumes.
 *
 * Concurrency model lifted from csat:
 *   - One accept-loop thread, never blocked by handlers.
 *   - Fixed handler pool of 4 — the AccessibilityService Binder path
 *     is the real bottleneck, so wider parallelism doesn't help.
 *   - `soTimeout` on the socket so a stuck handler can't pin its
 *     connection indefinitely.
 */
class HttpServer(
    private val port: Int,
    private val router: ActionRouter,
    /**
     * When true the accept socket binds `0.0.0.0` instead of
     * `127.0.0.1`, making the bridge reachable from the LAN. Fork
     * addition for cable-free phone farms; defaults to false so
     * upstream `adb forward` deployments are byte-for-byte unchanged.
     * See `BridgeSettings.bindAllInterfaces`.
     */
    private val bindAll: Boolean = false,
) {
    // Written by `start()` (caller thread), read by `stop()` from an
    // arbitrary Binder thread. Volatile because the bind used to happen
    // on the accept thread: `stop()` could observe a stale null, skip
    // `close()`, and leave the old listener owning the port — so the
    // rebind in `restartServer()` (the `set_bind_all` path) would fail
    // with EADDRINUSE and the bridge would stay on the old address
    // until a reboot. Binding synchronously in `start()` removes the
    // race entirely; the volatile keeps the publication honest.
    @Volatile
    private var serverSocket: ServerSocket? = null
    private val running = AtomicBoolean(false)
    private var executor: ExecutorService? = null
    private var acceptThread: Thread? = null
    private val activeHandlers = AtomicInteger(0)
    private val totalRequests = AtomicInteger(0)

    val isRunning: Boolean get() = running.get()

    /**
     * Binds the listener **synchronously** and only then starts the
     * accept thread, so a caller that returns from `start()` knows the
     * port is actually held (or, on failure, that it is not — `stop()`
     * followed by `start()` in `restartServer()` therefore either
     * rebinds to the new address or leaves `isRunning == false`, never
     * a half-open in-between that `/ping` would report as healthy).
     */
    fun start() {
        if (running.getAndSet(true)) return

        // Default: loopback only. `adb forward tcp:LOCAL tcp:REMOTE`
        // reaches us through 127.0.0.1, so wildcard binding only
        // adds an attack surface: a Wi-Fi-connected device would
        // otherwise expose the bridge to any LAN peer that knows
        // the auth token.
        //
        // `bindAll` opts into exactly that exposure, for phone
        // farms where there is no cable to forward over. Bearer
        // auth stays mandatory on every route except `/ping`
        // (see ActionRouter.route), so the token remains the
        // only credential — put the devices on a trusted VLAN.
        val host = if (bindAll) BIND_ALL_HOST else BIND_LOOPBACK_HOST
        val ss = try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(InetAddress.getByName(host), port))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Server start failed: cannot bind $host:$port", e)
            running.set(false)
            return
        }
        serverSocket = ss

        // Bounded queue. `Executors.newFixedThreadPool` hands out an
        // unbounded `LinkedBlockingQueue`, so on a LAN-exposed listener
        // any peer could queue sockets faster than four handlers drain
        // them and grow the backlog until the process OOMs. Past the
        // bound we answer 503 immediately and close, which is what the
        // farm's retry logic wants anyway.
        val pool = ThreadPoolExecutor(
            HANDLER_POOL_SIZE,
            HANDLER_POOL_SIZE,
            0L,
            TimeUnit.MILLISECONDS,
            LinkedBlockingQueue(MAX_QUEUED_CONNECTIONS),
        )
        executor = pool

        if (bindAll) {
            // Log the dialable address so an operator running
            // `adb logcat -s SimuseHttpServer` (or reading it
            // once over USB during bootstrap) can point the farm
            // at this device without a second tool.
            val lanIp = NetworkAddresses.lanIpv4()
            Log.i(
                TAG,
                "HTTP server listening on $host:$port (LAN mode) " +
                    "reachable at http://${lanIp ?: "<no-lan-ipv4>"}:$port",
            )
        } else {
            Log.i(TAG, "HTTP server listening on $host:$port")
        }

        acceptThread = Thread({ acceptLoop(ss, pool) }, "SimuseHttpServer-accept")
            .also { it.start() }
    }

    private fun acceptLoop(ss: ServerSocket, pool: ThreadPoolExecutor) {
        while (running.get()) {
            try {
                val socket = ss.accept()
                // Arm the read timeout here rather than in the handler:
                // a socket can sit in the pool queue for a while, and
                // until `soTimeout` is set the first read on it would
                // block forever.
                try {
                    socket.soTimeout = SOCKET_TIMEOUT_MS
                } catch (_: Exception) {
                }
                try {
                    pool.execute { handleConnection(socket) }
                } catch (_: RejectedExecutionException) {
                    rejectOverloaded(socket)
                }
            } catch (e: Exception) {
                if (running.get()) Log.w(TAG, "Accept error: ${e.message}")
            }
        }
    }

    /** Backlog full: answer 503 and hang up instead of queueing forever. */
    private fun rejectOverloaded(socket: Socket) {
        Log.w(TAG, "Connection backlog full ($MAX_QUEUED_CONNECTIONS queued) — rejecting with 503")
        try {
            socket.use { writeResponse(it, HttpResponse(503, ActionRouter.errorJson("server_busy"))) }
        } catch (_: Exception) {
        }
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        Log.i(TAG, "stopping (total_requests=${totalRequests.get()}, active=${activeHandlers.get()})")
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        acceptThread?.interrupt()
        acceptThread = null
        // `shutdown()` lets in-flight handlers finish writing
        // their response within `SHUTDOWN_GRACE_MS`; then
        // `shutdownNow()` interrupts anything still running.
        // Without the await, `stop()` followed quickly by a
        // re-`start()` (the SimuseAccessibilityService onUnbind
        // → onServiceConnected cycle) could race the handler
        // pool's worker threads against the new pool's accept
        // queue and lose responses mid-flight.
        executor?.let { pool ->
            pool.shutdown()
            try {
                if (!pool.awaitTermination(SHUTDOWN_GRACE_MS, TimeUnit.MILLISECONDS)) {
                    pool.shutdownNow()
                }
            } catch (_: InterruptedException) {
                pool.shutdownNow()
                Thread.currentThread().interrupt()
            }
        }
        executor = null
    }

    private fun handleConnection(socket: Socket) {
        val reqNum = totalRequests.incrementAndGet()
        val active = activeHandlers.incrementAndGet()
        // Deliberately NOT `socket.use { }` around the parse: `use`
        // closes the socket as the exception unwinds, so the 400/413
        // written from the catch block below used to land on an already
        // closed socket and the client saw a bare connection reset
        // instead of its error envelope. Close once, in the finally.
        try {
            socket.soTimeout = SOCKET_TIMEOUT_MS
            val deadline = System.nanoTime() + REQUEST_READ_DEADLINE_NANOS
            val request = parseRequest(BufferedInputStream(socket.getInputStream()), deadline)
                ?: return
            if (active >= HANDLER_POOL_SIZE) {
                Log.w(TAG, "Handler pool saturated: active=$active/$HANDLER_POOL_SIZE for ${request.method} ${request.path} (#$reqNum)")
            }
            // Exception isolation: `router.route` already wraps every
            // handler in its own try/catch and answers 500, so a single
            // malformed request can never take down the accept loop or
            // poison the next request on this pool thread.
            val response = router.route(
                method = request.method,
                path = request.path,
                params = request.params,
                headers = request.headers,
            )
            writeResponse(socket, response)
        } catch (e: BadRequestException) {
            Log.w(TAG, "Bad request (req #$reqNum): ${e.message}")
            try {
                writeResponse(socket, HttpResponse(e.statusCode, ActionRouter.errorJson(e.code, e.message)))
            } catch (_: Exception) {}
        } catch (e: Exception) {
            Log.w(TAG, "Connection error (req #$reqNum, active=$active): ${e.message}")
        } finally {
            try { socket.close() } catch (_: Exception) {}
            activeHandlers.decrementAndGet()
        }
    }

    // ── HTTP parsing ────────────────────────────────────────────

    /**
     * Byte-level request parser. The previous `BufferedReader`-based
     * version decoded the stream as chars before content-length had
     * been read, which breaks any body containing multi-byte UTF-8 —
     * `Content-Length: 12` (bytes) and `reader.read(charArr, 0, 12)`
     * disagree on what "12" means once the body contains a single
     * emoji (4 bytes / 2 chars or so). This parser reads bytes for
     * the header section (ASCII per HTTP spec) and the body
     * separately, then decodes the body as UTF-8 at the boundary.
     *
     * Also enforces:
     *   * `MAX_HEADER_BYTES` total header size (no slow-loris with
     *     megabyte-sized header floods).
     *   * `MAX_BODY_BYTES` content-length cap (`413 Payload Too
     *     Large` on overflow rather than allocating arbitrary
     *     gigabytes on `Content-Length: 2147483647`).
     *   * A wall-clock `deadline` (nanoTime) for the whole read, so a
     *     slow-loris client cannot hold a handler thread by dribbling
     *     a byte just inside `SOCKET_TIMEOUT_MS` forever. Defaults to
     *     "no deadline" for tests that feed a `ByteArrayInputStream`.
     */
    internal fun parseRequest(input: InputStream, deadline: Long = Long.MAX_VALUE): HttpRequest? {
        val headerBytes = readHeaderBytes(input, deadline) ?: return null
        val headerText = String(headerBytes, Charsets.US_ASCII)
        val lines = headerText.split("\r\n")
        if (lines.isEmpty()) return null

        val requestLine = lines[0]
        val parts = requestLine.split(" ", limit = 3)
        if (parts.size < 2) return null
        val method = parts[0].uppercase()
        val rawPath = parts[1]

        val (path, queryString) = if ("?" in rawPath) {
            val idx = rawPath.indexOf("?")
            rawPath.substring(0, idx) to rawPath.substring(idx + 1)
        } else {
            rawPath to ""
        }

        val headers = mutableMapOf<String, String>()
        for (line in lines.drop(1)) {
            if (line.isEmpty()) continue
            val colon = line.indexOf(":")
            if (colon > 0) {
                val key = line.substring(0, colon).trim().lowercase()
                val value = line.substring(colon + 1).trim()
                headers[key] = value
            }
        }

        // `Transfer-Encoding: chunked` is not supported — we only ever
        // talk to the sim-use CLI and the farm controller, both of which
        // send a Content-Length. Say so with a 411 instead of silently
        // treating the chunked body as absent and answering a confusing
        // `missing_x` 400.
        val transferEncoding = headers["transfer-encoding"]?.lowercase() ?: ""
        if (transferEncoding.contains("chunked")) {
            throw BadRequestException(
                411,
                "chunked_unsupported",
                "Transfer-Encoding: chunked is not supported; send Content-Length",
            )
        }

        val rawContentLength = headers["content-length"]
        val contentLength = if (rawContentLength == null) {
            0
        } else {
            rawContentLength.toIntOrNull()
                ?: throw BadRequestException(400, "bad_content_length", "malformed content-length: $rawContentLength")
        }
        if (contentLength < 0) {
            throw BadRequestException(400, "bad_content_length", "negative content-length: $contentLength")
        }
        if (contentLength > MAX_BODY_BYTES) {
            throw BadRequestException(
                413,
                "body_too_large",
                "content-length $contentLength exceeds $MAX_BODY_BYTES byte cap",
            )
        }
        val body = if (contentLength > 0) {
            val buf = ByteArray(contentLength)
            var read = 0
            while (read < contentLength) {
                if (System.nanoTime() > deadline) {
                    throw BadRequestException(408, "request_timeout", "body read exceeded deadline")
                }
                val n = input.read(buf, read, contentLength - read)
                if (n == -1) break
                read += n
            }
            // A body shorter than its Content-Length used to be parsed
            // as if it were complete, which turns a dropped connection
            // into a puzzling `missing_y` 400 on the farm side. Fail
            // explicitly instead.
            if (read < contentLength) {
                throw BadRequestException(
                    400,
                    "truncated_body",
                    "body ended after $read of $contentLength bytes",
                )
            }
            String(buf, 0, read, Charsets.UTF_8)
        } else {
            ""
        }

        val params = mutableMapOf<String, String>()
        if (queryString.isNotEmpty()) params.putAll(parseFormUrlEncoded(queryString))
        if (body.isNotEmpty()) {
            val contentType = headers["content-type"] ?: ""
            if (contentType.contains("application/json")) {
                // `/gesture` uses JSON body; surface the raw `strokes`
                // array as a string so the router parses it with JSON.
                try {
                    val json = org.json.JSONObject(body)
                    if (json.has("strokes")) {
                        params["strokes"] = json.getJSONArray("strokes").toString()
                    }
                } catch (_: Exception) {
                }
            } else {
                params.putAll(parseFormUrlEncoded(body))
            }
        }

        return HttpRequest(method, path, params, headers)
    }

    /**
     * Reads up to and including the `\r\n\r\n` header terminator,
     * returning all bytes BEFORE the terminator. Returns null on
     * EOF before any data was read. Caps total at
     * `MAX_HEADER_BYTES` and throws `BadRequestException` (413)
     * on overflow so a slow-loris client can't dribble megabytes
     * of header at us.
     */
    private fun readHeaderBytes(input: InputStream, deadline: Long): ByteArray? {
        val out = ByteArrayOutputStream()
        var lastFour = 0  // rolling buffer of the last 4 bytes
        var byte = input.read()
        if (byte == -1) return null
        while (byte != -1) {
            out.write(byte)
            if (out.size() > MAX_HEADER_BYTES) {
                throw BadRequestException(
                    413,
                    "headers_too_large",
                    "request headers exceed $MAX_HEADER_BYTES byte cap",
                )
            }
            // The byte cap alone does not bound *time*: a slow-loris
            // client that sends one byte every 7s stays inside
            // SOCKET_TIMEOUT_MS forever and can pin a handler thread
            // for ~18 hours before hitting the 8 KiB cap. Four such
            // connections would take the whole pool down on a
            // LAN-exposed listener, so bound the wall clock too.
            if (System.nanoTime() > deadline) {
                throw BadRequestException(
                    408,
                    "request_timeout",
                    "header read exceeded ${REQUEST_READ_DEADLINE_NANOS / 1_000_000}ms deadline",
                )
            }
            lastFour = (lastFour shl 8) or (byte and 0xFF)
            // 0x0D0A0D0A == "\r\n\r\n"
            if (lastFour == 0x0D0A0D0A) {
                val bytes = out.toByteArray()
                // Strip the trailing \r\n\r\n that terminates headers.
                return bytes.copyOf(bytes.size - 4)
            }
            byte = input.read()
        }
        // Stream ended before headers terminated — treat the
        // accumulated bytes as headers anyway (lenient parser; the
        // header-line split tolerates missing CRLF).
        return out.toByteArray()
    }

    /**
     * Parse failure that the client should hear about. `code` becomes
     * the `code` field of the standard `{status, code, error}` envelope
     * so the farm can branch on it the same way it does for router
     * errors.
     */
    internal class BadRequestException(
        val statusCode: Int,
        val code: String,
        message: String,
    ) : RuntimeException(message)

    private fun parseFormUrlEncoded(data: String): Map<String, String> =
        Companion.parseFormUrlEncoded(data)

    // ── HTTP response writer ────────────────────────────────────

    private fun writeResponse(socket: Socket, response: HttpResponse) {
        val statusText = HTTP_STATUS_TEXTS[response.statusCode] ?: "Unknown"
        val bodyBytes = response.body.toByteArray(Charsets.UTF_8)

        val header = buildString {
            append("HTTP/1.1 ${response.statusCode} $statusText\r\n")
            append("Content-Type: application/json; charset=utf-8\r\n")
            append("Content-Length: ${bodyBytes.size}\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }

        socket.getOutputStream().apply {
            write(header.toByteArray(Charsets.UTF_8))
            write(bodyBytes)
            flush()
        }
    }

    internal data class HttpRequest(
        val method: String,
        val path: String,
        val params: Map<String, String>,
        val headers: Map<String, String>,
    )

    companion object {
        private const val TAG = "SimuseHttpServer"
        private const val SOCKET_TIMEOUT_MS = 8_000
        private const val HANDLER_POOL_SIZE = 4
        private const val SHUTDOWN_GRACE_MS = 2_000L

        /**
         * Wall-clock budget for reading one complete request (headers
         * + body) off the wire. Independent of `SOCKET_TIMEOUT_MS`,
         * which bounds a single `read()` only. 10s is ~50x what the
         * farm's largest real request (a `keyboard/input` paste) needs
         * on a congested Wi-Fi link.
         */
        internal const val REQUEST_READ_DEADLINE_NANOS = 10_000_000_000L

        /**
         * Sockets allowed to wait for a free handler. Four handlers
         * plus this backlog is already ~3x the concurrency a single
         * farm controller generates against one phone; beyond it the
         * honest answer is 503, not an unbounded queue.
         */
        internal const val MAX_QUEUED_CONNECTIONS = 32

        // Bind hosts. IPv4 literals on purpose: `0.0.0.0` (rather than
        // `::`) keeps the listener on the v4 stack the farm dials, and
        // avoids the dual-stack `::ffff:` address shapes in logs.
        internal const val BIND_LOOPBACK_HOST = "127.0.0.1"
        internal const val BIND_ALL_HOST = "0.0.0.0"

        // 8 KiB is generous for a real HTTP request line + header
        // set (typical sim-use bridge requests fit in <1 KiB).
        // Past that we're either being slow-loris'd or a client
        // is dumping a megabyte-of-headers attack — refuse and
        // free the socket.
        internal const val MAX_HEADER_BYTES = 8 * 1024

        // 4 MiB body cap. Realistic bridge bodies are tap/swipe
        // params (<100 B), `keyboard/input` base64 text (a few KB
        // for normal paste), and `/gesture` stroke arrays (≤10
        // strokes × tiny). A `Content-Length: 2147483647` flood
        // without the cap would have us trying to allocate a 2 GiB
        // CharArray.
        internal const val MAX_BODY_BYTES = 4 * 1024 * 1024

        internal fun parseFormUrlEncoded(data: String): Map<String, String> {
            val result = mutableMapOf<String, String>()
            for (pair in data.split("&")) {
                val eq = pair.indexOf("=")
                if (eq > 0) {
                    val key = URLDecoder.decode(pair.substring(0, eq), "UTF-8")
                    val value = URLDecoder.decode(pair.substring(eq + 1), "UTF-8")
                    result[key] = value
                }
            }
            return result
        }

        private val HTTP_STATUS_TEXTS = mapOf(
            200 to "OK",
            400 to "Bad Request",
            401 to "Unauthorized",
            404 to "Not Found",
            // 408/411/413 are emitted by `parseRequest`; without an
            // entry here they went out as "HTTP/1.1 413 Unknown".
            408 to "Request Timeout",
            411 to "Length Required",
            413 to "Payload Too Large",
            500 to "Internal Server Error",
            503 to "Service Unavailable",
            504 to "Gateway Timeout",
        )
    }
}