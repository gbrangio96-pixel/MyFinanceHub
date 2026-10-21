package br.com.myfinancehub;

import org.junit.Test;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

/** Synthetic financial records only; exercises the report independently of Android UI. */
public final class FinanceReportTest {
    private static final LocalDate FROM = LocalDate.of(2026, 10, 1);
    private static final LocalDate TO = LocalDate.of(2026, 10, 31);

    @Test public void resultSeparatesCreditConsumptionFromCashAndOwnTransfers() {
        FinanceReport report = build(0, ledger(), accounts(), categories(), cards(), Collections.emptyList(), null);
        assertEquals(102000, report.incomeCents); // Includes the explicitly classified credit refund.
        assertEquals(35000, report.expenseCents); // Includes credit purchase, excludes bill payment.
        assertEquals(67000, report.resultCents);
        assertEquals(65000, report.cashMovementCents);
        assertEquals(10000, report.transferIncomeCents);
        assertEquals(10000, report.transferExpenseCents);
        assertEquals(30000, report.cardPaymentExpenseCents);
        assertEquals(30000, report.cardPaymentsCents);
        assertEquals(0, report.cardPaymentIncomeCents);
        assertEquals(7, report.transactions.size());
        assertEquals(3, report.transactions.stream().filter(row -> row.excludedFromResult).count());
        assertEquals(2, report.expenseCategories.size());
        assertEquals("Alimentação", report.expenseCategories.get(0).categoryName);
        assertEquals(30000, report.expenseCategories.get(0).amountCents);
        assertEquals(5000, report.expenseCategories.get(1).amountCents);
        assertEquals(report.cashMovementCents, report.months.get(0).cashMovementCents);
    }

    @Test public void singleAccountShowsItsOwnTransferCashFlow() {
        FinanceReport report = build(1, ledger(), accounts(), categories(), cards(), Collections.emptyList(), null);
        assertEquals("Conta principal", report.accountName);
        assertEquals(6, report.transactions.size());
        assertEquals(55000, report.cashMovementCents);
        assertEquals(0, report.transferIncomeCents);
        assertEquals(10000, report.transferExpenseCents);
        assertEquals(67000, report.resultCents);
    }

    @Test public void accountOpeningValuesDoNotBecomePeriodIncomeOrInferredHistoricalBalances() {
        List<Models.Account> accounts = accounts();
        accounts.get(0).openingCents = Long.MAX_VALUE;
        accounts.get(1).openingCents = Long.MIN_VALUE;
        FinanceReport report = build(0, ledger(), accounts, categories(), cards(), Collections.emptyList(), null);
        assertEquals(65000, report.cashMovementCents);
        assertEquals(67000, report.resultCents);
    }

    @Test public void periodIsInclusiveAndUsesCalendarDatesIncludingLeapDay() {
        List<Models.Entry> entries = Arrays.asList(
                entry(1, 1, 0, "2028-02-28", "expense", "pix", 100),
                entry(2, 1, 0, "2028-02-29", "expense", "pix", 200),
                entry(3, 1, 0, "2028-03-01", "income", "pix", 400),
                entry(4, 1, 0, "2028-03-02", "income", "pix", 800));
        FinanceReport report = build(LocalDate.of(2028, 2, 29), LocalDate.of(2028, 3, 1), 0, entries);
        assertEquals(2, report.transactions.size());
        assertEquals(2, report.transactions.get(0).id);
        assertEquals(3, report.transactions.get(1).id);
        assertEquals(200, report.expenseCents);
        assertEquals(400, report.incomeCents);
    }

    @Test public void seriesIncludesEmptyMonthsAndRespectsPartialMonthBoundaries() {
        FinanceReport report = build(LocalDate.of(2026, 2, 15), LocalDate.of(2026, 4, 1), 0,
                Arrays.asList(entry(1, 1, 0, "2026-02-14", "income", "pix", 100),
                        entry(2, 1, 0, "2026-02-15", "income", "pix", 200),
                        entry(3, 1, 0, "2026-04-01", "expense", "credit", 300),
                        entry(4, 1, 0, "2026-04-02", "expense", "pix", 400)));
        assertEquals(3, report.months.size());
        assertEquals(YearMonth.of(2026, 2), report.months.get(0).month);
        assertEquals(200, report.months.get(0).incomeCents);
        assertEquals(YearMonth.of(2026, 3), report.months.get(1).month);
        assertEquals(0, report.months.get(1).incomeCents);
        assertEquals(0, report.months.get(1).expenseCents);
        assertEquals(0, report.months.get(1).cashMovementCents);
        assertEquals(-300, report.months.get(2).resultCents);
        assertEquals(0, report.months.get(2).cashMovementCents);
    }

    @Test public void rowsSortByDateThenIdRegardlessOfInputOrder() {
        FinanceReport report = simple(Arrays.asList(
                entry(9, 1, 0, "2026-10-02", "expense", "pix", 100),
                entry(8, 1, 0, "2026-10-01", "expense", "pix", 100),
                entry(2, 1, 0, "2026-10-02", "expense", "pix", 100)));
        assertEquals(8, report.transactions.get(0).id);
        assertEquals(2, report.transactions.get(1).id);
        assertEquals(9, report.transactions.get(2).id);
    }

    @Test public void categoriesAreSortedByExpenseWithStableNameTieBreakAndIncludeUncategorized() {
        List<Models.Category> categories = Arrays.asList(new Models.Category(1, "Zebra", "expense"),
                new Models.Category(2, "Alimentação", "expense"));
        FinanceReport report = build(0, Arrays.asList(
                entry(1, 1, 1, "2026-10-01", "expense", "credit", 200),
                entry(2, 1, 2, "2026-10-01", "expense", "debit", 200),
                entry(3, 1, 0, "2026-10-01", "expense", "pix", 300),
                entry(4, 1, 1, "2026-10-01", "expense", "card_payment", 900)),
                accounts(), categories, cards(), Collections.emptyList(), null);
        assertEquals("Sem categoria", report.expenseCategories.get(0).categoryName);
        assertEquals(0, report.expenseCategories.get(0).categoryId);
        assertEquals("Alimentação", report.expenseCategories.get(1).categoryName);
        assertEquals("Zebra", report.expenseCategories.get(2).categoryName);
        assertEquals(700, report.expenseCents);
    }

    @Test public void currentInvestmentSnapshotsAreIndependentOfPeriodAndNeverAddedToResult() {
        B3Portfolio.Snapshot snapshot = snapshot();
        List<Models.Investment> investments = Arrays.asList(
                new Models.Investment(1, "Tesouro fictício", "DEMO", "Renda fixa", 50000, 55000),
                new Models.Investment(2, "Fundo fictício", "DEMO11", "Fundo", 10000, 9000));
        FinanceReport report = build(0, Collections.emptyList(), accounts(), categories(), cards(), investments, snapshot);
        assertTrue(report.investmentsIncluded);
        assertEquals(60000, report.manualInvestedCents);
        assertEquals(64000, report.manualCurrentCents);
        assertSame(snapshot, report.b3);
        assertEquals("2026-09-30", report.b3.asOfDate);
        assertEquals(70000, report.b3.totalCents());
        assertEquals(1, report.b3.unvaluedCount());
        assertEquals(0, report.resultCents);
        assertEquals(0, report.cashMovementCents);
    }

    @Test public void accountFilterOmitsInvestmentsWhichHaveNoLocalAccountAttribution() {
        FinanceReport report = build(1, Collections.emptyList(), accounts(), categories(), cards(),
                Collections.singletonList(new Models.Investment(1, "Demo", "", "", 100, 200)), snapshot());
        assertFalse(report.investmentsIncluded);
        assertEquals(0, report.manualInvestedCents);
        assertEquals(0, report.manualCurrentCents);
        assertNull(report.b3);
    }

    @Test public void reportDoesNotChangeWhenMutableDatabaseRecordsOrInputListsChange() {
        List<Models.Entry> entries = new ArrayList<>(ledger());
        List<Models.Account> accounts = accounts();
        List<Models.Category> categories = categories();
        List<Models.Card> cards = cards();
        FinanceReport report = build(0, entries, accounts, categories, cards, Collections.emptyList(), null);
        String csv = report.toCsv();
        entries.get(0).description = "Alterado"; entries.get(0).amountCents = 999;
        accounts.get(0).name = "Alterada"; categories.get(0).name = "Alterada";
        cards.get(0).name = "Alterado"; entries.clear();
        assertEquals(csv, report.toCsv());
        assertEquals(102000, report.incomeCents);
        immutable(() -> report.transactions.clear());
        immutable(() -> report.expenseCategories.clear());
        immutable(() -> report.months.clear());
    }

    @Test public void emptyReportStillContainsTheSelectedMonths() {
        FinanceReport report = simple(Collections.emptyList());
        assertEquals(1, report.months.size());
        assertTrue(report.transactions.isEmpty());
        assertTrue(report.expenseCategories.isEmpty());
        assertEquals(0, report.resultCents);
        assertEquals("Todas as contas", report.accountName);
    }

    @Test public void rangeIsBoundedByCalendarMonthsRatherThanApproximateDays() {
        FinanceReport report = build(LocalDate.of(2017, 1, 31), LocalDate.of(2026, 12, 1), 0, Collections.emptyList());
        assertEquals(120, report.months.size());
        reject(() -> build(LocalDate.of(2017, 1, 31), LocalDate.of(2027, 1, 1), 0, Collections.emptyList()));
        reject(() -> build(TO, FROM, 0, Collections.emptyList()));
        reject(() -> build(null, TO, 0, Collections.emptyList()));
        reject(() -> build(LocalDate.of(0, 1, 1), TO, 0, Collections.emptyList()));
        reject(() -> build(LocalDate.of(10000, 1, 1), LocalDate.of(10000, 1, 1), 0, Collections.emptyList()));
    }

    @Test public void rejectsMissingReferencesEvenForRowsOutsideTheSelectedPeriod() {
        Models.Entry entry = entry(1, 99, 0, "2020-01-01", "expense", "pix", 100);
        reject(() -> simple(Collections.singletonList(entry)));
        entry.accountId = 1; entry.categoryId = 99;
        reject(() -> simple(Collections.singletonList(entry)));
        entry.categoryId = 0; entry.cardId = 99;
        reject(() -> simple(Collections.singletonList(entry)));
        reject(() -> build(99, Collections.emptyList(), accounts(), categories(), cards(), Collections.emptyList(), null));
    }

    @Test public void rejectsDuplicateIdsAndNullCollectionsOrRecords() {
        Models.Entry entry = entry(1, 1, 0, "2026-10-01", "expense", "pix", 100);
        reject(() -> simple(Arrays.asList(entry, entry)));
        reject(() -> simple(Collections.singletonList(null)));
        reject(() -> build(0, null, accounts(), categories(), cards(), Collections.emptyList(), null));
        reject(() -> build(0, Collections.emptyList(), null, categories(), cards(), Collections.emptyList(), null));
        reject(() -> build(0, Collections.emptyList(), accounts(), null, cards(), Collections.emptyList(), null));
        reject(() -> build(0, Collections.emptyList(), accounts(), categories(), null, Collections.emptyList(), null));
        reject(() -> build(0, Collections.emptyList(), accounts(), categories(), cards(), null, null));
        reject(() -> build(-1, Collections.emptyList(), accounts(), categories(), cards(), Collections.emptyList(), null));
        List<Models.Account> duplicateAccounts = accounts(); duplicateAccounts.add(duplicateAccounts.get(0));
        reject(() -> build(0, Collections.emptyList(), duplicateAccounts, categories(), cards(), Collections.emptyList(), null));
    }

    @Test public void rejectsMalformedDatesNonpositiveAmountsAndInvalidFinancialTypes() {
        Models.Entry entry = entry(1, 1, 0, "2026-02-29", "expense", "pix", 100);
        reject(() -> simple(Collections.singletonList(entry)));
        for (String date : new String[]{null, "0000-01-01", "2026-2-01", "2026-13-01", "2026-10-01T00:00:00"}) {
            entry.date = date; reject(() -> simple(Collections.singletonList(entry)));
        }
        entry.date = "2026-10-01"; entry.amountCents = 0;
        reject(() -> simple(Collections.singletonList(entry)));
        entry.amountCents = -1; reject(() -> simple(Collections.singletonList(entry)));
        entry.amountCents = 100; entry.type = "unknown";
        reject(() -> simple(Collections.singletonList(entry)));
        entry.type = "income"; entry.method = "card_payment";
        reject(() -> simple(Collections.singletonList(entry)));
        entry.method = "unknown"; reject(() -> simple(Collections.singletonList(entry)));
        entry.method = "pix"; entry.categoryId = 1;
        reject(() -> simple(Collections.singletonList(entry))); // Expense-only category.
    }

    @Test public void rejectsOverflowInDisplayedTotalsEvenIfNetWouldCancel() {
        reject(() -> simple(Arrays.asList(
                entry(1, 1, 0, "2026-10-01", "income", "pix", Long.MAX_VALUE),
                entry(2, 1, 0, "2026-10-01", "income", "pix", 1),
                entry(3, 1, 0, "2026-10-01", "expense", "pix", Long.MAX_VALUE))));
        reject(() -> build(0, Collections.emptyList(), accounts(), categories(), cards(), Arrays.asList(
                new Models.Investment(1, "A", "", "", Long.MAX_VALUE, 0),
                new Models.Investment(2, "B", "", "", 1, 0)), null));
    }

    @Test public void exactCashAggregationDoesNotOverflowOnIntermediateValues() {
        FinanceReport report = simple(Arrays.asList(
                entry(1, 1, 0, "2026-10-01", "income", "pix", Long.MAX_VALUE),
                entry(2, 1, 0, "2026-10-01", "income", "transfer", 1),
                entry(3, 1, 0, "2026-10-01", "expense", "transfer", Long.MAX_VALUE)));
        assertEquals(1, report.cashMovementCents);
        assertEquals(Long.MAX_VALUE, report.incomeCents);
        assertEquals(Long.MAX_VALUE, report.resultCents);
    }

    @Test public void cashMovementSupportsTheFullSignedLongRange() {
        FinanceReport report = simple(Arrays.asList(
                entry(1, 1, 0, "2026-10-01", "expense", "pix", Long.MAX_VALUE),
                entry(2, 1, 0, "2026-10-01", "expense", "transfer", 1)));
        assertEquals(Long.MIN_VALUE, report.cashMovementCents);
        assertEquals(-Long.MAX_VALUE, report.resultCents);
        reject(() -> simple(Arrays.asList(
                entry(1, 1, 0, "2026-10-01", "expense", "pix", Long.MAX_VALUE),
                entry(2, 1, 0, "2026-10-01", "expense", "transfer", 2))));
    }

    @Test public void csvHasConsistentColumnsQuotesMultilineTextAndKeepsPositiveExactValues() {
        Models.Entry row = entry(1, 1, 1, "2026-10-01", "expense", "pix", Long.MAX_VALUE);
        row.description = "Mercado; \"fictício\"\nSegunda linha, com vírgula";
        FinanceReport report = simple(Collections.singletonList(row));
        String csv = report.toCsv();
        assertTrue(csv.startsWith("\"Data\";\"Descrição\";\"Valor\";"));
        assertTrue(csv.contains("\"Mercado; \"\"fictício\"\"\nSegunda linha, com vírgula\""));
        assertTrue(csv.contains("\"92233720368547758,07\";\"Saída\""));
        assertTrue(csv.endsWith("\r\n"));
        // Existing independent CSV reader verifies quoting, rectangular records, dates and money.
        List<StatementImporter.Row> parsed = StatementImporter.parse(csv, "relatorio.csv");
        assertEquals(1, parsed.size());
        assertEquals(row.description, parsed.get(0).description);
        assertEquals(Long.MAX_VALUE, parsed.get(0).amountCents);
        assertEquals("expense", parsed.get(0).type);
    }

    @Test public void csvNeutralizesFormulaTextInAllUserControlledNameColumns() {
        Models.Entry row = entry(1, 1, 1, "2026-10-01", "expense", "credit", 18547);
        row.cardId = 1; row.description = "\u00a0\uFEFF=HYPERLINK(\"https://example.invalid\")";
        List<Models.Account> accounts = accounts(); accounts.get(0).name = "+Conta";
        List<Models.Category> categories = categories(); categories.get(0).name = "-Categoria";
        List<Models.Card> cards = cards(); cards.get(0).name = "@Cartão";
        FinanceReport report = build(0, Collections.singletonList(row), accounts, categories, cards, Collections.emptyList(), null);
        String csv = report.toCsv();
        assertTrue(csv.contains("\"'\u00a0\uFEFF=HYPERLINK"));
        assertTrue(csv.contains("\"'+Conta\""));
        assertTrue(csv.contains("\"'-Categoria\""));
        assertTrue(csv.contains("\"'@Cartão\""));
        assertTrue(csv.contains("\"185,47\";\"Saída\""));
    }

    @Test public void csvIncludesTransfersAndCardPaymentsWithExplicitResultExclusion() {
        String csv = build(0, ledger(), accounts(), categories(), cards(), Collections.emptyList(), null).toCsv();
        assertTrue(csv.contains("\"Transferência\";\"Não\""));
        assertTrue(csv.contains("\"Pagamento de fatura\";\"Não\""));
        assertTrue(csv.contains("\"Crédito\";\"Sim\""));
        assertEquals(7, StatementImporter.parse(csv, "relatorio.csv").size());
    }

    private static FinanceReport simple(List<Models.Entry> entries) {
        return build(0, entries, accounts(), categories(), cards(), Collections.emptyList(), null);
    }

    private static FinanceReport build(long accountId, List<Models.Entry> entries, List<Models.Account> accounts,
                                       List<Models.Category> categories, List<Models.Card> cards,
                                       List<Models.Investment> investments, B3Portfolio.Snapshot b3) {
        return FinanceReport.build(FROM, TO, accountId, entries, accounts, categories, cards, investments, b3);
    }

    private static FinanceReport build(LocalDate from, LocalDate to, long accountId, List<Models.Entry> entries) {
        return FinanceReport.build(from, to, accountId, entries, accounts(), categories(), cards(), Collections.emptyList(), null);
    }

    private static List<Models.Account> accounts() {
        return new ArrayList<>(Arrays.asList(new Models.Account(1, "Conta principal", 10000), new Models.Account(2, "Reserva", 20000)));
    }

    private static List<Models.Category> categories() {
        return new ArrayList<>(Arrays.asList(new Models.Category(1, "Alimentação", "expense"),
                new Models.Category(2, "Transporte", "expense"), new Models.Category(3, "Salário", "income")));
    }

    private static List<Models.Card> cards() {
        return new ArrayList<>(Collections.singletonList(new Models.Card(1, "Cartão fictício", "1234", 1, 10)));
    }

    private static Models.Entry entry(long id, long account, long category, String date, String type, String method, long amount) {
        return new Models.Entry(id, account, category, 0, "Lançamento fictício " + id, date, type, method, "", amount);
    }

    private static List<Models.Entry> ledger() {
        Models.Entry credit = entry(2, 1, 1, "2026-10-02", "expense", "credit", 30000); credit.cardId = 1;
        Models.Entry refund = entry(4, 1, 0, "2026-10-04", "income", "credit", 2000); refund.cardId = 1;
        return Arrays.asList(entry(1, 1, 3, "2026-10-01", "income", "pix", 100000), credit,
                entry(3, 1, 2, "2026-10-03", "expense", "debit", 5000), refund,
                entry(5, 1, 0, "2026-10-05", "expense", "transfer", 10000),
                entry(6, 2, 0, "2026-10-05", "income", "transfer", 10000),
                entry(7, 1, 0, "2026-10-06", "expense", "card_payment", 30000));
    }

    private static B3Portfolio.Snapshot snapshot() {
        return new B3Portfolio.Snapshot("2026-09-30", "2026-10-01T10:00:00Z", String.join("", Collections.nCopies(64, "a")),
                Arrays.asList(new B3Portfolio.Position("Ativo fictício", "Corretora", "Demo", "DEMO3", "", "Ação", "10", 70000L),
                        new B3Portfolio.Position("Ativo sem cotação", "Corretora", "Demo", "DEMO4", "", "Ação", "1", null)));
    }

    private static void reject(Runnable action) {
        try { action.run(); }
        catch (IllegalArgumentException expected) { return; }
        fail("Accepted invalid report data");
    }

    private static void immutable(Runnable action) {
        try { action.run(); }
        catch (UnsupportedOperationException expected) { return; }
        fail("Report collection was mutable");
    }
}
