package br.com.myfinancehub;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.pdf.PdfDocument;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Offline, paginated A4 export. Amounts are formatted from exact integer cents. */
public final class FinanceReportPdf {
    public static final int MAX_TRANSACTIONS = 5000;
    private static final int MAX_PAGES = 1200;
    static final int PAGE_WIDTH = 595, PAGE_HEIGHT = 842;
    static final float LEFT = 40, RIGHT = 555, CONTENT_BOTTOM = 790;
    private static final int INK = Color.rgb(30, 46, 57);
    private static final int MUTED = Color.rgb(86, 102, 110);
    private static final int ACCENT = Color.rgb(0, 112, 98);
    private static final int LIGHT = Color.rgb(226, 237, 234);
    private static final Locale BRAZIL = new Locale("pt", "BR");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/uuuu");
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MMM/uuuu", BRAZIL);

    private FinanceReportPdf() {}

    /** Writes full financial values; callers must obtain the user's export confirmation. */
    public static void write(FinanceReport report, OutputStream output) throws IOException {
        if (output == null) throw new IllegalArgumentException("Destino do PDF não informado.");
        Layout layout = layout(report);
        PdfDocument document = new PdfDocument();
        try {
            for (int i = 0; i < layout.pages.size(); i++) {
                PdfDocument.Page page = document.startPage(new PdfDocument.PageInfo.Builder(
                        PAGE_WIDTH, PAGE_HEIGHT, i + 1).create());
                Canvas canvas = page.getCanvas();
                canvas.drawColor(Color.WHITE);
                Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
                for (Bar bar : layout.pages.get(i).bars) {
                    paint.setColor(bar.color);
                    canvas.drawRect(bar.x, bar.y, bar.x + bar.width, bar.y + bar.height, paint);
                }
                for (Text text : layout.pages.get(i).texts) {
                    configure(paint, text.size, text.bold, text.color);
                    canvas.drawText(text.value, text.x, text.baseline, paint);
                }
                document.finishPage(page);
            }
            document.writeTo(output);
        } finally {
            document.close();
        }
    }

    // A measured plan keeps pagination independent from PDF serialization and lets tests
    // check text preservation and page boundaries without depending on PDF font encoding.
    static Layout layout(FinanceReport report) throws IOException {
        if (report == null) throw new IllegalArgumentException("Relatório não informado.");
        if (report.transactions.size() > MAX_TRANSACTIONS)
            throw new IOException("O PDF aceita até 5.000 lançamentos. Reduza o período ou exporte em CSV para incluir todos.");
        Cursor cursor = new Cursor(report);
        cursor.section("Resumo do período");
        cursor.value("Entradas", Money.format(report.incomeCents), false);
        cursor.value("Despesas", Money.format(report.expenseCents), false);
        cursor.value("Resultado do período", Money.format(report.resultCents), true);
        cursor.paragraph("Resultado = entradas menos despesas. Compras no crédito entram como consumo; "
                + "transferências entre contas e pagamentos de fatura ficam fora desse resultado.", 9, MUTED);

        cursor.section("Movimentação de caixa");
        cursor.value("Variação de caixa no período", Money.format(report.cashMovementCents), true);
        cursor.paragraph("Considera os lançamentos da conta selecionada, inclusive transferências e pagamentos "
                + "de fatura. Compras no crédito não movimentam o caixa no dia da compra. Não representa "
                + "saldo inicial ou final da conta.", 9, MUTED);
        cursor.value("Transferências recebidas", Money.format(report.transferIncomeCents), false);
        cursor.value("Transferências enviadas", Money.format(report.transferExpenseCents), false);
        cursor.value("Pagamentos de fatura (saídas)", Money.format(report.cardPaymentExpenseCents), false);
        cursor.value("Pagamentos de fatura (entradas)", Money.format(report.cardPaymentIncomeCents), false);

        cursor.section("Despesas por categoria");
        if (report.expenseCategories.isEmpty()) {
            cursor.paragraph("Nenhuma despesa de consumo neste período.", 10, MUTED);
        } else {
            int rank = 0;
            long largest = report.expenseCategories.get(0).amountCents;
            for (FinanceReport.CategoryTotal category : report.expenseCategories) {
                List<String> lines = cursor.wrap(++rank + ". " + category.categoryName, 315, 10, false);
                float height = lines.size() * 14 + 15;
                cursor.ensure(height);
                float top = cursor.y;
                for (String line : lines) { cursor.text(line, LEFT, cursor.y + 11, 10, false, INK); cursor.y += 14; }
                cursor.right(Money.format(category.amountCents), RIGHT, top + 11, 10, false, INK);
                cursor.bar(LEFT, cursor.y + 3, 315, 4, LIGHT);
                // The ratio is used only for drawing; no monetary total uses floating point.
                float fraction = largest == 0 ? 0 : BigDecimal.valueOf(category.amountCents)
                        .divide(BigDecimal.valueOf(largest), 8, RoundingMode.HALF_UP).floatValue();
                cursor.bar(LEFT, cursor.y + 3, 315 * Math.max(0, Math.min(1, fraction)), 4, ACCENT);
                cursor.y += 15;
            }
        }

        cursor.section("Comparação mensal");
        cursor.paragraph("Cada mês considera somente os dias incluídos no período selecionado.", 9, MUTED);
        cursor.monthHeader();
        for (FinanceReport.MonthTotal month : report.months) {
            cursor.ensure(25, true);
            cursor.text(MONTH.format(month.month), LEFT, cursor.y + 11, 9, false, INK);
            cursor.right(Money.format(month.incomeCents), 250, cursor.y + 11, 8.3f, false, INK);
            cursor.right(Money.format(month.expenseCents), 400, cursor.y + 11, 8.3f, false, INK);
            cursor.right(Money.format(month.resultCents), RIGHT, cursor.y + 11, 8.3f, true, INK);
            cursor.bar(LEFT, cursor.y + 20, RIGHT - LEFT, 0.5f, LIGHT);
            cursor.y += 25;
        }

        if (report.investmentsIncluded) {
            cursor.section("Investimentos · posição atual");
            cursor.paragraph("Valores atuais cadastrados, independentes do período do relatório. Não são retorno "
                    + "ou rentabilidade do período. Investimentos manuais e B3 são apresentados separadamente, "
                    + "sem somá-los ao caixa ou entre si.", 9, MUTED);
            cursor.value("Investimentos manuais · aplicado", Money.format(report.manualInvestedCents), false);
            cursor.value("Investimentos manuais · valor atual", Money.format(report.manualCurrentCents), false);
            long manualChange = Math.subtractExact(report.manualCurrentCents, report.manualInvestedCents);
            cursor.value("Variação sobre o valor aplicado", Money.format(manualChange), true);
            String percentage = report.manualInvestedCents == 0 ? "Indisponível (aplicado = zero)"
                    : BigDecimal.valueOf(manualChange).multiply(BigDecimal.valueOf(100))
                    .divide(BigDecimal.valueOf(report.manualInvestedCents), 2, RoundingMode.HALF_UP)
                    .toPlainString().replace('.', ',') + "%";
            cursor.value("Variação percentual atual", percentage, false);
            cursor.paragraph("Variação simples do valor atual em relação ao aplicado. Não considera as datas "
                    + "de aportes e resgates nem calcula rentabilidade do período selecionado.", 9, MUTED);
            if (report.b3 == null) {
                cursor.paragraph("B3: nenhuma posição importada.", 10, MUTED);
            } else {
                cursor.paragraph("B3 · data da posição: " + DATE.format(java.time.LocalDate.parse(report.b3.asOfDate))
                        + " · " + report.b3.positions.size() + (report.b3.positions.size() == 1 ? " posição." : " posições."), 10, INK);
                int missing = report.b3.unvaluedCount();
                boolean allMissing = !report.b3.positions.isEmpty() && missing == report.b3.positions.size();
                cursor.value("B3 · valores informados" + (missing > 0 && !allMissing ? " (parcial)" : ""),
                        allMissing ? "Não informado" : Money.format(report.b3.totalCents()), false);
                cursor.paragraph(report.b3.unvaluedCount() + (report.b3.unvaluedCount() == 1
                        ? " posição sem valor informado. " : " posições sem valor informado. ")
                        + "Valores ausentes não são tratados como zero. O relatório B3 não informa custo "
                        + "histórico nem rentabilidade.", 9, MUTED);
            }
        } else {
            cursor.section("Investimentos");
            cursor.paragraph("A visão de investimentos está disponível em Todas as contas. As posições "
                    + "manuais e B3 não são vinculadas ao filtro de conta deste relatório.", 9, MUTED);
        }

        cursor.section("Lançamentos detalhados");
        cursor.paragraph(report.transactions.size() + " lançamentos · do mais recente ao mais antigo. "
                + "Os valores são exibidos integralmente; o tipo indica entrada ou saída.", 9, MUTED);
        List<FinanceReport.Row> rows = new ArrayList<>(report.transactions);
        rows.sort(Comparator.comparing((FinanceReport.Row row) -> row.date).thenComparingLong(row -> row.id).reversed());
        int index = 0;
        for (FinanceReport.Row row : rows) cursor.transaction(row, ++index);
        if (rows.isEmpty()) cursor.paragraph("Nenhum lançamento no período e conta selecionados.", 10, MUTED);
        cursor.finish();
        return cursor.layout;
    }

    private static String method(String method) {
        switch (method) {
            case "cash": return "Dinheiro";
            case "debit": return "Débito";
            case "credit": return "Crédito";
            case "pix": return "Pix";
            case "transfer": return "Transferência entre contas";
            case "card_payment": return "Pagamento de fatura";
            default: return "Outro";
        }
    }

    private static void configure(Paint paint, float size, boolean bold, int color) {
        paint.setTextSize(size);
        paint.setTypeface(Typeface.create("sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL));
        paint.setColor(color);
    }

    static final class Layout {
        final List<Page> pages = new ArrayList<>();
    }

    static final class Page {
        final List<Text> texts = new ArrayList<>();
        final List<Bar> bars = new ArrayList<>();
        final List<Integer> transactionNumbers = new ArrayList<>();
    }

    static final class Text {
        final String value;
        final float x, baseline, size, measuredWidth;
        final boolean bold;
        final int color;
        Text(String value, float x, float baseline, float size, boolean bold, int color, float measuredWidth) {
            this.value = value; this.x = x; this.baseline = baseline; this.size = size;
            this.bold = bold; this.color = color; this.measuredWidth = measuredWidth;
        }
    }

    static final class Bar {
        final float x, y, width, height;
        final int color;
        Bar(float x, float y, float width, float height, int color) {
            this.x = x; this.y = y; this.width = width; this.height = height; this.color = color;
        }
    }

    private static final class Cursor {
        final Layout layout = new Layout();
        final FinanceReport report;
        final Paint measure = new Paint(Paint.ANTI_ALIAS_FLAG);
        Page page;
        float y;

        Cursor(FinanceReport report) throws IOException { this.report = report; newPage(); }

        void newPage() throws IOException {
            if (layout.pages.size() >= MAX_PAGES)
                throw new IOException("O relatório excede o tamanho seguro para PDF. Reduza o período ou exporte em CSV.");
            page = new Page(); layout.pages.add(page);
            text("MyFinanceHub", LEFT, 47, 19, true, ACCENT);
            text("Relatório financeiro", LEFT, 68, 11, true, INK);
            text(DATE.format(report.from) + " a " + DATE.format(report.to), LEFT, 85, 9, false, MUTED);
            y = 99;
            for (String line : wrap("Conta: " + report.accountName, RIGHT - LEFT, 9, false)) {
                text(line, LEFT, y, 9, false, MUTED); y += 12;
            }
            bar(LEFT, y + 1, RIGHT - LEFT, 1, LIGHT); y += 18;
        }

        void ensure(float height) throws IOException { ensure(height, false); }
        void ensure(float height, boolean monthly) throws IOException {
            if (y + height <= CONTENT_BOTTOM) return;
            newPage();
            if (monthly) {
                text("Comparação mensal · continuação", LEFT, y + 12, 11, true, ACCENT); y += 23;
                monthHeader();
            }
        }

        void section(String title) throws IOException {
            ensure(54);
            y += 10;
            text(title, LEFT, y + 14, 13, true, ACCENT);
            y += 29;
        }

        void paragraph(String value, float size, int color) throws IOException {
            for (String line : wrap(value, RIGHT - LEFT, size, false)) {
                ensure(size + 5);
                text(line, LEFT, y + size, size, false, color); y += size + 5;
            }
            y += 5;
        }

        void value(String label, String value, boolean bold) throws IOException {
            List<String> lines = wrap(label, 305, 10, bold);
            ensure(lines.size() * 14 + 10);
            float start = y;
            for (String line : lines) { text(line, LEFT, y + 11, 10, bold, INK); y += 14; }
            right(value, RIGHT, start + 11, 10, bold, INK);
            y += 10;
        }

        void monthHeader() throws IOException {
            ensure(25);
            text("Mês", LEFT, y + 10, 9, true, MUTED);
            right("Entradas", 250, y + 10, 9, true, MUTED);
            right("Despesas", 400, y + 10, 9, true, MUTED);
            right("Resultado", RIGHT, y + 10, 9, true, MUTED);
            bar(LEFT, y + 17, RIGHT - LEFT, 0.5f, LIGHT);
            y += 25;
        }

        void transaction(FinanceReport.Row row, int number) throws IOException {
            List<String> description = wrap(row.description, RIGHT - LEFT, 10, false);
            List<String> metadata = wrap("Conta: " + row.accountName + " · Categoria: " + row.categoryName,
                    RIGHT - LEFT, 8.5f, false);
            List<String> payment = wrap("Forma: " + method(row.method)
                    + (row.cardName.isEmpty() ? "" : " · Cartão: " + row.cardName), RIGHT - LEFT, 8.5f, false);
            List<String> excluded = row.excludedFromResult
                    ? wrap("Fora do resultado: " + method(row.method) + ".", RIGHT - LEFT, 8.5f, false)
                    : Collections.emptyList();
            float height = 22 + description.size() * 14 + (metadata.size() + payment.size() + excluded.size()) * 12 + 11;
            ensure(height);
            page.transactionNumbers.add(number);
            text(number + ". " + DATE.format(row.date) + " · " + ("income".equals(row.type) ? "Entrada" : "Despesa"),
                    LEFT, y + 11, 10, true, INK);
            right(Money.format(row.amountCents), RIGHT, y + 11, 10, true, INK);
            y += 22;
            for (String line : description) { text(line, LEFT, y + 10, 10, false, INK); y += 14; }
            for (List<String> group : java.util.Arrays.asList(metadata, payment, excluded)) {
                for (String line : group) { text(line, LEFT, y + 9, 8.5f, false, MUTED); y += 12; }
            }
            bar(LEFT, y + 5, RIGHT - LEFT, 0.5f, LIGHT); y += 11;
        }

        List<String> wrap(String value, float width, float size, boolean bold) {
            configure(measure, size, bold, INK);
            String remaining = value == null ? "" : value.replaceAll("\\s+", " ").trim();
            List<String> lines = new ArrayList<>();
            if (remaining.isEmpty()) { lines.add("—"); return lines; }
            while (!remaining.isEmpty()) {
                int count = measure.breakText(remaining, true, width, null);
                if (count <= 0) count = Character.charCount(remaining.codePointAt(0));
                if (count < remaining.length() && count > 0 && Character.isHighSurrogate(remaining.charAt(count - 1))) count--;
                if (count == 0) count = Character.charCount(remaining.codePointAt(0));
                if (count < remaining.length()) {
                    int space = remaining.lastIndexOf(' ', count);
                    if (space > 0) count = space;
                }
                lines.add(remaining.substring(0, count).trim());
                remaining = remaining.substring(count).trim();
            }
            return lines;
        }

        void text(String value, float x, float baseline, float size, boolean bold, int color) {
            configure(measure, size, bold, color);
            page.texts.add(new Text(value, x, baseline, size, bold, color, measure.measureText(value)));
        }
        void right(String value, float right, float baseline, float size, boolean bold, int color) {
            configure(measure, size, bold, color);
            text(value, right - measure.measureText(value), baseline, size, bold, color);
        }
        void bar(float x, float y, float width, float height, int color) {
            page.bars.add(new Bar(x, y, width, height, color));
        }

        void finish() {
            Page last = page;
            for (int i = 0; i < layout.pages.size(); i++) {
                page = layout.pages.get(i);
                bar(LEFT, 804, RIGHT - LEFT, 0.5f, LIGHT);
                text("MyFinanceHub · relatório local", LEFT, 819, 8, false, MUTED);
                right("Página " + (i + 1) + " de " + layout.pages.size(), RIGHT, 819, 8, false, MUTED);
            }
            page = last;
        }
    }
}
