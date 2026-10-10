package com.acme.acme_payments_flutter

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.acme.payments.AcmePayments
import com.devstrike.henosis.insights.HenosisInsights
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import java.util.concurrent.Executors

/**
 * Android side of the Flutter wrapper. Forwards the Dart API to the native Acme Payments SDK, and
 * carries the two Henosis Insights bridge calls the Dart client needs (docs/bridge-protocol.md).
 */
class AcmePaymentsFlutterPlugin : FlutterPlugin, MethodCallHandler {
    private lateinit var channel: MethodChannel
    private lateinit var context: Context
    private val sdkThread = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        context = binding.applicationContext
        channel = MethodChannel(binding.binaryMessenger, "acme_payments_flutter")
        channel.setMethodCallHandler(this)
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel.setMethodCallHandler(null)
        sdkThread.shutdown()
    }

    override fun onMethodCall(call: MethodCall, result: Result) {
        when (call.method) {
            "init" -> {
                AcmePayments.init(
                    context,
                    apiKey = call.argument<String>("apiKey") ?: "",
                    insightsWriteKey = call.argument<String>("insightsWriteKey") ?: AcmePayments.DEFAULT_WRITE_KEY,
                    insightsEndpoint = call.argument<String>("insightsEndpoint") ?: "",
                )
                result.success(null)
            }
            // charge() is documented as worker-thread-only, so run it off the main thread.
            "charge" -> onSdkThread(result) {
                AcmePayments.charge(call.argument<Number>("amountMinor")?.toLong() ?: 0, call.argument<String>("currency") ?: "")
            }
            "chargeLegacy" -> onSdkThread(result) {
                @Suppress("DEPRECATION")
                AcmePayments.chargeLegacy(call.argument<Number>("amountMinor")?.toLong() ?: 0)
            }
            "flush" -> {
                AcmePayments.flushTelemetry()
                result.success(null)
            }
            // ---- Henosis Insights bridge ------------------------------------------------------------
            "insightsIsRunning" -> result.success(
                HenosisInsights.bridge.isRunning(call.argument<String>("writeKey") ?: "", call.argument<String>("sdkName") ?: ""),
            )
            "insightsForward" -> {
                HenosisInsights.bridge.receive(call.argument<String>("message") ?: "")
                result.success(null)
            }
            else -> result.notImplemented()
        }
    }

    private fun onSdkThread(result: Result, block: () -> Any?) {
        sdkThread.execute {
            val value = try {
                block()
            } catch (t: Throwable) {
                main.post { result.error("ACME_ERROR", t.javaClass.simpleName, null) }
                return@execute
            }
            main.post { result.success(value) }
        }
    }
}
