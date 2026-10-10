package com.devstrike.henosis.insights.internal

import java.io.File

/**
 * Everything the engine needs from the host platform, behind an interface so the engine itself is
 * plain Kotlin and testable without Android. [AndroidPlatform] is the real implementation.
 */
internal interface Platform {
    /** Bundle id / package name of the host app. */
    val appId: String
    val appVersion: String?
    val osVersion: String?
    /** `phone` or `tablet`; null when unknown. */
    val deviceClass: String?

    fun isMainThread(): Boolean

    /** true/false when it can be determined, null when the host app lacks ACCESS_NETWORK_STATE. */
    fun isOnWifi(): Boolean?

    fun hasPermission(permission: String): Boolean

    /** Host-app opt-out via manifest meta-data. */
    fun manifestOptOut(): Boolean

    /** Private directory for this SDK's queue and state. */
    fun storageDir(): File

    /** Small key-value store (SharedPreferences on Android), scoped per write key + sdk. */
    val prefs: KeyValueStore

    /** App-wide store shared by every Insights-enabled SDK (holds the host opt-out). */
    val globalPrefs: KeyValueStore

    fun log(message: String, throwable: Throwable?)
}

internal interface KeyValueStore {
    fun get(key: String): String?
    fun put(key: String, value: String?)
}

internal fun interface Clock {
    fun now(): Long
}

internal object SystemClock : Clock {
    override fun now(): Long = System.currentTimeMillis()
}
