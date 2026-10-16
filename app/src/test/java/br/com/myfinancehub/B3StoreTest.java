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

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE)
public final class B3StoreTest {
    private static final String DATE = "2025-01-15";
    private static final String HASH = "a".repeat(64);
    private Context context;
    private FinanceStore store;

    @Before public void before() {
        context = RuntimeEnvironment.getApplication();
        context.deleteDatabase("myfinancehub.db");
        store = new FinanceStore(context);
    }

    @After public void after() { store.close(); context.deleteDatabase("myfinancehub.db"); }

    @Test public void freshDatabaseHasNoInventedB3Report() {
        assertNull(store.b3Snapshot());
        assertTrue(store.investments().isEmpty());
    }

    @Test public void replacementAndReimportNeverAppendOrChangeManualFinances() {
        Models.Account account = store.accounts().get(0);
        account.openingCents = 10000; store.saveAccount(account);
        Models.Investment manual = new Models.Investment(0, "Minha ação", "TEST3", "Ações", 20000, 25000);
        store.saveInvestment(manual);
        List<B3Portfolio.Position> positions = Arrays.asList(position("TEST3", "Corretora A", "1", 25000L),
                position("OTHER4", "Corretora A", "1", 35000L));
        store.replaceB3Snapshot(positions, DATE, HASH);
        store.replaceB3Snapshot(positions, DATE, HASH);
        assertEquals(2, store.b3Snapshot().positions.size());
        assertEquals(60000, store.b3Snapshot().totalCents());
        assertEquals(10000, store.totalBalance());
        Models.Investment unchanged = store.investments().get(0);
        assertEquals(manual.id, unchanged.id);
        assertEquals(20000, unchanged.investedCents); assertEquals(25000, unchanged.currentCents);
        store.replaceB3Snapshot(Collections.singletonList(position("NEW3", "Corretora B", "2", 900L)), DATE, "b".repeat(64));
        assertEquals(1, store.b3Snapshot().positions.size());
        assertEquals("NEW3", store.b3Snapshot().positions.get(0).ticker);
        assertEquals(1, store.investments().size());
    }

    @Test public void unknownValuationAndFractionalQuantityRemainDistinctFromZero() {
        B3Portfolio.Position unknown = new B3Portfolio.Position("Título", "Instituição", "", "", "", "Renda fixa", "12.340000000000", null);
        store.replaceB3Snapshot(Arrays.asList(unknown, position("ZERO3", "Instituição", "", 0L)), DATE, HASH);
        B3Portfolio.Snapshot report = store.b3Snapshot();
        assertNull(report.positions.get(0).currentCents);
        assertEquals("12.34", report.positions.get(0).quantity);
        assertEquals(Long.valueOf(0), report.positions.get(1).currentCents);
        assertEquals(0, report.totalCents()); assertEquals(1, report.unvaluedCount());
    }

    @Test public void snapshotsAreDefensiveAndUnmodifiable() {
        List<B3Portfolio.Position> input = new ArrayList<>(); input.add(position("TEST3", "A", "", 100L));
        B3Portfolio.Snapshot report = new B3Portfolio.Snapshot(DATE, "2025-01-16T12:00:00Z", HASH, input);
        input.clear();
        assertEquals(1, report.positions.size());
        try { report.positions.clear(); fail("Report list must be immutable"); }
        catch (UnsupportedOperationException expected) { /* Immutable by design. */ }
    }

    @Test public void identityKeepsInstitutionsAndAccountsDistinctButRejectsAmbiguity() {
        store.replaceB3Snapshot(Arrays.asList(position("TEST3", "A", "1", 100L),
                position("TEST3", "B", "1", 200L), position("TEST3", "A", "2", 300L)), DATE, HASH);
        String before = store.exportBackup();
        invalid(() -> store.replaceB3Snapshot(Arrays.asList(position("TEST3", "A", "1", 100L),
                position("test3", "a", "1", 200L)), DATE, HASH));
        assertEquals(before, store.exportBackup());
        B3Portfolio.Position one = new B3Portfolio.Position("Produto A", "A", "1", "TEST3", "BRTEST000001", "Ações", "1", 100L);
        B3Portfolio.Position duplicate = new B3Portfolio.Position("Produto renomeado", "A", "1", "NEW3", "brtest000001", "Ações", "2", 200L);
        invalid(() -> store.replaceB3Snapshot(Arrays.asList(one, duplicate), DATE, HASH));
        assertEquals(before, store.exportBackup());
    }

    @Test public void malformedQuantitiesAndNegativeValuationsAreRejected() {
        for (String quantity : Arrays.asList("-1", "1e3", "1,25", "1.1234567890123", "1000000000000000000", "NaN", "")) {
            invalid(() -> new B3Portfolio.Position("Produto", "", "", "", "", "", quantity, null));
        }
        invalid(() -> position("TEST3", "A", "1", -1L));
        invalid(() -> new B3Portfolio.Position("Inválido\u0000", "", "", "", "", "", "1", null));
    }

    @Test public void aliasesAndClassificationCannotHideDuplicatePositions() {
        store.replaceB3Snapshot(Collections.singletonList(position("KEEP3", "A", "1", 900L)), DATE, HASH);
        String before = store.exportBackup();
        B3Portfolio.Position original = new B3Portfolio.Position("Produto", "A", "1", "TEST3", "BRTEST000001", "Ações", "10", 1000L);
        List<B3Portfolio.Position> duplicates = Arrays.asList(
                // A copied sheet/classification does not establish another position.
                new B3Portfolio.Position("Produto", "A", "1", "TEST3", "BRTEST000001", "Ações cópia", "10", 1000L),
                // A missing ISIN still shares the known ticker alias.
                new B3Portfolio.Position("Produto", "A", "1", "TEST3", "", "Outra classificação", "10", 1000L),
                // An instrument rename still shares its ISIN alias.
                new B3Portfolio.Position("Produto renomeado", "A", "1", "NEW3", "BRTEST000001", "Outra classificação", "10", 1000L));
        for (B3Portfolio.Position duplicate : duplicates) {
            invalid(() -> store.replaceB3Snapshot(Arrays.asList(original, duplicate), DATE, HASH));
            assertEquals(before, store.exportBackup());
        }
        B3Portfolio.Position unnumbered = new B3Portfolio.Position("Título sem código", "A", "1", "", "", "Renda fixa", "1", null);
        B3Portfolio.Position renamedKind = new B3Portfolio.Position("Título sem código", "A", "1", "", "", "Renda fixa cópia", "1", null);
        invalid(() -> store.replaceB3Snapshot(Arrays.asList(unnumbered, renamedKind), DATE, HASH));
        assertEquals(before, store.exportBackup());
    }

    @Test public void aClockChangeDoesNotMakeSavedReportsUnreadableButFutureImportsAreRejected() throws Exception {
        String futureDate = LocalDate.now().plusDays(10).toString();
        List<B3Portfolio.Position> positions = Collections.singletonList(position("TEST3", "A", "1", 100L));
        B3Portfolio.Snapshot immutable = new B3Portfolio.Snapshot(futureDate, "2025-01-16T12:00:00Z", HASH, positions);
        assertEquals(futureDate, immutable.asOfDate);
        store.replaceB3Snapshot(positions, DATE, HASH);
        String before = store.exportBackup();
        invalid(() -> store.replaceB3Snapshot(positions, futureDate, HASH));
        JSONObject futureBackup = new JSONObject(before); futureBackup.getJSONObject("b3").put("asOfDate", futureDate);
        invalid(() -> store.restoreBackup(futureBackup.toString()));
        assertEquals(before, store.exportBackup());

        // This is the stored state after the device clock moves behind an already saved report.
        store.close();
        try (SQLiteDatabase saved = context.openOrCreateDatabase("myfinancehub.db", Context.MODE_PRIVATE, null)) {
            saved.execSQL("UPDATE b3_snapshots SET as_of_date=? WHERE _id=1", new Object[]{futureDate});
        }
        store = new FinanceStore(context);
        assertEquals(futureDate, store.b3Snapshot().asOfDate);
        assertEquals(100, store.b3Snapshot().totalCents());
    }

    @Test public void datesHashesCountsAndAggregateAreValidatedBeforeReplacement() {
        store.replaceB3Snapshot(Collections.singletonList(position("TEST3", "A", "", 10L)), DATE, HASH);
        String before = store.exportBackup();
        for (String invalidDate : Arrays.asList("2025-02-29", "0000-01-01")) {
            invalid(() -> store.replaceB3Snapshot(Collections.emptyList(), invalidDate, HASH));
        }
        invalid(() -> store.replaceB3Snapshot(Collections.emptyList(), LocalDate.now().plusDays(1).toString(), HASH));
        invalid(() -> store.replaceB3Snapshot(Collections.emptyList(), DATE, "not-a-hash"));
        invalid(() -> store.replaceB3Snapshot(Collections.nCopies(5001, position("TEST3", "A", "", 1L)), DATE, HASH));
        invalid(() -> store.replaceB3Snapshot(Arrays.asList(position("TEST3", "A", "", Long.MAX_VALUE),
                position("OTHER4", "A", "", 1L)), DATE, HASH));
        assertEquals(before, store.exportBackup());
    }

    @Test public void backupVersionTwoRoundTripsB3WithoutInventingHistoricalCost() throws Exception {
        Models.Investment manual = new Models.Investment(0, "Manual", "", "", 4000, 5000); store.saveInvestment(manual);
        store.replaceB3Snapshot(Arrays.asList(position("TEST3", "A", "1", 12345L), position("UNKNOWN", "A", "1", null)), DATE, HASH);
        String backup = store.exportBackup();
        assertEquals(2, new JSONObject(backup).getInt("version"));
        store.clearB3Snapshot(); store.deleteInvestment(manual.id);
        store.restoreBackup(backup);
        assertEquals(backup, store.exportBackup());
        assertEquals(12345, store.b3Snapshot().totalCents());
        assertEquals(1, store.b3Snapshot().unvaluedCount());
        assertEquals(4000, store.investments().get(0).investedCents);
        assertEquals(manual.id, store.investments().get(0).id);
    }

    @Test public void restoringLegacyVersionOneClearsB3AsPartOfCompleteReplacement() throws Exception {
        Models.Account account = store.accounts().get(0); account.openingCents = 300; store.saveAccount(account);
        JSONObject legacy = new JSONObject(store.exportBackup()); legacy.put("version", 1); legacy.remove("b3");
        store.replaceB3Snapshot(Collections.singletonList(position("TEST3", "A", "", 1200L)), DATE, HASH);
        account.openingCents = 900; store.saveAccount(account);
        store.restoreBackup(legacy.toString());
        assertNull(store.b3Snapshot()); assertEquals(300, store.totalBalance());
    }

    @Test public void invalidB3BackupAndUnsupportedVersionKeepBothStoresIntact() throws Exception {
        store.replaceB3Snapshot(Collections.singletonList(position("TEST3", "A", "", 1200L)), DATE, HASH);
        String before = store.exportBackup();
        JSONObject malformed = new JSONObject(before);
        malformed.getJSONObject("b3").getJSONArray("positions").getJSONObject(0).put("currentCents", 12.5);
        invalid(() -> store.restoreBackup(malformed.toString())); assertEquals(before, store.exportBackup());
        JSONObject duplicate = new JSONObject(before);
        JSONArray rows = duplicate.getJSONObject("b3").getJSONArray("positions");
        rows.put(new JSONObject(rows.getJSONObject(0).toString()));
        invalid(() -> store.restoreBackup(duplicate.toString())); assertEquals(before, store.exportBackup());
        JSONObject missing = new JSONObject(before); missing.remove("b3");
        invalid(() -> store.restoreBackup(missing.toString())); assertEquals(before, store.exportBackup());
        JSONObject unsupported = new JSONObject(before); unsupported.put("version", 3);
        invalid(() -> store.restoreBackup(unsupported.toString())); assertEquals(before, store.exportBackup());
    }

    @Test public void failureAfterWritingB3RollsBackTheEntireRestore() throws Exception {
        long account = store.accounts().get(0).id;
        store.replaceB3Snapshot(Collections.singletonList(position("OLD3", "A", "", 1200L)), DATE, HASH);
        String before = store.exportBackup();
        JSONObject overflow = new JSONObject(before);
        overflow.getJSONObject("b3").getJSONArray("positions").getJSONObject(0).put("ticker", "NEW3");
        JSONArray entries = overflow.getJSONArray("entries");
        entries.put(new JSONObject().put("id", 1).put("accountId", account).put("categoryId", 0).put("cardId", 0)
                .put("description", "Excesso").put("date", DATE).put("type", "income").put("method", "pix")
                .put("sourceKey", "overflow1").put("amountCents", Long.MAX_VALUE));
        entries.put(new JSONObject(entries.getJSONObject(0).toString()).put("id", 2).put("sourceKey", "overflow2").put("amountCents", 1));
        invalid(() -> store.restoreBackup(overflow.toString()));
        assertEquals(before, store.exportBackup());
    }

    @Test public void migrationFromVersionOneIsAdditiveAndPreservesFinancialRecordsAndIds() {
        Models.Account account = store.accounts().get(0); account.openingCents = 1000; store.saveAccount(account);
        Models.Category category = new Models.Category(0, "Categoria existente", "both"); store.saveCategory(category);
        Models.Card card = new Models.Card(0, "Cartão existente", "1234", 7, 15); store.saveCard(card);
        Models.Entry entry = new Models.Entry(0, account.id, category.id, card.id, "Lançamento existente", DATE,
                "expense", "credit", "statement:keep", 200); store.saveEntry(entry);
        Models.Investment investment = new Models.Investment(0, "Investimento existente", "TEST3", "Ações", 500, 600); store.saveInvestment(investment);
        String before = store.exportBackup();
        store.close();
        // Core table definitions are unchanged since schema 1. Remove only the schema-2 additions.
        try (SQLiteDatabase legacy = context.openOrCreateDatabase("myfinancehub.db", Context.MODE_PRIVATE, null)) {
            legacy.execSQL("DROP TABLE b3_positions"); legacy.execSQL("DROP TABLE b3_snapshots"); legacy.setVersion(1);
        }
        store = new FinanceStore(context);
        assertEquals(before, store.exportBackup());
        assertEquals(entry.id, store.entries().get(0).id);
        assertEquals(category.id, store.entries().get(0).categoryId);
        assertEquals(card.id, store.entries().get(0).cardId);
        assertEquals(investment.id, store.investments().get(0).id);
        assertEquals(1000, store.totalBalance()); assertNull(store.b3Snapshot());
        Models.Investment next = new Models.Investment(0, "Novo", "", "", 1, 1); store.saveInvestment(next);
        assertTrue(next.id > investment.id);
    }

    @Test public void clearB3NeverDeletesManualInvestments() {
        Models.Investment manual = new Models.Investment(0, "Manual", "", "", 100, 200); store.saveInvestment(manual);
        store.replaceB3Snapshot(Collections.singletonList(position("TEST3", "A", "", 1200L)), DATE, HASH);
        store.clearB3Snapshot(); store.clearB3Snapshot();
        assertNull(store.b3Snapshot()); assertEquals(1, store.investments().size());
        assertEquals(manual.id, store.investments().get(0).id);
    }

    private static B3Portfolio.Position position(String ticker, String institution, String account, Long currentCents) {
        return new B3Portfolio.Position("Produto " + ticker, institution, account, ticker, "", "Ações", "10", currentCents);
    }
    private static void invalid(Runnable operation) {
        try { operation.run(); fail("Expected validation failure"); }
        catch (IllegalArgumentException expected) { assertFalse(expected.getMessage().isEmpty()); }
    }
}
