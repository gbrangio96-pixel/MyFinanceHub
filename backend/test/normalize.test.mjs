import assert from 'node:assert/strict';
import { test } from 'node:test';
import { normalizeAccount, normalizeTransaction } from '../src/normalize.mjs';

const itemId = 'item-demo-1';
const rawAccount = (overrides = {}) => ({
  id: 'account-demo-1', itemId, name: 'Conta de demonstração', type: 'BANK', currencyCode: 'BRL', balance: 125.29,
  ...overrides,
});
const account = (overrides = {}) => normalizeAccount(rawAccount(overrides), itemId);
const rawTransaction = (overrides = {}) => ({
  id: 'transaction-demo-1', accountId: 'account-demo-1', description: 'Mercado fictício', type: 'DEBIT',
  currencyCode: 'BRL', amount: -185.47, date: '2026-10-16T00:00:00.000Z', status: 'POSTED',
  ...overrides,
});
const normalize = (overrides = {}, destination = account()) => normalizeTransaction(rawTransaction(overrides), destination);

test('account DTO has an explicit bank/credit kind and reported balance, not an opening balance', () => {
  assert.deepEqual(account(), {
    externalId: 'account-demo-1', itemId, name: 'Conta de demonstração', kind: 'BANK', currency: 'BRL', reportedBalanceCents: '12529',
  });
  assert.equal(account({ type: 'CREDIT', balance: -50.01 }).kind, 'CREDIT');
  assert.equal(account({ balance: -50.01 }).reportedBalanceCents, '-5001');
  assert.equal(account({ balance: 0 }).reportedBalanceCents, '0');
});

test('decimal normalization preserves cents without floating-point multiplication or rounding', async () => {
  for (const [amount, expected] of [[0.29, '29'], [19.99, '1999'], [185.47, '18547'], [-0.01, '1'], ['5000.00', '500000']]) {
    assert.equal((await normalize({ amount })).amountCents, expected);
  }
  assert.equal(account({ balance: '-90071992547409.91' }).reportedBalanceCents, '-9007199254740991');
  assert.equal((await normalize({ amount: '90071992547409.91' })).amountCents, '9007199254740991');
});

test('invalid, missing, overprecise or unsafe amounts never become zero or a rounded value', async () => {
  for (const amount of [undefined, null, true, false, '', ' ', '1,23', '1e2', '01.00', '+1', '.5', NaN, Infinity, -Infinity, 1.005, '1.001', '90071992547409.92', '1'.repeat(100), 2 ** 46]) {
    assert.throws(() => account({ balance: amount }), /Invalid or unsupported financial data/);
    await assert.rejects(normalize({ amount }), /Invalid or unsupported financial data/);
  }
  for (const amount of [0, -0, '0', '-0.00']) await assert.rejects(normalize({ amount }));
});

test('direction uses provider CREDIT/DEBIT rather than the sign or the account kind', async () => {
  for (const kind of ['BANK', 'CREDIT']) for (const amount of [100, -100]) {
    assert.equal((await normalize({ type: 'DEBIT', amount }, account({ type: kind }))).direction, 'expense');
    assert.equal((await normalize({ type: 'CREDIT', amount }, account({ type: kind }))).direction, 'income');
  }
  for (const type of [undefined, null, 'income', 'UNKNOWN']) await assert.rejects(normalize({ type }));
});

test('pending remains separate from posted, including scheduled and future card transactions', async () => {
  const pending = await normalize({ status: 'PENDING', date: '2027-02-10' }, account({ type: 'CREDIT' }));
  const posted = await normalize({ status: 'POSTED', date: '2027-02-10' }, account({ type: 'CREDIT' }));
  assert.equal(pending.status, 'PENDING'); assert.equal(posted.status, 'POSTED');
  assert.equal(pending.sourceKey, posted.sourceKey);
  assert.equal(pending.date, '2027-02-10');
  for (const status of [undefined, null, 'posted', 'CANCELLED']) await assert.rejects(normalize({ status }));
});

test('BRL must be explicit on both resources, and no foreign-currency conversion is inferred', async () => {
  for (const currencyCode of [undefined, null, '', 'brl', 'USD', 'EUR']) {
    assert.throws(() => account({ currencyCode }));
    await assert.rejects(normalize({ currencyCode, amountInAccountCurrency: 10 }));
  }
  await assert.rejects(normalize({}, { ...account(), currency: 'USD' }));
});

test('IDs are bounded and resources must belong to the requested item/account', async () => {
  for (const id of ['', ' ', 'bad/id', 'bad?id', 'bad\nid', 'x'.repeat(201), null, 4]) {
    assert.throws(() => account({ id }));
    assert.throws(() => normalizeAccount(rawAccount(), id));
    await assert.rejects(normalize({ id }));
    if (id !== null) await assert.rejects(normalize({ providerId: id }));
  }
  assert.throws(() => account({ itemId: 'another-item' }));
  assert.throws(() => account({ itemId: null }));
  await assert.rejects(normalize({ accountId: 'another-account' }));
  await assert.rejects(normalize({ itemId: 'another-item' }));
  await assert.rejects(normalize({ itemId: null }));
  assert.equal(normalizeAccount({ ...rawAccount(), itemId: undefined }, itemId).itemId, itemId);
});

test('source keys repeat deterministically only within the same scoped identity', async () => {
  const original = await normalize();
  assert.match(original.sourceKey, /^pluggy:[a-f0-9]{64}$/);
  assert.equal(original.sourceKey, (await normalize()).sourceKey);
  assert.equal(original.identityKind, 'id'); assert.equal(original.providerTransactionId, null);
  assert.notEqual(original.sourceKey, (await normalize({ id: 'different-id' })).sourceKey);
  assert.notEqual(original.sourceKey, (await normalize({}, { ...account(), itemId: 'another-item' })).sourceKey);
  assert.notEqual(original.sourceKey, (await normalize({ accountId: 'another-account' }, { ...account(), externalId: 'another-account' })).sourceKey);
  const first = await normalize({ providerId: 'institution-tx-1' });
  const recreated = await normalize({ id: 'recreated-pluggy-id', providerId: 'institution-tx-1' });
  assert.equal(first.sourceKey, recreated.sourceKey);
  assert.notEqual(first.externalId, recreated.externalId);
  assert.equal(first.identityKind, 'providerId'); assert.equal(first.providerTransactionId, 'institution-tx-1');
  assert.notEqual(first.sourceKey, original.sourceKey);
  assert.notEqual(first.sourceKey, (await normalize({ providerId: 'institution-tx-2' })).sourceKey);
});

test('calendar day is validated and preserved without a timezone-induced date shift', async () => {
  const row = await normalize();
  assert.equal(row.date, '2026-10-16'); assert.equal(row.sourceDate, '2026-10-16T00:00:00.000Z');
  assert.equal((await normalize({ date: '2024-02-29T01:02:03-03:00' })).date, '2024-02-29');
  for (const date of ['2026-02-29', '2024-02-30', '2026-00-12', '2026-13-12', '2026-10-00', '0000-01-01', '16/10/2026', '2026-10-16T24:00:00Z', '2026-10-16T23:60:00Z', '2026-10-16T23:59:60Z', '2026-10-16T00:00:00', '2026-10-16T00:00:00+14:01', '2026-10-16T00:00:00+15:00', null]) {
    await assert.rejects(normalize({ date }));
  }
});

test('PIX requires explicit structured data, never a description or wallet-name guess', async () => {
  assert.equal((await normalize({ paymentData: { paymentMethod: 'PIX' } })).method, 'pix');
  assert.equal((await normalize({ operationType: 'PIX' })).method, 'pix');
  assert.equal((await normalize({ description: 'PIX Samsung Wallet', paymentData: { paymentMethod: 'TED' } })).method, 'other');
  assert.equal((await normalize({ description: 'PIX', paymentData: null, operationType: null })).method, 'other');
});

test('DTOs include only permitted fields and do not mutate provider data', async () => {
  const raw = rawTransaction({
    paymentData: { paymentMethod: 'PIX', payer: { documentNumber: { value: 'FAKE-CPF' } }, authenticationCode: 'SECRET' },
    creditCardMetadata: { cardNumber: '4111111111111111' }, merchant: { cnpj: 'FAKE-CNPJ' },
    accessToken: 'SECRET', apiKey: 'SECRET', category: 'Provider category',
  });
  const before = structuredClone(raw);
  const result = await normalizeTransaction(raw, account());
  assert.deepEqual(raw, before);
  assert.deepEqual(Object.keys(result).sort(), [
    'externalId', 'externalAccountId', 'itemId', 'providerTransactionId', 'identityKind', 'sourceKey',
    'direction', 'status', 'amountCents', 'date', 'sourceDate', 'description', 'method',
  ].sort());
  assert.doesNotMatch(JSON.stringify(result), /SECRET|FAKE-CPF|FAKE-CNPJ|4111111111111111|Provider category/);
  const normalizedAccount = account({ number: '4111111111111111', owner: 'FAKE OWNER', taxNumber: 'FAKE-CPF', creditData: { creditLimit: 5000 } });
  assert.doesNotMatch(JSON.stringify(normalizedAccount), /4111111111111111|FAKE OWNER|FAKE-CPF|creditLimit/);
});

test('malformed objects and text fail without exposing input in error messages', async () => {
  for (const raw of [null, [], 'payload', 7, new Date()]) {
    assert.throws(() => normalizeAccount(raw, itemId));
    await assert.rejects(normalizeTransaction(raw, account()));
  }
  for (const name of ['', ' ', 'x'.repeat(81), 'SENSITIVE\u0000VALUE', null]) assert.throws(() => account({ name }));
  for (const description of ['', ' ', 'x'.repeat(501), 'SENSITIVE\u0000VALUE', null]) {
    await assert.rejects(normalize({ description }), error => error.message === 'Invalid or unsupported financial data.');
  }
  assert.throws(() => account({ type: 'UNKNOWN' }));
  await assert.rejects(normalize({}, { ...account(), kind: 'UNKNOWN' }));
});
