// SPDX-License-Identifier: Apache-2.0
package com.linecorp.simuse.devicebridge

import com.linecorp.simuse.devicebridge.server.HttpServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * Byte-level tests for `HttpServer.parseRequest`. The parser was
 * previously char-based (`BufferedReader`) which mis-reads
 * `Content-Length` for any non-ASCII body — content-length is in
 * bytes, and a `CharArray(N)` of N chars consumes a variable
 * number of bytes depending on multi-byte UTF-8 sequences.
 *
 * Pinning the contract here so the parser stays byte-correct
 * across future refactors.
 */
class HttpServerParseRequestTest {

    private val server = HttpServer(port = 0, router = mockRouter())

    private fun mockRouter() = com.linecorp.simuse.devicebridge.server.ActionRouter(
        serviceProvider = { null },
        authManager = mockAuthManager()
    )

    private fun mockAuthManager() = io.mockk.mockk<com.linecorp.simuse.devicebridge.config.AuthManager>(relaxed = true)

    private fun parse(raw: String): HttpServer.HttpRequest? {
        val stream = ByteArrayInputStream(raw.toByteArray(Charsets.UTF_8))
        return server.parseRequest(stream)
    }

    @Test
    fun simpleGetWithoutBody() {
        val req = parse("GET /ping HTTP/1.1\r\nHost: localhost\r\n\r\n")
        assertNotNull(req)
        assertEquals("GET", req!!.method)
        assertEquals("/ping", req.path)
    }

    @Test
    fun postWithFormBody() {
        val body = "x=10&y=20"
        val req = parse(
            "POST /tap HTTP/1.1\r\n" +
                "Content-Type: application/x-www-form-urlencoded\r\n" +
                "Content-Length: ${body.toByteArray(Charsets.UTF_8).size}\r\n" +
                "\r\n" +
                body
        )
        assertNotNull(req)
        assertEquals("10", req!!.params["x"])
        assertEquals("20", req.params["y"])
    }

    /// Regression for the original bug: a body whose char count
    /// differs from its byte count (multi-byte UTF-8). The previous
    /// `BufferedReader` parser would mis-count and truncate the
    /// body or read past it; the byte-level parser must round-trip
    /// the exact bytes.
    @Test
    fun postWithMultibyteUtf8Body() {
        val text = "こんにちは"  // 5 chars, 15 bytes in UTF-8
        val body = "base64_text=${java.net.URLEncoder.encode(text, "UTF-8")}"
        val byteLen = body.toByteArray(Charsets.UTF_8).size
        val req = parse(
            "POST /keyboard/input HTTP/1.1\r\n" +
                "Content-Type: application/x-www-form-urlencoded\r\n" +
                "Content-Length: $byteLen\r\n" +
                "\r\n" +
                body
        )
        assertNotNull(req)
        assertEquals(text, req!!.params["base64_text"])
    }

    @Test
    fun rejectsExcessiveContentLength() {
        // parseRequest throws RuntimeException(BadRequestException)
        // on oversize Content-Length so the handler can surface a
        // 413. Catch and pin the contract.
        try {
            parse(
                "POST /paste HTTP/1.1\r\n" +
                    "Content-Length: ${HttpServer.MAX_BODY_BYTES + 1}\r\n" +
                    "\r\n"
            )
            org.junit.Assert.fail("expected BadRequestException for oversize content-length")
        } catch (e: RuntimeException) {
            assertTrue(
                "expected message to mention byte cap; got: ${e.message}",
                (e.message ?: "").contains("byte cap")
            )
        }
    }

    @Test
    fun rejectsHugeHeaderFlood() {
        val flood = "X-Pad: " + "A".repeat(HttpServer.MAX_HEADER_BYTES + 1) + "\r\n"
        try {
            parse("GET /ping HTTP/1.1\r\n$flood\r\n")
            org.junit.Assert.fail("expected BadRequestException for header flood")
        } catch (e: RuntimeException) {
            assertTrue((e.message ?: "").contains("byte cap"))
        }
    }

    @Test
    fun parsesQueryString() {
        val req = parse("GET /a11y_tree_full?filter=true HTTP/1.1\r\nHost: localhost\r\n\r\n")
        assertNotNull(req)
        assertEquals("/a11y_tree_full", req!!.path)
        assertEquals("true", req.params["filter"])
    }

    // ── Slow-loris / malformed-framing contract (fork hardening) ─

    /**
     * The byte cap alone bounds size, not time: a client that sends one
     * byte just inside `SOCKET_TIMEOUT_MS` can hold a handler thread for
     * hours. `parseRequest` therefore takes a wall-clock deadline. Pass
     * an already-expired one and assert it fires on the very first
     * header byte.
     */
    @Test
    fun headerReadHonoursTheDeadline() {
        val stream = ByteArrayInputStream("GET /ping HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())
        try {
            server.parseRequest(stream, deadline = System.nanoTime() - 1)
            fail("expected the expired deadline to abort the header read")
        } catch (e: HttpServer.BadRequestException) {
            assertEquals(408, e.statusCode)
            assertEquals("request_timeout", e.code)
        }
    }

    /**
     * The body loop is deadline-checked too, not just the header scan:
     * a client that completes its headers and then stalls mid-body must
     * not hold the handler thread either. The stream below hands over
     * the headers instantly, then sleeps past the deadline.
     */
    @Test
    fun deadlineAlsoBoundsTheBodyRead() {
        val head = ("POST /tap HTTP/1.1\r\n" +
            "Content-Type: application/x-www-form-urlencoded\r\n" +
            "Content-Length: 7\r\n\r\n").toByteArray()
        val stalling = object : java.io.InputStream() {
            private var i = 0
            override fun read(): Int {
                if (i < head.size) return head[i++].toInt() and 0xFF
                Thread.sleep(300)  // outlives the 100ms deadline below
                return 'x'.code
            }
        }
        try {
            server.parseRequest(stalling, deadline = System.nanoTime() + 100_000_000L)
            fail("expected the stalled body read to hit the deadline")
        } catch (e: HttpServer.BadRequestException) {
            assertEquals(408, e.statusCode)
            assertEquals("request_timeout", e.code)
        }
    }

    @Test
    fun truncatedBodyIsRejectedRatherThanParsedAsComplete() {
        // A body cut short by a dropped connection used to be parsed as
        // if complete, so the farm saw a puzzling `missing_y` 400
        // instead of a framing error.
        val raw = "POST /tap HTTP/1.1\r\n" +
            "Content-Type: application/x-www-form-urlencoded\r\n" +
            "Content-Length: 40\r\n\r\nx=10&y=2"
        try {
            parse(raw)
            fail("expected a truncated-body rejection")
        } catch (e: HttpServer.BadRequestException) {
            assertEquals(400, e.statusCode)
            assertEquals("truncated_body", e.code)
        }
    }

    @Test
    fun chunkedTransferEncodingIsRefusedExplicitly() {
        // We do not implement chunked decoding. Saying so with a 411 is
        // far easier to debug than silently seeing an empty body.
        try {
            parse(
                "POST /tap HTTP/1.1\r\n" +
                    "Transfer-Encoding: chunked\r\n\r\n"
            )
            fail("expected chunked to be refused")
        } catch (e: HttpServer.BadRequestException) {
            assertEquals(411, e.statusCode)
            assertEquals("chunked_unsupported", e.code)
        }
    }

    @Test
    fun malformedContentLengthIsA400NotASilentZero() {
        try {
            parse("POST /tap HTTP/1.1\r\nContent-Length: banana\r\n\r\n")
            fail("expected a malformed content-length rejection")
        } catch (e: HttpServer.BadRequestException) {
            assertEquals(400, e.statusCode)
            assertEquals("bad_content_length", e.code)
        }
    }

    @Test
    fun oversizeHeadersAndBodiesCarryStructuredCodes() {
        // Regression guard for the hand-rolled `{"status":"error",...}`
        // string these used to be reported with, which carried no `code`
        // the farm could branch on.
        try {
            parse("POST /paste HTTP/1.1\r\nContent-Length: ${HttpServer.MAX_BODY_BYTES + 1}\r\n\r\n")
            fail("expected oversize body rejection")
        } catch (e: HttpServer.BadRequestException) {
            assertEquals(413, e.statusCode)
            assertEquals("body_too_large", e.code)
        }
        try {
            parse("GET /ping HTTP/1.1\r\nX-Pad: " + "A".repeat(HttpServer.MAX_HEADER_BYTES + 1) + "\r\n\r\n")
            fail("expected header flood rejection")
        } catch (e: HttpServer.BadRequestException) {
            assertEquals(413, e.statusCode)
            assertEquals("headers_too_large", e.code)
        }
    }

    /**
     * Every status code this app can put on a status line needs a reason
     * phrase; a missing entry used to emit `HTTP/1.1 413 Unknown`.
     */
    @Test
    fun everyEmittedStatusCodeHasAReasonPhrase() {
        for (code in listOf(200, 400, 401, 404, 408, 411, 413, 500, 503, 504)) {
            assertTrue(
                "status $code has no reason phrase",
                HttpServer.statusText(code) != "Unknown"
            )
        }
    }

    // ── Query-string / form decoding ────────────────────────────

    @Test
    fun percentEncodedQueryParamsAreDecoded() {
        val req = parse("GET /a11y_tree_full?filter=true&note=a%20b%2Fc HTTP/1.1\r\nHost: x\r\n\r\n")
        assertNotNull(req)
        assertEquals("true", req!!.params["filter"])
        assertEquals("a b/c", req.params["note"])
    }

    /**
     * Documents a real footgun for the farm: form-urlencoded decoding
     * maps `+` to a space, and standard Base64 uses `+` in its alphabet.
     * A client that sends a raw Base64 blob as `base64_text=<blob>`
     * without percent-encoding gets a corrupted value (which then fails
     * as `invalid_base64`). Percent-encode it — `%2B` — as
     * `application/x-www-form-urlencoded` requires. Pinned here so the
     * behaviour cannot change silently under the farm.
     */
    @Test
    fun plusInAFormValueDecodesAsSpaceUnlessPercentEncoded() {
        val naive = parse(
            "POST /keyboard/input HTTP/1.1\r\n" +
                "Content-Type: application/x-www-form-urlencoded\r\n" +
                "Content-Length: 20\r\n\r\nbase64_text=aGk+aGk="
        )
        assertEquals("aGk aGk=", naive!!.params["base64_text"])

        val correct = parse(
            "POST /keyboard/input HTTP/1.1\r\n" +
                "Content-Type: application/x-www-form-urlencoded\r\n" +
                "Content-Length: 22\r\n\r\nbase64_text=aGk%2BaGk="
        )
        assertEquals("aGk+aGk=", correct!!.params["base64_text"])
    }
}
