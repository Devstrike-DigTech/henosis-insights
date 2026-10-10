package com.devstrike.henosis.insights.internal

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NamesTest {
    private val method = Regex("^[A-Za-z_$][A-Za-z0-9_.$-]{0,127}$")
    private val code = Regex("^[A-Z][A-Z0-9_]{0,63}$")
    private val check = Regex("^[a-z][a-z0-9_]{0,63}$")
    private val version = Regex("^[A-Za-z0-9+._-]{1,64}$")

    @Test
    fun `valid identifiers pass through unchanged`() {
        assertEquals("Client.charge", Names.method("Client.charge"))
        assertEquals("CARD_DECLINED_CONFIG", Names.code("CARD_DECLINED_CONFIG"))
        assertEquals("init_before_use", Names.checkName("init_before_use"))
        assertEquals("1.0.0-beta.1+7", Names.version("1.0.0-beta.1+7"))
    }

    @Test
    fun `anything else is normalised into the schema's patterns`() {
        val inputs = listOf("", " ", "9lives", "charge card 4111", "ünïcödé", "a".repeat(500), "-x", "@scope/pkg", "\n\t")
        for (raw in inputs) {
            assertTrue(method.matches(Names.method(raw)), "method <$raw> -> ${Names.method(raw)}")
            assertTrue(code.matches(Names.code(raw)), "code <$raw> -> ${Names.code(raw)}")
            assertTrue(check.matches(Names.checkName(raw)), "check <$raw> -> ${Names.checkName(raw)}")
            Names.version(raw)?.let { assertTrue(version.matches(it), "version <$raw> -> $it") }
        }
    }

    @Test
    fun `permission and main-thread check names`() {
        assertEquals("permission_camera", Names.permissionCheck("android.permission.CAMERA"))
        assertEquals("permission_post_notifications", Names.permissionCheck("android.permission.POST_NOTIFICATIONS"))
        assertEquals("main_thread_charge", Names.mainThreadCheck("charge"))
    }

    @Test
    fun `customer keys that are not hash-like are hashed`() {
        val (ok, valid) = Names.customerKey(CUSTOMER_HASH)
        assertEquals(CUSTOMER_HASH, ok)
        assertTrue(valid)
        val (hashed, wasValid) = Names.customerKey("sk_live 1")
        assertFalse(wasValid)
        assertEquals(64, hashed.length)
        assertFalse(hashed.contains("sk_live"))
    }

    @Test
    fun `sdk names are lowercased to the schema pattern`() {
        assertEquals("acme-payments", Names.sdkName("Acme Payments"))
        assertTrue(Regex("^[a-z0-9][a-z0-9._-]{0,63}$").matches(Names.sdkName("__Weird!!Name__")))
    }
}
