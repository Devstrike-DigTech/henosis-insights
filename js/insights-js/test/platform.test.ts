import { afterEach, describe, expect, it } from 'vitest';
import { detectPlatform, envOptOut, onPageHide } from '../src/platform.js';

const g = globalThis as Record<string, unknown>;
const saved: Record<string, PropertyDescriptor | undefined> = {};
function setGlobal(name: string, value: unknown) {
  if (!(name in saved)) saved[name] = Object.getOwnPropertyDescriptor(g, name);
  Object.defineProperty(g, name, { value, configurable: true, writable: true });
}
afterEach(() => {
  for (const [name, d] of Object.entries(saved)) {
    if (d) Object.defineProperty(g, name, d);
    else delete g[name];
  }
  for (const k of Object.keys(saved)) delete saved[k];
  delete process.env.HENOSIS_INSIGHTS_DISABLED;
});

describe('platform detection', () => {
  it('Node: package name and version from package.json, node version as osVersion', () => {
    const p = detectPlatform();
    expect(p.wrapperKind).toBe('node');
    expect(p.os).toBe('node');
    expect(p.osVersion).toBe(process.versions.node);
    expect(p.appId).toBe('@devstrike/insights-js');
    expect(p.appVersion).toBe('0.1.0');
  });

  it('React Native on iOS via the TurboModule proxy, without importing react-native', () => {
    setGlobal('navigator', { product: 'ReactNative' });
    setGlobal('__turboModuleProxy', (name: string) =>
      name === 'PlatformConstants'
        ? { getConstants: () => ({ systemName: 'iOS', osVersion: '19.0', interfaceIdiom: 'pad' }) }
        : null,
    );
    expect(detectPlatform()).toEqual({
      wrapperKind: 'react-native',
      os: 'ios',
      osVersion: '19.0',
      deviceClass: 'tablet',
    });
  });

  it('React Native on Android via the legacy native module proxy', () => {
    setGlobal('navigator', { product: 'ReactNative' });
    setGlobal('nativeModuleProxy', {
      PlatformConstants: { getConstants: () => ({ Release: '16', Version: 36, uiMode: 'normal' }) },
    });
    expect(detectPlatform()).toEqual({
      wrapperKind: 'react-native',
      os: 'android',
      osVersion: '16',
      deviceClass: undefined,
    });
  });

  it('React Native with no readable constants still reports kind and omits the rest', () => {
    setGlobal('navigator', { product: 'ReactNative' });
    expect(detectPlatform()).toMatchObject({ wrapperKind: 'react-native', os: 'other' });
    expect(detectPlatform().appId).toBeUndefined();
  });

  it('web: hostname as app id, device class only from userAgentData', () => {
    setGlobal('window', {});
    setGlobal('document', {});
    setGlobal('location', { hostname: 'shop.example.com' });
    setGlobal('navigator', { userAgentData: { mobile: true } });
    expect(detectPlatform()).toEqual({
      wrapperKind: 'web',
      os: 'web',
      deviceClass: 'phone',
      appId: 'shop.example.com',
    });
    setGlobal('navigator', {});
    expect(detectPlatform().deviceClass).toBeUndefined();
  });
});

describe('opt-out without code', () => {
  it('reads HENOSIS_INSIGHTS_DISABLED and the global flag', () => {
    expect(envOptOut()).toBe(false);
    process.env.HENOSIS_INSIGHTS_DISABLED = '1';
    expect(envOptOut()).toBe(true);
    delete process.env.HENOSIS_INSIGHTS_DISABLED;
    setGlobal('HENOSIS_INSIGHTS_DISABLED', true);
    expect(envOptOut()).toBe(true);
  });
});

describe('page hide', () => {
  it('fires on pagehide and on visibilitychange to hidden', () => {
    const listeners: Record<string, () => void> = {};
    const doc = {
      visibilityState: 'visible',
      addEventListener: (t: string, f: () => void) => (listeners[`d:${t}`] = f),
      removeEventListener: () => {},
    };
    setGlobal('document', doc);
    setGlobal('window', {
      addEventListener: (t: string, f: () => void) => (listeners[`w:${t}`] = f),
      removeEventListener: () => {},
    });
    let n = 0;
    onPageHide(() => n++);
    listeners['w:pagehide']?.();
    listeners['d:visibilitychange']?.();
    doc.visibilityState = 'hidden';
    listeners['d:visibilitychange']?.();
    expect(n).toBe(2);
  });

  it('is a no-op outside a browser', () => {
    expect(onPageHide(() => {})).toBeUndefined();
  });
});
