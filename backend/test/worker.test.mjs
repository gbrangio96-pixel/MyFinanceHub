import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createWorker } from '../src/worker.mjs';

const env = {
  PLUGGY_CLIENT_ID: 'fixture-client', PLUGGY_CLIENT_SECRET: 'fixture-private-secret',
  PLUGGY_ITEM_IDS: 'item-a,item-b', APP_ACCESS_TOKEN: 'a'.repeat(43),
};
const instant = new Date('2026-10-16T01:00:00.000Z');
const request = (path = '/v1/snapshot', token = env.APP_ACCESS_TOKEN, method = 'GET') =>
  new Request('https://finance.example' + path, { method,
    headers: token ? { authorization: 'Bearer ' + token } : {} });

test('no network or financial response is available without authentication', async () => {
  let calls = 0;
  const worker = createWorker({ snapshot: async () => { calls++; return {}; } });
  for (const token of [null, 'b'.repeat(43), 'short']) {
    const response = await worker.fetch(request('/v1/snapshot', token), env);
    assert.equal(response.status, 401);
    assert.equal(response.headers.get('cache-control'), 'no-store');
    assert.equal(response.headers.get('access-control-allow-origin'), null);
  }
  assert.equal(calls, 0);
});

test('health reports configuration, not a verified bank connection; secrets never appear', async () => {
  const worker = createWorker({ snapshot: () => assert.fail('health must not contact provider') });
  const response = await worker.fetch(request('/health', null), env);
  assert.deepEqual(await response.json(), { configured: true, provider: 'pluggy', access: 'read_only' });
  const missing = await worker.fetch(request('/health', null), {});
  assert.equal(missing.status, 503);
  assert.deepEqual(await missing.json(), { configured: false, provider: 'pluggy', access: 'read_only' });
});

test('snapshot defaults to 30 calendar days in Sao Paulo and passes only configured items', async () => {
  let received;
  const data = { schemaVersion: 1, accounts: [], transactions: [] };
  const worker = createWorker({ now: () => instant, snapshot: async options => { received = options; return data; } });
  const response = await worker.fetch(request(), env);
  assert.equal(response.status, 200);
  assert.deepEqual(await response.json(), data);
  assert.deepEqual(received.itemIds, ['item-a', 'item-b']);
  assert.equal(received.dateTo, '2026-10-15');
  assert.equal(received.dateFrom, '2026-09-16');
  assert.equal(received.now, instant);
  assert.equal(received.accessToken, undefined);
});

test('invalid, duplicate, unknown, future and overlong ranges never call provider', async () => {
  const worker = createWorker({ now: () => instant, snapshot: () => assert.fail('invalid request') });
  for (const query of ['?from=2026-02-30', '?from=2026-10-14&from=2026-10-13',
    '?itemId=outside', '?to=2026-10-16', '?from=2026-01-01&to=2026-10-15', '?from=2026-10-15&to=2026-10-14']) {
    assert.equal((await worker.fetch(request('/v1/snapshot' + query), env)).status, 400, query);
  }
});

test('configured identities cannot be empty, duplicated, path segments or more than five', async () => {
  const worker = createWorker({ snapshot: () => assert.fail('invalid config') });
  for (const ids of ['', 'item-a,item-a', '../another', 'a,b,c,d,e,f']) {
    const response = await worker.fetch(request(), { ...env, PLUGGY_ITEM_IDS: ids });
    assert.equal(response.status, 503);
  }
  assert.equal((await worker.fetch(request(), { ...env, APP_ACCESS_TOKEN: 'short' })).status, 503);
});

test('only HTTPS and GET are accepted; there are no mutation or payment routes', async () => {
  const worker = createWorker({ snapshot: () => assert.fail('forbidden route') });
  assert.equal((await worker.fetch(new Request('http://finance.example/v1/snapshot'), env)).status, 400);
  assert.equal((await worker.fetch(request('/v1/snapshot', env.APP_ACCESS_TOKEN, 'POST'), env)).status, 405);
  assert.equal((await worker.fetch(request('/v1/payments'), env)).status, 404);
});

test('provider errors expose neither credentials nor account or transaction data', async () => {
  const worker = createWorker({ now: () => instant, snapshot: async () => {
    const error = new Error('fixture-private-secret; CPF-PRIVATE; API_KEY_PRIVATE');
    error.code = 'INVALID_PROVIDER_DATA'; throw error;
  } });
  const response = await worker.fetch(request(), env);
  assert.equal(response.status, 502);
  assert.deepEqual(await response.json(), { error: 'provider_unavailable' });
});

test('budget exhaustion returns no partial financial snapshot', async () => {
  const worker = createWorker({ now: () => instant, snapshot: async () => {
    const error = new Error('internal request budget'); error.code = 'REQUEST_LIMIT'; throw error;
  } });
  const response = await worker.fetch(request(), env);
  assert.equal(response.status, 502);
  assert.deepEqual(await response.json(), { error: 'snapshot_too_large' });
});
