package br.com.myfinancehub;

import java.math.BigInteger;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Immutable view of local entries for an inclusive period. Result includes credit purchases;
 * cash movement excludes credit and includes transfers and card payments. Investments are
 * current snapshots, independent of the period, and are never added to account movements.
 */
public final class FinanceReport {
    public static final int MAX_MONTHS = 120;
    public final LocalDate from, to;
    /** Zero means all accounts. */
    public final long accountId;
    public final String accountName;
    public final List<Row> transactions;
    public final List<CategoryTotal> expenseCategories;
    /** Every calendar month touched by the period, including months without entries. */
    public final List<MonthTotal> months;
    public final long incomeCents, expenseCents, resultCents, cashMovementCents;
    public final long transferIncomeCents, transferExpenseCents;
    public final long cardPaymentIncomeCents, cardPaymentExpenseCents, cardPaymentsCents;
    /** Investments lack an account link and are only included for the all-accounts view. */
    public final boolean investmentsIncluded;
    public final long manualInvestedCents, manualCurrentCents;
    /** Explicitly dated custody snapshot; null when absent or filtering a local account. */
    public final B3Portfolio.Snapshot b3;

    private FinanceReport(LocalDate from, LocalDate to, long accountId, String accountName,
                          List<Row> rows, List<CategoryTotal> categories, List<MonthTotal> months,
                          Totals totals, long manualInvestedCents, long manualCurrentCents,
                          B3Portfolio.Snapshot b3) {
        this.from = from; this.to = to; this.accountId = accountId; this.accountName = accountName;
        this.transactions = Collections.unmodifiableList(new ArrayList<>(rows));
        this.expenseCategories = Collections.unmodifiableList(new ArrayList<>(categories));
        this.months = Collections.unmodifiableList(new ArrayList<>(months));
        this.incomeCents = checked(totals.income); this.expenseCents = checked(totals.expense);
        this.resultCents = checked(totals.income.subtract(totals.expense));
        this.cashMovementCents = checked(totals.cash);
        this.transferIncomeCents = checked(totals.transferIncome);
        this.transferExpenseCents = checked(totals.transferExpense);
        this.cardPaymentIncomeCents = checked(totals.cardPaymentIncome);
        this.cardPaymentExpenseCents = checked(totals.cardPaymentExpense);
        this.cardPaymentsCents = this.cardPaymentExpenseCents;
        this.investmentsIncluded = accountId == 0;
        this.manualInvestedCents = investmentsIncluded ? manualInvestedCents : 0;
        this.manualCurrentCents = investmentsIncluded ? manualCurrentCents : 0;
        this.b3 = investmentsIncluded ? b3 : null;
    }

    /** Validates the entire input boundary before producing a report; no model is mutated. */
    public static FinanceReport build(LocalDate from, LocalDate to, long accountId,
                                      List<Models.Entry> entries, List<Models.Account> accounts,
                                      List<Models.Category> categories, List<Models.Card> cards,
                                      List<Models.Investment> investments, B3Portfolio.Snapshot b3) {
        if (from == null || to == null || from.getYear() < 1 || from.getYear() > 9999
                || to.getYear() < 1 || to.getYear() > 9999 || from.isAfter(to))
            throw invalid("Informe um período válido, com a data inicial até a data final.");
        long monthCount = ChronoUnit.MONTHS.between(YearMonth.from(from), YearMonth.from(to)) + 1;
        if (monthCount > MAX_MONTHS)
            throw invalid("O relatório pode abranger no máximo 120 meses.");
        if (accountId < 0 || entries == null || accounts == null || categories == null
                || cards == null || investments == null)
            throw invalid("Dados do relatório inválidos.");

        Map<Long, String> accountNames = new HashMap<>(), categoryNames = new HashMap<>(), cardNames = new HashMap<>();
        Map<Long, String> categoryTypes = new HashMap<>();
        for (Models.Account account : accounts) {
            if (account == null) throw invalid("Conta inválida no relatório.");
            putName(accountNames, account.id, account.name, "Conta", 80);
        }
        if (accountId != 0 && !accountNames.containsKey(accountId))
            throw invalid("A conta selecionada não existe.");
        for (Models.Category category : categories) {
            if (category == null) throw invalid("Categoria inválida no relatório.");
            putName(categoryNames, category.id, category.name, "Categoria", 80);
            categoryTypes.put(category.id, choice(category.type, "Tipo da categoria", "income", "expense", "both"));
        }
        for (Models.Card card : cards) {
            if (card == null) throw invalid("Cartão inválido no relatório.");
            putName(cardNames, card.id, card.name, "Cartão", 80);
            String lastFour = text(card.lastFour, "Últimos quatro dígitos", 4, true);
            if ((!lastFour.isEmpty() && !lastFour.matches("[0-9]{4}"))
                    || card.closingDay < 1 || card.closingDay > 31 || card.dueDay < 1 || card.dueDay > 31)
                throw invalid("Cartão inválido no relatório.");
        }

        BigInteger invested = BigInteger.ZERO, current = BigInteger.ZERO;
        Set<Long> investmentIds = new HashSet<>();
        for (Models.Investment investment : investments) {
            if (investment == null) throw invalid("Investimento inválido no relatório.");
            uniqueId(investmentIds, investment.id, "Investimento");
            text(investment.name, "Investimento", 100, false);
            text(investment.ticker, "Código do investimento", 30, true);
            text(investment.kind, "Tipo do investimento", 80, true);
            if (investment.investedCents < 0 || investment.currentCents < 0)
                throw invalid("Valores de investimento não podem ser negativos.");
            invested = invested.add(BigInteger.valueOf(investment.investedCents));
            current = current.add(BigInteger.valueOf(investment.currentCents));
        }
        long manualInvested = checked(invested), manualCurrent = checked(current);

        Map<YearMonth, Totals> monthTotals = new LinkedHashMap<>();
        YearMonth firstMonth = YearMonth.from(from);
        for (int i = 0; i < monthCount; i++) monthTotals.put(firstMonth.plusMonths(i), new Totals());
        Map<Long, BigInteger> categoryTotals = new HashMap<>();
        List<Row> rows = new ArrayList<>();
        Totals totals = new Totals();
        Set<Long> entryIds = new HashSet<>();
        for (Models.Entry entry : entries) {
            if (entry == null) throw invalid("Lançamento inválido no relatório.");
            uniqueId(entryIds, entry.id, "Lançamento");
            if (entry.accountId <= 0 || !accountNames.containsKey(entry.accountId))
                throw invalid("Conta do lançamento inexistente.");
            if (entry.categoryId < 0 || (entry.categoryId != 0 && !categoryNames.containsKey(entry.categoryId)))
                throw invalid("Categoria do lançamento inexistente.");
            if (entry.cardId < 0 || (entry.cardId != 0 && !cardNames.containsKey(entry.cardId)))
                throw invalid("Cartão do lançamento inexistente.");
            String description = text(entry.description, "Descrição", 500, false);
            String type = choice(entry.type, "Tipo do lançamento", "income", "expense");
            String method = choice(entry.method, "Forma de pagamento", "cash", "debit", "credit", "pix", "transfer", "card_payment", "other");
            if (entry.categoryId != 0 && !"both".equals(categoryTypes.get(entry.categoryId))
                    && !type.equals(categoryTypes.get(entry.categoryId)))
                throw invalid("A categoria não corresponde ao tipo do lançamento.");
            if ("card_payment".equals(method) && !"expense".equals(type))
                throw invalid("Pagamento de fatura deve ser uma saída.");
            if (entry.amountCents <= 0) throw invalid("O valor do lançamento deve ser maior que zero.");
            text(entry.sourceKey, "Identificador da importação", 512, true);
            LocalDate date = date(entry.date);
            if (date.isBefore(from) || date.isAfter(to) || (accountId != 0 && accountId != entry.accountId)) continue;
            Row row = new Row(entry.id, entry.accountId, entry.categoryId, entry.cardId, date,
                    description, type, method, accountNames.get(entry.accountId),
                    entry.categoryId == 0 ? "Sem categoria" : categoryNames.get(entry.categoryId),
                    entry.cardId == 0 ? "" : cardNames.get(entry.cardId), entry.amountCents);
            rows.add(row); totals.add(row); monthTotals.get(YearMonth.from(date)).add(row);
            if (!row.excludedFromResult && "expense".equals(type))
                categoryTotals.put(row.categoryId, categoryTotals.getOrDefault(row.categoryId, BigInteger.ZERO)
                        .add(BigInteger.valueOf(row.amountCents)));
        }
        rows.sort(Comparator.comparing((Row row) -> row.date).thenComparingLong(row -> row.id));
        List<CategoryTotal> categoryRows = new ArrayList<>();
        for (Map.Entry<Long, BigInteger> entry : categoryTotals.entrySet())
            categoryRows.add(new CategoryTotal(entry.getKey(), entry.getKey() == 0 ? "Sem categoria"
                    : categoryNames.get(entry.getKey()), checked(entry.getValue())));
        categoryRows.sort(Comparator.comparingLong((CategoryTotal row) -> row.amountCents).reversed()
                .thenComparing(row -> row.categoryName, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(row -> row.categoryName).thenComparingLong(row -> row.categoryId));
        List<MonthTotal> monthRows = new ArrayList<>();
        for (Map.Entry<YearMonth, Totals> entry : monthTotals.entrySet())
            monthRows.add(new MonthTotal(entry.getKey(), entry.getValue()));
        return new FinanceReport(from, to, accountId, accountId == 0 ? "Todas as contas" : accountNames.get(accountId),
                rows, categoryRows, monthRows, totals, manualInvested, manualCurrent, b3);
    }

    public static final class Row {
        public final long id, accountId, categoryId, cardId, amountCents;
        public final LocalDate date;
        public final String description, type, method, accountName, categoryName, cardName;
        public final boolean excludedFromResult;

        private Row(long id, long accountId, long categoryId, long cardId, LocalDate date,
                    String description, String type, String method, String accountName,
                    String categoryName, String cardName, long amountCents) {
            this.id = id; this.accountId = accountId; this.categoryId = categoryId; this.cardId = cardId;
            this.date = date; this.description = description; this.type = type; this.method = method;
            this.accountName = accountName; this.categoryName = categoryName; this.cardName = cardName;
            this.amountCents = amountCents;
            this.excludedFromResult = "transfer".equals(method) || "card_payment".equals(method);
        }
    }

    public static final class CategoryTotal {
        public final long categoryId, amountCents;
        public final String categoryName;
        private CategoryTotal(long categoryId, String categoryName, long amountCents) {
            this.categoryId = categoryId; this.categoryName = categoryName; this.amountCents = amountCents;
        }
    }

    public static final class MonthTotal {
        public final YearMonth month;
        public final long incomeCents, expenseCents, resultCents, cashMovementCents;
        private MonthTotal(YearMonth month, Totals totals) {
            this.month = month; this.incomeCents = checked(totals.income);
            this.expenseCents = checked(totals.expense);
            this.resultCents = checked(totals.income.subtract(totals.expense));
            this.cashMovementCents = checked(totals.cash);
        }
    }

    /**
     * One rectangular table, semicolon-separated with CRLF records. Save using UTF-8.
     * All amounts are positive BRL decimals; direction is explicit in Tipo. Formula-like
     * text is prefixed with an apostrophe before quoting for safe spreadsheet opening.
     */
    public String toCsv() {
        StringBuilder out = new StringBuilder();
        csvRow(out, "Data", "Descrição", "Valor", "Tipo", "Categoria", "Cartão", "Conta", "Método", "Incluído no resultado", "ID");
        for (Row row : transactions) {
            String amount = java.math.BigDecimal.valueOf(row.amountCents, 2).toPlainString().replace('.', ',');
            csvRow(out, row.date.toString(), row.description, amount,
                    "income".equals(row.type) ? "Entrada" : "Saída", row.categoryName, row.cardName,
                    row.accountName, methodName(row.method), row.excludedFromResult ? "Não" : "Sim", Long.toString(row.id));
        }
        return out.toString();
    }

    private static void csvRow(StringBuilder out, String... cells) {
        for (int i = 0; i < cells.length; i++) {
            if (i != 0) out.append(';');
            String cell = cells[i];
            int first = 0;
            while (first < cell.length() && (Character.isWhitespace(cell.charAt(first))
                    || Character.isSpaceChar(cell.charAt(first)) || cell.charAt(first) == '\ufeff')) first++;
            boolean formula = !cell.isEmpty() && "\t\r\n".indexOf(cell.charAt(0)) >= 0;
            formula |= first < cell.length() && "=+-@".indexOf(cell.charAt(first)) >= 0;
            out.append('"');
            if (formula) out.append('\'');
            out.append(cell.replace("\"", "\"\"")).append('"');
        }
        out.append("\r\n");
    }

    private static String methodName(String method) {
        switch (method) {
            case "cash": return "Dinheiro";
            case "debit": return "Débito";
            case "credit": return "Crédito";
            case "pix": return "Pix";
            case "transfer": return "Transferência";
            case "card_payment": return "Pagamento de fatura";
            default: return "Outro";
        }
    }

    private static final class Totals {
        BigInteger income = BigInteger.ZERO, expense = BigInteger.ZERO, cash = BigInteger.ZERO;
        BigInteger transferIncome = BigInteger.ZERO, transferExpense = BigInteger.ZERO;
        BigInteger cardPaymentIncome = BigInteger.ZERO, cardPaymentExpense = BigInteger.ZERO;
        void add(Row row) {
            BigInteger value = BigInteger.valueOf(row.amountCents);
            boolean incoming = "income".equals(row.type);
            if (!row.excludedFromResult) {
                if (incoming) income = income.add(value); else expense = expense.add(value);
            }
            if (!"credit".equals(row.method)) cash = cash.add(incoming ? value : value.negate());
            if ("transfer".equals(row.method)) {
                if (incoming) transferIncome = transferIncome.add(value); else transferExpense = transferExpense.add(value);
            }
            if ("card_payment".equals(row.method)) {
                if (incoming) cardPaymentIncome = cardPaymentIncome.add(value); else cardPaymentExpense = cardPaymentExpense.add(value);
            }
        }
    }

    private static void putName(Map<Long, String> names, long id, String name, String label, int max) {
        if (id <= 0 || names.containsKey(id)) throw invalid(label + " com identificador inválido ou repetido.");
        names.put(id, text(name, label, max, false));
    }

    private static void uniqueId(Set<Long> ids, long id, String label) {
        if (id <= 0 || !ids.add(id)) throw invalid(label + " com identificador inválido ou repetido.");
    }

    private static String text(String value, String label, int max, boolean optional) {
        if (value != null && value.indexOf('\u0000') >= 0) throw invalid(label + " inválido.");
        String result = value == null ? "" : value.trim();
        if ((!optional && result.isEmpty()) || result.length() > max) throw invalid(label + " inválido.");
        return result;
    }

    private static String choice(String value, String label, String... allowed) {
        for (String option : allowed) if (option.equals(value)) return option;
        throw invalid(label + " inválido.");
    }

    private static LocalDate date(String value) {
        try {
            if (value == null || !value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"))
                throw invalid("Data inválida no lançamento.");
            LocalDate parsed = LocalDate.parse(value);
            if (parsed.getYear() < 1) throw invalid("Data inválida no lançamento.");
            return parsed;
        } catch (DateTimeParseException error) { throw invalid("Data inválida no lançamento."); }
    }

    private static long checked(BigInteger value) {
        if (value.bitLength() > 63)
            throw invalid("O total do relatório excede o limite monetário suportado.");
        return value.longValue();
    }

    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
}
