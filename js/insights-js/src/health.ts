import { checkName, permissionCheck } from './names.js';
import type { HealthCheckResult, HealthDetail } from './types.js';

const DETAILS: readonly HealthDetail[] = [
  'missing',
  'invalid_format',
  'not_initialized',
  'wrong_thread',
  'permission_missing',
  'unsupported_platform',
  'other',
];

/** Built-in and custom health checks. Names are fixed or normalised; details come from a fixed set. */
export const HealthCheck = {
  /** The SDK's own init was (or was not) called before first use. */
  initBeforeUse: (ok: boolean): HealthCheckResult =>
    ok
      ? { name: 'init_before_use', ok }
      : { name: 'init_before_use', ok, detail: 'not_initialized' },

  /** The customer key the host app supplied has (or has not) the expected format. */
  customerKeyFormat: (ok: boolean): HealthCheckResult =>
    ok
      ? { name: 'customer_key_format', ok }
      : { name: 'customer_key_format', ok, detail: 'invalid_format' },

  /** A permission or capability the SDK needs is (or is not) available. */
  permissionGranted: (permission: string, ok: boolean): HealthCheckResult =>
    ok
      ? { name: permissionCheck(permission), ok }
      : { name: permissionCheck(permission), ok, detail: 'permission_missing' },

  /** A vendor-defined check. `detail` must come from the fixed set; there is no free-text field. */
  custom: (name: string, ok: boolean, detail?: HealthDetail): HealthCheckResult => {
    if (ok) return { name: checkName(name), ok };
    return {
      name: checkName(name),
      ok,
      detail: detail && DETAILS.includes(detail) ? detail : 'other',
    };
  },
} as const;
