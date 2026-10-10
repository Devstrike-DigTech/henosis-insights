// Normalisers that make any vendor-supplied identifier fit schema v1's bounded patterns, so a batch
// is never rejected for a stray character and no free text slips into an identifier field.
import { sha256Hex } from './sha256.js';

const METHOD = /^[A-Za-z_$][A-Za-z0-9_.$-]{0,127}$/;
const CODE = /^[A-Z][A-Z0-9_]{0,63}$/;
const CHECK = /^[a-z][a-z0-9_]{0,63}$/;
const VERSION = /^[A-Za-z0-9+._-]{1,64}$/;
const CUSTOMER_KEY = /^[A-Za-z0-9_-]{8,128}$/;
const SDK_NAME = /^[a-z0-9][a-z0-9._-]{0,63}$/;
const APP_ID = /^[A-Za-z0-9@][A-Za-z0-9._:/-]{0,255}$/;
const FRAME_CLASS = /^[A-Za-z_$@][A-Za-z0-9_.$<>/@-]{0,255}$/;
const FRAME_FN = /^[A-Za-z_$<][A-Za-z0-9_.$<>-]{0,127}$/;

const str = (v: unknown): string => (typeof v === 'string' ? v : v == null ? '' : String(v));

export function method(raw: unknown): string {
  const s = str(raw);
  if (METHOD.test(s)) return s;
  let c = s.replace(/[^A-Za-z0-9_.$-]/g, '_') || 'unknown';
  if (!/^[A-Za-z_$]/.test(c)) c = `_${c}`;
  return c.slice(0, 128);
}

export function code(raw: unknown): string {
  const s = str(raw);
  if (CODE.test(s)) return s;
  let c =
    s
      .toUpperCase()
      .replace(/[^A-Z0-9_]/g, '_')
      .replace(/^_+|_+$/g, '') || 'UNKNOWN';
  if (!/^[A-Z]/.test(c)) c = `E_${c}`;
  return c.slice(0, 64);
}

export function checkName(raw: unknown): string {
  const s = str(raw);
  if (CHECK.test(s)) return s;
  let c =
    s
      .toLowerCase()
      .replace(/[^a-z0-9_]/g, '_')
      .replace(/_+/g, '_')
      .replace(/^_+|_+$/g, '') || 'custom';
  if (!/^[a-z]/.test(c)) c = `c_${c}`;
  return c.slice(0, 64);
}

export function version(raw: unknown): string | undefined {
  const s = str(raw);
  if (!s) return undefined;
  if (VERSION.test(s)) return s;
  return s.replace(/[^A-Za-z0-9+._-]/g, '_').slice(0, 64) || undefined;
}

export function sdkName(raw: unknown): string {
  const s = str(raw);
  if (SDK_NAME.test(s)) return s;
  return (
    s
      .toLowerCase()
      .replace(/[^a-z0-9._-]/g, '-')
      .replace(/^[._-]+/, '')
      .slice(0, 64) || 'sdk'
  );
}

export function appId(raw: unknown): string | undefined {
  const s = str(raw);
  return APP_ID.test(s) ? s : undefined;
}

/** The key if already hash-like; otherwise its SHA-256 and `ok = false`. */
export function customerKey(raw: unknown): { key: string; ok: boolean; missing: boolean } {
  const s = str(raw);
  if (CUSTOMER_KEY.test(s)) return { key: s, ok: true, missing: false };
  return { key: sha256Hex(s), ok: false, missing: s.trim() === '' };
}

export function frameClass(raw: string): string | undefined {
  const c = raw.replace(/[^A-Za-z0-9_.$<>/@-]/g, '_').slice(0, 256);
  return FRAME_CLASS.test(c) ? c : undefined;
}

export function frameFn(raw: string): string {
  const s = raw
    .replace(/^(async|new)\s+/, '')
    .replace(/\s*\[as [^\]]+\]$/, '')
    .trim();
  if (FRAME_FN.test(s)) return s;
  const c = s.replace(/[^A-Za-z0-9_.$<>-]/g, '_').slice(0, 128);
  return FRAME_FN.test(c) ? c : '<anonymous>';
}

export const permissionCheck = (permission: string): string =>
  checkName(`permission_${str(permission).split('.').pop() ?? ''}`);
