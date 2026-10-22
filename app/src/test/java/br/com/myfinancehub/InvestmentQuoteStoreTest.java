package br.com.myfinancehub;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.time.Instant;
import java.util.Collections;

import static org.junit.Assert.*;

/** Financial invariants for optional unit-price updates and additive schema/backup migration. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE)
public final class InvestmentQuoteStoreTest {
    private static final String TIME = "2026-01-01T12:00:00Z";
    private static final String LATER = "2026-01-01T12:01:00Z";
    private static final String HASH = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private Context context;
    private FinanceStore store;

    @Before public void before() {
        context = RuntimeEnvironment.getApplication();
        context.deleteDatabase("myfinancehub.db");
        store = new FinanceStore(context);
    }

    @After public void after() { store.close(); context.deleteDatabase("myfinancehub.db"); }

    @Test public void quoteMultipliesExactUnitPriceBeforeTheOnlyCentRounding() {
        Models.Investment investment = holding("100", true);
        assertTrue(quote(investment, "1.23456789", TIME));
        Models.Investment saved = first();
        assertEquals(12346, saved.currentCents); // 1.23 rounded before multiplication would give 12300.
        assertEquals("1.23456789", saved.quotePrice);
        assertEquals("100", saved.quantity);
        assertEquals(TIME, saved.quoteAt); assertEquals("brapi", saved.quoteSource);
        assertEquals(investment.investedCents, saved.investedCents); assertTrue(saved.autoQuote);
    }

    @Test public void fractionalQuantityRoundsHalfCentUpAndCanRepresentLessThanOneCent() {
        Models.Investment investment = holding("0.5", false);
        assertTrue(quote(investment, "0.01", TIME)); assertEquals(1, first().currentCents);
        assertTrue(quote(investment, "0.00000001", LATER)); assertEquals(0, first().currentCents);
        assertFalse("Update-now is allowed without opting into automatic updates", first().autoQuote);
    }

    @Test public void smallCryptoUnitPricesKeepTwelveDecimalsUntilFinalValuation() {
        Models.Investment investment = holding("1000000000", false);
        investment.ticker = "BTC-BRL"; store.saveInvestment(investment);
        assertTrue(quote(investment, "0.000000000005", TIME));
        assertEquals(1, first().currentCents);
        assertEquals("0.000000000005", first().quotePrice);
    }

    @Test public void queryResultCannotOverwriteAnEditedTickerQuantityOrDeletedInvestment() {
        Models.Investment investment = holding("10", true);
        Models.Investment edited = first(); edited.ticker = "OTHER4"; store.saveInvestment(edited);
        assertFalse(quote(investment, "10", TIME)); assertEquals(700, first().currentCents);
        edited.ticker = investment.ticker; edited.quantity = "11"; store.saveInvestment(edited);
        assertFalse(quote(investment, "10", TIME)); assertEquals(700, first().currentCents);
        store.deleteInvestment(investment.id);
        assertFalse(quote(investment, "10", TIME)); assertTrue(store.investments().isEmpty());
    }

    @Test public void oldAndSameTimestampQuotesCannotReplaceTheLatestValueAcrossStoreInstances() {
        Models.Investment investment = holding("2", false);
        assertTrue(quote(investment, "20", LATER));
        String before = store.exportBackup();
        try (FinanceStore another = new FinanceStore(context)) {
            assertFalse(another.applyInvestmentQuote(investment.id, investment.ticker, "2.0000", "100", TIME, "brapi"));
            assertFalse(another.applyInvestmentQuote(investment.id, investment.ticker, "2", "100", LATER, "brapi"));
        }
        assertEquals(before, store.exportBackup()); assertEquals(4000, first().currentCents);
    }

    @Test public void guardedRefreshDetectsManualEditsThroughAnotherStoreAndPreservesIndependentCostEdits() {
        Models.Investment expected = holding("2", true);
        try (FinanceStore another = new FinanceStore(context)) {
            Models.Investment edited = another.investments().get(0);
            edited.currentCents = 900; another.saveInvestment(edited);
            assertFalse(guardedQuote(expected, "10", TIME));
            assertEquals(900, first().currentCents);

            expected = first();
            edited.investedCents = 800; edited.name = "Nome atualizado"; another.saveInvestment(edited);
            assertTrue(guardedQuote(expected, "10", TIME));
            assertEquals(2000, first().currentCents); assertEquals(800, first().investedCents);
            assertEquals("Nome atualizado", first().name);

            expected = first();
            edited = another.investments().get(0); edited.autoQuote = false; another.saveInvestment(edited);
            assertFalse(guardedQuote(expected, "20", LATER));
            assertEquals(2000, first().currentCents); assertFalse(first().autoQuote);
        }
    }

    @Test public void guardedRefreshDetectsClearedQuoteMetadataEvenWhenTheManualValueIsUnchanged() {
        Models.Investment investment = holding("2", true); quote(investment, "10", TIME);
        Models.Investment expected = first();
        try (FinanceStore another = new FinanceStore(context)) {
            Models.Investment edited = another.investments().get(0);
            edited.quotePrice = ""; edited.quoteAt = ""; edited.quoteSource = "";
            another.saveInvestment(edited);
        }
        String before = store.exportBackup();
        assertFalse(guardedQuote(expected, "20", LATER));
        assertEquals(before, store.exportBackup());
    }

    @Test public void interruptedGuardedRequestCannotCommitAPrice() {
        Models.Investment expected = holding("2", true);
        String before = store.exportBackup();
        try {
            Thread.currentThread().interrupt();
            assertFalse(guardedQuote(expected, "10", TIME));
        } finally { Thread.interrupted(); }
        assertEquals(before, store.exportBackup());
    }

    @Test public void quoteDoesNotAlterCostCashLedgerOrImportedB3Report() {
        long account = store.accounts().get(0).id;
        store.saveEntry(new Models.Entry(0, account, 0, 0, "Receita fictícia", "2026-01-01", "income", "pix", "", 50000));
        B3Portfolio.Position position = new B3Portfolio.Position("Posição importada", "Corretora", "", "DEMO3", "", "Ações", "2", 123L);
        store.replaceB3Snapshot(Collections.singletonList(position), "2026-01-01", HASH);
        Models.Investment investment = holding("10", true);
        JSONObject before = json(store.exportBackup());
        assertTrue(quote(investment, "45.67", TIME));
        JSONObject after = json(store.exportBackup());
        assertEquals(before.optJSONArray("entries").toString(), after.optJSONArray("entries").toString());
        assertEquals(before.optJSONArray("accounts").toString(), after.optJSONArray("accounts").toString());
        assertEquals(before.optJSONObject("b3").toString(), after.optJSONObject("b3").toString());
        assertEquals(50000, store.totalBalance()); assertEquals(600, first().investedCents);
        assertEquals(45670, first().currentCents);
    }

    @Test public void badPricesSourcesAndTimestampsLeaveTheExistingValueIntact() {
        Models.Investment investment = holding("10", true);
        String before = store.exportBackup();
        for (String price : new String[]{"", "0", "-1", "1,23", "1e3", "NaN", "0.0000000000001", "9999999999999999999999999"}) {
            invalid(() -> quote(investment, price, TIME)); assertEquals(before, store.exportBackup());
        }
        for (String time : new String[]{"", "2026-01-01", "1969-12-31T23:59:59Z", "bad", Instant.now().plusSeconds(3600).toString()}) {
            invalid(() -> quote(investment, "10", time)); assertEquals(before, store.exportBackup());
        }
        invalid(() -> store.applyInvestmentQuote(investment.id, investment.ticker, investment.quantity, "10", TIME, "unknown"));
        assertEquals(before, store.exportBackup());
    }

    @Test public void quoteOverflowAndPortfolioAggregateOverflowRollBackTheWholeMutation() {
        Models.Investment investment = holding("100", true);
        String before = store.exportBackup();
        invalid(() -> quote(investment, Long.toString(Long.MAX_VALUE), TIME));
        assertEquals(before, store.exportBackup());
        // The individual quoted amount fits long; the portfolio's conservative aggregate does not.
        invalid(() -> quote(investment, "922337203685477.58", TIME));
        assertEquals(before, store.exportBackup()); assertEquals(700, first().currentCents);
    }

    @Test public void automaticUpdatesRequireAPositiveExactQuantityAndATicker() {
        for (String quantity : new String[]{"", "0", "-1", "1,5", "1e2", "0.000000001"}) {
            Models.Investment investment = unsaved(quantity, true);
            invalid(() -> store.saveInvestment(investment)); assertEquals(0, investment.id);
        }
        Models.Investment noTicker = unsaved("2", true); noTicker.ticker = "";
        invalid(() -> store.saveInvestment(noTicker));
        Models.Investment manual = unsaved("", false); manual.ticker = ""; store.saveInvestment(manual);
        assertEquals(700, first().currentCents); assertEquals("", first().quantity);
    }

    @Test public void changingManualValueRequiresClearingMetadataButCostAndNameEditsPreserveIt() {
        Models.Investment investment = holding("2", true); quote(investment, "10.5", TIME);
        Models.Investment edited = first(); edited.name = "Nome alterado"; edited.kind = "ETF"; edited.investedCents = 2000;
        store.saveInvestment(edited);
        assertEquals(TIME, first().quoteAt); assertEquals("10.5", first().quotePrice); assertEquals(2000, first().investedCents);
        String before = store.exportBackup();
        edited.currentCents = 3000; invalid(() -> store.saveInvestment(edited));
        assertEquals(before, store.exportBackup());
        edited.quotePrice = ""; edited.quoteAt = ""; edited.quoteSource = ""; store.saveInvestment(edited);
        assertEquals(3000, first().currentCents); assertEquals("", first().quoteAt);
    }

    @Test public void backupThreeRoundTripsAllQuoteFieldsAndDoesNotInventPortfolioCash() {
        Models.Investment investment = holding("0010.25000000", true); quote(investment, "100.1234", TIME);
        String backup = store.exportBackup();
        assertEquals(4, json(backup).optInt("version"));
        store.deleteInvestment(investment.id); store.restoreBackup(backup);
        assertEquals(backup, store.exportBackup()); assertEquals(investment.id, first().id);
        assertEquals("10.25", first().quantity); assertEquals("100.1234", first().quotePrice);
        assertTrue(first().autoQuote); assertEquals(0, store.totalBalance());
    }

    @Test public void versionOneAndTwoBackupsRestoreLegacyInvestmentValuesWithQuotesOff() throws Exception {
        Models.Investment investment = holding("2", true); quote(investment, "10", TIME);
        for (int version : new int[]{1, 2}) {
            JSONObject legacy = new JSONObject(store.exportBackup()); legacy.put("version", version);
            JSONObject row = legacy.getJSONArray("investments").getJSONObject(0);
            for (String field : new String[]{"quantity", "autoQuote", "quotePrice", "quoteAt", "quoteSource"}) row.remove(field);
            if (version == 1) legacy.remove("b3");
            store.restoreBackup(legacy.toString());
            assertEquals(investment.id, first().id); assertEquals(2000, first().currentCents); assertEquals(600, first().investedCents);
            assertEquals("", first().quantity); assertEquals("", first().quoteAt); assertFalse(first().autoQuote);
        }
    }

    @Test public void backupThreeAllowsAbsentOptionalFieldsAndRejectsInvalidTypesAndInconsistentQuotes() throws Exception {
        Models.Investment investment = holding("10", true); quote(investment, "10", TIME);
        String before = store.exportBackup();
        String[] fields = {"quantity", "autoQuote", "quotePrice", "quoteAt", "quoteSource"};
        Object[] malformed = {2.5, "true", 10, JSONObject.NULL, false};
        for (int n = 0; n < fields.length; n++) {
            JSONObject invalid = new JSONObject(before);
            invalid.getJSONArray("investments").getJSONObject(0).put(fields[n], malformed[n]);
            invalid(() -> store.restoreBackup(invalid.toString())); assertEquals(before, store.exportBackup());
        }
        JSONObject mismatched = new JSONObject(before);
        mismatched.getJSONArray("investments").getJSONObject(0).put("currentCents", 999);
        invalid(() -> store.restoreBackup(mismatched.toString())); assertEquals(before, store.exportBackup());
        JSONObject sparse = new JSONObject(before);
        JSONObject row = sparse.getJSONArray("investments").getJSONObject(0);
        for (String field : fields) row.remove(field);
        store.restoreBackup(sparse.toString());
        assertEquals(10000, first().currentCents); assertEquals("", first().quantity); assertFalse(first().autoQuote);
    }

    @Test public void migrationFromTwoAddsDefaultsAndPreservesB3AndAllFinancialRecords() {
        Models.Investment investment = holding("", false);
        store.replaceB3Snapshot(Collections.singletonList(new B3Portfolio.Position("Posição", "Corretora", "", "DEMO3", "", "Ações", "2", 123L)), "2026-01-01", HASH);
        String before = store.exportBackup();
        store.close();
        try (SQLiteDatabase legacy = context.openOrCreateDatabase("myfinancehub.db", Context.MODE_PRIVATE, null)) {
            legacy.execSQL("CREATE TABLE investments_legacy (_id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, ticker TEXT NOT NULL, kind TEXT NOT NULL, invested_cents INTEGER NOT NULL CHECK(invested_cents>=0), current_cents INTEGER NOT NULL CHECK(current_cents>=0))");
            legacy.execSQL("INSERT INTO investments_legacy SELECT _id,name,ticker,kind,invested_cents,current_cents FROM investments");
            legacy.execSQL("DROP TABLE investments"); legacy.execSQL("ALTER TABLE investments_legacy RENAME TO investments");
            legacy.setVersion(2);
        }
        store = new FinanceStore(context);
        assertEquals(before, store.exportBackup()); assertEquals(investment.id, first().id);
        assertEquals("", first().quotePrice); assertFalse(first().autoQuote);
        assertEquals(123, store.b3Snapshot().totalCents());
        try (SQLiteDatabase migrated = context.openOrCreateDatabase("myfinancehub.db", Context.MODE_PRIVATE, null)) {
            assertEquals(4, migrated.getVersion());
        }
        Models.Investment next = holding("2", false); assertTrue(next.id > investment.id);
    }

    private Models.Investment holding(String quantity, boolean auto) {
        Models.Investment investment = unsaved(quantity, auto); store.saveInvestment(investment); return investment;
    }
    private static Models.Investment unsaved(String quantity, boolean auto) {
        Models.Investment investment = new Models.Investment(0, "Ação fictícia", "DEMO3", "Ações", 600, 700);
        investment.quantity = quantity; investment.autoQuote = auto; return investment;
    }
    private Models.Investment first() { return store.investments().get(0); }
    private boolean quote(Models.Investment investment, String price, String at) {
        return store.applyInvestmentQuote(investment.id, investment.ticker, investment.quantity, price, at, "brapi");
    }
    private boolean guardedQuote(Models.Investment expected, String price, String at) {
        return store.applyInvestmentQuote(expected.id, expected.ticker, expected.quantity,
                expected.currentCents, expected.quoteAt, price, at, "brapi");
    }
    private static JSONObject json(String value) {
        try { return new JSONObject(value); } catch (Exception error) { throw new AssertionError(error); }
    }
    private static void invalid(Runnable action) {
        try { action.run(); fail("Expected quote/backup validation failure"); }
        catch (IllegalArgumentException expected) { assertNotNull(expected.getMessage()); }
    }
}
