package com.devstrike.henosis.insights

import com.devstrike.henosis.insights.internal.Names

/**
 * The only values a health check may attach as detail. Fixed so no free text (and so no end-user data)
 * can ride along on a health event. Mirrors `health.detail` in schema v1.
 */
public enum class HealthDetail(internal val wire: String) {
    MISSING("missing"),
    INVALID_FORMAT("invalid_format"),
    NOT_INITIALIZED("not_initialized"),
    WRONG_THREAD("wrong_thread"),
    PERMISSION_MISSING("permission_missing"),
    UNSUPPORTED_PLATFORM("unsupported_platform"),
    OTHER("other"),
    ;

    internal companion object {
        fun fromWire(value: String?): HealthDetail? = entries.firstOrNull { it.wire == value }
    }
}

/**
 * A health check result the vendor SDK reports through [HenosisInsights.check].
 *
 * Built-in checks have fixed names so the dashboard can group them across SDKs. [Custom] lets a vendor
 * add its own; the name is normalised to `snake_case` and capped at 64 characters.
 */
public sealed class HealthCheck(
    public val name: String,
    public val ok: Boolean,
    public val detail: HealthDetail?,
) {
    /** The SDK's own `init()` was (or was not) called before the first use. */
    public class InitBeforeUse(ok: Boolean) :
        HealthCheck("init_before_use", ok, if (ok) null else HealthDetail.NOT_INITIALIZED)

    /** The customer key the host app supplied has (or has not) the format the SDK expects. */
    public class CustomerKeyFormat(ok: Boolean) :
        HealthCheck("customer_key_format", ok, if (ok) null else HealthDetail.INVALID_FORMAT)

    /** The host app passed no customer key at all. Reported by the client automatically. */
    internal class CustomerKeyMissing : HealthCheck("customer_key_format", false, HealthDetail.MISSING)

    /** A runtime permission the SDK needs is (or is not) granted. Name becomes `permission_<suffix>`. */
    public class PermissionGranted(permission: String, ok: Boolean) :
        HealthCheck(Names.permissionCheck(permission), ok, if (ok) null else HealthDetail.PERMISSION_MISSING)

    /** A method the vendor documents as worker-thread-only was (or was not) called off the main thread. */
    public class WorkerThread(method: String, ok: Boolean) :
        HealthCheck(Names.mainThreadCheck(method), ok, if (ok) null else HealthDetail.WRONG_THREAD)

    /** A vendor-defined check. [detail] must come from the fixed enum; there is no free-text field. */
    public class Custom(name: String, ok: Boolean, detail: HealthDetail? = null) :
        HealthCheck(Names.checkName(name), ok, if (ok) null else detail ?: HealthDetail.OTHER)
}
