package br.com.myfinancehub;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class PluggyPersonalClientTest {
    private static final LocalDate FROM = LocalDate.of(2026, 10, 20), TO = LocalDate.of(2026, 10, 22);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-22T12:00:00Z"), ZoneOffset.UTC);
    private static final String CLIENT_ID = "fixture-client", CLIENT_SECRET = "private-fixture-secret", KEY = "fixture-api-key";
    private static final String CONNECT_TOKEN = "fixture-connect-token.temporary.signature";
    private interface Route { PluggyPersonalClient.Response get(String method, URI uri, Map<String, String> headers, String body) throws Exception; }
    private static final class Fake implements PluggyPersonalClient.Transport {
        JSONObject account = account(), transaction = transaction();
        String next;
        List<JSONObject> extra = new ArrayList<>();
        final List<URI> urls = new ArrayList<>();
        Route route;
        int pages;
        @Override public PluggyPersonalClient.Response request(String method, URI uri, Map<String, String> headers, String body) throws IOException {
            urls.add(uri);
            assertEquals("https", uri.getScheme()); assertEquals("api.pluggy.ai", uri.getHost());
            assertEquals(-1, uri.getPort()); assertNull(uri.getUserInfo());
            assertFalse(uri.toString().contains(CLIENT_SECRET)); assertFalse(uri.toString().contains(KEY));
            try {
                if (route != null) { PluggyPersonalClient.Response reply = route.get(method, uri, headers, body); if (reply != null) return reply; }
                if (uri.getPath().equals("/auth")) {
                    assertEquals("POST", method); assertFalse(headers.containsKey("X-API-KEY"));
                    JSONObject credentials = new JSONObject(body); assertEquals(CLIENT_ID, credentials.getString("clientId")); assertEquals(CLIENT_SECRET, credentials.getString("clientSecret"));
                    return json(new JSONObject().put("apiKey", KEY));
                }
                assertEquals("GET", method); assertEquals(KEY, headers.get("X-API-KEY")); assertNull(body);
                if (uri.getPath().startsWith("/items/")) return json(new JSONObject().put("id", uri.getPath().substring(7)).put("status", "UPDATED").put("lastUpdatedAt", "2026-10-21T12:00:00Z"));
                if (uri.getPath().equals("/accounts")) return json(new JSONObject().put("results", new JSONArray().put(account)).put("total", 1));
                if (uri.getPath().equals("/v2/transactions")) {
                    pages++;
                    JSONArray rows = new JSONArray().put(transaction); for (JSONObject row : extra) rows.put(row);
                    return json(new JSONObject().put("results", rows).put("next", next == null ? JSONObject.NULL : next));
                }
                fail("Unexpected endpoint " + uri); return null;
            } catch (IOException error) { throw error; }
            catch (Exception error) { throw new IOException(error); }
        }
        PluggyClient.Snapshot fetch() throws IOException { return new PluggyPersonalClient(this, CLOCK).fetch(CLIENT_ID, CLIENT_SECRET, Collections.singletonList("item-a"), FROM, TO); }
    }
    private static JSONObject account() {
        try {return new JSONObject().put("id", "bank-a").put("itemId", "item-a").put("name", "Conta de teste").put("type", "BANK").put("currencyCode", "BRL").put("balance", "-9876.54");}
        catch (Exception error) {throw new AssertionError(error);}
    }
    private static JSONObject transaction() {
        try {return new JSONObject().put("id", "tx-a").put("accountId", "bank-a").put("type", "DEBIT").put("status", "POSTED").put("currencyCode", "BRL").put("amount", "-25.90").put("date", "2026-10-20T00:00:00Z").put("description", "Pix escrito apenas na descrição");}
        catch (Exception error) {throw new AssertionError(error);}
    }
    private static PluggyPersonalClient.Response json(JSONObject body) {return new PluggyPersonalClient.Response(200, body.toString());}
    private static void invalid(Fake fake) throws Exception {
        try {fake.fetch(); fail("Invalid/incomplete provider data was accepted");}
        catch (PluggyPersonalClient.Failure expected) {assertFalse(expected.getMessage().contains(CLIENT_SECRET)); assertFalse(expected.getMessage().contains(KEY));}
    }
    private static String connectToken(Fake fake) throws IOException {
        return new PluggyPersonalClient(fake, CLOCK).createConnectToken(CLIENT_ID, CLIENT_SECRET);
    }
    private static void invalidConnectToken(Fake fake) throws Exception {
        try { connectToken(fake); fail("Invalid Connect response was accepted"); }
        catch (PluggyPersonalClient.Failure expected) {
            assertFalse(expected.getMessage().contains(CLIENT_SECRET));
            assertFalse(expected.getMessage().contains(KEY));
            assertFalse(expected.getMessage().contains(CONNECT_TOKEN));
            assertNull("Transport/provider errors must not retain sensitive causes", expected.getCause());
        }
    }

    @Test public void connectTokenAuthenticatesThenPostsAnEmptyBodyWithoutItemIdsOrFinancialRequests() throws Exception {
        Fake fake = new Fake();
        fake.route = (method, uri, headers, body) -> {
            if (!uri.getPath().equals("/connect_token")) return null;
            assertEquals("POST", method); assertNull(uri.getRawQuery());
            assertEquals("application/json", headers.get("Accept"));
            assertEquals("application/json", headers.get("Content-Type"));
            assertEquals(KEY, headers.get("X-API-KEY"));
            assertEquals("No item IDs, user IDs or bank credentials are sent", 0, new JSONObject(body).length());
            assertFalse(body.contains(CLIENT_ID)); assertFalse(body.contains(CLIENT_SECRET));
            return json(new JSONObject().put("accessToken", CONNECT_TOKEN));
        };
        assertEquals(CONNECT_TOKEN, connectToken(fake));
        assertEquals(2, fake.urls.size());
        assertEquals(PluggyPersonalClient.API_ORIGIN + "/auth", fake.urls.get(0).toString());
        assertEquals(PluggyPersonalClient.API_ORIGIN + "/connect_token", fake.urls.get(1).toString());
        assertEquals(0, fake.pages);
    }
    @Test public void connectTokenValidatesOnlyProjectCredentialsBeforeNetwork() throws Exception {
        PluggyPersonalClient.validateCredentials(CLIENT_ID, CLIENT_SECRET);
        for (String id : new String[]{null, "", " id", "id\n", "x".repeat(257)}) {
            Fake fake = new Fake();
            try { new PluggyPersonalClient(fake, CLOCK).createConnectToken(id, CLIENT_SECRET); fail(); }
            catch (IllegalArgumentException expected) { assertTrue(fake.urls.isEmpty()); assertFalse(expected.getMessage().contains(CLIENT_SECRET)); }
        }
        for (String secret : new String[]{null, "", " secret", "secret\n", "x".repeat(4097)}) {
            Fake fake = new Fake();
            try { new PluggyPersonalClient(fake, CLOCK).createConnectToken(CLIENT_ID, secret); fail(); }
            catch (IllegalArgumentException expected) { assertTrue(fake.urls.isEmpty()); assertFalse(expected.getMessage().contains("itemId")); }
        }
    }
    @Test public void connectTokenRejectsMissingMalformedAndWhitespaceTokensWithoutFallbackRequests() throws Exception {
        for (Object token : new Object[]{JSONObject.NULL, 42, true, new JSONObject(), new JSONArray(), "", " ",
                " token", "token ", "token\nvalue", "token\tvalue", "token\u007fvalue", "token\u0085value",
                "token\u00a0value", "token\u2003value", "x".repeat(8193)}) {
            Fake fake = new Fake();
            fake.route = (method, uri, headers, body) -> uri.getPath().equals("/connect_token")
                    ? json(new JSONObject().put("accessToken", token)) : null;
            invalidConnectToken(fake); assertEquals(2, fake.urls.size());
        }
        Fake missing = new Fake(); missing.route = (m, u, h, b) -> u.getPath().equals("/connect_token") ? json(new JSONObject()) : null;
        invalidConnectToken(missing); assertEquals(2, missing.urls.size());
    }
    @Test public void connectTokenRejectsMalformedAuthenticationBeforeCreatingAConnectSession() throws Exception {
        for (Object apiKey : new Object[]{JSONObject.NULL, 23, "", "key value", "key\nvalue", "key\u00a0value", "x".repeat(8193)}) {
            Fake fake = new Fake(); fake.route = (m, u, h, b) -> json(new JSONObject().put("apiKey", apiKey));
            invalidConnectToken(fake); assertEquals(1, fake.urls.size());
        }
    }
    @Test public void connectTokenRedactsProviderAndTransportErrorsAndNeverFollowsAnotherEndpoint() throws Exception {
        for (String endpoint : new String[]{"/auth", "/connect_token"}) {
            for (int status : new int[]{301, 302, 307, 401, 403, 429, 500, 503}) {
                Fake fake = new Fake(); fake.route = (m, u, h, b) -> u.getPath().equals(endpoint)
                        ? new PluggyPersonalClient.Response(status, CLIENT_SECRET + KEY + CONNECT_TOKEN) : null;
                invalidConnectToken(fake); assertEquals(endpoint.equals("/auth") ? 1 : 2, fake.urls.size());
            }
            Fake failed = new Fake(); failed.route = (m, u, h, b) -> {
                if (u.getPath().equals(endpoint)) throw new IOException(CLIENT_SECRET + KEY + CONNECT_TOKEN);
                return null;
            };
            invalidConnectToken(failed); assertEquals(endpoint.equals("/auth") ? 1 : 2, failed.urls.size());
        }
    }
    @Test public void connectTokenRetainsJsonByteAndCancellationLimits() throws Exception {
        for (PluggyPersonalClient.Response response : new PluggyPersonalClient.Response[]{
                new PluggyPersonalClient.Response(200, "{\"accessToken\":\"" + CONNECT_TOKEN + "\"}", "text/html"),
                new PluggyPersonalClient.Response(200, "{\"accessToken\":\"" + CONNECT_TOKEN + "\"} trailing"),
                new PluggyPersonalClient.Response(200, "x".repeat(PluggyPersonalClient.MAX_RESPONSE_BYTES + 1)),
                new PluggyPersonalClient.Response(200, null), null}) {
            Fake fake = new Fake();
            PluggyPersonalClient.Response auth = json(new JSONObject().put("apiKey", KEY));
            // A separate transport allows a null reply without falling through the fixture router.
            PluggyPersonalClient client = new PluggyPersonalClient((method, uri, headers, body) -> {
                fake.urls.add(uri); return uri.getPath().equals("/auth") ? auth : response;
            }, CLOCK);
            try { client.createConnectToken(CLIENT_ID, CLIENT_SECRET); fail(); }
            catch (PluggyPersonalClient.Failure expected) { assertEquals(2, fake.urls.size()); assertFalse(expected.getMessage().contains(CONNECT_TOKEN)); }
        }
        Fake cancelled = new Fake();
        try { Thread.currentThread().interrupt(); invalidConnectToken(cancelled); assertTrue(cancelled.urls.isEmpty()); }
        finally { Thread.interrupted(); }
    }

    @Test public void readsOnlyFixedProviderAndPreservesExactMoneySourceIdentityAndLiteralDate() throws Exception {
        Fake fake = new Fake(); PluggyClient.Snapshot result = fake.fetch();
        assertEquals(4, fake.urls.size()); assertEquals(1, result.accounts.size()); assertEquals(-987654, result.accounts.get(0).balance);
        assertEquals(1, result.transactions.size()); PluggyClient.Transaction row = result.transactions.get(0);
        assertEquals(2590, row.amount); assertEquals("expense", row.direction); assertEquals("2026-10-20", row.date);
        assertEquals("other", row.method); assertEquals("pluggy:a7408703f1f50a46be95ea93e47bbbc2311f035f316bd59246043b65249b5f73", row.sourceKey);
        assertEquals("2026-10-22T12:00:00Z", result.observedAt);
    }
    @Test public void pendingAndCreditRemainExplicitWithoutInventingPostedCash() throws Exception {
        Fake fake = new Fake(); fake.account.put("type", "CREDIT"); fake.transaction.put("status", "PENDING").put("type", "CREDIT").put("operationType", "PIX");
        PluggyClient.Snapshot result = fake.fetch(); assertEquals("CREDIT", result.accounts.get(0).kind); assertEquals("PENDING", result.transactions.get(0).status);
        assertEquals("income", result.transactions.get(0).direction); assertEquals("pix", result.transactions.get(0).method);
    }
    @Test public void credentialsAndItemIdsAreValidatedBeforeAnyNetwork() throws Exception {
        for (List<String> ids : Arrays.asList(Collections.<String>emptyList(), Arrays.asList("a", "a"), Arrays.asList("../other"), Arrays.asList("a", "b", "c", "d", "e", "f"))) {
            Fake fake = new Fake(); try {new PluggyPersonalClient(fake, CLOCK).fetch(CLIENT_ID, CLIENT_SECRET, ids, FROM, TO); fail();} catch (IllegalArgumentException expected) {assertTrue(fake.urls.isEmpty());}
        }
        for (String secret : new String[]{"", " secret", "secret\n", "x".repeat(4097)}) {
            Fake fake = new Fake(); try {new PluggyPersonalClient(fake, CLOCK).fetch(CLIENT_ID, secret, Collections.singletonList("item-a"), FROM, TO); fail();} catch (IllegalArgumentException expected) {assertTrue(fake.urls.isEmpty());}
        }
        assertEquals(Arrays.asList("a", "b", "c"), PluggyPersonalClient.itemIds("a, b; c"));
    }
    @Test public void futureReversedAndOverlongPeriodsAreRejectedBeforeNetwork() throws Exception {
        for (LocalDate[] range : new LocalDate[][]{{FROM, TO.plusDays(1)}, {TO, FROM}, {TO.minusDays(90), TO}}) {
            Fake fake = new Fake(); try {new PluggyPersonalClient(fake, CLOCK).fetch(CLIENT_ID, CLIENT_SECRET, Collections.singletonList("item-a"), range[0], range[1]); fail();} catch (IllegalArgumentException expected) {assertTrue(fake.urls.isEmpty());}
        }
    }
    @Test public void paginationUsesDocumentedQueryAndRestoresRequestedFilters() throws Exception {
        Fake fake = new Fake(); fake.next = "?accountId=bank-a&after=cursor%2F2";
        fake.route = (method, uri, headers, body) -> uri.getRawQuery() != null && uri.getRawQuery().contains("after=")
                ? json(new JSONObject().put("results", new JSONArray()).put("next", JSONObject.NULL)) : null;
        PluggyClient.Snapshot result = fake.fetch(); assertEquals(1, result.transactions.size());
        URI continuation = fake.urls.get(4); assertEquals("/v2/transactions", continuation.getPath());
        assertTrue(continuation.getRawQuery().contains("after=cursor%2F2")); assertTrue(continuation.getRawQuery().contains("dateFrom=2026-10-20")); assertTrue(continuation.getRawQuery().contains("dateTo=2026-10-22"));
    }
    @Test public void hostileContinuationCannotChangeHostAccountPeriodOrParameters() throws Exception {
        for (String next : new String[]{"https://evil.example/data", "/v2/transactions?after=x", "?accountId=other&after=x", "?accountId=bank-a&after=x&dateFrom=2020-01-01", "?accountId=bank-a&after=x&unknown=y", "?accountId=bank-a&after=x&after=y", "?accountId=bank-a&after=x#fragment", "?accountId=bank-a&after=x\\bad"}) {
            Fake fake = new Fake(); fake.next = next; invalid(fake); assertEquals(4, fake.urls.size());
        }
    }
    @Test public void pageFailureNeverReturnsTheEarlierFinancialRows() throws Exception {
        Fake fake = new Fake(); fake.next = "?accountId=bank-a&after=second";
        fake.route = (method, uri, headers, body) -> uri.getRawQuery() != null && uri.getRawQuery().contains("after=") ? new PluggyPersonalClient.Response(503, CLIENT_SECRET) : null;
        invalid(fake);
    }
    @Test public void paginationCyclesAndPageLimitFailInsteadOfReturningPartialSnapshot() throws Exception {
        Fake cycle = new Fake(); cycle.next = "?accountId=bank-a&after=repeat"; invalid(cycle); assertEquals(5, cycle.urls.size());
        Fake pages = new Fake(); pages.route = (method, uri, headers, body) -> uri.getPath().equals("/v2/transactions")
                ? json(new JSONObject().put("results", new JSONArray()).put("next", "?accountId=bank-a&after=page" + pages.urls.size())) : null;
        invalid(pages); assertEquals(23, pages.urls.size());
    }
    @Test public void duplicateBankIdentityWithNewProviderRowIdIsConsolidatedOnlyWhenFieldsAgree() throws Exception {
        Fake fake = new Fake(); fake.transaction.put("providerId", "bank-event");
        JSONObject alias = new JSONObject(fake.transaction.toString()).put("id", "new-tx-id"); fake.extra.add(alias);
        assertEquals(1, fake.fetch().transactions.size());
        alias.put("amount", "26.90"); invalid(fake);
    }
    @Test public void changedProviderIdentityCannotHideConflictOnSameRawTransactionId() throws Exception {
        Fake fake = new Fake(); fake.transaction.put("providerId", "event-one"); fake.extra.add(new JSONObject(fake.transaction.toString()).put("providerId", "event-two")); invalid(fake);
    }
    @Test public void truncatedOrConflictingAccountsFailClosed() throws Exception {
        Fake truncated = new Fake(); truncated.route = (method, uri, headers, body) -> uri.getPath().equals("/accounts") ? json(new JSONObject().put("results", new JSONArray().put(truncated.account)).put("totalPages", 2)) : null; invalid(truncated);
        Fake duplicate = new Fake(); duplicate.route = (method, uri, headers, body) -> uri.getPath().equals("/accounts") ? json(new JSONObject().put("results", new JSONArray().put(duplicate.account).put(new JSONObject(duplicate.account.toString()).put("balance", "1.00")))) : null; invalid(duplicate);
    }
    @Test public void unsupportedMoneyCurrencyDatesAndReferencesNeverProducePartialResult() throws Exception {
        for (Object amount : new Object[]{JSONObject.NULL, "0", "1.001", "1e3", "999999999999999999999999", true, 70368744177664d}) {Fake fake = new Fake(); fake.transaction.put("amount", amount); invalid(fake);}
        Fake currency = new Fake(); currency.account.put("currencyCode", "USD"); invalid(currency);
        Fake reference = new Fake(); reference.transaction.put("accountId", "outside"); invalid(reference);
        Fake date = new Fake(); date.transaction.put("date", "2026-02-30"); invalid(date);
        Fake outOfRange = new Fake(); outOfRange.transaction.put("date", "2026-10-19"); invalid(outOfRange);
    }
    @Test public void numericMoneyUsesExactDecimalInterpretationAndNeverInfersPixFromDescription() throws Exception {
        Fake fake = new Fake(); fake.transaction.put("amount", -25.9); assertEquals(2590, fake.fetch().transactions.get(0).amount);
        fake.transaction.put("paymentData", new JSONObject().put("paymentMethod", "PIX")); assertEquals("pix", fake.fetch().transactions.get(0).method);
    }
    @Test public void statusErrorsRedirectsMalformedBodiesAndNetworkErrorsAreRedacted() throws Exception {
        for (int status : new int[]{301, 302, 307, 401, 403, 429, 500, 503}) {Fake fake = new Fake(); fake.route = (m, u, h, b) -> new PluggyPersonalClient.Response(status, CLIENT_SECRET + KEY); invalid(fake); assertEquals(1, fake.urls.size());}
        Fake broken = new Fake(); broken.route = (m, u, h, b) -> {throw new IOException(CLIENT_SECRET + KEY);}; invalid(broken);
        Fake html = new Fake(); html.route = (m, u, h, b) -> new PluggyPersonalClient.Response(200, "{}", "text/html"); invalid(html);
        Fake trailing = new Fake(); trailing.route = (m, u, h, b) -> new PluggyPersonalClient.Response(200, "{} trailing"); invalid(trailing);
    }
    @Test public void rawRowAndByteBudgetsCountRepeatedData() throws Exception {
        Fake accounts = new Fake(); JSONArray many = new JSONArray(); for (int i = 0; i < 101; i++) many.put(accounts.account);
        accounts.route = (m, u, h, b) -> u.getPath().equals("/accounts") ? json(new JSONObject().put("results", many)) : null; invalid(accounts);
        Fake rows = new Fake(); JSONArray repeated = new JSONArray(); for (int i = 0; i < 5001; i++) repeated.put(rows.transaction);
        rows.route = (m, u, h, b) -> u.getPath().equals("/v2/transactions") ? json(new JSONObject().put("results", repeated).put("next", JSONObject.NULL)) : null; invalid(rows);
        Fake bytes = new Fake(); bytes.route = (m, u, h, b) -> new PluggyPersonalClient.Response(200, "a".repeat(PluggyPersonalClient.MAX_RESPONSE_BYTES + 1)); invalid(bytes);
    }
    @Test public void aggregateByteBudgetIncludesAuthAndMetadata() throws Exception {
        Fake fake = new Fake(); String padding = "a".repeat(1_700_000);
        fake.route = (method, uri, headers, body) -> {
            JSONObject response;
            if (uri.getPath().equals("/auth")) response = new JSONObject().put("apiKey", KEY);
            else if (uri.getPath().startsWith("/items/")) response = new JSONObject().put("id", "item-a").put("status", "UPDATED");
            else if (uri.getPath().equals("/accounts")) response = new JSONObject().put("results", new JSONArray().put(fake.account));
            else response = new JSONObject().put("results", new JSONArray()).put("next", "?accountId=bank-a&after=page" + fake.urls.size());
            return json(response.put("padding", padding));
        };
        invalid(fake); assertEquals(5, fake.urls.size());
    }
    @Test public void cancelledThreadDoesNotStartAnyProviderRequest() throws Exception {
        Fake fake = new Fake();
        try {Thread.currentThread().interrupt(); invalid(fake); assertTrue(fake.urls.isEmpty());}
        finally {Thread.interrupted();}
    }
    @Test public void requestBudgetIncludesAuthenticationItemsAndEmptyAccounts() throws Exception {
        Fake fake = new Fake(); JSONArray many = new JSONArray();
        for (int i = 0; i < 40; i++) many.put(new JSONObject(fake.account.toString()).put("id", "account-" + i));
        fake.route = (m, u, h, b) -> u.getPath().equals("/accounts") ? json(new JSONObject().put("results", many))
                : u.getPath().equals("/v2/transactions") ? json(new JSONObject().put("results", new JSONArray()).put("next", JSONObject.NULL)) : null;
        invalid(fake); assertEquals(40, fake.urls.size());
    }
}
