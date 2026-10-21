package br.com.myfinancehub;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;

/** Tests the measured plan actually consumed by PdfDocument, including pagination. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, qualifiers = "pt-rBR")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class FinanceReportPdfTest {
    private static final LocalDate FROM = LocalDate.of(2026, 10, 1);
    private static final LocalDate TO = LocalDate.of(2026, 10, 31);
    private static final String HASH = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Test public void longDescriptionsPaginateWithoutLosingRowsOrClippingValues() throws Exception {
        List<Models.Entry> entries = new ArrayList<>();
        List<String> descriptions = new ArrayList<>();
        for (int i = 1; i <= 160; i++) {
            String description = "Exemplo" + i + "_" + "ábcdefghijklmnopqrstuvwxyz".repeat(18) + "_fim" + i;
            descriptions.add(description);
            entries.add(entry(i, description, i % 2 == 0 ? "income" : "expense", "credit", 101));
        }
        FinanceReport report = build(entries, 0, null,
                "Conta fictícia " + "longa ".repeat(10),
                "Categoria fictícia " + "extensa ".repeat(7),
                "Cartão fictício " + "longo ".repeat(10));
        FinanceReportPdf.Layout layout = FinanceReportPdf.layout(report);
        assertTrue("Long descriptions must produce several readable pages", layout.pages.size() > 10);
        Set<Integer> found = new HashSet<>();
        List<Integer> sequence = new ArrayList<>();
        for (int i = 0; i < layout.pages.size(); i++) {
            FinanceReportPdf.Page page = layout.pages.get(i);
            for (Integer number : page.transactionNumbers) {
                assertTrue("A transaction must not be duplicated across pages", found.add(number));
                sequence.add(number);
            }
            assertTrue(text(page).contains("Página " + (i + 1) + " de " + layout.pages.size()));
            assertTrue("The period must repeat on every page", text(page).contains("01/10/2026 a 31/10/2026"));
        }
        assertEquals(160, found.size());
        for (int i = 0; i < sequence.size(); i++) assertEquals(i + 1, sequence.get(i).intValue());
        String content = text(layout).replace(" ", "");
        for (String description : descriptions)
            assertTrue("Every complete description must survive wrapping: " + description.substring(0, 12),
                    content.contains(description.replace(" ", "")));
        assertBounds(layout);
    }

    @Test public void newestTransactionsComeFirstAndInternalMovementsRemainVisible() throws Exception {
        Models.Entry old = entry(1, "Registro fictício antigo", "expense", "pix", 101);
        old.date = "2026-10-02";
        Models.Entry recent = entry(2, "Registro fictício recente", "expense", "transfer", 202);
        recent.date = "2026-10-29";
        Models.Entry invoice = entry(3, "Fatura fictícia", "expense", "card_payment", 303);
        invoice.date = "2026-10-29";
        FinanceReportPdf.Layout layout = FinanceReportPdf.layout(build(Arrays.asList(old, recent, invoice), 0));
        String content = text(layout);
        assertTrue(content.indexOf("Fatura fictícia") < content.indexOf("Registro fictício recente"));
        assertTrue(content.indexOf("Registro fictício recente") < content.indexOf("Registro fictício antigo"));
        assertTrue(content.contains("Fora do resultado: Transferência entre contas."));
        assertTrue(content.contains("Fora do resultado: Pagamento de fatura."));
        assertTrue(content.contains("Forma: Pix"));
        assertTrue(content.contains(Money.format(101)));
        assertTrue(content.contains(Money.format(202)));
        assertTrue(content.contains(Money.format(303)));
        assertBounds(layout);
    }

    @Test public void fullLongAmountsRemainExactAndFitInAllSummaryAndTableColumns() throws Exception {
        FinanceReport report = build(Collections.singletonList(
                entry(1, "Despesa fictícia no limite", "expense", "credit", Long.MAX_VALUE)), 0);
        FinanceReportPdf.Layout layout = FinanceReportPdf.layout(report);
        String content = text(layout);
        assertTrue(content.contains(Money.format(Long.MAX_VALUE)));
        assertTrue(content.contains(Money.format(-Long.MAX_VALUE)));
        assertBounds(layout);
        for (FinanceReportPdf.Page page : layout.pages) {
            for (FinanceReportPdf.Text value : page.texts) {
                if (value.size < 9 && value.value.equals(Money.format(-Long.MAX_VALUE)))
                    assertTrue("A negative monthly result must fit its own column", value.x >= 400);
            }
        }
    }

    @Test public void emptyPeriodAndCurrentInvestmentSnapshotsStayExplicit() throws Exception {
        B3Portfolio.Snapshot snapshot = new B3Portfolio.Snapshot("2026-09-30", "2026-10-01T12:00:00Z", HASH,
                Arrays.asList(
                        new B3Portfolio.Position("Posição fictícia zero", "Instituição fictícia", "", "DEMO3", "", "Ação", "1", 0L),
                        new B3Portfolio.Position("Posição fictícia sem valor", "Instituição fictícia", "", "DEMO4", "", "Ação", "1", null)));
        FinanceReportPdf.Layout layout = FinanceReportPdf.layout(build(Collections.emptyList(), 0, snapshot,
                "Conta fictícia", "Categoria fictícia", "Cartão fictício"));
        String content = text(layout);
        assertTrue(content.contains("Nenhum lançamento no período e conta selecionados."));
        assertTrue(content.contains("Nenhuma despesa de consumo neste período."));
        assertTrue(content.contains("Investimentos · posição atual"));
        assertTrue(content.contains("30/09/2026"));
        assertTrue(content.contains("1 posição sem valor informado."));
        assertTrue(content.contains("Valores ausentes não são tratados como zero."));
        assertTrue(content.replace(" ", "").contains("Nãosãoretornoourentabilidadedoperíodo."));
        assertTrue(content.contains(Money.format(0)));
        assertBounds(layout);
    }

    @Test public void accountFilterExplainsWhyInvestmentSnapshotsAreOutsideItsScope() throws Exception {
        String content = text(FinanceReportPdf.layout(build(Collections.emptyList(), 1)));
        assertTrue(content.contains("Todas as contas"));
        assertTrue(content.replace(" ", "").contains("AsposiçõesmanuaiseB3nãosãovinculadasaofiltrodeconta"));
        assertFalse(content.contains("B3 · valores informados"));
    }

    @Test public void unvaluedB3SnapshotIsNotPresentedAsAZeroValuation() throws Exception {
        B3Portfolio.Position unknown = new B3Portfolio.Position("Posição fictícia", "Instituição fictícia", "", "DEMO3", "", "Ação", "1", null);
        B3Portfolio.Snapshot snapshot = new B3Portfolio.Snapshot("2026-09-30", "2026-10-01T12:00:00Z", HASH,
                Collections.singletonList(unknown));
        FinanceReportPdf.Layout missing = FinanceReportPdf.layout(build(Collections.emptyList(), 0, snapshot,
                "Conta fictícia", "Categoria fictícia", "Cartão fictício"));
        assertEquals("Não informado", valueAfter(missing, "B3 · valores informados"));
        B3Portfolio.Position zero = new B3Portfolio.Position("Posição fictícia", "Instituição fictícia", "", "DEMO3", "", "Ação", "1", 0L);
        B3Portfolio.Snapshot zeroSnapshot = new B3Portfolio.Snapshot("2026-09-30", "2026-10-01T12:00:00Z", HASH,
                Collections.singletonList(zero));
        FinanceReportPdf.Layout valued = FinanceReportPdf.layout(build(Collections.emptyList(), 0, zeroSnapshot,
                "Conta fictícia", "Categoria fictícia", "Cartão fictício"));
        assertEquals(Money.format(0), valueAfter(valued, "B3 · valores informados"));
    }

    @Test public void manualInvestmentVariationUsesExactCentsAndIsNotAPeriodReturn() throws Exception {
        FinanceReport report = FinanceReport.build(FROM, TO, 0, Collections.emptyList(),
                Collections.singletonList(new Models.Account(1, "Conta fictícia", 0)),
                Collections.emptyList(), Collections.emptyList(),
                Collections.singletonList(new Models.Investment(1, "Investimento fictício", "DEMO3", "Outro", 100000, 135555)), null);
        String content = text(FinanceReportPdf.layout(report));
        assertTrue(content.contains("Variação sobre o valor aplicado"));
        assertTrue(content.contains(Money.format(35555)));
        assertTrue(content.contains("35,56%"));
        assertTrue(content.replace(" ", "").contains("nemcalcularentabilidadedoperíodoselecionado."));

        String empty = text(FinanceReportPdf.layout(build(Collections.emptyList(), 0)));
        assertTrue("A zero cost must never invent a return percentage", empty.contains("Indisponível (aplicado = zero)"));
        assertFalse(empty.contains("NaN"));
        assertFalse(empty.contains("Infinity"));
    }

    @Test public void excessiveTransactionCountRefusesPdfBeforeWritingAnyBytes() throws Exception {
        List<Models.Entry> entries = new ArrayList<>();
        for (int i = 1; i <= FinanceReportPdf.MAX_TRANSACTIONS + 1; i++)
            entries.add(entry(i, "Registro fictício " + i, "expense", "pix", 1));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            FinanceReportPdf.write(build(entries, 0), output);
            fail("Large exports must not silently truncate transactions or start an incomplete PDF");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("5.000"));
            assertTrue(expected.getMessage().contains("CSV"));
            assertEquals(0, output.size());
        }
    }

    private static Models.Entry entry(long id, String description, String type, String method, long amount) {
        return new Models.Entry(id, 1, 2, "credit".equals(method) ? 3 : 0, description,
                "2026-10-15", type, method, "", amount);
    }

    private static FinanceReport build(List<Models.Entry> entries, long accountId) {
        return build(entries, accountId, null, "Conta fictícia", "Categoria fictícia", "Cartão fictício");
    }

    private static FinanceReport build(List<Models.Entry> entries, long accountId, B3Portfolio.Snapshot snapshot,
                                       String account, String category, String card) {
        return FinanceReport.build(FROM, TO, accountId, entries,
                Collections.singletonList(new Models.Account(1, account, 0)),
                Collections.singletonList(new Models.Category(2, category, "both")),
                Collections.singletonList(new Models.Card(3, card, "0000", 1, 10)),
                Collections.emptyList(), snapshot);
    }

    private static void assertBounds(FinanceReportPdf.Layout layout) {
        for (FinanceReportPdf.Page page : layout.pages) {
            for (FinanceReportPdf.Text run : page.texts) {
                assertTrue("Text outside left page margin: " + run.value, run.x >= FinanceReportPdf.LEFT - 0.1f);
                assertTrue("Text clipped at right margin: " + run.value,
                        run.x + run.measuredWidth <= FinanceReportPdf.RIGHT + 0.1f);
                assertTrue("Text outside vertical page bounds: " + run.value,
                        run.baseline - run.size >= 20 && run.baseline <= 820);
            }
            for (FinanceReportPdf.Bar bar : page.bars) {
                assertTrue(bar.x >= FinanceReportPdf.LEFT);
                assertTrue(bar.x + bar.width <= FinanceReportPdf.RIGHT + 0.1f);
                assertTrue(bar.y > 0 && bar.y + bar.height < FinanceReportPdf.PAGE_HEIGHT);
            }
        }
    }

    private static String text(FinanceReportPdf.Layout layout) {
        StringBuilder result = new StringBuilder();
        for (FinanceReportPdf.Page page : layout.pages) result.append(text(page));
        return result.toString();
    }

    private static String valueAfter(FinanceReportPdf.Layout layout, String label) {
        for (FinanceReportPdf.Page page : layout.pages) {
            for (int i = 0; i + 1 < page.texts.size(); i++)
                if (label.equals(page.texts.get(i).value)) return page.texts.get(i + 1).value;
        }
        throw new AssertionError("Missing report label: " + label);
    }

    private static String text(FinanceReportPdf.Page page) {
        StringBuilder result = new StringBuilder();
        for (FinanceReportPdf.Text run : page.texts) result.append(run.value);
        return result.toString();
    }
}
