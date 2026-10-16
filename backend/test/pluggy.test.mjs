import test from 'node:test';
import assert from 'node:assert/strict';
import { fetchSnapshot } from '../src/pluggy.mjs';

const ITEM = 'item-own-1';
const ACCOUNT = 'account-own-1';
const RANGE = { dateFrom: '2026-10-01', dateTo: '2026-10-15' };
const CONFIG = {
  clientId: 'client-test', clientSecret: 'private-test-secret', itemIds: [ITEM],
  ...RANGE, now: () => new Date('2026-10-16T12:00:00.000Z'),
};
const account = (patch = {}) => ({
  id: ACCOUNT, itemId: ITEM, type: 'BANK', name: 'Conta de demonstração',
  currencyCode: 'BRL', balance: 500.25, ...patch,
});
const transaction = (patch = {}) => ({
  id: 'transaction-1', accountId: ACCOUNT, currencyCode: 'BRL',
  amount: -25.1, type: 'DEBIT', status: 'POSTED',
  date: '2026-10-05T00:00:00.000Z', description: 'Compra fictícia', ...patch,
});
const item = (patch = {}) => ({
  id: ITEM, status: 'UPDATED', lastUpdatedAt: '2026-10-15T10:00:00.000Z', ...patch,
});
const json = (value, status = 200) => new Response(JSON.stringify(value), {
  status, headers: { 'content-type': 'application/json' },
});

function fixture({ items = [item()], accountPages, transactions, extra } = {}) {
  const calls = [];
  const accountByItem = accountPages ?? { [ITEM]: { results: [account()], total: 1, totalPages: 1 } };
  const transactionPages = transactions ?? [{ results: [transaction()], next: null }];
  let transactionIndex = 0;
  const fetchImpl = async (input, options) => {
    const url = new URL(input);
    calls.push({ url, options });
    if (extra) {
      const result = await extra(url, options, calls.length);
      if (result) return result;
    }
    assert.equal(url.origin, 'https://api.pluggy.ai');
    assert.equal(options.redirect, 'error');
    assert.ok(options.signal instanceof AbortSignal);
    if (url.pathname === '/auth') {
      assert.equal(options.method, 'POST');
      assert.deepEqual(JSON.parse(options.body), {
        clientId: CONFIG.clientId, clientSecret: CONFIG.clientSecret,
      });
      assert.equal(options.headers['X-API-KEY'], undefined);
      return json({ apiKey: 'test-api-key' });
    }
    assert.equal(options.method, 'GET');
    assert.equal(options.body, undefined);
    assert.equal(options.headers['X-API-KEY'], 'test-api-key');
    if (url.pathname.startsWith('/items/')) {
      const requested = decodeURIComponent(url.pathname.slice('/items/'.length));
      return json(items.find((entry) => entry.id === requested) ?? item({ id: requested }));
    }
    if (url.pathname === '/accounts') {
      assert.deepEqual([...url.searchParams.keys()], ['itemId']);
      return json(accountByItem[url.searchParams.get('itemId')] ?? { results: [] });
    }
    if (url.pathname === '/v2/transactions') {
      return json(transactionPages[transactionIndex++]);
    }
    throw new Error('Unexpected request');
  };
  return { fetchImpl, calls };
}

async function rejectsCode(promise, code) {
  await assert.rejects(promise, (error) => {
    assert.equal(error.code, code);
    assert.doesNotMatch(`${error.message}\n${error.stack}`, /private-test-secret|test-api-key|CPF-SECRET|provider-stack-secret/);
    return true;
  });
}

test('authenticates at fixed origin and returns complete normalized personal snapshot', async () => {
  const { fetchImpl, calls } = fixture();
  const snapshot = await fetchSnapshot({ ...CONFIG, fetchImpl });
  assert.equal(calls.length, 4);
  assert.deepEqual(snapshot.connections, [{
    externalId: ITEM, status: 'UPDATED', providerUpdatedAt: '2026-10-15T10:00:00.000Z',
  }]);
  assert.equal(snapshot.schemaVersion, 1);
  assert.equal(snapshot.provider, 'pluggy');
  assert.equal(snapshot.observedAt, '2026-10-16T12:00:00.000Z');
  assert.deepEqual(snapshot.range, { from: RANGE.dateFrom, to: RANGE.dateTo });
  assert.equal(snapshot.accounts[0].reportedBalanceCents, '50025');
  assert.equal(snapshot.transactions[0].amountCents, '2510');
  assert.equal(snapshot.transactions[0].direction, 'expense');
  assert.equal(snapshot.transactions[0].status, 'POSTED');
  assert.match(snapshot.transactions[0].sourceKey, /^pluggy:[0-9a-f]{64}$/);
  const query = calls.at(-1).url.searchParams;
  assert.equal(query.get('accountId'), ACCOUNT);
  assert.equal(query.get('dateFrom'), RANGE.dateFrom);
  assert.equal(query.get('dateTo'), RANGE.dateTo);
});

test('cursor next is a query, not the whole after value; preserves range and deduplicates replay', async () => {
  const next = `?accountId=${ACCOUNT}&after=YWJj%2BMTIz%3D`;
  const { fetchImpl, calls } = fixture({ transactions: [
    { results: [transaction()], next },
    { results: [transaction(), transaction({ id: 'transaction-2', status: 'PENDING' })], next: null },
  ] });
  const snapshot = await fetchSnapshot({ ...CONFIG, fetchImpl });
  assert.equal(calls.length, 5);
  assert.equal(calls.at(-1).url.searchParams.get('after'), 'YWJj+MTIz=');
  assert.equal(calls.at(-1).url.searchParams.get('accountId'), ACCOUNT);
  assert.equal(calls.at(-1).url.searchParams.get('dateFrom'), RANGE.dateFrom);
  assert.deepEqual(snapshot.transactions.map((entry) => entry.status), ['POSTED', 'PENDING']);
});

test('replayed bank providerId with new Pluggy id deduplicates only identical normalized data', async () => {
  const { fetchImpl } = fixture({ transactions: [{ results: [
    transaction({ providerId: 'bank-own-id' }),
    transaction({ id: 'transaction-new-id', providerId: 'bank-own-id' }),
  ], next: null }] });
  const snapshot = await fetchSnapshot({ ...CONFIG, fetchImpl });
  assert.equal(snapshot.transactions.length, 1);
  assert.equal(snapshot.transactions[0].identityKind, 'providerId');
  for (const patch of [{ amount: -25.11 }, { status: 'PENDING' }, { description: 'Outra compra' }]) {
    const conflicting = fixture({ transactions: [{ results: [
      transaction({ providerId: 'bank-own-id' }),
      transaction({ ...patch, id: 'transaction-new-id', providerId: 'bank-own-id' }),
    ], next: null }] });
    await rejectsCode(fetchSnapshot({ ...CONFIG, fetchImpl: conflicting.fetchImpl }), 'INVALID_PROVIDER_DATA');
  }
});

test('same transaction identity with conflicting amount or status fails the entire snapshot', async () => {
  for (const patch of [{ amount: -25.11 }, { status: 'PENDING' }]) {
    const { fetchImpl } = fixture({ transactions: [{
      results: [transaction(), transaction(patch)], next: null,
    }] });
    await rejectsCode(fetchSnapshot({ ...CONFIG, fetchImpl }), 'INVALID_PROVIDER_DATA');
  }
});

test('same scoped raw id cannot switch provider identity or its absence to bypass conflicts', async () => {
  for (const [original, replacement] of [
    ['bank-id', 'another-bank-id'], ['bank-id', null], ['bank-id', undefined],
    [null, 'bank-id'], [undefined, 'bank-id'],
  ]) {
    const { fetchImpl } = fixture({ transactions: [
      { results: [transaction({ providerId: original })], next: `?accountId=${ACCOUNT}&after=second` },
      { results: [transaction({ providerId: replacement })], next: null },
    ] });
    await rejectsCode(fetchSnapshot({ ...CONFIG, fetchImpl }), 'INVALID_PROVIDER_DATA');
  }
});

test('identical raw ids in different accounts stay separately scoped', async () => {
  const secondId = 'account-own-2';
  const { fetchImpl } = fixture({
    accountPages: { [ITEM]: { results: [account(), account({ id: secondId })] } },
    transactions: [
      { results: [transaction()], next: null },
      { results: [transaction({ accountId: secondId })], next: null },
    ],
  });
  const snapshot = await fetchSnapshot({ ...CONFIG, fetchImpl });
  assert.equal(snapshot.accounts.length, 2);
  assert.equal(snapshot.transactions.length, 2);
  assert.notEqual(snapshot.transactions[0].sourceKey, snapshot.transactions[1].sourceKey);
});

test('identical account replay avoids duplicate fetch; conflicting account replay fails', async () => {
  const replay = fixture({ accountPages: { [ITEM]: { results: [account(), account()] } } });
  const snapshot = await fetchSnapshot({ ...CONFIG, fetchImpl: replay.fetchImpl });
  assert.equal(snapshot.accounts.length, 1);
  assert.equal(replay.calls.length, 4);
  const conflicting = fixture({
    accountPages: { [ITEM]: { results: [account(), account({ balance: 999 })] } },
  });
  await rejectsCode(fetchSnapshot({ ...CONFIG, fetchImpl: conflicting.fetchImpl }), 'INVALID_PROVIDER_DATA');
});

test('auth 401 and network exceptions never expose provider response or cause', async () => {
  const denied = fixture({ extra: (url) => url.pathname === '/auth'
    ? json({ message: 'CPF-SECRET private-test-secret provider-stack-secret' }, 401) : null });
  await rejectsCode(fetchSnapshot({ ...CONFIG, fetchImpl: denied.fetchImpl }), 'PROVIDER_UNAVAILABLE');
  assert.equal(denied.calls.length, 1);
  await rejectsCode(fetchSnapshot({ ...CONFIG, fetchImpl: async () => {
    throw new Error('provider-stack-secret private-test-secret');
  } }), 'PROVIDER_UNAVAILABLE');
});

test('failure on page two never returns a partial snapshot', async () => {
  const { fetchImpl, calls } = fixture({
    transactions: [{ results: [transaction()], next: `?accountId=${ACCOUNT}&after=page2` }],
    extra: (url) => url.pathname === '/v2/transactions' && url.searchParams.has('after')
      ? json({ message: 'CPF-SECRET' }, 503) : null,
  });
  await rejectsCode(fetchSnapshot({ ...CONFIG, fetchImpl }), 'PROVIDER_UNAVAILABLE');
  assert.equal(calls.length, 5);
});

test('rejects URL, path, duplicate, cross-account and unexpected pagination inputs before following', async (t) => {
  const bad = [
    'https://attacker.invalid/v2/transactions?accountId=account-own-1&after=x',
    '//attacker.invalid/?accountId=account-own-1&after=x',
    '/v2/transactions?accountId=account-own-1&after=x',
    '?accountId=another-account&after=x',
    `?accountId=${ACCOUNT}&accountId=${ACCOUNT}&after=x`,
    `?accountId=${ACCOUNT}&after=x&after=y`,
    `?accountId=${ACCOUNT}&after=x&clientSecret=private-test-secret`,
    `?accountId=${ACCOUNT}&after=x#fragment`,
    `?accountId=${ACCOUNT}&after=x&dateFrom=2020-01-01`,
    `?accountId=${ACCOUNT}&after=x&dateTo=2030-01-01`,
    `?accountId=${ACCOUNT}`, '?after=x', '', false,
  ];
  for (const next of bad) {
    await t.test(String(next), async () => {
      const { fetchImpl, calls } = fixture({ transactions: [{ results: [], next }] });
      await rejectsCode(fetchSnapshot({ ...CONFIG, fetchImpl }), 'INVALID_PROVIDER_DATA');
      assert.equal(calls.length, 4);
    });
  }
});

test('repeated cursor and more than 20 transaction pages are rejected', async () => {
  const loop = fixture({ transactions: [
    { results: [], next: `?accountId=${ACCOUNT}&after=same` },
    { results: [], next: `?accountId=${ACCOUNT}&after=same` },
  ] });
  await rejectsCode(fetchSnapshot({ ...CONFIG, fetchImpl: loop.fetchImpl }), 'INVALID_PROVIDER_DATA');
  assert.equal(loop.calls.length, 5);
  const pages = Array.from({ length: 21 }, (_, i) => ({
    results: [], next: `?accountId=${ACCOUNT}&after=${i + 1}`,
  }));
  const bounded = fixture({ transactions: pages });
  await rejectsCode(fetchSnapshot({ ...CONFIG, fetchImpl: bounded.fetchImpl }), 'REQUEST_LIMIT');
  assert.equal(bounded.calls.length, 23);
});

test('global request budget stops before a 41st outbound request', async () => {
  const manyAccounts = Array.from({ length: 40 }, (_, i) => account({ id: `account-${i}` }));
  const { fetchImpl, calls } = fixture({
    accountPages: { [ITEM]: { results: manyAccounts } },
    transactions: Array.from({ length: 40 }, () => ({ results: [], next: null })),
  });
  await rejectsCode(fetchSnapshot({ ...CONFIG, fetchImpl }), 'REQUEST_LIMIT');
  assert.equal(calls.length, 40);
});

test('caps account rows at 100, including raw replays, before processing an oversized account page', async () => {
  const accepted = fixture({ accountPages: {
    [ITEM]: { results: Array.from({ length: 100 }, () => account()) },
  } });
  const snapshot = await fetchSnapshot({ ...CONFIG, fetchImpl: accepted.fetchImpl });
  assert.equal(snapshot.accounts.length, 1);
  const denied = fixture({ accountPages: {
    [ITEM]: { results: Array.from({ length: 101 }, () => account()) },
  } });
  await rejectsCode(fetchSnapshot({ ...CONFIG, fetchImpl: denied.fetchImpl }), 'REQUEST_LIMIT');
  assert.equal(denied.calls.length, 3);
});

test('caps raw transaction rows at 5000 across pages, including duplicates', async () => {
  const first = Array.from({ length: 2500 }, () => transaction());
  const accepted = fixture({ transactions: [
    { results: first, next: `?accountId=${ACCOUNT}&after=second` },
    { results: first, next: null },
  ] });
  const snapshot = await fetchSnapshot({ ...CONFIG, fetchImpl: accepted.fetchImpl });
  assert.equal(snapshot.transactions.length, 1);
  const denied = fixture({ transactions: [
    { results: first, next: `?accountId=${ACCOUNT}&after=second` },
    { results: [...first, transaction()], next: null },
  ] });
  await rejectsCode(fetchSnapshot({ ...CONFIG, fetchImpl: denied.fetchImpl }), 'REQUEST_LIMIT');
  assert.equal(denied.calls.length, 5);
});

test('caps total streamed provider bytes at 8 MiB even when each response is below 2 MiB', async () => {
  let pages = 0;
  let cancelled = 0;
  const encoder = new TextEncoder();
  const paddingChunk = encoder.encode('x'.repeat(64 * 1024));
  const { fetchImpl, calls } = fixture({ extra: (url) => {
    if (url.pathname !== '/v2/transactions') return null;
    pages++;
    const header = JSON.stringify({ results: [], next: `?accountId=${ACCOUNT}&after=${pages}` });
    let chunk = -1;
    return new Response(new ReadableStream({
      pull(controller) {
        if (chunk === -1) controller.enqueue(encoder.encode(`${header.slice(0, -1)},"padding":"`));
        else if (chunk < 16) controller.enqueue(paddingChunk);
        else if (chunk === 16) controller.enqueue(encoder.encode('"}'));
        else controller.close();
        chunk++;
      },
      cancel() { cancelled++; },
    }));
  } });
  await rejectsCode(fetchSnapshot({ ...CONFIG, fetchImpl }), 'REQUEST_LIMIT');
  assert.equal(pages, 8);
  assert.equal(calls.length, 11);
  assert.equal(cancelled, 1);
});

test('total 45-second deadline prevents another request without a real-time wait', async () => {
  let elapsed = 0;
  const setup = fixture({ extra: () => {
    elapsed += 12_000;
    return null;
  } });
  await rejectsCode(fetchSnapshot({ ...CONFIG, fetchImpl: setup.fetchImpl, clock: () => elapsed }), 'REQUEST_LIMIT');
  assert.equal(setup.calls.length, 4);
});

test('bounds streamed and declared provider responses and rejects invalid JSON', async () => {
  for (const response of [
    new Response('x', { headers: { 'content-length': String(2 * 1024 * 1024 + 1) } }),
    new Response('x'.repeat(2 * 1024 * 1024 + 1)),
    new Response('{not-json'),
    new Response(new Uint8Array([255, 254])),
  ]) {
    await rejectsCode(fetchSnapshot({ ...CONFIG, fetchImpl: async () => response }), 'INVALID_PROVIDER_DATA');
  }
});

test('validates configured scope, credentials and range before any request', async () => {
  let calls = 0;
  const fetchImpl = async () => { calls++; throw new Error('must not fetch'); };
  for (const patch of [
    { clientId: '' }, { clientSecret: '' }, { clientSecret: 'bad\nsecret' },
    { itemIds: [] }, { itemIds: [ITEM, ITEM] }, { itemIds: Array.from({ length: 6 }, (_, i) => `item-${i}`) },
    { itemIds: ['../outside'] }, { itemIds: ['https://attacker.invalid'] },
  ]) {
    await rejectsCode(fetchSnapshot({ ...CONFIG, ...patch, fetchImpl }), 'INVALID_CONFIG');
  }
  for (const patch of [
    { dateFrom: '2026-02-30' }, { dateTo: '2026-01-01' }, { dateFrom: '01/10/2026' },
  ]) {
    await rejectsCode(fetchSnapshot({ ...CONFIG, ...patch, fetchImpl }), 'INVALID_RANGE');
  }
  assert.equal(calls, 0);
});

test('rejects mismatched ownership, unsafe remote ids, missing page completeness and out-of-range rows', async () => {
  const cases = [
    { accountPages: { [ITEM]: { results: [account({ itemId: 'another-item' })] } } },
    { accountPages: { [ITEM]: { results: [account({ id: '../../outside' })] } } },
    { accountPages: { [ITEM]: { results: [account()], totalPages: 2 } } },
    { accountPages: { [ITEM]: { results: [account()], total: 2 } } },
    { transactions: [{ results: [transaction({ accountId: 'another-account' })], next: null }] },
    { transactions: [{ results: [transaction({ id: '../outside' })], next: null }] },
    { transactions: [{ results: [transaction({ date: '2026-09-30T00:00:00.000Z' })], next: null }] },
    { transactions: [{ results: [] }] },
  ];
  for (const scenario of cases) {
    await rejectsCode(fetchSnapshot({ ...CONFIG, fetchImpl: fixture(scenario).fetchImpl }), 'INVALID_PROVIDER_DATA');
  }
});

test('does not invent provider freshness or return untrusted item status text', async () => {
  const { fetchImpl } = fixture({
    items: [item({ lastUpdatedAt: '2026-02-30T00:00:00.000Z', status: 'CPF-SECRET' })],
  });
  const snapshot = await fetchSnapshot({ ...CONFIG, fetchImpl });
  assert.equal(snapshot.connections[0].providerUpdatedAt, null);
  assert.equal(snapshot.connections[0].status, 'UNKNOWN');
  assert.doesNotMatch(JSON.stringify(snapshot), /CPF-SECRET/);
});
