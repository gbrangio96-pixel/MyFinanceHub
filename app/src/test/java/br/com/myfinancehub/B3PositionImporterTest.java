package br.com.myfinancehub;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.*;

/** All workbooks in these tests are synthetic; no bank/investor records are included. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class B3PositionImporterTest {
    @Test public void csvReadsExactAmountsFractionalQuantityAndExplicitReferenceDate() {
        B3PositionImporter.Report report = csv("\uFEFFData da posição;16/10/2026\r\n"
                + "Produto;Instituição;Conta;Código de Negociação;ISIN;Tipo;Quantidade;Valor Atualizado\r\n"
                + "Fundo fictício;Corretora Exemplo;DEMO;DEMO11;BRDEMO000001;Fundo;1.234,567890123456;9.007.199.254.740,91\r\n"
                + "Título fictício;Corretora Exemplo;DEMO;;;Renda Fixa;0,25;-\r\n");
        assertEquals("2026-10-16", report.asOfDate);
        assertEquals(2, report.positions.size());
        B3Portfolio.Position p = report.positions.get(0);
        assertEquals("Fundo fictício", p.product);
        assertEquals("Corretora Exemplo", p.institution);
        assertEquals("DEMO", p.account);
        assertEquals("DEMO11", p.ticker);
        assertEquals("BRDEMO000001", p.isin);
        assertEquals("Fundo", p.kind);
        assertEquals("1234.567890123456", p.quantity);
        assertEquals(Long.valueOf(900719925474091L), p.currentCents);
        assertEquals("0.25", report.positions.get(1).quantity);
        assertNull(report.positions.get(1).currentCents);
    }

    @Test public void csvAcceptsQuotedCommasEscapedQuotesAndWindows1252() {
        B3PositionImporter.Report quoted = csv("Produto,Quantidade,Valor Total\n"
                + "\"Empresa, \"\"Exemplo\"\"\",\"10,5\",\"1.234,56\"\n");
        assertEquals("Empresa, \"Exemplo\"", quoted.positions.get(0).product);
        assertEquals("10.5", quoted.positions.get(0).quantity);
        assertEquals(Long.valueOf(123456), quoted.positions.get(0).currentCents);
        byte[] legacy = "Produto;Quantidade;Instituição\nAção fictícia;10;Corretora Exemplo\n"
                .getBytes(Charset.forName("windows-1252"));
        assertEquals("Ação fictícia", B3PositionImporter.parse(legacy, "posicao.csv").positions.get(0).product);
    }

    @Test public void acquisitionAndUnitPricesDoNotBecomeCurrentValueOrAComputedValue() {
        B3Portfolio.Position p = csv("Produto;Quantidade;Valor Aplicado;Preço fechamento;Preço unitário atualizado\n"
                + "Título fictício;3;1.000,00;90,00;95,00\n").positions.get(0);
        assertNull(p.currentCents);
    }

    @Test public void dateComesOnlyFromExplicitReportDataAndCanBeAColumn() {
        B3PositionImporter.Report noDate = B3PositionImporter.parse(bytes("Produto;Quantidade\nExemplo;1\n"), "posicao-2026-10-16-10-20-30.csv");
        assertEquals("", noDate.asOfDate);
        assertEquals("2026-10-16", csv("Produto;Data de Referência;Quantidade\nExemplo;16/10/2026;1\n").asOfDate);
        invalidCsv("Produto;Data de Referência;Quantidade\nA;16/10/2026;1\nB;15/10/2026;2\n", "datas");
        invalidCsv("Data da posição;31/02/2026\nProduto;Quantidade\nA;1\n", "Data");
    }

    @Test public void xlsxNumericCellsUseDecimalDotAndInlineStringsAreDecoded() throws Exception {
        String sheet = "<worksheet><sheetData><row r=\"1\">" + inline("A1", "Produto")
                + inline("B1", "Quantidade") + inline("C1", "Valor Atualizado") + "</row>"
                + "<row r=\"2\">" + inline("A2", "Empresa &amp; Exemplo")
                + numeric("B2", "4.5") + numeric("C2", "1234.56") + "</row></sheetData></worksheet>";
        B3PositionImporter.Report report = B3PositionImporter.parse(workbook("Ações", sheet), "posicao.xlsx");
        assertEquals("Empresa & Exemplo", report.positions.get(0).product);
        assertEquals("4.5", report.positions.get(0).quantity);
        assertEquals("Ações", report.positions.get(0).kind);
        assertEquals(Long.valueOf(123456), report.positions.get(0).currentCents);
    }

    @Test public void xlsxUsesSharedStringsRichTextAndActualReferencesWithMissingCells() throws Exception {
        String sheet = "<worksheet><sheetData><row r=\"3\"><c r=\"A3\" t=\"s\"><v>0</v></c>"
                + "<c r=\"D3\" t=\"s\"><v>1</v></c><c r=\"F3\" t=\"s\"><v>2</v></c></row>"
                + "<row r=\"5\"><c r=\"A5\" t=\"s\"><v>3</v></c>"
                + numeric("D5", "0.125") + numeric("F5", "999.90") + "</row></sheetData></worksheet>";
        Map<String, byte[]> archive = base("Fundos", sheet);
        archive.put("xl/sharedStrings.xml", bytes("<sst><si><t>Produto</t></si><si><t>Quantidade</t></si>"
                + "<si><t>Valor Total</t></si><si><r><t>Fundo </t></r><r><t>fictício</t></r></si></sst>"));
        B3Portfolio.Position p = B3PositionImporter.parse(zip(archive), "posicao.xlsx").positions.get(0);
        assertEquals("Fundo fictício", p.product);
        assertEquals("0.125", p.quantity);
        assertEquals(Long.valueOf(99990), p.currentCents);
    }

    @Test public void allNonemptyWorksheetsMustBeRecognizedRatherThanSilentlyDropped() throws Exception {
        Map<String, byte[]> archive = base("Ações", basicSheet("Empresa fictícia"));
        archive.put("xl/workbook.xml", bytes("<workbook xmlns:r=\"urn:rels\"><sheets>"
                + "<sheet name=\"Ações\" r:id=\"r1\"/><sheet name=\"Renda Fixa\" r:id=\"r2\"/></sheets></workbook>"));
        archive.put("xl/_rels/workbook.xml.rels", bytes("<Relationships><Relationship Id=\"r1\" Target=\"worksheets/sheet1.xml\"/>"
                + "<Relationship Id=\"r2\" Target=\"worksheets/sheet2.xml\"/></Relationships>"));
        archive.put("xl/worksheets/sheet2.xml", bytes(basicSheet("Título fictício")));
        assertEquals(2, B3PositionImporter.parse(zip(archive), "b3.xlsx").positions.size());
        archive.put("xl/worksheets/sheet2.xml", bytes("<worksheet><sheetData><row r=\"1\">"
                + inline("A1", "Informações desconhecidas") + "</row></sheetData></worksheet>"));
        invalid(zip(archive), "b3.xlsx", "Cabeçalho");
        archive.put("xl/worksheets/sheet2.xml", bytes("<worksheet><sheetData/></worksheet>"));
        assertEquals(1, B3PositionImporter.parse(zip(archive), "b3.xlsx").positions.size());
    }

    @Test public void movementReportsAreRejectedEvenWithProductAndQuantityColumns() {
        invalidCsv("Data;Movimentação;Produto;Quantidade;Valor da Operação\n16/10/2026;Compra;Exemplo;1;10,00\n", "movimentação");
        invalidCsv("Entrada/Saída;Produto;Quantidade\nEntrada;Exemplo;1\n", "movimentação");
    }

    @Test public void duplicateAcrossCopiedSheetsCannotDoubleThePosition() throws Exception {
        String sheet = "<worksheet><sheetData><row r=\"1\">" + inline("A1", "Produto")
                + inline("B1", "Quantidade") + inline("C1", "Ticker") + "</row>"
                + "<row r=\"2\">" + inline("A2", "Empresa fictícia") + numeric("B2", "1")
                + inline("C2", "DEMO3") + "</row></sheetData></worksheet>";
        Map<String, byte[]> archive = base("Ações", sheet);
        archive.put("xl/workbook.xml", bytes("<workbook xmlns:r=\"urn:rels\"><sheets><sheet name=\"Ações\" r:id=\"r1\"/>"
                + "<sheet name=\"Ações cópia\" r:id=\"r2\"/></sheets></workbook>"));
        archive.put("xl/_rels/workbook.xml.rels", bytes("<Relationships><Relationship Id=\"r1\" Target=\"worksheets/sheet1.xml\"/>"
                + "<Relationship Id=\"r2\" Target=\"worksheets/sheet2.xml\"/></Relationships>"));
        archive.put("xl/worksheets/sheet2.xml", bytes(sheet));
        invalid(zip(archive), "posicao.xlsx", "duplicadas");
    }

    @Test public void duplicateTickerIsRejectedWhenOnlyOneRowHasAnIsin() {
        invalidCsv("Produto;Ticker;ISIN;Instituição;Conta;Tipo;Quantidade\n"
                + "Empresa;DEMO3;BRDEMO000001;Corretora;DEMO;Ações;1\n"
                + "Nome alternativo;DEMO3;;Corretora;DEMO;Ações cópia;2\n", "duplicadas");
    }

    @Test public void duplicatesAndMalformedLaterRowsFailTheWholeReport() {
        invalidCsv("Produto;Ticker;Instituição;Conta;Quantidade\nA;DEMO3;Exemplo;1;10\nB;DEMO3;Exemplo;1;20\n", "duplicadas");
        invalidCsv("Produto;Quantidade;Valor Atual\nVálido;1;2,00\nInválido;2;2,001\n", "duas casas");
        invalidCsv("Produto;Quantidade\nVálido;1\nInválido;-1\n", "Número");
        invalidCsv("Produto;Quantidade\nA;1.23\n", "Número");
        invalidCsv("Produto;Quantidade\nA;0,1234567890123\n", "12 casas");
        invalidCsv("Produto;Quantidade\nTotal;2\n", "total");
        invalidCsv("Produto;Quantidade\nA;1;conteúdo extra\n", "além");
        invalidCsv("Produto;Quantidade;Quantidade Total\nA;1;1\n", "repete");
    }

    @Test public void optionalUnpricedAssetsRemainUnknownAndZeroValuationIsPreserved() {
        B3PositionImporter.Report report = csv("Produto;Quantidade;Valor Atual\nA;1;-\nB;1;\nC;0;0,00\n");
        assertNull(report.positions.get(0).currentCents);
        assertNull(report.positions.get(1).currentCents);
        assertEquals(Long.valueOf(0), report.positions.get(2).currentCents);
    }

    @Test public void aggregateOverflowFailsWithAReadableErrorBeforePreview() {
        assertEquals(Long.valueOf(Long.MAX_VALUE), csv("Produto;Quantidade;Valor Atual\nA;1;92.233.720.368.547.758,07\n").positions.get(0).currentCents);
        invalidCsv("Produto;Quantidade;Valor Atual\nA;1;92.233.720.368.547.758,07\nB;1;0,01\n", "total da posição");
    }

    @Test public void onlyExplicitBrazilianCurrencyIsAcceptedWithoutConvertingValues() {
        assertEquals(4, csv("Produto;Quantidade;Valor Atual;Moeda\nA;1;10,00;brl\nB;1;20,00;R$\nC;1;30,00;Real\nD;1;40,00;Reais\n").positions.size());
        for (String header : new String[]{"Moeda", "Código da moeda", "Currency", "Currency code"}) {
            invalidCsv("Produto;Quantidade;Valor Atual;" + header + "\nA;1;10,00;USD\n", "somente valores em reais");
        }
        invalidCsv("Moeda;USD\nProduto;Quantidade;Valor Atual\nA;1;10,00\n", "somente valores em reais");
        invalidCsv("Moeda: USD\nProduto;Quantidade;Valor Atual\nA;1;10,00\n", "somente valores em reais");
        assertEquals(Long.valueOf(1000), csv("Moeda;BRL\nProduto;Quantidade;Valor Atual\nA;1;10,00\n").positions.get(0).currentCents);
        try { csv("Produto;Quantidade;Valor Atual;Moeda\nA;1;10,00;MOEDA-PRIVADA-FICTICIA\n"); fail(); }
        catch (IllegalArgumentException ex) { assertFalse(ex.getMessage().contains("MOEDA-PRIVADA-FICTICIA")); }
    }

    @Test public void xmlFormulasErrorsBadReferencesAndSharedStringIndexesAreRejected() throws Exception {
        String header = "<worksheet><sheetData><row r=\"1\">" + inline("A1", "Produto") + inline("B1", "Quantidade") + "</row>";
        invalid(workbook("Ações", header + "<row r=\"2\">" + inline("A2", "Exemplo")
                + "<c r=\"B2\"><f>1+1</f><v>2</v></c></row></sheetData></worksheet>"), "p.xlsx", "fórmulas");
        invalid(workbook("Ações", header + "<row r=\"2\">" + inline("A2", "Exemplo")
                + "<c r=\"B2\" t=\"e\"><v>#VALUE!</v></c></row></sheetData></worksheet>"), "p.xlsx", "erro");
        invalid(workbook("Ações", header + "<row r=\"2\">" + inline("A2", "Exemplo")
                + numeric("B3", "2") + "</row></sheetData></worksheet>"), "p.xlsx", "Referência");
        invalid(workbook("Ações", "<worksheet><sheetData><row r=\"1\"><c r=\"A1\" t=\"s\"><v>99</v></c></row></sheetData></worksheet>"), "p.xlsx", "Índice");
    }

    @Test public void xmlEntitiesExternalRelationshipsMacrosAndTraversalAreRejected() throws Exception {
        Map<String, byte[]> archive = base("Ações", basicSheet("Exemplo"));
        archive.put("xl/worksheets/sheet1.xml", bytes("<!DOCTYPE worksheet [<!ENTITY x SYSTEM 'file:///not-read'>]><worksheet/>"));
        invalid(zip(archive), "b3.xlsx", "DTD");
        archive = base("Ações", basicSheet("Exemplo"));
        archive.put("xl/worksheets/_rels/sheet1.xml.rels", bytes("<Relationships><Relationship Id=\"external\" TargetMode=\"External\" Target=\"https://example.invalid/private\"/></Relationships>"));
        invalid(zip(archive), "b3.xlsx", "externos");
        archive = base("Ações", basicSheet("Exemplo"));
        archive.put("xl/vbaProject.bin", new byte[]{1});
        invalid(zip(archive), "b3.xlsx", "macros");
        archive = base("Ações", basicSheet("Exemplo"));
        archive.put("../outside.xml", bytes("<x/>"));
        invalid(zip(archive), "b3.xlsx", "interna");
    }

    @Test public void emptyUnsupportedOversizedAndExpandedZipFilesAreRejected() throws Exception {
        invalid(new byte[0], "p.csv", "vazio");
        invalid(bytes("não é Excel"), "p.xlsx", "válida");
        invalid(bytes("x"), "p.xls", ".xls");
        invalid(bytes("x"), "p.xlsm", "macros");
        invalid(bytes("x"), "p.pdf", "Formato");
        invalid(new byte[B3PositionImporter.MAX_FILE_BYTES + 1], "p.csv", "8 MiB");
        Map<String, byte[]> archive = base("Ações", basicSheet("Exemplo"));
        byte[] expanded = new byte[8 * 1024 * 1024 + 1];
        Arrays.fill(expanded, (byte) ' ');
        archive.put("xl/large.xml", expanded);
        invalid(zip(archive), "p.xlsx", "expandida");
        invalidCsv("Produto;Quantidade\n", "não contém");
    }

    @Test public void positionsLimitIsEnforcedAndReportsCannotBeMutated() {
        StringBuilder text = new StringBuilder("Produto;Quantidade\n");
        for (int index = 0; index <= B3PositionImporter.MAX_POSITIONS; index++) text.append("Produto ").append(index).append(";1\n");
        invalidCsv(text.toString(), "5.000");
        B3PositionImporter.Report report = csv("Produto;Quantidade\nA;1\n");
        try { report.positions.clear(); fail("mutable report"); }
        catch (UnsupportedOperationException expected) { }
    }

    @Test public void errorsDoNotEchoPrivateCellDataAndPrivateColumnsAreIgnored() {
        String privateMarker = "DADO-PRIVADO-FICTICIO";
        B3Portfolio.Position p = csv("Produto;Quantidade;CPF;CNPJ\nExemplo;1;" + privateMarker + ";" + privateMarker + "\n").positions.get(0);
        assertEquals("Exemplo", p.product);
        try { csv("Produto;Quantidade\nExemplo;" + privateMarker + "\n"); fail(); }
        catch (IllegalArgumentException ex) { assertFalse(ex.getMessage().contains(privateMarker)); }
    }

    private static B3PositionImporter.Report csv(String text) { return B3PositionImporter.parse(bytes(text), "posicao.csv"); }
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static void invalidCsv(String text, String message) { invalid(bytes(text), "posicao.csv", message); }
    private static void invalid(byte[] data, String name, String message) {
        try { B3PositionImporter.parse(data, name); fail("Should reject: " + message); }
        catch (IllegalArgumentException ex) { assertTrue(ex.getMessage(), ex.getMessage().contains(message)); }
    }

    private static String inline(String reference, String value) {
        return "<c r=\"" + reference + "\" t=\"inlineStr\"><is><t>" + value + "</t></is></c>";
    }
    private static String numeric(String reference, String value) { return "<c r=\"" + reference + "\"><v>" + value + "</v></c>"; }
    private static String basicSheet(String product) {
        return "<worksheet><sheetData><row r=\"1\">" + inline("A1", "Produto") + inline("B1", "Quantidade")
                + "</row><row r=\"2\">" + inline("A2", product) + numeric("B2", "10") + "</row></sheetData></worksheet>";
    }
    private static byte[] workbook(String name, String sheet) throws Exception { return zip(base(name, sheet)); }
    private static Map<String, byte[]> base(String name, String sheet) {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("[Content_Types].xml", bytes("<Types><Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/></Types>"));
        entries.put("xl/workbook.xml", bytes("<workbook xmlns:r=\"urn:rels\"><sheets><sheet name=\"" + name + "\" r:id=\"r1\"/></sheets></workbook>"));
        entries.put("xl/_rels/workbook.xml.rels", bytes("<Relationships><Relationship Id=\"r1\" Target=\"worksheets/sheet1.xml\"/></Relationships>"));
        entries.put("xl/worksheets/sheet1.xml", bytes(sheet));
        return entries;
    }
    private static byte[] zip(Map<String, byte[]> entries) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey())); zip.write(entry.getValue()); zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }
}
