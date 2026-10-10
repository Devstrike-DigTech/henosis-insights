package com.devstrike.henosis.insights.internal

import java.security.MessageDigest

/**
 * Normalisers that make any vendor-supplied identifier fit schema v1's bounded patterns, so a batch
 * can never be rejected for a stray character and no free text can slip into an identifier field.
 */
internal object Names {
    private val METHOD = Regex("^[A-Za-z_$][A-Za-z0-9_.$-]{0,127}$")
    private val CODE = Regex("^[A-Z][A-Z0-9_]{0,63}$")
    private val CHECK = Regex("^[a-z][a-z0-9_]{0,63}$")
    private val VERSION = Regex("^[A-Za-z0-9+._-]{1,64}$")
    private val CUSTOMER_KEY = Regex("^[A-Za-z0-9_-]{8,128}$")
    private val SDK_NAME = Regex("^[a-z0-9][a-z0-9._-]{0,63}$")
    private val FRAME_CLASS = Regex("^[A-Za-z_$@][A-Za-z0-9_.$<>/@-]{0,255}$")
    private val FRAME_FN = Regex("^[A-Za-z_$<][A-Za-z0-9_.$<>-]{0,127}$")

    fun method(raw: String): String {
        if (METHOD.matches(raw)) return raw
        val cleaned = raw.map { if (it.isLetterOrDigit() && it.code < 128 || it in "_.$-") it else '_' }
            .joinToString("")
            .ifEmpty { "unknown" }
        val lead = if (cleaned[0].isLetter() || cleaned[0] == '_' || cleaned[0] == '$') cleaned else "_$cleaned"
        return lead.take(128)
    }

    fun code(raw: String): String {
        if (CODE.matches(raw)) return raw
        val cleaned = raw.uppercase().map { if (it in 'A'..'Z' || it in '0'..'9' || it == '_') it else '_' }
            .joinToString("")
            .trim('_')
            .ifEmpty { "UNKNOWN" }
        val lead = if (cleaned[0] in 'A'..'Z') cleaned else "E_$cleaned"
        return lead.take(64)
    }

    fun checkName(raw: String): String {
        if (CHECK.matches(raw)) return raw
        val cleaned = raw.lowercase().map { if (it in 'a'..'z' || it in '0'..'9' || it == '_') it else '_' }
            .joinToString("")
            .replace(Regex("_+"), "_")
            .trim('_')
            .ifEmpty { "custom" }
        val lead = if (cleaned[0] in 'a'..'z') cleaned else "c_$cleaned"
        return lead.take(64)
    }

    fun permissionCheck(permission: String): String = checkName("permission_" + permission.substringAfterLast('.'))

    fun mainThreadCheck(method: String): String = checkName("main_thread_$method")

    fun version(raw: String?): String? {
        if (raw == null) return null
        if (VERSION.matches(raw)) return raw
        val cleaned = raw.map { if (it.isLetterOrDigit() && it.code < 128 || it in "+._-") it else '_' }
            .joinToString("")
        return cleaned.take(64).ifEmpty { null }
    }

    fun sdkName(raw: String): String {
        if (SDK_NAME.matches(raw)) return raw
        val cleaned = raw.lowercase().map { if (it in 'a'..'z' || it in '0'..'9' || it in "._-") it else '-' }
            .joinToString("")
            .trimStart('.', '_', '-')
            .ifEmpty { "sdk" }
        return cleaned.take(64)
    }

    /** Returns the key if already a valid hash-like token, else its SHA-256 hex plus `false`. */
    fun customerKey(raw: String): Pair<String, Boolean> =
        if (CUSTOMER_KEY.matches(raw)) raw to true else sha256(raw) to false

    fun frameClass(raw: String): String? = raw.takeIf { FRAME_CLASS.matches(it) } ?: raw.take(256).takeIf { FRAME_CLASS.matches(it) }

    fun frameFn(raw: String): String? = raw.takeIf { FRAME_FN.matches(it) } ?: raw.take(128).takeIf { FRAME_FN.matches(it) }

    fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) sb.append(String.format("%02x", b))
        return sb.toString()
    }
}
