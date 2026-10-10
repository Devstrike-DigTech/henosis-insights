package com.devstrike.henosis.insights.internal

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

class HttpTransportTest {
    private lateinit var server: HttpServer
    private val status = AtomicInteger(202)
    private var lastBody = ""
    private var lastAuth: String? = null
    private var lastType: String? = null

    @BeforeEach
    fun up() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/batches") { ex ->
            lastBody = ex.requestBody.readBytes().toString(Charsets.UTF_8)
            lastAuth = ex.requestHeaders.getFirst("Authorization")
            lastType = ex.requestHeaders.getFirst("Content-Type")
            val reply = """{"ok":true}""".toByteArray()
            ex.sendResponseHeaders(status.get(), reply.size.toLong())
            ex.responseBody.use { it.write(reply) }
        }
        server.start()
    }

    @AfterEach
    fun down() = server.stop(0)

    private fun url() = "http://127.0.0.1:${server.address.port}/v1/batches"

    @Test
    fun `posts JSON with the write key as a bearer token`() {
        val result = HttpTransport().send(url(), TEST_WRITE_KEY, """{"schema":1}""".toByteArray())
        assertEquals(SendResult.Accepted, result)
        assertEquals("""{"schema":1}""", lastBody)
        assertEquals("Bearer $TEST_WRITE_KEY", lastAuth)
        assertEquals("application/json", lastType)
    }

    @Test
    fun `classifies responses`() {
        status.set(400)
        assertEquals(SendResult.Rejected(400), HttpTransport().send(url(), TEST_WRITE_KEY, "{}".toByteArray()))
        status.set(401)
        assertEquals(SendResult.Rejected(401), HttpTransport().send(url(), TEST_WRITE_KEY, "{}".toByteArray()))
        status.set(429)
        assertEquals(SendResult.Retry(429), HttpTransport().send(url(), TEST_WRITE_KEY, "{}".toByteArray()))
        status.set(503)
        assertEquals(SendResult.Retry(503), HttpTransport().send(url(), TEST_WRITE_KEY, "{}".toByteArray()))
    }

    @Test
    fun `a refused connection is retryable, not thrown`() {
        val port = server.address.port
        server.stop(0)
        val result = HttpTransport(connectTimeoutMs = 500, readTimeoutMs = 500).send("http://127.0.0.1:$port/v1/batches", TEST_WRITE_KEY, "{}".toByteArray())
        assertTrue(result is SendResult.Retry, result.toString())
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).also { it.start() }
    }
}
