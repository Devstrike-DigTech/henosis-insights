package com.devstrike.henosis.insights.internal

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Looper
import android.util.Log
import com.devstrike.henosis.insights.HenosisInsights
import com.devstrike.henosis.insights.InsightsConfig
import java.io.File

internal class AndroidPlatform(
    private val context: Context,
    private val config: InsightsConfig,
) : Platform {
    private val scope = Names.sha256("${config.writeKey}|${config.sdkName}").take(16)

    override val appId: String = context.packageName

    override val appVersion: String? by lazy {
        try {
            Names.version(context.packageManager.getPackageInfo(context.packageName, 0).versionName)
        } catch (_: Throwable) {
            null
        }
    }

    override val osVersion: String? by lazy { Names.version(Build.VERSION.RELEASE) }

    override val deviceClass: String? by lazy {
        try {
            if (context.resources.configuration.smallestScreenWidthDp >= 600) "tablet" else "phone"
        } catch (_: Throwable) {
            null
        }
    }

    override fun isMainThread(): Boolean = Looper.myLooper() == Looper.getMainLooper()

    // Insights never declares ACCESS_NETWORK_STATE (CLAUDE.md rule 4). It reads network state only when
    // the host app already holds that permission, checked on the first line; otherwise it returns null
    // and the 6-hour upload interval applies. Lint cannot see that guard, hence the suppression.
    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    override fun isOnWifi(): Boolean? {
        if (!hasPermission("android.permission.ACCESS_NETWORK_STATE")) return null
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val network = cm.activeNetwork ?: return false
                cm.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ?: false
            } else {
                cm.activeNetworkInfo?.type == ConnectivityManager.TYPE_WIFI
            }
        } catch (_: Throwable) {
            null
        }
    }

    override fun hasPermission(permission: String): Boolean =
        try {
            context.checkPermission(permission, android.os.Process.myPid(), android.os.Process.myUid()) ==
                PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) {
            false
        }

    override fun manifestOptOut(): Boolean {
        return try {
            val info = context.packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
            val meta = info.metaData ?: return false
            if (!meta.containsKey(HenosisInsights.MANIFEST_ENABLED_KEY)) return false
            !meta.getBoolean(HenosisInsights.MANIFEST_ENABLED_KEY, true)
        } catch (_: Throwable) {
            false
        }
    }

    override fun storageDir(): File = File(context.filesDir, "henosis-insights/$scope")

    override val prefs: KeyValueStore = SharedPrefsStore(context, "henosis-insights-$scope")

    override val globalPrefs: KeyValueStore = SharedPrefsStore(context, "henosis-insights")

    override fun log(message: String, throwable: Throwable?) {
        if (!config.debug) return
        if (throwable == null) Log.d(TAG, message) else Log.d(TAG, message, throwable)
    }

    private companion object {
        const val TAG = "HenosisInsights"
    }
}

private class SharedPrefsStore(
    private val context: Context,
    private val name: String,
) : KeyValueStore {
    private val sp by lazy { context.getSharedPreferences(name, Context.MODE_PRIVATE) }

    override fun get(key: String): String? = sp.getString(key, null)

    override fun put(key: String, value: String?) {
        sp.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
    }
}
