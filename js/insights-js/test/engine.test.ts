import { afterEach, describe, expect, it } from 'vitest';
import { Engine, MAX_INTERVAL_MS, MemoryStorage, RETRY_BACKOFF_MS } from '../src/engine.js';
import { HealthCheck } from '../src/health.js';
import { sha256Hex } from '../src/sha256.js';
import type { InsightsStorage } from '../src/types.js';
import {
  allowedNames,
  Clock,
  config,
  deps,
  FakeTransport,
  fieldNames,
  HOUR,
  schemaErrors,
  settle,
  T0,
  WRITE_KEY,
} from './helpers.js';

let engines: Engine[] = [];
afterEach(() => {
  for (const e of engines) e.stop();
  engines = [];
});

async function start(
  over: Parameters<typeof config>[0] = {},
  d: Partial<Parameters<typeof deps>[0]> = {},
) {
  const transport = (d.transport as FakeTransport) ?? new FakeTransport();
  const clock = d.clock ?? new Clock();
  const e = new Engine(config(over), deps({ ...d, transport, clock }));
  engines.push(e);
  await e.loaded;
  await settle();
  return { e, transport, clock };
}

describe('startup', () => {
  it('uploads init and a heartbeat on first start', async () => {
    const { transport } = await start();
    expect(transport.kinds()).toEqual(['init', 'heartbeat']);
    const b = transport.batches()[0]!;
    expect(b.schema).toBe(1);
    expect(b.writeKey).toBe(WRITE_KEY);
    expect(b.client).toEqual({ lang: 'ts', version: '0.1.0', mode: 'direct' });
    expect(b.wrapper).toEqual({ kind: 'react-native', version: '0.9.4' });
    expect(b.device).toEqual({ os: 'ios', osVersion: '19.0', class: 'phone' });
    expect(b.app).toEqual({});
    expect(b.events[0]).toEqual({
      kind: 'init',
      ok: true,
      checks: [{ name: 'customer_key_format', ok: true }],
    });
  });

  it('hashes a raw customer key and flags it', async () => {
    const { transport } = await start({ customerKey: 'sk live 1' });
    expect(transport.batches()[0]!.customerKey).toBe(sha256Hex('sk live 1'));
    expect(transport.events().find((e) => e.kind === 'health')).toEqual({
      kind: 'health',
      check: 'customer_key_format',
      ok: false,
      detail: 'invalid_format',
    });
  });

  it('reports a blank customer key as missing', async () => {
    const { transport } = await start({ customerKey: '' });
    expect(transport.events().find((e) => e.kind === 'health')?.detail).toBe('missing');
  });

  it('config.platform overrides detection (e.g. an appId read natively)', async () => {
    const { transport } = await start({
      platform: { appId: 'com.example.messenger', appVersion: '7.1.0' },
    });
    expect(transport.batches()[0]!.app).toEqual({ id: 'com.example.messenger', version: '7.1.0' });
  });

  it('calls made before stored state loads are replayed in order', async () => {
    let release!: () => void;
    const gate = new Promise<void>((r) => {
      release = r;
    });
    const storage: InsightsStorage = {
      getItem: async () => {
        await gate;
        return null;
      },
      setItem: () => {},
      removeItem: () => {},
    };
    const transport = new FakeTransport();
    const e = new Engine(config({ storage }), deps({ transport, clock: new Clock() }));
    engines.push(e);
    e.countCall('connect');
    e.reportError('connect', 'EARLY');
    release();
    await e.loaded;
    await e.flush();
    expect(transport.kinds()).toEqual(['init', 'heartbeat', 'error', 'calls']);
  });
});

describe('upload policy', () => {
  it('waits six hours after the last upload, then sends calls as one event', async () => {
    const { e, transport, clock } = await start();
    transport.clear();
    e.countCall('sendMessage');
    e.countCall('sendMessage');
    clock.advance(5 * HOUR);
    e.countCall('listRooms');
    await settle();
    expect(transport.bodies).toHaveLength(0);
    clock.advance(HOUR);
    e.countCall('sendMessage');
    await settle();
    const calls = transport.events().find((x) => x.kind === 'calls')!;
    expect(calls.counts).toEqual({ sendMessage: 3, listRooms: 1 });
    expect(calls.from).toBe(new Date(T0).toISOString());
    expect(calls.to).toBe(new Date(T0 + MAX_INTERVAL_MS).toISOString());
  });

  it('flush forces an upload and resolves after it', async () => {
    const { e, transport } = await start();
    transport.clear();
    e.countCall('x');
    await e.flush();
    expect(transport.kinds()).toEqual(['calls']);
  });

  it('a retryable failure keeps events and backs off; a rejection drops them', async () => {
    const transport = new FakeTransport();
    transport.result = { kind: 'retry', status: 503 };
    const { e, clock } = await start({}, { transport });
    expect(transport.bodies).toHaveLength(1);
    await e.flush();
    expect(transport.bodies).toHaveLength(1);
    clock.advance(RETRY_BACKOFF_MS);
    transport.result = { kind: 'accepted' };
    await e.flush();
    expect(transport.kinds()).toEqual(['init', 'heartbeat', 'init', 'heartbeat']);

    transport.clear();
    transport.result = { kind: 'rejected', status: 400 };
    e.reportError('x', 'Y');
    await e.flush();
    transport.result = { kind: 'accepted' };
    transport.clear();
    await e.flush();
    expect(transport.bodies).toHaveLength(0);
  });

  it('sends large queues in batches of at most 500 events', async () => {
    const transport = new FakeTransport();
    transport.result = { kind: 'retry' };
    const { e, clock } = await start({}, { transport });
    for (let i = 0; i < 1200; i++) e.reportError('m', `E_${i}`);
    transport.result = { kind: 'accepted' };
    transport.clear();
    clock.advance(RETRY_BACKOFF_MS);
    await e.flush();
    expect(transport.batches().map((b) => b.events.length)).toEqual([500, 500, 202]);
  });

  it('a page-hide flush uses keepalive and fits the 64 KB limit', async () => {
    let hide!: () => void;
    const transport = new FakeTransport();
    const { e } = await start(
      {},
      {
        transport,
        onPageHide: (cb) => {
          hide = cb;
          return () => {};
        },
      },
    );
    transport.clear();
    for (let i = 0; i < 400; i++) e.reportError(`method_${i}`, `CODE_${i}`);
    hide();
    await settle();
    expect(transport.keepalive.every(Boolean)).toBe(true);
    expect(transport.bodies.every((b) => b.length <= 60_000)).toBe(true);
    expect(transport.events().filter((x) => x.kind === 'error')).toHaveLength(400);
  });
});

describe('persistence', () => {
  it('queue, counters and last-upload survive a restart through storage', async () => {
    const storage = new MemoryStorage();
    const t1 = new FakeTransport();
    t1.result = { kind: 'retry' };
    const first = await start({ storage }, { transport: t1 });
    first.e.reportError('connect', 'WS_HANDSHAKE_FAILED');
    first.e.countCall('connect');
    first.e.stop();
    await settle();

    const t2 = new FakeTransport();
    const second = await start({ storage }, { transport: t2, clock: new Clock(T0 + 10 * 60_000) });
    await second.e.flush();
    expect(t2.kinds()).toContain('error');
    expect(t2.events().find((x) => x.kind === 'calls')?.counts).toEqual({ connect: 1 });
  });

  it('works with async (AsyncStorage-like) storage', async () => {
    const map = new Map<string, string>();
    const storage: InsightsStorage = {
      getItem: async (k) => map.get(k) ?? null,
      setItem: async (k, v) => void map.set(k, v),
      removeItem: async (k) => void map.delete(k),
    };
    const { e, transport } = await start({ storage });
    e.countCall('a');
    await e.flush();
    await settle();
    expect(transport.kinds()).toContain('calls');
    expect([...map.keys()].some((k) => k.endsWith(':state'))).toBe(true);
  });

  it('sends one heartbeat per UTC day', async () => {
    const { e, transport, clock } = await start();
    clock.advance(6 * HOUR);
    e.countCall('a');
    await settle();
    expect(transport.kinds().filter((k) => k === 'heartbeat')).toHaveLength(1);
    clock.advance(14 * HOUR);
    await e.flush();
    expect(transport.kinds().filter((k) => k === 'heartbeat')).toHaveLength(2);
  });
});

describe('opt-out and sampling', () => {
  it('host opt-out deletes everything and stops; opting back in resumes', async () => {
    const storage = new MemoryStorage();
    const transport = new FakeTransport();
    transport.result = { kind: 'retry' };
    const { e, clock } = await start({ storage }, { transport });
    e.countCall('a');
    e.reportError('a', 'B');
    e.setHostEnabled(false);
    await settle();
    expect(e.isEnabled()).toBe(false);
    expect([...storage.map.keys()].filter((k) => k.endsWith(':state'))).toEqual([]);
    transport.result = { kind: 'accepted' };
    transport.clear();
    clock.advance(RETRY_BACKOFF_MS);
    e.countCall('a');
    await e.flush();
    expect(transport.bodies).toHaveLength(0);
    e.setHostEnabled(true);
    e.countCall('a');
    await e.flush();
    expect(e.isEnabled()).toBe(true);
    expect(transport.kinds()).toContain('calls');
  });

  it('the host opt-out is remembered in storage for the next start', async () => {
    const storage = new MemoryStorage();
    const first = await start({ storage });
    first.e.setHostEnabled(false);
    first.e.stop();
    const transport = new FakeTransport();
    const second = await start({ storage, sdkName: 'other-sdk' }, { transport });
    expect(second.e.isEnabled()).toBe(false);
    expect(transport.bodies).toHaveLength(0);
  });

  it('an env opt-out sends nothing', async () => {
    const transport = new FakeTransport();
    const { e } = await start({}, { transport, envOptOut: () => true });
    e.countCall('a');
    await e.flush();
    expect(transport.bodies).toHaveLength(0);
  });

  it('sampling decides once and remembers the draw', async () => {
    const storage = new MemoryStorage();
    const transport = new FakeTransport();
    const off = await start({ storage, sampleRate: 0.3 }, { transport, random: () => 0.5 });
    expect(off.e.isEnabled()).toBe(false);
    await settle();
    expect(Object.keys(JSON.parse([...storage.map.values()][0] ?? '{}'))).toEqual(['sample']);
    off.e.stop();
    await settle();
    const again = await start({ storage, sampleRate: 0.3 }, { transport, random: () => 0.0 });
    expect(again.e.isEnabled()).toBe(false);
    expect((await start({ sampleRate: 0 }, { transport })).e.isEnabled()).toBe(false);
    expect(transport.bodies).toHaveLength(0);
  });
});

describe('payload', () => {
  it('every batch matches schema v1 and uses only allowed field names', async () => {
    const { e, transport } = await start({ customerKey: 'not a hash' });
    e.countCall('sendMessage');
    e.countCall('bad name / ünicode');
    e.reportError('send', 'ws handshake failed', new Error('secret'));
    e.reportError('', '', undefined);
    e.check(HealthCheck.initBeforeUse(false));
    e.check(HealthCheck.permissionGranted('android.permission.CAMERA', false));
    e.check(HealthCheck.custom('Webhook Reachable!', false, 'other'));
    e.check(HealthCheck.custom('fine', true));
    e.check(HealthCheck.custom('x', false, 'free text' as never));
    e.markDeprecated('sendMessageSync');
    await e.flush();
    expect(new Set(transport.kinds())).toEqual(
      new Set(['init', 'heartbeat', 'health', 'calls', 'error', 'deprecated']),
    );
    for (const body of transport.bodies) {
      expect(schemaErrors(body), body).toEqual([]);
      const unknown = [...fieldNames(JSON.parse(body))].filter((n) => !allowedNames.has(n));
      expect(unknown).toEqual([]);
    }
  });

  it('never includes error messages, user data or app frames', async () => {
    const { e, transport } = await start();
    const err = new Error('user ada@example.com card 4111111111111111');
    err.stack = [
      'Error: user ada@example.com card 4111111111111111',
      '    at send (/app/node_modules/@acme/chat/dist/index.js:203:11)',
      '    at onPress (/app/src/screens/Checkout.tsx:42:7)',
      '    at callFunction (/app/node_modules/react-native/Libraries/Bridge.js:1:1)',
    ].join('\n');
    e.reportError('send', 'WS_HANDSHAKE_FAILED', err);
    await e.flush();
    const body = transport.bodies.join('\n');
    for (const forbidden of [
      'ada@example.com',
      '4111',
      'Checkout',
      'screens',
      'react-native/Libraries',
      '/app/node_modules',
    ]) {
      expect(body).not.toContain(forbidden);
    }
    const error = transport.events().find((x) => x.kind === 'error')!;
    expect(error.frames).toEqual([{ cls: '@acme/chat/dist/index.js', fn: 'send', line: 203 }]);
  });
});
