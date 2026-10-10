package com.acme.payments

import android.content.Context
import com.devstrike.henosis.insights.HealthCheck
import com.devstrike.henosis.insights.HenosisInsights
import com.devstrike.henosis.insights.InsightsClient
import com.devstrike.henosis.insights.InsightsConfig
import java.security.MessageDigest

/**
 * Sample vendor SDK. Shows the three things a vendor adds: one `start` in init, a `countCall` per
 * public method, and `reportError` / `check` where the SDK already detects problems.
 */
object AcmePayments {
    const val VERSION = "4.2.0"

    // Replace with the write key from the Insights dashboard.
    const val DEFAULT_WRITE_KEY = "hi_test_0123456789abcdef0123456789abcdef"

    private var insights: InsightsClient? = null
    private var apiKey: String? = null

    @JvmStatic
    @JvmOverloads
    fun init(
        context: Context,
        apiKey: String,
        insightsWriteKey: String = DEFAULT_WRITE_KEY,
        insightsEndpoint: String = InsightsConfig.DEFAULT_ENDPOINT,
    ) {
        this.apiKey = apiKey
        insights = HenosisInsights.start(
            context,
            InsightsConfig(
                writeKey = insightsWriteKey,
                sdkName = "acme-payments",
                sdkVersion = VERSION,
                // Never the raw customer key: a hash identifies the customer without exposing the key.
                customerKey = sha256(apiKey),
                sdkPackagePrefixes = listOf("com.acme.payments."),
                endpoint = insightsEndpoint,
            ),
        )
    }

    /** A public SDK method. Must not be called on the main thread. */
    @JvmStatic
    fun charge(amountMinor: Long, currency: String): String {
        val client = insights
        client?.countCall("charge")
        client?.checkWorkerThread("charge")
        client?.check(HealthCheck.InitBeforeUse(ok = apiKey != null))
        return try {
            Gateway.post(amountMinor, currency)
        } catch (e: IllegalStateException) {
            client?.reportError("charge", code = "CARD_DECLINED_CONFIG", throwable = e)
            "declined"
        }
    }

    @Deprecated("Use charge()", ReplaceWith("charge(amountMinor, currency)"))
    @JvmStatic
    fun chargeLegacy(amountMinor: Long): String {
        insights?.markDeprecated("chargeLegacy")
        return charge(amountMinor, "NGN")
    }

    /** Sends whatever is queued now (sample/testing convenience). */
    @JvmStatic
    fun flushTelemetry() {
        insights?.flush()
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}

internal object Gateway {
    fun post(amountMinor: Long, currency: String): String {
        check(amountMinor > 0) { "amount must be positive for $currency" }
        return "ok"
    }
}
