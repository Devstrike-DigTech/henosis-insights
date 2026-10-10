import { checkName, code, method } from './names.js';
import type { Frame } from './stack.js';
import type { HealthCheckResult } from './types.js';

export type Json = Record<string, unknown>;

export const LIMITS = { checks: 50, counts: 500, eventsPerBatch: 500 } as const;

export const iso = (ms: number): string => new Date(ms).toISOString();
export const utcDay = (ms: number): string => iso(ms).slice(0, 10);

export const initEvent = (checks: HealthCheckResult[]): Json => ({
  kind: 'init',
  ok: checks.every((c) => c.ok),
  checks: checks.slice(0, LIMITS.checks).map((c) => ({ name: c.name, ok: c.ok })),
});

export function callsEvent(from: number, to: number, counts: Map<string, number>): Json {
  const top = [...counts.entries()].sort((a, b) => b[1] - a[1]).slice(0, LIMITS.counts);
  return { kind: 'calls', from: iso(from), to: iso(to), counts: Object.fromEntries(top) };
}

export const errorEvent = (
  m: string,
  c: string,
  frames: Frame[],
  layer: 'native' | 'wrapper' = 'native',
): Json => ({
  kind: 'error',
  method: method(m),
  code: code(c),
  layer,
  frames,
});

export function healthEvent(check: HealthCheckResult): Json {
  const e: Json = { kind: 'health', check: checkName(check.name), ok: !!check.ok };
  if (!check.ok && check.detail) e.detail = check.detail;
  return e;
}

export const deprecatedEvent = (m: string, count: number): Json => ({
  kind: 'deprecated',
  method: method(m),
  count,
});

export const heartbeatEvent = (): Json => ({ kind: 'heartbeat' });
