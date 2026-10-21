package br.com.myfinancehub;

import android.content.Context;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE)
public final class InvestmentQuoteUpdaterTest {
    private static final String HASH = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private Context context;
    private FinanceStore store;
    private Instant now;
    private Clock clock;

    @Before public void before() {
        context = RuntimeEnvironment.getApplication();
        context.deleteDatabase("myfinancehub.db");
        context.getSharedPreferences("investment_quotes", Context.MODE_PRIVATE).edit().clear().commit();
        InvestmentQuoteCache.clear(context);
        store = new FinanceStore(context);
        now = Instant.now().minusSeconds(120).truncatedTo(ChronoUnit.SECONDS);
        clock = Clock.fixed(now, ZoneOffset.UTC);
    }
    @After public void after() {
        store.close(); context.deleteDatabase("myfinancehub.db");
        context.getSharedPreferences("investment_quotes", Context.MODE_PRIVATE).edit().clear().commit();
        InvestmentQuoteCache.clear(context);
    }

    @Test public void sameSymbolIsRequestedOnceForManualPositionsAndB3WithoutMutatingCustody() {
        Models.Investment first = investment("Posição fictícia A", "PETR4", "2.5", true, 100);
        Models.Investment second = investment("Posição fictícia B", "PETR4", "3", true, 200);
        Models.Investment manual = investment("Posição fictícia manual", "VALE3", "4", false, 300);
        importSnapshot();
        AtomicInteger requests = new AtomicInteger();
        InvestmentQuotes api = api((uri, headers) -> {
            requests.incrementAndGet();
            assertEquals("symbols=PETR4", uri.getQuery());
            assertFalse("No quantity or financial amount may leave the device", uri.toString().contains("2.5"));
            return response("PETR4", "12.345", now);
        });
        InvestmentQuoteUpdater.Result result = updater().refresh(store, api, false);
        assertEquals(1, requests.get()); assertEquals(2, result.updated); assertEquals(1, result.cached);
        assertEquals(0, result.failed);
        assertEquals(3086, find(first.id).currentCents);
        assertEquals(3704, find(second.id).currentCents);
        assertEquals(100, find(first.id).investedCents);
        assertEquals(300, find(manual.id).currentCents);
        assertEquals("12.345", find(first.id).quotePrice);
        assertEquals(999, store.b3Snapshot().totalCents());
        assertEquals(HASH, store.b3Snapshot().sourceHash);
        assertTrue(store.entries().isEmpty()); assertEquals(0, store.totalBalance());
        assertNotNull(InvestmentQuoteCache.read(context, "PETR4"));
    }

    @Test public void automaticRefreshIsOptInAndThrottlesSuccessfulAndFailedAttempts() {
        investment("Posição fictícia", "PETR4", "1", true, 100);
        AtomicInteger requests = new AtomicInteger();
        InvestmentQuotes api = api((uri, headers) -> { requests.incrementAndGet(); throw new IOException("offline"); });
        updater().refresh(store, api, true); assertEquals(0, requests.get());
        InvestmentQuoteUpdater.saveConfiguration(context, "", true);
        assertEquals(1, updater().refresh(store, api, true).failed);
        assertEquals(1, requests.get());
        updater().refresh(store, api, true); assertEquals(1, requests.get());
        new InvestmentQuoteUpdater(context, Clock.fixed(now.plusSeconds(3600), ZoneOffset.UTC)).refresh(store, api, true);
        assertEquals(2, requests.get());
        updater().refresh(store, api, false); assertEquals("Explicit manual requests may retry", 3, requests.get());
    }

    @Test public void failedOrOlderQuoteNeverReplacesLastKnownValue() {
        Models.Investment investment = investment("Posição fictícia", "PETR4", "1", true, 100);
        InvestmentQuoteUpdater.Result first = updater().refresh(store, api((uri, headers) -> response("PETR4", "15.20", now)), false);
        assertEquals(1, first.updated);
        InvestmentQuoteUpdater.Result old = updater().refresh(store,
                api((uri, headers) -> response("PETR4", "1.00", now.minusSeconds(60))), false);
        assertEquals(0, old.updated); assertEquals(1, old.skipped);
        assertEquals(1520, find(investment.id).currentCents);
        assertEquals("15.2", find(investment.id).quotePrice);
        InvestmentQuoteUpdater.Result failed = updater().refresh(store,
                api((uri, headers) -> new InvestmentQuotes.Response(429, "")), false);
        assertEquals(1, failed.failed); assertEquals(1520, find(investment.id).currentCents);
        assertTrue(failed.summary.contains("Valores anteriores foram preservados"));
    }

    @Test public void changingQuantityOrManualValueDuringRequestCancelsApplication() {
        Models.Investment investment = investment("Posição fictícia", "PETR4", "1", true, 100);
        InvestmentQuoteUpdater.Result changedValue = updater().refresh(store, api((uri, headers) -> {
            FinanceStore editor = new FinanceStore(context);
            try {
                Models.Investment edited = editor.investments().get(0); edited.currentCents = 777; editor.saveInvestment(edited);
            } finally { editor.close(); }
            return response("PETR4", "20", now);
        }), false);
        assertEquals(0, changedValue.updated); assertEquals(1, changedValue.skipped);
        assertEquals(777, find(investment.id).currentCents);

        InvestmentQuoteUpdater.Result changedQuantity = updater().refresh(store, api((uri, headers) -> {
            Models.Investment edited = find(investment.id); edited.quantity = "2"; store.saveInvestment(edited);
            return response("PETR4", "20", now.plusSeconds(1));
        }), false);
        assertEquals(0, changedQuantity.updated); assertEquals(1, changedQuantity.skipped);
        assertEquals("2", find(investment.id).quantity); assertEquals(777, find(investment.id).currentCents);
    }

    @Test public void disablingOrDeletingPositionDuringRequestNeverRecreatesIt() {
        Models.Investment investment = investment("Posição fictícia", "PETR4", "1", true, 100);
        InvestmentQuoteUpdater.Result disabled = updater().refresh(store, api((uri, headers) -> {
            Models.Investment edited = find(investment.id); edited.autoQuote = false; store.saveInvestment(edited);
            return response("PETR4", "20", now);
        }), false);
        assertEquals(0, disabled.updated); assertEquals(100, find(investment.id).currentCents);
        Models.Investment enabled = find(investment.id); enabled.autoQuote = true; store.saveInvestment(enabled);
        InvestmentQuoteUpdater.Result deleted = updater().refresh(store, api((uri, headers) -> {
            store.deleteInvestment(investment.id); return response("PETR4", "20", now);
        }), false);
        assertEquals(0, deleted.updated); assertTrue(store.investments().isEmpty());
    }

    @Test public void b3OnlyRefreshCachesPriceAndLeavesMissingAndFixedIncomeUntouched() {
        importSnapshot();
        InvestmentQuoteUpdater.Result result = updater().refresh(store,
                api((uri, headers) -> response("PETR4", "12.34", now)), false);
        assertEquals(0, result.updated); assertEquals(1, result.cached);
        assertEquals(2, result.skipped);
        assertEquals(999, store.b3Snapshot().totalCents());
        assertEquals(1, store.b3Snapshot().unvaluedCount());
        assertTrue(store.investments().isEmpty()); assertTrue(store.entries().isEmpty());
    }

    @Test public void credentialsStayOutOfFinancialBackupAndErrorSummary() {
        investment("Posição fictícia", "PETR4", "1", true, 100);
        String demoKey = "demonstracao-sem-credencial-real";
        InvestmentQuoteUpdater.saveConfiguration(context, demoKey, true);
        InvestmentQuoteUpdater.Result result = updater().refresh(store, api((uri, headers) -> {
            assertEquals("Bearer " + demoKey, headers.get("Authorization"));
            assertFalse(uri.toString().contains(demoKey));
            throw new IOException("do not expose " + demoKey);
        }), false);
        assertFalse(store.exportBackup().contains(demoKey));
        assertFalse(result.summary.contains(demoKey)); assertFalse(result.summary.contains("do not expose"));
        assertEquals(demoKey, InvestmentQuoteUpdater.token(context));
    }

    @Test public void changingProviderKeyAllowsImmediateAutomaticRetry() {
        investment("Posição fictícia", "PETR4", "1", true, 100);
        InvestmentQuoteUpdater.saveConfiguration(context, "demonstracao-a", true);
        AtomicInteger requests = new AtomicInteger();
        InvestmentQuotes api = api((uri, headers) -> { requests.incrementAndGet(); return response("PETR4", "10", now); });
        updater().refresh(store, api, true); updater().refresh(store, api, true); assertEquals(1, requests.get());
        InvestmentQuoteUpdater.saveConfiguration(context, "demonstracao-b", true);
        updater().refresh(store, api, true); assertEquals(2, requests.get());
    }

    @Test public void cancellationAfterNetworkResponseStopsAllApplicationsAndFurtherRequests() {
        Models.Investment first = investment("Posição fictícia A", "PETR4", "1", true, 100);
        Models.Investment second = investment("Posição fictícia B", "VALE3", "1", true, 200);
        AtomicInteger requests = new AtomicInteger();
        InvestmentQuoteUpdater.Result result;
        try {
            result = updater().refresh(store, api((uri, headers) -> {
                requests.incrementAndGet(); Thread.currentThread().interrupt(); return response("PETR4", "20", now);
            }), false);
        } finally { Thread.interrupted(); }
        assertEquals(1, requests.get()); assertEquals(0, result.updated); assertEquals(0, result.cached);
        assertEquals(2, result.skipped); assertTrue(result.summary.contains("interrompida"));
        assertEquals(100, find(first.id).currentCents); assertEquals(200, find(second.id).currentCents);
        assertNull(InvestmentQuoteCache.read(context, "PETR4"));
    }

    @Test public void bdrSymbolsWithDigitsInTheirPrefixCanUpdate() {
        Models.Investment investment = investment("BDR fictício", "A1AP34", "2", true, 100);
        AtomicInteger requests = new AtomicInteger();
        InvestmentQuoteUpdater.Result result = updater().refresh(store, api((uri, headers) -> {
            requests.incrementAndGet(); assertEquals("symbols=A1AP34", uri.getQuery());
            return response("A1AP34", "15.25", now);
        }), false);
        assertEquals(1, requests.get()); assertEquals(1, result.updated);
        assertEquals(3050, find(investment.id).currentCents);
    }

    private InvestmentQuoteUpdater updater() { return new InvestmentQuoteUpdater(context, clock); }
    private InvestmentQuotes api(InvestmentQuotes.Transport transport) { return new InvestmentQuotes(transport, clock); }
    private Models.Investment investment(String name, String ticker, String quantity, boolean automatic, long current) {
        Models.Investment investment = new Models.Investment(0, name, ticker, "Ação", current, current);
        investment.quantity = quantity; investment.autoQuote = automatic; store.saveInvestment(investment); return investment;
    }
    private Models.Investment find(long id) {
        for (Models.Investment investment : store.investments()) if (investment.id == id) return investment;
        throw new AssertionError("Missing fictitious investment " + id);
    }
    private B3Portfolio.Snapshot snapshot() {
        return new B3Portfolio.Snapshot(LocalDate.now().minusDays(1).toString(), now.toString(), HASH,
                Arrays.asList(
                        new B3Portfolio.Position("Ação fictícia", "Instituição fictícia", "", "PETR4", "", "Ação", "2", 999L),
                        new B3Portfolio.Position("Renda fixa fictícia", "Instituição fictícia", "", "ABCD11", "", "Renda fixa", "1", 0L),
                        new B3Portfolio.Position("Ativo fictício sem código", "Instituição fictícia", "", "", "", "Outro", "1", null)));
    }
    private void importSnapshot() {
        B3Portfolio.Snapshot snapshot = snapshot();
        store.replaceB3Snapshot(snapshot.positions, snapshot.asOfDate, snapshot.sourceHash);
    }
    private static InvestmentQuotes.Response response(String symbol, String price, Instant instant) {
        return new InvestmentQuotes.Response(200, "{\"results\":[{\"requestedSymbol\":\"" + symbol
                + "\",\"symbol\":\"" + symbol + "\",\"changed\":false,\"data\":{\"currency\":\"BRL\","
                + "\"regularMarketPrice\":" + price + ",\"regularMarketTime\":\"" + instant + "\"}}]}");
    }
}
