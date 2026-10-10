import { frameClass, frameFn } from './names.js';

export interface Frame {
  cls: string;
  fn: string;
  line: number;
}

const MAX_FRAMES = 64;
// V8 / Hermes: "    at fn (file:line:col)" or "    at file:line:col"
const V8 = /^\s*at (?:(.+?) \()?(.+?):(\d+)(?::\d+)?\)?$/;
// JavaScriptCore / SpiderMonkey: "fn@file:line:col"
const GECKO = /^(.*?)@(.+?):(\d+)(?::\d+)?$/;

/**
 * Keep only frames inside the SDK's own files. The error message, its type and every other frame
 * (the app's, the framework's) are discarded here and never read into an event.
 */
export function sdkFrames(error: unknown, prefixes: readonly string[]): Frame[] {
  if (!prefixes.length) return [];
  const out: Frame[] = [];
  let current: unknown = error;
  const seen = new Set<unknown>();
  for (
    let depth = 0;
    depth < 8 && current && !seen.has(current) && out.length < MAX_FRAMES;
    depth++
  ) {
    seen.add(current);
    const stack = (current as { stack?: unknown }).stack;
    if (typeof stack === 'string') {
      for (const line of stack.split('\n')) {
        const m = V8.exec(line) ?? GECKO.exec(line);
        if (!m) continue;
        const file = (m[2] ?? '').replace(/[?#].*$/, '');
        const prefix = prefixes.find((p) => p && file.includes(p));
        if (!prefix) continue;
        const cls = frameClass(file.slice(file.indexOf(prefix)));
        if (!cls) continue;
        out.push({ cls, fn: frameFn(m[1] || '<anonymous>'), line: Number(m[3]) || 0 });
        if (out.length >= MAX_FRAMES) break;
      }
    }
    current = (current as { cause?: unknown }).cause;
  }
  return out;
}
