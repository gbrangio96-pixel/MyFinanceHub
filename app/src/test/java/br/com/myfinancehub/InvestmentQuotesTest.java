package br.com.myfinancehub;

import org.junit.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/** Offline, synthetic provider fixtures. No credentials or network requests are used. */
public final class InvestmentQuotesTest {
    private static final Instant NOW = Instant.parse("2026-10-21T18:00:00Z");
    private static final String TIME = "2026-10-21T17:00:00.123Z";

    @Test public void b3QuotePreservesExactDecimalAndProviderTime() throws Exception {
        Fake transport = new Fake(quote("1234567890123456.123456789012", '"' + TIME + '"'));
        InvestmentQuotes.Quote quote = service(transport).quote(" petr4 ", "");
        assertEquals("PETR4", quote.symbol);
        assertEquals("BRL", quote.currency);
        assertEquals("brapi", quote.source);
        assertEquals(new BigDecimal("1234567890123456.123456789012"), quote.price);
        assertEquals(Instant.parse(TIME), quote.asOf);
        assertEquals("https://brapi.dev/api/v2/stocks/quote?symbols=PETR4", transport.uri.toString());
        assertEquals(1, transport.calls);
    }

    @Test public void tokenIsOnlyInAuthorizationHeaderAndHeaderMapIsImmutable() throws Exception {
        Fake transport = new Fake(quote("58.23", '"' + TIME + '"'));
        service(transport).quote("PETR4", "  synthetic-secret-token  ");
        assertEquals("Bearer synthetic-secret-token", transport.headers.get("Authorization"));
        assertEquals("application/json", transport.headers.get("Accept"));
        assertEquals("https", transport.uri.getScheme());
        assertEquals("brapi.dev", transport.uri.getHost());
        assertFalse(transport.uri.toString().contains("synthetic-secret-token"));
        try { transport.headers.put("Authorization", "changed"); fail(); }
        catch (UnsupportedOperationException expected) { /* expected */ }
    }

    @Test public void unauthenticatedProbeDoesNotInventAKeyOrAppendAnEmptyToken() throws Exception {
        Fake transport = new Fake(quote("58.23", '"' + TIME + '"'));
        service(transport).quote("PETR4", null);
        assertFalse(transport.headers.containsKey("Authorization"));
        assertEquals("symbols=PETR4", transport.uri.getRawQuery());
    }

    @Test public void acceptsNumericEpochSecondsAndDoesNotReplaceAnOlderQuoteWithNow() throws Exception {
        long oldTime = Instant.parse("2026-10-20T16:00:00Z").getEpochSecond();
        InvestmentQuotes.Quote quote = service(new Fake(quote("1.0050", Long.toString(oldTime)))).quote("PETR4", "");
        assertEquals(Instant.ofEpochSecond(oldTime), quote.asOf);
        assertEquals(new BigDecimal("1.0050"), quote.price);
    }

    @Test public void rejectsChangedOrUnrelatedSymbolsBeforeApplyingAValue() {
        String base = quote("10.00", '"' + TIME + '"');
        rejectsIo(() -> service(new Fake(base.replace("\"changed\":false", "\"changed\":true"))).quote("PETR4", ""));
        rejectsIo(() -> service(new Fake(base.replace("\"symbol\":\"PETR4\"", "\"symbol\":\"VALE3\""))).quote("PETR4", ""));
        rejectsIo(() -> service(new Fake(base.replace("\"requestedSymbol\":\"PETR4\"", "\"requestedSymbol\":\"VALE3\""))).quote("PETR4", ""));
        rejectsIo(() -> service(new Fake(base.replace("\"changed\":false", "\"changed\":\"false\""))).quote("PETR4", ""));
    }

    @Test public void rejectsMissingOrForeignCurrencyRatherThanConvertingImplicitly() {
        String base = quote("10.00", '"' + TIME + '"');
        rejectsIo(() -> service(new Fake(base.replace("\"BRL\"", "\"USD\""))).quote("PETR4", ""));
        rejectsIo(() -> service(new Fake(base.replace("\"currency\":\"BRL\",", ""))).quote("PETR4", ""));
    }

    @Test public void rejectsAbsentNonpositiveStringAndPathologicalPrices() {
        for (String price : new String[]{"null", "0", "-0.01", "\"58.23\"", "NaN", "1e1000000", "0.0000000000001"})
            rejectsIo(() -> service(new Fake(quote(price, '"' + TIME + '"'))).quote("PETR4", ""));
    }

    @Test public void futureToleranceIsTenMinutesAndInvalidTimesAreRejected() throws Exception {
        service(new Fake(quote("10", '"' + NOW.plusSeconds(600).toString() + '"'))).quote("PETR4", "");
        for (String time : new String[]{'"' + NOW.plusSeconds(601).toString() + '"', "null", "0", "-1", "1.5", "\"yesterday\""})
            rejectsIo(() -> service(new Fake(quote("10", time))).quote("PETR4", ""));
    }

    @Test public void rejectsAmbiguousOrMissingQuoteResults() {
        for (String body : new String[]{"{}", "{\"results\":[]}", "{\"results\":[{},{}]}", "{\"results\":[null]}",
                quote("10", '"' + TIME + '"').replace("\"data\":{", "\"data\":null,\"ignored\":{" )})
            rejectsIo(() -> service(new Fake(body)).quote("PETR4", ""));
    }

    @Test public void searchEncodesNamesAndOnlyReturnsActiveB3AssetsInReais() throws Exception {
        String body = "{\"results\":[" + asset("PETR4", "Petrobras", "stock", "stock", "BRL", "B3", true)
                + ',' + asset("TAEE11", "Taesa", "stock", "unit", "BRL", "B3", true)
                + ',' + asset("HGLG11", "Fundo", "fund", "fii", "BRL", "B3", true)
                + ',' + asset("BOVA11", "ETF", "fund", "etf", "BRL", "B3", true)
                + ',' + asset("AAPL34", "BDR", "bdr", "bdr", "BRL", "B3", true)
                + ',' + asset("AGRO11", "Fiagro", "fund", "fi-agro", "BRL", "B3", true)
                + ',' + asset("AAPL", "US stock", "stock", "stock", "USD", "NASDAQ", true)
                + ',' + asset("INAT3", "Inativo", "stock", "stock", "BRL", "B3", false)
                + ',' + asset("RF123", "Renda fixa", "fixed_income", "bond", "BRL", "B3", true)
                + ',' + asset("BAD/3", "Inválido", "stock", "stock", "BRL", "B3", true) + "]}";
        Fake transport = new Fake(body);
        List<InvestmentQuotes.Asset> found = service(transport).search("Petróleo & gás", "");
        assertEquals(6, found.size());
        assertEquals("PETR4", found.get(0).symbol);
        assertEquals("Petrobras", found.get(0).name);
        assertEquals("AAPL34", found.get(4).symbol);
        assertEquals("/api/v2/tickers", transport.uri.getPath());
        assertEquals("search=Petr%C3%B3leo+%26+g%C3%A1s&limit=20", transport.uri.getRawQuery());
    }

    @Test public void searchUsesLongNameThenShortNameDeduplicatesAndIsImmutable() throws Exception {
        String first = asset("PETR4", "Nome curto", "stock", "stock", "BRL", "B3", true);
        String withLong = first.replace("\"name\":\"Nome curto\"", "\"name\":\"Nome curto\",\"longName\":\"Nome longo\"");
        List<InvestmentQuotes.Asset> assets = service(new Fake("{\"results\":[" + withLong + ',' + first + "]}")).search("PETR", "");
        assertEquals(1, assets.size());
        assertEquals("Nome longo", assets.get(0).name);
        try { assets.clear(); fail(); }
        catch (UnsupportedOperationException expected) { /* expected */ }
    }

    @Test public void searchDoesNotTreatUntimestampedSearchPricesAsAQuote() throws Exception {
        String item = asset("PETR4", "Petrobras", "stock", "stock", "BRL", "B3", true);
        item = item.substring(0, item.length() - 1) + ",\"quote\":{\"lastPrice\":999999,\"changePercent\":12.5}}";
        List<InvestmentQuotes.Asset> assets = service(new Fake("{\"results\":[" + item + "]}")).search("PETR", "");
        assertEquals(1, assets.size());
        assertEquals("PETR4", assets.get(0).symbol);
        assertEquals("Petrobras", assets.get(0).name);
    }

    @Test public void searchCapsResultsAtTwentyAndEmptySearchResultsAreValid() throws Exception {
        StringBuilder body = new StringBuilder("{\"results\":[");
        for (int i = 0; i < 25; i++) {
            if (i != 0) body.append(',');
            body.append(asset("ABCD" + i, "Ativo " + i, "stock", "stock", "BRL", "B3", true));
        }
        body.append("]}");
        assertEquals(20, service(new Fake(body.toString())).search("ABCD", "").size());
        assertTrue(service(new Fake("{\"results\":[]}")).search("Nada", "").isEmpty());
    }

    @Test public void cryptoQuoteUsesExplicitBrlPairAndVerifiesCoinAndCurrency() throws Exception {
        String body = crypto("BTC", "BRL", "365123.4567", '"' + TIME + '"');
        Fake transport = new Fake(body);
        InvestmentQuotes.Quote quote = service(transport).quote("btc-brl", "demo");
        assertEquals("BTC-BRL", quote.symbol);
        assertEquals(new BigDecimal("365123.4567"), quote.price);
        assertEquals(Instant.parse(TIME), quote.asOf);
        assertEquals("https://brapi.dev/api/v2/crypto?coin=BTC&currency=BRL", transport.uri.toString());
        rejectsIo(() -> service(new Fake(body.replace("\"BTC\"", "\"ETH\""))).quote("BTC-BRL", ""));
        rejectsIo(() -> service(new Fake(body.replace("\"BRL\"", "\"USD\""))).quote("BTC-BRL", ""));
        rejectsIo(() -> service(new Fake("{\"coins\":[]}")).quote("BTC-BRL", ""));
    }

    @Test public void cryptoSearchReturnsCanonicalPairsWithoutInventingPricesOrNames() throws Exception {
        Fake transport = new Fake("{\"coins\":[\"BTC\",\"ETH\",\"BTC\",\"1INCH\",\"bad/value\",null]}");
        List<InvestmentQuotes.Asset> assets = service(transport).searchCrypto("btc-brl", "demo");
        assertEquals(3, assets.size());
        assertEquals("BTC-BRL", assets.get(0).symbol);
        assertEquals("BTC (criptomoeda)", assets.get(0).name);
        assertEquals("1INCH-BRL", assets.get(2).symbol);
        assertEquals("search=btc", transport.uri.getRawQuery());
        assertEquals("/api/v2/crypto/available", transport.uri.getPath());
        try { assets.clear(); fail(); }
        catch (UnsupportedOperationException expected) { /* expected */ }
    }

    @Test public void malformedSearchAndCryptoResponsesFailAsAWhole() {
        rejectsIo(() -> service(new Fake("{\"results\":{}}")).search("PETR", ""));
        rejectsIo(() -> service(new Fake("{\"results\":[null]}")).search("PETR", ""));
        rejectsIo(() -> service(new Fake("{\"coins\":null}")).searchCrypto("BTC", ""));
        rejectsIo(() -> service(new Fake(crypto("BTC", "BRL", "null", '"' + TIME + '"'))).quote("BTC-BRL", ""));
    }

    @Test public void authenticationAndQuotaErrorsAreFriendlyAndNeverReflectResponseOrToken() {
        for (int status : new int[]{401, 403, 429, 500, 302}) {
            Fake transport = new Fake(new InvestmentQuotes.Response(status, "SECRET_PROVIDER_BODY synthetic-secret-token"));
            IOException error = rejectsIo(() -> service(transport).quote("PETR4", "synthetic-secret-token"));
            assertFalse(error.getMessage().contains("synthetic-secret-token"));
            assertFalse(error.getMessage().contains("SECRET_PROVIDER_BODY"));
            assertNull(error.getCause());
            if (status == 401 || status == 403) {
                assertTrue(error.getMessage().contains("chave brapi"));
                assertTrue(error.getMessage().contains("PETR4, VALE3, ITUB4 ou MGLU3"));
            }
            if (status == 429) assertTrue(error.getMessage().contains("limite"));
        }
    }

    @Test public void transportFailuresDoNotExposeExceptionDetailsOrCredentials() {
        InvestmentQuotes quotes = new InvestmentQuotes((uri, headers) -> {
            throw new IOException("sensitive URI and synthetic-secret-token");
        }, clock());
        IOException error = rejectsIo(() -> quotes.quote("PETR4", "synthetic-secret-token"));
        assertTrue(error.getMessage().contains("conexão"));
        assertFalse(error.getMessage().contains("synthetic-secret-token"));
        assertNull(error.getCause());
    }

    @Test public void rejectsWrongContentTypeAndOversizedResponses() {
        rejectsIo(() -> service(new Fake(new InvestmentQuotes.Response(200, "<html>failure</html>", "text/html"))).quote("PETR4", ""));
        rejectsIo(() -> service(new Fake(new InvestmentQuotes.Response(200, quote("1", '"' + TIME + '"'), null))).quote("PETR4", ""));
        rejectsIo(() -> service(new Fake(" ".repeat(InvestmentQuotes.MAX_RESPONSE_BYTES + 1))).quote("PETR4", ""));
        // UTF-8 bytes, rather than UTF-16 character count, define the response bound.
        rejectsIo(() -> service(new Fake("á".repeat(InvestmentQuotes.MAX_RESPONSE_BYTES / 2 + 1))).quote("PETR4", ""));
    }

    @Test public void jsonReaderRejectsDuplicateKeysTrailingDataAndDeepNesting() {
        String valid = quote("1", '"' + TIME + '"');
        for (String malformed : new String[]{valid + "true", valid.replace("\"currency\":\"BRL\"", "\"currency\":\"BRL\",\"currency\":\"USD\""),
                "{\"results\":[] ,}", "{\"results\":[}", "[null]", "{\"results\":[],\"extra\":" + "[".repeat(40) + "0" + "]".repeat(40) + "}"})
            rejectsIo(() -> service(new Fake(malformed)).quote("PETR4", ""));
    }

    @Test public void jsonReaderSupportsEscapesUnicodeAndScientificDecimalPrices() throws Exception {
        InvestmentQuotes.Quote quote = service(new Fake(quote("5.823e1", '"' + TIME + '"'))).quote("PETR4", "");
        assertEquals(new BigDecimal("58.23"), quote.price);
        String item = asset("PETR4", "Ação", "stock", "stock", "BRL", "B3", true)
                .replace("Ação", "A\\u00e7\\u00e3o \\\"Fictícia\\\" \\/ teste");
        List<InvestmentQuotes.Asset> found = service(new Fake("{\"results\":[" + item + "],\"ignored\":[true,false,null]}")).search("PETR", "");
        assertEquals("Ação \"Fictícia\" / teste", found.get(0).name);
    }

    @Test public void invalidUserInputsFailBeforeAnyRequest() {
        Fake transport = new Fake(quote("1", '"' + TIME + '"'));
        InvestmentQuotes quotes = service(transport);
        for (String symbol : new String[]{null, "", "PETR4/other", "PETR4?token=secret", "PETR4,VALE3", "BTC-USD"})
            rejectsArgument(() -> quotes.quote(symbol, ""));
        for (String query : new String[]{null, "", " ", "a".repeat(81), "PETR\nVALE"}) {
            rejectsArgument(() -> quotes.search(query, ""));
            rejectsArgument(() -> quotes.searchCrypto(query, ""));
        }
        rejectsArgument(() -> quotes.quote("PETR4", "demo\r\nInjected: value"));
        rejectsArgument(() -> quotes.quote("PETR4", "a".repeat(2049)));
        rejectsArgument(() -> quotes.quote("PETR4", "demo\u2028secret"));
        rejectsArgument(() -> quotes.searchCrypto("-BRL", ""));
        assertEquals(0, transport.calls);
    }

    private static String quote(String price, String time) {
        return "{\"results\":[{\"requestedSymbol\":\"PETR4\",\"symbol\":\"PETR4\",\"changed\":false,\"data\":{"
                + "\"currency\":\"BRL\",\"regularMarketPrice\":" + price + ",\"regularMarketTime\":" + time + "}}]}";
    }

    private static String crypto(String coin, String currency, String price, String time) {
        return "{\"coins\":[{\"coin\":\"" + coin + "\",\"currency\":\"" + currency + "\",\"regularMarketPrice\":"
                + price + ",\"regularMarketTime\":" + time + "}]}";
    }

    private static String asset(String symbol, String name, String type, String subType, String currency, String exchange, boolean active) {
        return "{\"symbol\":\"" + symbol + "\",\"name\":\"" + name + "\",\"assetType\":\"" + type + "\",\"subType\":\""
                + subType + "\",\"currency\":\"" + currency + "\",\"exchange\":\"" + exchange + "\",\"isActive\":" + active + "}";
    }

    private static InvestmentQuotes service(Fake transport) { return new InvestmentQuotes(transport, clock()); }
    private static Clock clock() { return Clock.fixed(NOW, ZoneOffset.UTC); }
    private interface Request { void run() throws IOException; }
    private static IOException rejectsIo(Request action) {
        try { action.run(); }
        catch (IOException expected) { return expected; }
        fail("Accepted an invalid quote response"); return null;
    }
    private static void rejectsArgument(Request action) {
        try { action.run(); }
        catch (IllegalArgumentException expected) { return; }
        catch (IOException unexpected) { throw new AssertionError(unexpected); }
        fail("Accepted invalid quote input");
    }

    private static final class Fake implements InvestmentQuotes.Transport {
        final InvestmentQuotes.Response response;
        URI uri; Map<String, String> headers; int calls;
        Fake(String body) { this(new InvestmentQuotes.Response(200, body)); }
        Fake(InvestmentQuotes.Response response) { this.response = response; }
        @Override public InvestmentQuotes.Response get(URI uri, Map<String, String> headers) {
            this.uri = uri; this.headers = headers; this.calls++; return response;
        }
    }
}
