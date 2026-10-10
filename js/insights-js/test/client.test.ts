import { createServer, type Server } from 'node:http';
import type { AddressInfo } from 'node:net';
import { afterEach, describe, expect, it } from 'vitest';
import {
  _resetForTests,
  HealthCheck,
  HenosisInsights,
  InsightsClient,
  startWith,
} from '../src/index.js';
import { fetchTransport } from '../src/transport.js';
import type { InsightsStorage } from '../src/types.js';
import { Clock, config, FakeTransport, RN_PLATFORM, settle, WRITE_KEY } from './helpers.js';

afterEach(() => _resetForTests());

const start = (
  sdkName: string,
  transport: FakeTransport,
  over: Parameters<typeof config>[0] = {},
) =>
  startWith(config({ sdkName, ...over }), {
    transport,
    platform: RN_PLATFORM,
    now: new Clock().now,
    envOptOut: () => false,
  });

const callCount = (t: FakeTransport) =>
  t
    .events()
    .filter((e) => e.kind === 'calls')
    .reduce((n, e) => n + (e.counts.send ?? 0), 0);

describe('routing', () => {
  it('the same SDK gets the same handle; static calls reach the only one', async () => {
    const t = new FakeTransport();
    const a = start('acme-chat', t);
    expect(start('acme-chat', t)).toBe(a);
    HenosisInsights.countCall('send');
    await HenosisInsights.flush();
    expect(callCount(t)).toBe(1);
  });

  it('with two SDKs, static calls are dropped and handles stay separate', async () => {
    const ta = new FakeTransport();
    const tb = new FakeTransport();
    const a = start('acme-chat', ta);
    const b = start('acme-kyc', tb);
    HenosisInsights.countCall('send');
    await HenosisInsights.flush();
    a.countCall('send');
    b.countCall('send');
    b.countCall('send');
    await Promise.all([a.flush(), b.flush()]);
    expect(callCount(ta)).toBe(1);
    expect(callCount(tb)).toBe(2);
    expect(HenosisInsights.isEnabled()).toBe(false);
  });

  it('host opt-out before start, and across every running SDK', async () => {
    HenosisInsights.setEnabled(false);
    const t = new FakeTransport();
    const a = start('acme-chat', t);
    a.countCall('send');
    await a.flush();
    expect(a.isEnabled()).toBe(false);
    expect(t.bodies).toHaveLength(0);
  });
});

describe('never throws, never rejects', () => {
  const hostile: unknown[] = [
    '',
    'x'.repeat(10_000),
    null,
    undefined,
    42,
    {},
    Symbol('s'),
    () => 1,
  ];

  function exercise(c: InsightsClient) {
    for (const v of hostile) {
      c.countCall(v as string);
      c.markDeprecated(v as string);
      c.reportError(v as string, v as string, v);
      c.check(v as never);
      c.check(HealthCheck.custom(v as string, false));
      HenosisInsights.countCall(v as string);
      HenosisInsights.reportError(v as string, v as string, new Error(String(typeof v)));
    }
  }

  it('before start and after stop', async () => {
    exercise(new InsightsClient(undefined));
    await expect(HenosisInsights.flush()).resolves.toBeUndefined();
    HenosisInsights.stop();
    HenosisInsights.setEnabled(true);
  });

  it('with storage that throws and rejects', async () => {
    const bad: InsightsStorage = {
      getItem: () => {
        throw new Error('boom');
      },
      setItem: () => Promise.reject(new Error('boom')),
      removeItem: () => {
        throw new Error('boom');
      },
    };
    const c = start('acme-chat', new FakeTransport(), { storage: bad });
    exercise(c);
    await expect(c.flush()).resolves.toBeUndefined();
    HenosisInsights.setEnabled(false);
    await settle();
  });

  it('with a transport that throws', async () => {
    const t = new FakeTransport();
    t.failure = new Error('network down');
    const c = start('acme-chat', t);
    exercise(c);
    await expect(c.flush()).resolves.toBeUndefined();
  });

  it('with a broken config', async () => {
    const c = HenosisInsights.start(null as never);
    exercise(c);
    await expect(c.flush()).resolves.toBeUndefined();
    const d = startWith(
      { ...config(), sdkPackagePrefixes: 'nope' as never, sampleRate: Number.NaN },
      {
        transport: new FakeTransport(),
        platform: RN_PLATFORM,
      },
    );
    exercise(d);
    await expect(d.flush()).resolves.toBeUndefined();
  });

  it('API calls only enqueue: a hung network never blocks the caller', async () => {
    const hung = { send: () => new Promise<never>(() => {}) };
    const c = startWith(config(), { transport: hung, platform: RN_PLATFORM });
    const t0 = performance.now();
    for (let i = 0; i < 10_000; i++) c.countCall('send');
    expect(performance.now() - t0).toBeLessThan(500);
  });
});

describe('fetch transport', () => {
  let server: Server;
  afterEach(() => new Promise<void>((r) => server?.close(() => r())));

  async function serve(status: number) {
    const seen: { body: string; headers: Record<string, unknown> }[] = [];
    server = createServer((req, res) => {
      let body = '';
      req.on('data', (c) => (body += c));
      req.on('end', () => {
        seen.push({ body, headers: req.headers });
        res.writeHead(status).end('{}');
      });
    });
    await new Promise<void>((r) => server.listen(0, '127.0.0.1', r));
    return { url: `http://127.0.0.1:${(server.address() as AddressInfo).port}/v1/batches`, seen };
  }

  it('posts JSON with the write key as a bearer token, and classifies status codes', async () => {
    const ok = await serve(202);
    expect(
      await fetchTransport.send(ok.url, WRITE_KEY, '{"schema":1}', { keepalive: false }),
    ).toEqual({ kind: 'accepted' });
    expect(ok.seen[0]?.body).toBe('{"schema":1}');
    expect(ok.seen[0]?.headers.authorization).toBe(`Bearer ${WRITE_KEY}`);
    expect(ok.seen[0]?.headers['content-type']).toBe('application/json');
    expect(ok.seen[0]?.headers.cookie).toBeUndefined();
    await new Promise<void>((r) => server.close(() => r()));
    for (const [status, kind] of [
      [400, 'rejected'],
      [429, 'retry'],
      [503, 'retry'],
    ] as const) {
      const s = await serve(status);
      expect((await fetchTransport.send(s.url, WRITE_KEY, '{}', { keepalive: false })).kind).toBe(
        kind,
      );
      await new Promise<void>((r) => server.close(() => r()));
    }
    server = createServer();
  });

  it('a refused connection is retryable, not thrown', async () => {
    server = createServer();
    expect(
      await fetchTransport.send('http://127.0.0.1:9/v1/batches', WRITE_KEY, '{}', {
        keepalive: false,
      }),
    ).toEqual({ kind: 'retry' });
  });
});
