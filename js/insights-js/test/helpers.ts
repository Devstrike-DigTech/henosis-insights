import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import Ajv2020 from 'ajv/dist/2020.js';
import type { EngineDeps } from '../src/engine.js';
import type { SendResult, Transport } from '../src/transport.js';
import type { InsightsConfig, PlatformInfo } from '../src/types.js';

export const WRITE_KEY = 'hi_test_0123456789abcdef0123456789abcdef';
export const CUSTOMER_HASH = '4e07408562bedb8b60ce05c1decfe3ad16b72230967de01f640b7e4729b49fce';
/** 2026-10-10T10:00:00Z */
export const T0 = Date.UTC(2026, 9, 10, 10, 0, 0);
export const HOUR = 3_600_000;

export const config = (over: Partial<InsightsConfig> = {}): InsightsConfig => ({
  writeKey: WRITE_KEY,
  sdkName: 'acme-chat',
  sdkVersion: '0.9.4',
  customerKey: CUSTOMER_HASH,
  sdkPackagePrefixes: ['@acme/chat/'],
  endpoint: 'http://127.0.0.1:9/v1/batches',
  ...over,
});

export const RN_PLATFORM: PlatformInfo = {
  wrapperKind: 'react-native',
  os: 'ios',
  osVersion: '19.0',
  deviceClass: 'phone',
};

export class FakeTransport implements Transport {
  bodies: string[] = [];
  keepalive: boolean[] = [];
  result: SendResult = { kind: 'accepted' };
  failure: unknown;
  async send(
    _endpoint: string,
    _key: string,
    body: string,
    options: { keepalive: boolean },
  ): Promise<SendResult> {
    if (this.failure) throw this.failure;
    this.bodies.push(body);
    this.keepalive.push(options.keepalive);
    return this.result;
  }
  batches(): Record<string, any>[] {
    return this.bodies.map((b) => JSON.parse(b));
  }
  events(): Record<string, any>[] {
    return this.batches().flatMap((b) => b.events);
  }
  kinds(): string[] {
    return this.events().map((e) => e.kind);
  }
  clear() {
    this.bodies = [];
    this.keepalive = [];
  }
}

export class Clock {
  constructor(public t = T0) {}
  now = () => this.t;
  advance(ms: number) {
    this.t += ms;
  }
}

export function deps(
  over: Partial<EngineDeps> & { transport: Transport; clock: Clock },
): EngineDeps {
  return {
    platform: RN_PLATFORM,
    now: over.clock.now,
    random: () => 0.5,
    envOptOut: () => false,
    ...over,
  };
}

/** Let queued microtasks and fake-transport promises settle. */
export const settle = async () => {
  for (let i = 0; i < 10; i++) await new Promise((r) => setTimeout(r, 0));
};

// ---- schema v1 ------------------------------------------------------------------------------------
const schemaPath = resolve(import.meta.dirname, '../../../schema/v1.json');
export const schemaJson = JSON.parse(readFileSync(schemaPath, 'utf8'));
const ajv = new (Ajv2020 as unknown as typeof Ajv2020.default)({
  strict: false,
  validateFormats: false,
  allErrors: true,
});
const validate = ajv.compile(schemaJson);

export function schemaErrors(body: string): string[] {
  return validate(JSON.parse(body))
    ? []
    : (validate.errors ?? []).map((e) => `${e.instancePath} ${e.message}`);
}

export const allowedNames: Set<string> = (() => {
  const out = new Set<string>();
  const walk = (n: unknown) => {
    if (Array.isArray(n)) n.forEach(walk);
    else if (n && typeof n === 'object') {
      const o = n as Record<string, unknown>;
      if (o.properties && typeof o.properties === 'object')
        for (const k of Object.keys(o.properties)) out.add(k);
      Object.values(o).forEach(walk);
    }
  };
  walk(schemaJson);
  return out;
})();

/** Every key in the payload except method names inside `counts`. */
export function fieldNames(
  payload: unknown,
  parent?: string,
  out = new Set<string>(),
): Set<string> {
  if (Array.isArray(payload)) {
    for (const p of payload) fieldNames(p, parent, out);
  } else if (payload && typeof payload === 'object') {
    for (const [k, v] of Object.entries(payload)) {
      if (parent !== 'counts') out.add(k);
      fieldNames(v, k, out);
    }
  }
  return out;
}
