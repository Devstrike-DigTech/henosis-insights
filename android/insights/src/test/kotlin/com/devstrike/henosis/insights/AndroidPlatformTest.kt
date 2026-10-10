package com.devstrike.henosis.insights

import android.app.Application
import android.os.Bundle
import com.devstrike.henosis.insights.internal.AndroidPlatform
import com.devstrike.henosis.insights.internal.testConfig
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import tech.apter.junit.jupiter.robolectric.RobolectricExtension

@ExtendWith(RobolectricExtension::class)
class AndroidPlatformTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()

    @AfterEach
    fun reset() = HenosisInsights.resetForTests()

    @Test
    fun `reports app id, os version and device class without identifiers`() {
        val p = AndroidPlatform(app, testConfig())
        assertEquals(app.packageName, p.appId)
        assertTrue(p.osVersion!!.isNotBlank())
        assertEquals("phone", p.deviceClass)
    }

    @Test
    fun `storage is private and scoped without exposing the write key`() {
        val p = AndroidPlatform(app, testConfig())
        val path = p.storageDir().absolutePath
        assertTrue(path.startsWith(app.filesDir.absolutePath), path)
        assertFalse(path.contains("hi_test"), path)
    }

    @Test
    fun `network state is unknown unless the host already holds ACCESS_NETWORK_STATE`() {
        val p = AndroidPlatform(app, testConfig())
        shadowOf(app).denyPermissions("android.permission.ACCESS_NETWORK_STATE")
        assertNull(p.isOnWifi())
        shadowOf(app).grantPermissions("android.permission.ACCESS_NETWORK_STATE")
        assertTrue(p.isOnWifi() != null)
    }

    @Test
    fun `permission checks reflect runtime grants`() {
        val p = AndroidPlatform(app, testConfig())
        shadowOf(app).denyPermissions("android.permission.CAMERA")
        assertFalse(p.hasPermission("android.permission.CAMERA"))
        shadowOf(app).grantPermissions("android.permission.CAMERA")
        assertTrue(p.hasPermission("android.permission.CAMERA"))
    }

    @Test
    fun `manifest meta-data opt-out is read`() {
        val p = AndroidPlatform(app, testConfig())
        assertFalse(p.manifestOptOut())
        val info = shadowOf(app.packageManager).getInternalMutablePackageInfo(app.packageName).applicationInfo!!
        info.metaData = Bundle().apply { putBoolean(HenosisInsights.MANIFEST_ENABLED_KEY, false) }
        assertTrue(p.manifestOptOut())
    }

    @Test
    fun `public start with a real context never throws and honours the manifest opt-out`() {
        val config = testConfig()
        val client = HenosisInsights.start(app, config)
        client.countCall("charge")
        client.reportError("charge", "CARD_DECLINED_CONFIG", RuntimeException("x"))
        client.flush()
        assertTrue(client.engine!!.awaitIdle())
        assertTrue(client.isEnabled())
        HenosisInsights.stop()

        val info = shadowOf(app.packageManager).getInternalMutablePackageInfo(app.packageName).applicationInfo!!
        info.metaData = Bundle().apply { putBoolean(HenosisInsights.MANIFEST_ENABLED_KEY, false) }
        val optedOut = HenosisInsights.start(app, config)
        assertTrue(optedOut.engine!!.awaitIdle())
        assertFalse(optedOut.isEnabled())
    }
}
