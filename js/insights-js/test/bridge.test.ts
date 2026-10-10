import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import Ajv2020 from 'ajv/dist/2020.js';
import { afterEach, describe, expect, it } from 'vitest';
import { Engine } from '../src/engine.js';
import { HealthCheck } from '../src/health.js';
import type { BridgeTransport, InsightsConfig } from '../src/types.js';
import { Clock, CUSTOMER_HASH, deps, FakeTransport, settle, WRITE_KEY } from './helpers.js';

const bridgeSchema = JSON.parse(
  readFileSync(resolve(import.meta.dirname, '../../../schema/bridge-v1.json'), 'utf8'),
);
const ajv = new (Ajv2020 as unknown as typeof Ajv2020.default)({
  strict: false,
  validateFormats: false,
  allErrors: true,
});
const validateBridge = ajv.compile(bridgeSchema);

/** Stands in for the wrapper's TurboModule to the native SDK. */
class FakeBridge implements BridgeTransport {
  probes = 0;
  sent: string[] = [];
  constructor(
    private readonly runningAfterProbes = 1,
    private readonly opts: { failSend?: boolean; throwOnProbe?: boolean } = {},
  ) {}
  isNativeRunning(): Promise<boolean> {
    this.probes++;
    if (this.opts.throwOnProbe) return Promise.reject(new Error('module not found'));
    return Promise.resolve(this.runningAfterProbes > 0 && this.probes >= this.runningAfterProbes);
  }
  send(message: string): void {
    if (this.opts.failSend) throw new Error('bridge closed');
    this.sent.push(message);
  }
  messages(): Record<string, any>[] {
    return this.sent.map((m) => JSON.parse(m));
  }
}

const wrapped = (over: Partial<InsightsConfig> = {}): InsightsConfig => ({
  writeKey: WRITE_KEY,
  sdkName: 'acme-payments',
  sdkVersion: '4.2.0',
  wrapperVersion: '0.9.4',
  customerKey: CUSTOMER_HASH,
  sdkPackagePrefixes: ['@acme/payments-react-native/'],
  endpoint: 'http://127.0.0.1:9/v1/batches',
  ...over,
});

let engines: Engine[] = [];
afterEach(() => {
  for (const e of engines) e.stop();
  engines = [];
});

async function start(config: InsightsConfig, envOptOut = false, probeDelays = [0, 0, 0]) {
  const transport = new FakeTransport();
  const e = new Engine(config, {
    ...deps({ transport, clock: new Clock(), envOptOut: () => envOptOut }),
    probeDelays,
  });
  engines.push(e);
  await e.loaded;
  await settle();
  return { e, transport };
}

describe('auto mode', () => {
  it('with the native client running: bridged, and this client never uploads (one stream)', async () => {
    const bridge = new FakeBridge();
    const { e, transport } = await start(wrapped({ bridge }));
    expect(e.activeMode).toBe('bridged');
    const err = new Error('card 4111 for ada@example.com');
    err.stack = [
      'Error: card 4111 for ada@example.com',
      '    at charge (/app/node_modules/@acme/payments-react-native/src/index.ts:30:9)',
      '    at onPay (/app/src/Checkout.tsx:12:3)',
    ].join('\n');
    e.countCall('Wrapper.ready');
    e.countCall('Wrapper.ready');
    e.markDeprecated('chargeLegacy');
    e.reportError('charge', 'INVALID_CURRENCY', err);
    e.check(HealthCheck.custom('currency_supported', false, 'unsupported_platform'));
    await e.flush();
    await settle();
    expect(transport.bodies).toEqual([]);

    const msgs = bridge.messages();
    expect(msgs[0]?.wrapper).toEqual({ kind: 'react-native', version: '0.9.4' });
    expect(msgs[0]?.events).toEqual([]);
    const calls: Record<string, number> = {};
    for (const m of msgs)
      for (const [k, v] of Object.entries(m.calls as Record<string, number>))
        calls[k] = (calls[k] ?? 0) + v;
    expect(calls).toEqual({ 'Wrapper.ready': 2 });
    const events = msgs.flatMap((m) => m.events);
    expect(events.find((x) => x.kind === 'error')).toEqual({
      kind: 'error',
      method: 'charge',
      code: 'INVALID_CURRENCY',
      layer: 'wrapper',
      frames: [{ cls: '@acme/payments-react-native/src/index.ts', fn: 'charge', line: 30 }],
    });
    expect(JSON.stringify(msgs)).not.toContain('ada@example.com');
    expect(JSON.stringify(msgs)).not.toContain('Checkout');
    for (const m of bridge.sent)
      expect(validateBridge(JSON.parse(m)), JSON.stringify(validateBridge.errors)).toBe(true);
  });

  it('native found on a later probe: bridged; calls made meanwhile are kept', async () => {
    const bridge = new FakeBridge(3);
    const transport = new FakeTransport();
    const e = new Engine(wrapped({ bridge }), {
      ...deps({ transport, clock: new Clock() }),
      probeDelays: [0, 5, 5],
    });
    engines.push(e);
    e.countCall('early');
    expect(e.activeMode).toBeUndefined();
    await e.loaded;
    await e.flush();
    await settle();
    expect(bridge.probes).toBe(3);
    expect(e.activeMode).toBe('bridged');
    expect(transport.bodies).toEqual([]);
    expect(bridge.messages().some((m) => m.calls.early === 1)).toBe(true);
  });

  it('native never running: direct after probing, bridge unused', async () => {
    const bridge = new FakeBridge(0);
    const { e, transport } = await start(wrapped({ bridge }));
    expect(bridge.probes).toBe(3);
    expect(e.activeMode).toBe('direct');
    expect(transport.kinds()).toEqual(['init', 'heartbeat']);
    expect(bridge.sent).toEqual([]);
  });

  it('a module without the bridge methods counts as not running; no bridge means direct', async () => {
    expect(
      (await start(wrapped({ bridge: new FakeBridge(1, { throwOnProbe: true }) }))).e.activeMode,
    ).toBe('direct');
    expect((await start(wrapped({ sdkName: 'plain' }))).e.activeMode).toBe('direct');
  });

  it('a probe that never answers times out and falls back to direct', async () => {
    const hung: BridgeTransport = {
      isNativeRunning: () => new Promise<boolean>(() => {}),
      send: () => {},
    };
    const { e } = await start(wrapped({ bridge: hung }), false, [0]);
    expect(e.activeMode).toBe('direct');
  }, 10_000);
});

describe('forced modes', () => {
  it('direct ignores a bridge', async () => {
    const bridge = new FakeBridge();
    const { e, transport } = await start(wrapped({ mode: 'direct', bridge }));
    expect(e.activeMode).toBe('direct');
    expect(bridge.probes).toBe(0);
    expect(transport.bodies.length).toBeGreaterThan(0);
  });

  it('bridged never sends directly, even when the native client is absent', async () => {
    const bridge = new FakeBridge(0);
    const { e, transport } = await start(wrapped({ mode: 'bridged', bridge }));
    e.countCall('x');
    await e.flush();
    expect(transport.bodies).toEqual([]);
    expect(bridge.sent.length).toBeGreaterThan(0);
  });

  it('bridged without a bridge is disabled', async () => {
    const { e, transport } = await start(wrapped({ mode: 'bridged' }));
    e.countCall('x');
    await e.flush();
    expect(e.isEnabled()).toBe(false);
    expect(transport.bodies).toEqual([]);
  });
});

describe('opt-out and failures', () => {
  it('host opt-out stops forwarding', async () => {
    const bridge = new FakeBridge();
    const { e, transport } = await start(wrapped({ bridge }));
    bridge.sent = [];
    e.countCall('x');
    e.setHostEnabled(false);
    await e.flush();
    await settle();
    expect(bridge.sent).toEqual([]);
    expect(transport.bodies).toEqual([]);
  });

  it('an environment opt-out forwards nothing, not even the registration', async () => {
    const bridge = new FakeBridge();
    await start(wrapped({ bridge }), true);
    expect(bridge.sent).toEqual([]);
  });

  it('a failing send never throws and never falls back to direct', async () => {
    const bridge = new FakeBridge(1, { failSend: true });
    const { e, transport } = await start(wrapped({ bridge }));
    e.countCall('x');
    await e.flush();
    await settle();
    expect(transport.bodies).toEqual([]);
    expect(e.activeMode).toBe('bridged');
  });
});
