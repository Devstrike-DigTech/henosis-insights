package com.devstrike.henosis.insights.internal

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

internal sealed class SendResult {
    /** 2xx: the ingest accepted the batch. */
    object Accepted : SendResult()

    /** 4xx other than 408/429: the batch itself is bad; retrying will not help. */
    data class Rejected(val status: Int) : SendResult()

    /** 408, 429, 5xx or a network failure: keep the events and try later. */
    data class Retry(val status: Int?, val cause: Throwable? = null) : SendResult()
}

internal interface Transport {
    fun send(endpoint: String, writeKey: String, body: ByteArray): SendResult
}

/** `HttpURLConnection` only. No third-party HTTP client, no connection pooling to configure. */
internal class HttpTransport(
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 15_000,
) : Transport {
    override fun send(endpoint: String, writeKey: String, body: ByteArray): SendResult {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                doOutput = true
                useCaches = false
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer $writeKey")
                setRequestProperty("User-Agent", "henosis-insights-android")
                setFixedLengthStreamingMode(body.size)
            }
            connection.outputStream.use { it.write(body) }
            val status = connection.responseCode
            // Drain so the connection can be reused by the platform.
            try {
                (if (status >= 400) connection.errorStream else connection.inputStream)?.use { it.skip(Long.MAX_VALUE) }
            } catch (_: IOException) {
            }
            classify(status)
        } catch (t: Throwable) {
            SendResult.Retry(null, t)
        } finally {
            connection?.disconnect()
        }
    }

    companion object {
        fun classify(status: Int): SendResult = when {
            status in 200..299 -> SendResult.Accepted
            status == 408 || status == 429 || status >= 500 -> SendResult.Retry(status)
            status >= 400 -> SendResult.Rejected(status)
            else -> SendResult.Retry(status)
        }
    }
}
