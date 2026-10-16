import { normalizeAccount, normalizeTransaction } from './normalize.mjs';

// Personal Meu Pluggy reads only. These bounds also fit the Worker free-plan
// subrequest budget; increasing them requires reviewing the deployment budget.
const API_ORIGIN = 'https://api.pluggy.ai';
const MAX_ITEMS = 5;
const MAX_REQUESTS = 40;
const MAX_TRANSACTION_PAGES = 20;
const MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
const MAX_TOTAL_RESPONSE_BYTES = 8 * 1024 * 1024;
const MAX_ACCOUNT_ROWS = 100;
const MAX_TRANSACTION_ROWS = 5000;
const REQUEST_TIMEOUT_MS = 15_000;
const SNAPSHOT_TIMEOUT_MS = 45_000;
const ID_PATTERN = /^[A-Za-z0-9_-]{1,128}$/;
const ITEM_STATUSES = new Set([
  'CREATED', 'UPDATED', 'UPDATING', 'WAITING_USER_INPUT',
  'LOGIN_ERROR', 'OUTDATED', 'ERROR',
]);
const ERROR_MESSAGES = {
  INVALID_CONFIG: 'Configuração do provedor inválida.',
  INVALID_RANGE: 'Período de sincronização inválido.',
  INVALID_PROVIDER_DATA: 'O provedor retornou dados inconsistentes; nenhuma sincronização foi concluída.',
  PROVIDER_UNAVAILABLE: 'Não foi possível consultar o provedor; tente novamente mais tarde.',
  REQUEST_LIMIT: 'A consulta excedeu o limite seguro; reduza o período ou as conexões.',
};

class ProviderError extends Error {
  constructor(code) {
    super(ERROR_MESSAGES[code]);
    this.name = 'ProviderError';
    this.code = code;
  }
}

function fail(code = 'INVALID_PROVIDER_DATA') {
  throw new ProviderError(code);
}

function object(value) {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}

function safeId(value) {
  return typeof value === 'string' && ID_PATTERN.test(value);
}

function validDate(value) {
  if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(value)) return false;
  const date = new Date(`${value}T00:00:00.000Z`);
  return Number.isFinite(date.getTime()) && date.toISOString().slice(0, 10) === value;
}

function providerTimestamp(value) {
  if (typeof value !== 'string' || value.length > 64
      || !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?(?:Z|[+-]\d{2}:\d{2})$/.test(value)
      || !validDate(value.slice(0, 10)) || !Number.isFinite(Date.parse(value))) return null;
  return value;
}

function validCredential(value) {
  return typeof value === 'string' && value.length > 0 && value.length <= 4096
    && value === value.trim() && !/[\u0000-\u001f\u007f]/.test(value);
}

async function readJsonBounded(response, consumeBytes) {
  const contentLength = response.headers.get('content-length');
  if (contentLength !== null && (!/^\d+$/.test(contentLength)
      || Number(contentLength) > MAX_RESPONSE_BYTES)) {
    await response.body?.cancel().catch(() => {});
    fail();
  }
  if (!response.body || typeof response.body.getReader !== 'function') fail();
  const reader = response.body.getReader();
  const chunks = [];
  let length = 0;
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      length += value.byteLength;
      if (length > MAX_RESPONSE_BYTES) fail();
      consumeBytes(value.byteLength);
      chunks.push(value);
    }
  } catch (error) {
    await reader.cancel().catch(() => {});
    throw error;
  } finally {
    reader.releaseLock();
  }
  const bytes = new Uint8Array(length);
  let offset = 0;
  for (const chunk of chunks) {
    bytes.set(chunk, offset);
    offset += chunk.byteLength;
  }
  try {
    return JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(bytes));
  } catch {
    fail();
  }
}

function nextTransactionQuery(next, accountId, dateFrom, dateTo) {
  if (typeof next !== 'string' || next.length < 2 || next.length > 8192
      || !next.startsWith('?') || /[\s#\\]/.test(next)) fail();
  const params = new URLSearchParams(next.slice(1));
  const allowed = new Set(['accountId', 'after', 'dateFrom', 'dateTo']);
  const seen = new Set();
  for (const [key, value] of params) {
    if (!allowed.has(key) || seen.has(key) || !value) fail();
    seen.add(key);
  }
  if (params.get('accountId') !== accountId || !params.has('after')) fail();
  if ((params.has('dateFrom') && params.get('dateFrom') !== dateFrom)
      || (params.has('dateTo') && params.get('dateTo') !== dateTo)) fail();
  // Pluggy's next is a complete query string, never an `after` value or URL.
  // Keep our requested range even if a cursor response omits its filters.
  if (!params.has('dateFrom')) params.set('dateFrom', dateFrom);
  if (!params.has('dateTo')) params.set('dateTo', dateTo);
  return `?${params.toString()}`;
}

function comparableTransaction(transaction) {
  // providerId is Pluggy's bank identity when available. A replay with a new
  // Pluggy id is the same event only if every normalized financial field agrees.
  if (transaction.identityKind === 'providerId') {
    const { externalId, ...identityAndData } = transaction;
    return JSON.stringify(identityAndData);
  }
  return JSON.stringify(transaction);
}

/**
 * Fetch a complete in-memory snapshot of explicitly configured own connections.
 * No persistence or partial result is returned when any request/page fails.
 * Never accepts a base URL, callback URL, or a provider continuation host/path.
 */
export async function fetchSnapshot({
  clientId, clientSecret, itemIds, dateFrom, dateTo,
  fetchImpl = globalThis.fetch, now = () => new Date(), clock = () => Date.now(),
} = {}) {
  if (!validCredential(clientId) || !validCredential(clientSecret)
      || !Array.isArray(itemIds) || itemIds.length < 1 || itemIds.length > MAX_ITEMS
      || itemIds.some((id) => !safeId(id)) || new Set(itemIds).size !== itemIds.length
      || typeof fetchImpl !== 'function' || typeof clock !== 'function') fail('INVALID_CONFIG');
  if (!validDate(dateFrom) || !validDate(dateTo) || dateFrom > dateTo) fail('INVALID_RANGE');

  let requests = 0;
  let totalResponseBytes = 0;
  let accountRows = 0;
  let transactionRows = 0;
  const startedAt = clock();
  if (!Number.isFinite(startedAt)) fail('INVALID_CONFIG');
  function remainingTime() {
    const currentTime = clock();
    if (!Number.isFinite(currentTime)) fail('INVALID_CONFIG');
    const remaining = SNAPSHOT_TIMEOUT_MS - (currentTime - startedAt);
    if (remaining <= 0) fail('REQUEST_LIMIT');
    return remaining;
  }
  function consumeBytes(length) {
    totalResponseBytes += length;
    if (totalResponseBytes > MAX_TOTAL_RESPONSE_BYTES) fail('REQUEST_LIMIT');
  }
  let apiKey;
  async function request(path, { authBody } = {}) {
    const remaining = remainingTime();
    if (++requests > MAX_REQUESTS) fail('REQUEST_LIMIT');
    const controller = new AbortController();
    let timer;
    const timedOut = new Promise((_, reject) => {
      timer = setTimeout(() => {
        controller.abort();
        reject(new ProviderError(remaining <= REQUEST_TIMEOUT_MS ? 'REQUEST_LIMIT' : 'PROVIDER_UNAVAILABLE'));
      }, Math.min(REQUEST_TIMEOUT_MS, remaining));
    });
    try {
      return await Promise.race([
        (async () => {
          const response = await fetchImpl(`${API_ORIGIN}${path}`, {
            method: authBody ? 'POST' : 'GET',
            headers: authBody
              ? { Accept: 'application/json', 'Content-Type': 'application/json' }
              : { Accept: 'application/json', 'X-API-KEY': apiKey },
            ...(authBody ? { body: JSON.stringify(authBody) } : {}),
            signal: controller.signal,
            redirect: 'error',
          });
          if (!response || !response.ok) {
            await response?.body?.cancel().catch(() => {});
            fail('PROVIDER_UNAVAILABLE');
          }
          return readJsonBounded(response, consumeBytes);
        })(),
        timedOut,
      ]);
    } catch (error) {
      if (error instanceof ProviderError) throw error;
      fail('PROVIDER_UNAVAILABLE');
    } finally {
      clearTimeout(timer);
    }
  }

  const auth = await request('/auth', { authBody: { clientId, clientSecret } });
  if (!object(auth) || typeof auth.apiKey !== 'string' || auth.apiKey.length < 1
      || auth.apiKey.length > 8192 || /[\s\u0000-\u001f\u007f]/.test(auth.apiKey)) fail();
  apiKey = auth.apiKey;

  const connections = [];
  const accountsByIdentity = new Map();
  const transactionsByIdentity = new Map();
  const transactionsByRawId = new Map();
  try {
    for (const itemId of itemIds) {
      const item = await request(`/items/${encodeURIComponent(itemId)}`);
      if (!object(item) || item.id !== itemId || typeof item.status !== 'string') fail();
      connections.push({
        externalId: itemId,
        providerUpdatedAt: providerTimestamp(item.lastUpdatedAt),
        status: ITEM_STATUSES.has(item.status) ? item.status : 'UNKNOWN',
      });
      const accountPage = await request(`/accounts?${new URLSearchParams({ itemId })}`);
      if (!object(accountPage) || !Array.isArray(accountPage.results)
          || (accountPage.totalPages !== undefined
            && (!Number.isInteger(accountPage.totalPages) || accountPage.totalPages < 0 || accountPage.totalPages > 1))
          || (accountPage.total !== undefined
            && (!Number.isInteger(accountPage.total) || accountPage.total !== accountPage.results.length))
          || (accountPage.next !== undefined && accountPage.next !== null)) fail();
      accountRows += accountPage.results.length;
      if (accountRows > MAX_ACCOUNT_ROWS) fail('REQUEST_LIMIT');
      // /accounts exposes no documented pagination inputs. Refuse a truncated
      // page rather than inventing a pagination API and returning partial money.
      for (const rawAccount of accountPage.results) {
        remainingTime();
        if (!object(rawAccount) || !safeId(rawAccount.id)
            || (rawAccount.itemId !== undefined && rawAccount.itemId !== itemId)) fail();
        const account = normalizeAccount(rawAccount, itemId);
        const accountIdentity = JSON.stringify([itemId, rawAccount.id]);
        const priorAccount = accountsByIdentity.get(accountIdentity);
        if (priorAccount) {
          if (JSON.stringify(priorAccount) !== JSON.stringify(account)) fail();
          continue;
        }
        accountsByIdentity.set(accountIdentity, account);
        let query = `?${new URLSearchParams({ accountId: rawAccount.id, dateFrom, dateTo })}`;
        const seenQueries = new Set();
        let pageCount = 0;
        while (query !== null) {
          if (++pageCount > MAX_TRANSACTION_PAGES) fail('REQUEST_LIMIT');
          if (seenQueries.has(query)) fail();
          seenQueries.add(query);
          const transactionPage = await request(`/v2/transactions${query}`);
          if (!object(transactionPage) || !Array.isArray(transactionPage.results)
              || !Object.hasOwn(transactionPage, 'next')) fail();
          transactionRows += transactionPage.results.length;
          if (transactionRows > MAX_TRANSACTION_ROWS) fail('REQUEST_LIMIT');
          for (const rawTransaction of transactionPage.results) {
            remainingTime();
            if (!object(rawTransaction) || !safeId(rawTransaction.id)
                || (rawTransaction.accountId !== undefined
                  && rawTransaction.accountId !== rawAccount.id)) fail();
            const transaction = await normalizeTransaction(rawTransaction, account);
            if (transaction.date < dateFrom || transaction.date > dateTo) fail();
            // Both identities must agree: changing providerId must not bypass a
            // collision on the same scoped Pluggy id and create a second entry.
            const rawIdentity = JSON.stringify([itemId, rawAccount.id, rawTransaction.id]);
            const rawIdentityData = JSON.stringify(transaction);
            const priorRawIdentityData = transactionsByRawId.get(rawIdentity);
            if (priorRawIdentityData !== undefined && priorRawIdentityData !== rawIdentityData) fail();
            transactionsByRawId.set(rawIdentity, rawIdentityData);
            const priorTransaction = transactionsByIdentity.get(transaction.sourceKey);
            if (priorTransaction) {
              if (comparableTransaction(priorTransaction) !== comparableTransaction(transaction)) fail();
            } else {
              transactionsByIdentity.set(transaction.sourceKey, transaction);
            }
          }
          query = transactionPage.next === null ? null
            : nextTransactionQuery(transactionPage.next, rawAccount.id, dateFrom, dateTo);
        }
      }
    }
  } catch (error) {
    if (error instanceof ProviderError) throw error;
    fail();
  }
  remainingTime();
  let observedAt;
  try {
    const instant = typeof now === 'function' ? now() : now;
    observedAt = new Date(instant).toISOString();
  } catch {
    fail('INVALID_CONFIG');
  }
  return {
    schemaVersion: 1,
    provider: 'pluggy',
    observedAt,
    range: { from: dateFrom, to: dateTo },
    connections,
    accounts: [...accountsByIdentity.values()],
    transactions: [...transactionsByIdentity.values()],
  };
}
