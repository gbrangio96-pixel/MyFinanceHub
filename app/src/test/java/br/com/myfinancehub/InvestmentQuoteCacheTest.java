package br.com.myfinancehub;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Map;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE)
public final class InvestmentQuoteCacheTest {
    private Context context;
    private SharedPreferences preferences;
    private Instant time;

    @Before public void before() {
        context = RuntimeEnvironment.getApplication();
        preferences = context.getSharedPreferences(InvestmentQuoteCache.PREFERENCES, Context.MODE_PRIVATE);
        preferences.edit().clear().commit();
        time = Instant.now().minus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
    }

    @Test public void publicQuoteRoundTripsWithoutLosingDecimalPrecisionOrProviderDate() throws Exception {
        InvestmentQuotes.Quote quote = quote("PETR4", "42.123400000001", time);
        assertTrue(InvestmentQuoteCache.store(context, quote));
        InvestmentQuoteCache.Value cached = InvestmentQuoteCache.read(new ContextWrapper(context), " petr4 ");
        assertNotNull(cached);
        assertEquals("PETR4", cached.symbol);
        assertEquals(new BigDecimal("42.123400000001"), cached.price);
        assertEquals(time, cached.asOf);
        assertEquals("brapi", cached.source);
        assertEquals(cached.price, InvestmentQuoteCache.get(context, "PETR4").price);
        String raw = preferences.getString(InvestmentQuoteCache.KEY, "");
        assertTrue(raw.contains("42.123400000001"));
        assertFalse(raw.contains("Authorization"));
        assertFalse(raw.contains("apiKey"));
    }

    @Test public void olderAndEqualTimestampsCannotReplaceTheLatestKnownPrice() throws Exception {
        InvestmentQuoteCache.put(context, quote("VALE3", "55.50", time));
        assertFalse(InvestmentQuoteCache.store(context, quote("VALE3", "1.00", time.minusSeconds(1))));
        assertFalse(InvestmentQuoteCache.store(context, quote("VALE3", "2.00", time)));
        assertEquals(new BigDecimal("55.50"), InvestmentQuoteCache.read(context, "VALE3").price);
        assertTrue(InvestmentQuoteCache.store(context, quote("VALE3", "56.25", time.plusSeconds(1))));
        assertEquals(time.plusSeconds(1), InvestmentQuoteCache.read(context, "VALE3").asOf);
        assertEquals(new BigDecimal("56.25"), InvestmentQuoteCache.read(context, "VALE3").price);
    }

    @Test public void snapshotIsImmutableAndKeepsThePricesAtTheTimeItWasRead() throws Exception {
        assertTrue(InvestmentQuoteCache.store(context, quote("PETR4", "42.00", time)));
        assertTrue(InvestmentQuoteCache.store(context, quote("ETH-BRL", "12000.00000001", time)));
        Map<String, InvestmentQuoteCache.Value> snapshot = InvestmentQuoteCache.snapshot(context);
        assertEquals(2, snapshot.size());
        assertEquals(new BigDecimal("42.00"), snapshot.get("PETR4").price);
        assertEquals(new BigDecimal("12000.00000001"), snapshot.get("ETH-BRL").price);
        assertEquals(time, snapshot.get("PETR4").asOf);
        try { snapshot.clear(); fail("A rendered snapshot must be immutable"); }
        catch (UnsupportedOperationException expected) { /* Caller cannot alter cached values. */ }
        assertTrue(InvestmentQuoteCache.store(context, quote("PETR4", "43.00", time.plusSeconds(1))));
        assertEquals(new BigDecimal("42.00"), snapshot.get("PETR4").price);
        assertEquals(new BigDecimal("43.00"), InvestmentQuoteCache.snapshot(context).get("PETR4").price);
        preferences.edit().putString(InvestmentQuoteCache.KEY, "not-json").commit();
        assertTrue(InvestmentQuoteCache.snapshot(context).isEmpty());
        assertTrue(InvestmentQuoteCache.snapshot(null).isEmpty());
    }

    @Test public void boundsCacheAtTwoHundredSymbolsAndEvictsByProviderDate() throws Exception {
        for (int index = 0; index <= InvestmentQuoteCache.MAX_SYMBOLS; index++)
            assertTrue(InvestmentQuoteCache.store(context, quote(symbol(index), "10.01", time.plusSeconds(index))));
        assertNull(InvestmentQuoteCache.read(context, symbol(0)));
        assertNotNull(InvestmentQuoteCache.read(context, symbol(1)));
        assertEquals(time.plusSeconds(200), InvestmentQuoteCache.read(context, symbol(200)).asOf);
        JSONObject json = new JSONObject(preferences.getString(InvestmentQuoteCache.KEY, ""));
        assertEquals(200, json.getJSONArray("quotes").length());
        assertTrue(preferences.getString(InvestmentQuoteCache.KEY, "").length() < InvestmentQuoteCache.MAX_JSON_BYTES);

        String previous = preferences.getString(InvestmentQuoteCache.KEY, "");
        assertFalse(InvestmentQuoteCache.store(context, quote("OLD999", "11.00", time.minusSeconds(1))));
        assertNull(InvestmentQuoteCache.read(context, "OLD999"));
        assertEquals(previous, preferences.getString(InvestmentQuoteCache.KEY, ""));
    }

    @Test public void corruptedPreferencesAreUnavailableAndNextValidQuoteRepairsThem() throws Exception {
        for (String raw : Arrays.asList("not-json", "[]", "{", "null", "{\"version\":2,\"quotes\":[]}",
                "{\"version\":1,\"quotes\":{}}", "{\"version\":1}", "{\"quotes\":[]}")) {
            preferences.edit().putString(InvestmentQuoteCache.KEY, raw).commit();
            assertNull("Damaged cache should be ignored: " + raw, InvestmentQuoteCache.read(context, "PETR4"));
        }
        preferences.edit().putInt(InvestmentQuoteCache.KEY, 123).commit();
        assertNull(InvestmentQuoteCache.read(context, "PETR4"));
        assertTrue(InvestmentQuoteCache.store(context, quote("PETR4", "43.00", time)));
        assertEquals(new BigDecimal("43.00"), InvestmentQuoteCache.read(context, "PETR4").price);
    }

    @Test public void invalidCachedPriceCurrencySourceSymbolOrDateIsNeverDisplayed() throws Exception {
        for (Object[] change : new Object[][]{
                {"price", "0"}, {"price", "-1.00"}, {"price", "NaN"},
                {"price", "1E+999999"}, {"price", "0.0000000000001"},
                {"price", "1234567890123456789012345678901"}, {"price", 123},
                {"currency", "USD"}, {"source", "other-provider"},
                {"symbol", "../PETR4"}, {"symbol", "petr4"},
                {"asOf", "not-an-instant"}, {"asOf", Instant.EPOCH.toString()},
                {"asOf", Instant.now().plus(1, ChronoUnit.DAYS).toString()}
        }) {
            JSONObject row = row("PETR4", "40.00", time);
            row.put((String) change[0], change[1]);
            cache(new JSONArray().put(row));
            assertNull("Reject damaged " + change[0] + "=" + change[1], InvestmentQuoteCache.read(context, "PETR4"));
        }
    }

    @Test public void duplicatesAndOversizedOrDeeplyNestedCacheAreRejectedSafely() throws Exception {
        cache(new JSONArray().put(row("PETR4", "40.00", time)).put(row("PETR4", "41.00", time)));
        assertNull(InvestmentQuoteCache.read(context, "PETR4"));

        JSONArray oversized = new JSONArray();
        for (int index = 0; index <= InvestmentQuoteCache.MAX_SYMBOLS; index++)
            oversized.put(row(symbol(index), "10.00", time));
        cache(oversized);
        assertNull(InvestmentQuoteCache.read(context, symbol(0)));

        char[] huge = new char[InvestmentQuoteCache.MAX_JSON_BYTES + 1];
        Arrays.fill(huge, ' ');
        preferences.edit().putString(InvestmentQuoteCache.KEY, new String(huge)).commit();
        assertNull(InvestmentQuoteCache.read(context, "PETR4"));

        String deep = "[".repeat(1000) + "0" + "]".repeat(1000);
        preferences.edit().putString(InvestmentQuoteCache.KEY, deep).commit();
        assertNull(InvestmentQuoteCache.read(context, "PETR4"));
    }

    @Test public void cryptoBrlKeyRemainsSeparateFromB3Assets() throws Exception {
        cache(new JSONArray().put(row("BTC-BRL", "350123.12345678", time))
                .put(row("ETH-BRL", "12500.12345678", time))
                .put(row("1INCH-BRL", "1.12345678", time))
                .put(row("PETR4", "42.00", time)));
        assertEquals(new BigDecimal("350123.12345678"), InvestmentQuoteCache.read(context, "btc-brl").price);
        assertEquals(new BigDecimal("12500.12345678"), InvestmentQuoteCache.read(context, "eth-brl").price);
        assertEquals(new BigDecimal("1.12345678"), InvestmentQuoteCache.read(context, "1inch-brl").price);
        assertEquals(new BigDecimal("42.00"), InvestmentQuoteCache.read(context, "PETR4").price);
        for (String invalid : new String[]{"BTC-USD", "ETH-USD", "A-BRL", "ABCDEFGHIJKLM-BRL",
                "https://example.com", "", "..", "A", "PETR4/extra"})
            assertNull(InvestmentQuoteCache.read(context, invalid));
        assertNull(InvestmentQuoteCache.read(context, null));
        assertNull(InvestmentQuoteCache.read(null, "PETR4"));
        assertFalse(InvestmentQuoteCache.store(context, null));
    }

    @Test public void anySupportedCryptoBrlQuoteCanBeStoredAndReadBack() throws Exception {
        for (String symbol : new String[]{"BTC-BRL", "ETH-BRL", "1INCH-BRL"}) {
            assertTrue(InvestmentQuoteCache.store(context, quote(symbol, "12.12345678", time)));
            InvestmentQuoteCache.Value cached = InvestmentQuoteCache.read(context, symbol.toLowerCase(java.util.Locale.ROOT));
            assertNotNull(cached);
            assertEquals(symbol, cached.symbol);
            assertEquals(new BigDecimal("12.12345678"), cached.price);
            assertEquals(time, cached.asOf);
        }
    }

    @Test public void clearingQuoteCachePreservesUnrelatedPrivatePreferences() throws Exception {
        context.getSharedPreferences("appearance", Context.MODE_PRIVATE).edit().putString("mode", "dark").commit();
        assertTrue(InvestmentQuoteCache.store(context, quote("PETR4", "42.00", time)));
        preferences.edit().putString("future_setting", "preserve").commit();
        InvestmentQuoteCache.clear(context);
        assertNull(InvestmentQuoteCache.read(context, "PETR4"));
        assertEquals("preserve", preferences.getString("future_setting", ""));
        assertEquals("dark", context.getSharedPreferences("appearance", Context.MODE_PRIVATE).getString("mode", ""));
    }

    private InvestmentQuotes.Quote quote(String symbol, String price, Instant asOf) throws Exception {
        String body = "{\"results\":[{\"requestedSymbol\":\"" + symbol + "\",\"symbol\":\"" + symbol
                + "\",\"changed\":false,\"data\":{\"currency\":\"BRL\",\"regularMarketPrice\":" + price
                + ",\"regularMarketTime\":\"" + asOf + "\"}}]}";
        if (symbol.endsWith("-BRL")) body = "{\"coins\":[{\"coin\":\"" + symbol.substring(0, symbol.length() - 4)
                + "\",\"currency\":\"BRL\",\"regularMarketPrice\":" + price + ",\"regularMarketTime\":\"" + asOf + "\"}]}";
        final String responseBody = body;
        InvestmentQuotes service = new InvestmentQuotes((uri, headers) -> new InvestmentQuotes.Response(200, responseBody),
                Clock.fixed(asOf.plusSeconds(1), ZoneOffset.UTC));
        return service.quote(symbol, "");
    }

    private JSONObject row(String symbol, String price, Instant asOf) throws Exception {
        return new JSONObject().put("symbol", symbol).put("price", price).put("asOf", asOf.toString())
                .put("currency", "BRL").put("source", "brapi");
    }

    private void cache(JSONArray rows) throws Exception {
        preferences.edit().putString(InvestmentQuoteCache.KEY,
                new JSONObject().put("version", 1).put("quotes", rows).toString()).commit();
    }

    private String symbol(int number) { return "TST" + String.format(java.util.Locale.ROOT, "%03d", number); }
}
