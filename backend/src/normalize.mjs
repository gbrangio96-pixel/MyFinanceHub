/**
 * Minimal read-only DTOs for the Pluggy accounts/transactions endpoints.
 * No object is a local ledger entry, and no account balance is an opening balance.
 * Sources: https://docs.pluggy.ai/openapi/pluggy-api.json and
 * https://docs.pluggy.ai/en/docs/products/transactions.md
 */

const MAX_SAFE_CENTS = 9_007_199_254_740_991n;
// At 2**46, adjacent IEEE-754 numbers are more than one cent apart. Reject
// numeric values at that scale before interpreting a potentially rounded value.
const MAX_NUMERIC_AMOUNT = 2 ** 46;
const ID_PATTERN = /^[A-Za-z0-9][A-Za-z0-9._:-]{0,199}$/;
const CONTROL_CHARACTERS = /[\u0000-\u001f\u007f-\u009f]/u;

function invalid() {
  // Do not echo institution responses, identifiers, PII or credentials in errors.
  return new Error('Invalid or unsupported financial data.');
}

function record(value) {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) throw invalid();
  const prototype = Object.getPrototypeOf(value);
  if (prototype !== Object.prototype && prototype !== null) throw invalid();
  return value;
}

function id(value) {
  if (typeof value !== 'string' || !ID_PATTERN.test(value)) throw invalid();
  return value;
}

function text(value, limit) {
  if (typeof value !== 'string' || value.length > limit || CONTROL_CHARACTERS.test(value)) throw invalid();
  const result = value.trim();
  if (result.length === 0) throw invalid();
  return result;
}

function brl(value) {
  // The provider schema requires currencyCode; never assume a missing currency.
  if (value !== 'BRL') throw invalid();
  return value;
}

function cents(value, positive = false) {
  if (typeof value === 'number') {
    if (!Number.isFinite(value) || Math.abs(value) >= MAX_NUMERIC_AMOUNT) throw invalid();
    value = String(value);
  } else if (typeof value !== 'string') {
    throw invalid();
  }
  // Bound before BigInt parsing. No rounding, exponent notation, comma decimals,
  // coercion of null/booleans, or silent missing-value-to-zero conversion.
  if (value.length > 24) throw invalid();
  const match = /^(-?)(0|[1-9][0-9]*)(?:\.([0-9]{1,2}))?$/.exec(value);
  if (!match) throw invalid();
  let result = BigInt(match[2]) * 100n + BigInt((match[3] ?? '').padEnd(2, '0'));
  if (result > MAX_SAFE_CENTS || positive && result === 0n) throw invalid();
  if (match[1] === '-' && !positive) result = -result;
  return result.toString();
}

function calendarDate(value) {
  if (typeof value !== 'string' || value.length > 35) throw invalid();
  const match = /^(\d{4})-(\d{2})-(\d{2})(?:T(\d{2}):(\d{2}):(\d{2})(?:\.(\d{1,3}))?(Z|[+-]\d{2}:\d{2}))?$/.exec(value);
  if (!match) throw invalid();
  const year = Number(match[1]), month = Number(match[2]), day = Number(match[3]);
  const leap = year % 4 === 0 && (year % 100 !== 0 || year % 400 === 0);
  const days = [31, leap ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31];
  if (year === 0 || month < 1 || month > 12 || day < 1 || day > days[month - 1]) throw invalid();
  if (match[4] !== undefined) {
    if (Number(match[4]) > 23 || Number(match[5]) > 59 || Number(match[6]) > 59) throw invalid();
    if (match[8] !== 'Z') {
      const hour = Number(match[8].slice(1, 3)), minute = Number(match[8].slice(4, 6));
      if (hour > 14 || minute > 59 || hour === 14 && minute !== 0) throw invalid();
    }
  }
  // Pluggy documents date as ISO-8601 UTC. Preserve its literal calendar day and
  // sourceDate; do not silently shift midnight to the previous day in GMT-3.
  return value.slice(0, 10);
}

/**
 * @returns {{externalId:string,itemId:string,name:string,kind:'BANK'|'CREDIT',currency:'BRL',reportedBalanceCents:string}}
 */
export function normalizeAccount(raw, itemId) {
  record(raw);
  itemId = id(itemId);
  if (raw.itemId !== undefined && id(raw.itemId) !== itemId) throw invalid();
  if (raw.type !== 'BANK' && raw.type !== 'CREDIT') throw invalid();
  return {
    externalId: id(raw.id),
    itemId,
    name: text(raw.name, 80),
    kind: raw.type,
    currency: brl(raw.currencyCode),
    reportedBalanceCents: cents(raw.balance),
  };
}

/**
 * The hash is deterministic within the same item/account. providerId is preferred
 * when supplied, but neither this key nor Pluggy's id guarantees identity across
 * reconnections. With no providerId a delete/re-create can produce a new key.
 * See https://docs.pluggy.ai/en/docs/products/credit-card-statement-cycle.md.
 * PENDING remains PENDING, including future dates, and must not be posted locally.
 */
export async function normalizeTransaction(raw, account) {
  record(raw); record(account);
  const externalId = id(raw.id), externalAccountId = id(account.externalId), itemId = id(account.itemId);
  if (id(raw.accountId) !== externalAccountId) throw invalid();
  if (raw.itemId !== undefined && id(raw.itemId) !== itemId) throw invalid();
  if (account.kind !== 'BANK' && account.kind !== 'CREDIT') throw invalid();
  brl(account.currency); brl(raw.currencyCode);
  if (raw.type !== 'CREDIT' && raw.type !== 'DEBIT') throw invalid();
  if (raw.status !== 'POSTED' && raw.status !== 'PENDING') throw invalid();
  const providerTransactionId = raw.providerId === undefined || raw.providerId === null ? null : id(raw.providerId);
  const identityKind = providerTransactionId === null ? 'id' : 'providerId';
  const tuple = [itemId, externalAccountId, identityKind, providerTransactionId ?? externalId];
  const encoded = new TextEncoder().encode(JSON.stringify(tuple));
  const digest = new Uint8Array(await globalThis.crypto.subtle.digest('SHA-256', encoded));
  const hash = Array.from(digest, byte => byte.toString(16).padStart(2, '0')).join('');
  return {
    externalId,
    externalAccountId,
    itemId,
    providerTransactionId,
    identityKind,
    sourceKey: `pluggy:${hash}`,
    direction: raw.type === 'CREDIT' ? 'income' : 'expense',
    status: raw.status,
    amountCents: cents(raw.amount, true),
    date: calendarDate(raw.date),
    sourceDate: raw.date,
    description: text(raw.description, 500),
    // Never guess PIX from a free-text description or a merchant/wallet name.
    method: raw.operationType === 'PIX' || raw.paymentData?.paymentMethod === 'PIX' ? 'pix' : 'other',
  };
}
