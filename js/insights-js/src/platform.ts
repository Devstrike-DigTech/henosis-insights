import { appId as cleanAppId, version } from './names.js';
import type { DeviceClass, DeviceOs, PlatformInfo } from './types.js';

type AnyRecord = Record<string, unknown>;
const g = globalThis as unknown as AnyRecord;

function tryGet<T>(fn: () => T): T | undefined {
  try {
    return fn();
  } catch {
    return undefined;
  }
}

/** React Native exposes its native constants on globals; read them without importing react-native. */
function reactNativeConstants(): AnyRecord | undefined {
  const viaTurbo = tryGet(() => {
    const proxy = g.__turboModuleProxy as ((name: string) => AnyRecord | null) | undefined;
    const mod = proxy?.('PlatformConstants');
    return (mod?.getConstants as (() => AnyRecord) | undefined)?.call(mod) ?? undefined;
  });
  if (viaTurbo) return viaTurbo;
  return tryGet(() => {
    const legacy = (g.nativeModuleProxy as AnyRecord | undefined)?.PlatformConstants as
      | AnyRecord
      | undefined;
    return (legacy?.getConstants as (() => AnyRecord) | undefined)?.call(legacy) ?? legacy;
  });
}

function detectReactNative(): PlatformInfo {
  const c = reactNativeConstants() ?? {};
  const systemName = typeof c.systemName === 'string' ? c.systemName : undefined;
  const isIos = systemName !== undefined || typeof c.interfaceIdiom === 'string';
  const isAndroid = !isIos && (typeof c.Release === 'string' || typeof c.Version === 'number');
  const os: DeviceOs = isIos ? 'ios' : isAndroid ? 'android' : 'other';
  let deviceClass: DeviceClass | undefined;
  if (c.interfaceIdiom === 'phone') deviceClass = 'phone';
  else if (c.interfaceIdiom === 'pad') deviceClass = 'tablet';
  return {
    wrapperKind: 'react-native',
    os,
    osVersion: version(isIos ? c.osVersion : c.Release),
    deviceClass,
  };
}

function detectWeb(): PlatformInfo {
  const nav = g.navigator as AnyRecord | undefined;
  const mobile = tryGet(() => (nav?.userAgentData as AnyRecord | undefined)?.mobile);
  return {
    wrapperKind: 'web',
    os: 'web',
    deviceClass: mobile === true ? 'phone' : mobile === false ? 'desktop' : undefined,
    appId: cleanAppId(tryGet(() => (g.location as AnyRecord).hostname)),
  };
}

function detectNode(): PlatformInfo {
  const proc = g.process as AnyRecord;
  const nodeVersion = (proc.versions as AnyRecord | undefined)?.node;
  const pkg = tryGet(() => {
    // `process.getBuiltinModule` (Node 22.3+, 20.16+) reads files without an import that would break
    // browser and React Native bundlers.
    const getBuiltin = proc.getBuiltinModule as ((id: string) => AnyRecord) | undefined;
    const fs = getBuiltin?.('node:fs') as
      | { readFileSync(p: string, e: string): string }
      | undefined;
    const cwd = (proc.cwd as () => string)();
    return fs
      ? (JSON.parse(fs.readFileSync(`${cwd}/package.json`, 'utf8')) as AnyRecord)
      : undefined;
  });
  return {
    wrapperKind: 'node',
    os: 'node',
    osVersion: version(nodeVersion),
    appId: cleanAppId(pkg?.name),
    appVersion: version(pkg?.version),
  };
}

export function detectPlatform(): PlatformInfo {
  const detected = tryGet((): PlatformInfo | undefined => {
    const nav = g.navigator as AnyRecord | undefined;
    if (nav?.product === 'ReactNative') return detectReactNative();
    if (typeof g.window === 'object' && typeof g.document === 'object') return detectWeb();
    const proc = g.process as AnyRecord | undefined;
    if (typeof (proc?.versions as AnyRecord | undefined)?.node === 'string') return detectNode();
    return undefined;
  }) ?? { wrapperKind: 'other', os: 'other' };
  return detected;
}

/** Host-level opt-out without code: `HENOSIS_INSIGHTS_DISABLED=1` (Node) or a global flag (web). */
export function envOptOut(): boolean {
  return (
    tryGet(() => {
      if (g.HENOSIS_INSIGHTS_DISABLED === true) return true;
      const env = (g.process as AnyRecord | undefined)?.env as AnyRecord | undefined;
      const v = env?.HENOSIS_INSIGHTS_DISABLED;
      return v === '1' || v === 'true';
    }) ?? false
  );
}

/** Call `cb` when a web page is being hidden or unloaded, so queued events can leave with it. */
export function onPageHide(cb: () => void): (() => void) | undefined {
  return tryGet(() => {
    const doc = g.document as AnyRecord | undefined;
    const win = g.window as AnyRecord | undefined;
    const add = win?.addEventListener as ((t: string, f: () => void) => void) | undefined;
    const remove = win?.removeEventListener as ((t: string, f: () => void) => void) | undefined;
    if (!add || !doc) return undefined;
    const onHide = () => cb();
    const onVisibility = () => {
      if (doc.visibilityState === 'hidden') cb();
    };
    add.call(win, 'pagehide', onHide);
    (doc.addEventListener as (t: string, f: () => void) => void).call(
      doc,
      'visibilitychange',
      onVisibility,
    );
    return () => {
      remove?.call(win, 'pagehide', onHide);
      (doc.removeEventListener as (t: string, f: () => void) => void).call(
        doc,
        'visibilitychange',
        onVisibility,
      );
    };
  });
}
