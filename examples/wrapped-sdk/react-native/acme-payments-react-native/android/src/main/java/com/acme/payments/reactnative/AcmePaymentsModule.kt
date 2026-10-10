package com.acme.payments.reactnative

import com.acme.payments.AcmePayments
import com.devstrike.henosis.insights.HenosisInsights
import com.devstrike.henosis.insights.InsightsConfig
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.module.annotations.ReactModule
import java.util.concurrent.Executors

/**
 * TurboModule: forwards the JS API to the native Acme Payments SDK, and carries the two Henosis
 * Insights bridge calls the TypeScript client needs (docs/bridge-protocol.md).
 */
@ReactModule(name = AcmePaymentsModule.NAME)
class AcmePaymentsModule(context: ReactApplicationContext) : NativeAcmePaymentsSpec(context) {
    private val sdkThread = Executors.newSingleThreadExecutor()

    override fun getName(): String = NAME

    override fun init(apiKey: String, insightsWriteKey: String, insightsEndpoint: String, promise: Promise) {
        AcmePayments.init(
            reactApplicationContext,
            apiKey = apiKey,
            insightsWriteKey = insightsWriteKey,
            insightsEndpoint = insightsEndpoint.ifEmpty { InsightsConfig.DEFAULT_ENDPOINT },
        )
        promise.resolve(null)
    }

    // charge() is documented as worker-thread-only.
    override fun charge(amountMinor: Double, currency: String, promise: Promise) {
        sdkThread.execute { promise.resolve(AcmePayments.charge(amountMinor.toLong(), currency)) }
    }

    override fun chargeLegacy(amountMinor: Double, promise: Promise) {
        sdkThread.execute {
            @Suppress("DEPRECATION")
            promise.resolve(AcmePayments.chargeLegacy(amountMinor.toLong()))
        }
    }

    override fun flush() {
        AcmePayments.flushTelemetry()
    }

    // ---- Henosis Insights bridge ----------------------------------------------------------------

    override fun insightsIsRunning(writeKey: String, sdkName: String): Boolean =
        HenosisInsights.bridge.isRunning(writeKey, sdkName)

    override fun insightsForward(message: String) {
        HenosisInsights.bridge.receive(message)
    }

    override fun invalidate() {
        sdkThread.shutdown()
        super.invalidate()
    }

    companion object {
        const val NAME = "AcmePayments"
    }
}
