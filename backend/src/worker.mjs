import { fetchSnapshot } from './pluggy.mjs';

const NO_STORE = {
  'content-type': 'application/json; charset=utf-8',
  'cache-control': 'no-store',
  'x-content-type-options': 'nosniff',
};

function json(body, status = 200, extraHeaders = {}) {
  return new Response(JSON.stringify(body), {
    status, headers: { ...NO_STORE, ...extraHeaders },
  });
}

function config(env) {
  const { PLUGGY_CLIENT_ID: clientId, PLUGGY_CLIENT_SECRET: clientSecret,
    PLUGGY_ITEM_IDS: rawItems, APP_ACCESS_TOKEN: accessToken } = env ?? {};
  if (typeof clientId !== 'string' || !clientId.trim() || clientId.length > 256
      || typeof clientSecret !== 'string' || !clientSecret.trim() || clientSecret.length > 4096
      || typeof accessToken !== 'string' || !/^[A-Za-z0-9_-]{32,128}$/.test(accessToken)
      || typeof rawItems !== 'string' || rawItems.length > 1024) return null;
  const itemIds = rawItems.split(',').map(value => value.trim());
  if (itemIds.length < 1 || itemIds.length > 5
      || itemIds.some(id => !/^[A-Za-z0-9_-]{1,128}$/.test(id))
      || new Set(itemIds).size !== itemIds.length) return null;
  return { clientId, clientSecret, itemIds, accessToken };
}

async function authorized(header, expected) {
  const match = /^Bearer ([A-Za-z0-9_-]{32,128})$/i.exec(header ?? '');
  if (!match) return false;
  const encoder = new TextEncoder();
  const [left, right] = await Promise.all([match[1], expected].map(value =>
    crypto.subtle.digest('SHA-256', encoder.encode(value)).then(data => new Uint8Array(data))));
  let difference = 0;
  for (let index = 0; index < left.length; index++) difference |= left[index] ^ right[index];
  return difference === 0;
}

function validDate(value) {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) return false;
  const parsed = new Date(value + 'T00:00:00.000Z');
  return Number.isFinite(parsed.valueOf()) && parsed.toISOString().slice(0, 10) === value;
}

function todayInBrazil(now) {
  const parts = new Intl.DateTimeFormat('en-US', {
    timeZone: 'America/Sao_Paulo', year: 'numeric', month: '2-digit', day: '2-digit',
  }).formatToParts(now);
  const part = type => parts.find(value => value.type === type).value;
  return `${part('year')}-${part('month')}-${part('day')}`;
}

function dateRange(url, now) {
  if ([...url.searchParams.keys()].some(key => !['from', 'to'].includes(key))
      || ['from', 'to'].some(key => url.searchParams.getAll(key).length > 1)) return null;
  const today = todayInBrazil(now);
  const to = url.searchParams.get('to') ?? today;
  if (!validDate(to) || to > today) return null;
  const from = url.searchParams.get('from')
    ?? new Date(Date.parse(to + 'T00:00:00.000Z') - 29 * 86400000).toISOString().slice(0, 10);
  if (!validDate(from) || from > to
      || Date.parse(to) - Date.parse(from) > 89 * 86400000) return null;
  return { from, to };
}

/** A single-owner, read-only bridge. No application or provider credential is returned. */
export function createWorker({ snapshot = fetchSnapshot, now = () => new Date() } = {}) {
  return {
    async fetch(request, env) {
      try {
        const url = new URL(request.url);
        if (url.protocol !== 'https:') return json({ error: 'https_required' }, 400);
        if (!['/health', '/v1/snapshot'].includes(url.pathname))
          return json({ error: 'not_found' }, 404);
        if (request.method !== 'GET')
          return json({ error: 'method_not_allowed' }, 405, { allow: 'GET' });
        const settings = config(env);
        if (url.pathname === '/health')
          return json({ configured: settings !== null, provider: 'pluggy', access: 'read_only' }, settings ? 200 : 503);
        if (!settings) return json({ error: 'setup_required' }, 503);
        if (!await authorized(request.headers.get('authorization'), settings.accessToken))
          return json({ error: 'unauthorized' }, 401, { 'www-authenticate': 'Bearer' });
        const instant = now();
        const range = dateRange(url, instant);
        if (!range) return json({ error: 'invalid_range', maximumDays: 90 }, 400);
        const result = await snapshot({ clientId: settings.clientId,
          clientSecret: settings.clientSecret, itemIds: settings.itemIds,
          dateFrom: range.from, dateTo: range.to, now: instant });
        return json(result);
      } catch (error) {
        const errors = {
          INVALID_CONFIG: [503, 'setup_required'],
          INVALID_RANGE: [400, 'invalid_range'],
          REQUEST_LIMIT: [502, 'snapshot_too_large'],
        };
        const [status, message] = errors[error?.code] ?? [502, 'provider_unavailable'];
        return json({ error: message }, status);
      }
    },
  };
}

export default createWorker();
