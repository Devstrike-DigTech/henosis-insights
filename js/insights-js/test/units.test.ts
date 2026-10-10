import { createHash } from 'node:crypto';
import { describe, expect, it } from 'vitest';
import * as Names from '../src/names.js';
import { sha256Hex } from '../src/sha256.js';
import { sdkFrames } from '../src/stack.js';

describe('sha256', () => {
  it('matches node:crypto for ASCII, unicode, empty and multi-block input', () => {
    for (const s of [
      '',
      'abc',
      'sk_live_123',
      'ünïcödé 😀',
      'x'.repeat(1000),
      'a'.repeat(55),
      'a'.repeat(56),
      'a'.repeat(64),
    ]) {
      expect(sha256Hex(s)).toBe(createHash('sha256').update(s, 'utf8').digest('hex'));
    }
  });
});

describe('names', () => {
  const patterns = {
    method: /^[A-Za-z_$][A-Za-z0-9_.$-]{0,127}$/,
    code: /^[A-Z][A-Z0-9_]{0,63}$/,
    check: /^[a-z][a-z0-9_]{0,63}$/,
  };
  it('normalises any input into the schema patterns', () => {
    for (const raw of [
      '',
      ' ',
      '9lives',
      'charge card 4111',
      'ünïcödé',
      'a'.repeat(500),
      '-x',
      null,
      undefined,
      42,
      {},
    ]) {
      expect(Names.method(raw)).toMatch(patterns.method);
      expect(Names.code(raw)).toMatch(patterns.code);
      expect(Names.checkName(raw)).toMatch(patterns.check);
    }
  });
  it('keeps valid identifiers', () => {
    expect(Names.method('Client.send')).toBe('Client.send');
    expect(Names.code('WS_HANDSHAKE_FAILED')).toBe('WS_HANDSHAKE_FAILED');
    expect(Names.appId('shop.example.com')).toBe('shop.example.com');
    expect(Names.appId('@example/bot')).toBe('@example/bot');
    expect(Names.appId('has space')).toBeUndefined();
  });
});

describe('stack frames', () => {
  const prefixes = ['@acme/chat/'];
  const err = (stack: string) => Object.assign(new Error('m'), { stack });

  it('parses V8 / Node frames', () => {
    const frames = sdkFrames(
      err(
        'Error: m\n    at Socket.connect (file:///srv/node_modules/@acme/chat/dist/index.js:10:5)\n    at main (file:///srv/app.js:1:1)',
      ),
      prefixes,
    );
    expect(frames).toEqual([{ cls: '@acme/chat/dist/index.js', fn: 'Socket.connect', line: 10 }]);
  });

  it('parses Safari / Firefox frames and strips query strings', () => {
    const frames = sdkFrames(
      err(
        'connect@https://cdn.example.com/npm/@acme/chat/dist/index.js?v=3:7:2\nonClick@https://shop.example.com/app.js:1:1',
      ),
      prefixes,
    );
    expect(frames).toEqual([{ cls: '@acme/chat/dist/index.js', fn: 'connect', line: 7 }]);
  });

  it('parses Hermes-style anonymous frames and async / new prefixes', () => {
    const frames = sdkFrames(
      err(
        'Error\n    at async send (/x/node_modules/@acme/chat/src/a.ts:3:1)\n    at new Client (/x/node_modules/@acme/chat/src/b.ts:4:1)\n    at /x/node_modules/@acme/chat/src/c.ts:5:1',
      ),
      prefixes,
    );
    expect(frames.map((f) => f.fn)).toEqual(['send', 'Client', '<anonymous>']);
  });

  it('follows cause chains without looping and keeps nothing when prefixes are empty', () => {
    const a = err('    at f (/n/@acme/chat/a.js:1:1)');
    const b = Object.assign(err('    at g (/n/@acme/chat/b.js:2:1)'), { cause: a });
    (a as { cause?: unknown }).cause = b;
    expect(sdkFrames(b, prefixes)).toHaveLength(2);
    expect(sdkFrames(b, [])).toEqual([]);
    expect(sdkFrames('not an error', prefixes)).toEqual([]);
    expect(sdkFrames(undefined, prefixes)).toEqual([]);
  });
});
