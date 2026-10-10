export type SendResult =
  | { kind: 'accepted' }
  | { kind: 'rejected'; status: number }
  | { kind: 'retry'; status?: number };

export interface Transport {
  send(
    endpoint: string,
    writeKey: string,
    body: string,
    options: { keepalive: boolean },
  ): Promise<SendResult>;
}

export function classify(status: number): SendResult {
  if (status >= 200 && status < 300) return { kind: 'accepted' };
  if (status === 408 || status === 429 || status >= 500) return { kind: 'retry', status };
  if (status >= 400) return { kind: 'rejected', status };
  return { kind: 'retry', status };
}

const TIMEOUT_MS = 15_000;
/** Browsers cap keepalive request bodies at 64 KB. */
export const KEEPALIVE_MAX_BYTES = 60_000;

/** `fetch` only: no third-party HTTP client. */
export const fetchTransport: Transport = {
  async send(endpoint, writeKey, body, { keepalive }) {
    const f = (globalThis as { fetch?: typeof fetch }).fetch;
    if (typeof f !== 'function') return { kind: 'retry' };
    const controller = typeof AbortController === 'function' ? new AbortController() : undefined;
    const timer = controller ? setTimeout(() => controller.abort(), TIMEOUT_MS) : undefined;
    try {
      const init: RequestInit = {
        method: 'POST',
        headers: { 'content-type': 'application/json', authorization: `Bearer ${writeKey}` },
        body,
        keepalive: keepalive && body.length <= KEEPALIVE_MAX_BYTES,
        credentials: 'omit',
      };
      if (controller) init.signal = controller.signal;
      const res = await f(endpoint, init);
      return classify(res.status);
    } catch {
      return { kind: 'retry' };
    } finally {
      if (timer) clearTimeout(timer);
    }
  },
};
