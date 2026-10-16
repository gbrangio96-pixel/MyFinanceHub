package br.com.myfinancehub;

import org.junit.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;

public class StatementImporterTest {
    @Test public void readsBomPortugueseHeadersExactMoneyAndOptionalSuggestions() {
        String csv = "\uFEFFDATA;Descrição;VALOR;CATEGORIA;Cartão;FITID\r\n"
                + "14/10/2026;Mercado fictício;-1.234,56;Alimentação;Teste 1234;DEMO-1\r\n"
                + "2026-10-15;Salário fictício;5.000,00;Salário;;DEMO-2\r\n";
        List<StatementImporter.Row> rows = StatementImporter.parse(csv, "extrato.csv");
        assertEquals(2, rows.size());
        StatementImporter.Row expense = rows.get(0);
        assertEquals("2026-10-14", expense.date);
        assertEquals("Mercado fictício", expense.description);
        assertEquals("expense", expense.type);
        assertEquals(123456L, expense.amountCents);
        assertEquals("Alimentação", expense.suggestedCategory);
        assertEquals("Teste 1234", expense.suggestedCard);
        assertEquals("income", rows.get(1).type);
        assertEquals(500000L, rows.get(1).amountCents);
        assertNull(rows.get(1).suggestedCard);
    }

    @Test public void handlesRfc4180QuotedSeparatorsEscapedQuotesAndMultilineFields() {
        String csv = "date,description,amount\r\n"
                + "2026-10-14,\"Loja, \"\"Exemplo\"\"\r\nsegunda linha\",\"-12,34\"\r\n";
        StatementImporter.Row row = StatementImporter.parse(csv, "extrato.csv").get(0);
        assertEquals("Loja, \"Exemplo\"\nsegunda linha", row.description);
        assertEquals(1234L, row.amountCents);
        assertEquals("expense", row.type);
    }

    @Test public void acceptsCrOnlyFilesAndBlankPhysicalLines() {
        String csv = "\r  \rdate;description;amount\r2026-10-14;Exemplo;-10\r\r";
        assertEquals(1, StatementImporter.parse(csv, "extrato.csv").size());
    }

    @Test public void explicitTypeTakesPrecedenceOverSignAndCardCsvOption() {
        String csv = "date;description;amount;type\n"
                + "2026-10-14;Compra;10,00;Saída\n"
                + "2026-10-15;Estorno;-2,50;income\n"
                + "2026-10-16;Compra 2;3,00;débito\n";
        List<StatementImporter.Row> rows = StatementImporter.parse(csv, "cartao.csv", true);
        assertEquals("expense", rows.get(0).type);
        assertEquals("income", rows.get(1).type);
        assertEquals(250L, rows.get(1).amountCents);
        assertEquals("expense", rows.get(2).type);
    }

    @Test public void cardCsvUsesPositiveExpensesAndNegativeRefundsOnlyWhenSelected() {
        String csv = "date,title,amount\n2026-10-14,Compra,45.90\n2026-10-15,Estorno,-10.00\n";
        List<StatementImporter.Row> card = StatementImporter.parse(csv, "nubank.csv", true);
        List<StatementImporter.Row> account = StatementImporter.parse(csv, "nubank.csv");
        assertEquals("expense", card.get(0).type);
        assertEquals("income", card.get(1).type);
        assertEquals("income", account.get(0).type);
        assertEquals("expense", account.get(1).type);
    }

    @Test public void readsTheAppsExportFormatWithoutReversingExplicitDirections() {
        String csv = "date;description;type;amount;account;category;method;card\r\n"
                + "\"2026-10-14\";\"Compra; exemplo\";\"expense\";\"12,34\";\"Conta\";\"Lazer\";\"credit\";\"Teste 1234\"\r\n"
                + "\"2026-10-15\";\"Estorno\";\"income\";\"2,34\";\"Conta\";\"Lazer\";\"credit\";\"Teste 1234\"\r\n";
        List<StatementImporter.Row> rows = StatementImporter.parse(csv, "exportacao.csv", true);
        assertEquals("expense", rows.get(0).type);
        assertEquals("income", rows.get(1).type);
        assertEquals("Compra; exemplo", rows.get(0).description);
        assertEquals("Lazer", rows.get(0).suggestedCategory);
    }

    @Test public void supportsCurrencyParenthesesAndEnglishGroupingWithoutFloatingPointLoss() {
        String csv = "date;description;amount\n"
                + "2026-10-14;BR;R$ (1.234,50)\n"
                + "2026-10-14;US;1,234.50\n"
                + "2026-10-14;Grande;90071992547409.91\n";
        List<StatementImporter.Row> rows = StatementImporter.parse(csv, "extrato.csv");
        assertEquals(123450L, rows.get(0).amountCents);
        assertEquals("expense", rows.get(0).type);
        assertEquals(123450L, rows.get(1).amountCents);
        assertEquals(9007199254740991L, rows.get(2).amountCents);
    }

    @Test public void rejectsMissingOrDuplicateRequiredHeaders() {
        invalid("date;amount\n2026-10-14;-1\n", "CSV", "cabeçalho");
        invalid("date;data;description;amount\n2026-10-14;2026-10-14;X;1\n", "CSV", "repete");
    }

    @Test public void reportsMalformedRowsRatherThanReturningPartialSuccess() {
        invalid("date;description;amount\n2026-10-14;Válido;-10\n2026-10-15;Inválido\n", "CSV", "Linha 3");
        invalid("date,description,amount\n2026-10-14,Compra,-10,25\n", "CSV", "Quantidade de colunas");
        invalid("date;description;amount\n2026-10-14;;10\n", "CSV", "descrição");
        invalid("date;description;amount;type\n2026-10-14;X;10;talvez\n", "CSV", "Tipo inválido");
        invalid("date;description;amount\n;;;\n", "CSV", "Linha 2");
    }

    @Test public void readsTheReportedSevenColumnSampleWithQuotedDecimalComma() {
        String csv = "data,descrição,valor,tipo,categoria,cartão,id             \n"
                + "01/10/2026,\"Salário fictício — apenas   demonstração\",\"5000,00\",entrada,Salário,,\"DEMO-CSV-001\"\n"
                + "02/10/2026,\"Mercado fictício — apenas   demonstração\",\"-185,47\",saída,Alimentação,,\"DEMO-CSV-002\"";
        List<StatementImporter.Row> rows = StatementImporter.parse(csv, "extrato.csv");
        assertEquals(2, rows.size());
        assertEquals("2026-10-01", rows.get(0).date);
        assertEquals("Salário fictício — apenas   demonstração", rows.get(0).description);
        assertEquals("income", rows.get(0).type);
        assertEquals(500000L, rows.get(0).amountCents);
        assertEquals("Salário", rows.get(0).suggestedCategory);
        assertNull(rows.get(0).suggestedCard);
        assertEquals("2026-10-02", rows.get(1).date);
        assertEquals("Mercado fictício — apenas   demonstração", rows.get(1).description);
        assertEquals("expense", rows.get(1).type);
        assertEquals(18547L, rows.get(1).amountCents);
        assertEquals("Alimentação", rows.get(1).suggestedCategory);
        assertNull(rows.get(1).suggestedCard);
        assertEquals(StatementImporter.parse("date;description;amount;id\n"
                + "2026-10-01;Exemplo;1;DEMO-CSV-001\n", "outro.csv").get(0).sourceKey,
                rows.get(0).sourceKey);
    }

    @Test public void acceptsUnnamedTrailingHeaderColumnsWhenTheirDataIsEmpty() {
        String csv = "date;description;amount;;\n"
                + "2026-10-14;Sem extras;-1\n"
                + "2026-10-15;Um extra;-2;\n"
                + "2026-10-16;Dois extras;-3;;\n";
        List<StatementImporter.Row> rows = StatementImporter.parse(csv, "extrato.csv");
        assertEquals(3, rows.size());
        assertEquals(100L, rows.get(0).amountCents);
        assertEquals(200L, rows.get(1).amountCents);
        assertEquals(300L, rows.get(2).amountCents);
    }

    @Test public void acceptsDifferentNumbersOfTrailingEmptyCellsOnDataRows() {
        String csv = "date,description,amount\n"
                + "2026-10-14,Sem extras,-1\n"
                + "2026-10-15,Um extra,-2,\n"
                + "2026-10-16,Vários extras,\"-3,25\",,,\"\"\n";
        List<StatementImporter.Row> rows = StatementImporter.parse(csv, "extrato.csv");
        assertEquals(3, rows.size());
        assertEquals(325L, rows.get(2).amountCents);
        assertEquals("expense", rows.get(2).type);
    }

    @Test public void acceptsOnlyWhitespaceInTrailingUnnamedHeadersAndDataCells() {
        String csv = "date;description;amount; \t;\" \t\"\n"
                + "2026-10-14;Compra;-12,34; \t;\" \t\";\n";
        StatementImporter.Row row = StatementImporter.parse(csv, "extrato.csv").get(0);
        assertEquals("Compra", row.description);
        assertEquals(1234L, row.amountCents);
    }

    @Test public void neverMovesCellsAcrossUnnamedInteriorHeaders() {
        String csv = "date;;description;amount;\n2026-10-14;;Compra;-12,34;;\n";
        StatementImporter.Row row = StatementImporter.parse(csv, "extrato.csv").get(0);
        assertEquals("2026-10-14", row.date);
        assertEquals("Compra", row.description);
        assertEquals(1234L, row.amountCents);
    }

    @Test public void rejectsMeaningfulDataInTrailingUnnamedOrExtraColumns() {
        invalid("date;description;amount\n2026-10-14;Compra;-10;conteúdo\n",
                "CSV", "Quantidade de colunas");
        invalid("date;description;amount;;\n2026-10-14;Compra;-10;conteúdo;\n",
                "CSV", "Quantidade de colunas");
        invalid("date;description;amount;;\n2026-10-14;Compra;-10;;\" conteúdo \"\n",
                "CSV", "Quantidade de colunas");
    }

    @Test public void rejectsMissingCellsAndNeverFillsOrShiftsRequiredFields() {
        invalid("date;description;amount;;\n2026-10-14;Compra\n",
                "CSV", "esperadas 3, encontradas 2");
        invalid("date;description;amount;id\n2026-10-14;Compra;-10\n",
                "CSV", "esperadas 4, encontradas 3");
        invalid("date;description;amount;;\n2026-10-14;;-10; \n",
                "CSV", "descrição não foi informada");
        invalid("date;description;amount;;\n2026-10-14;Compra;; \n",
                "CSV", "valor inválido");
    }

    @Test public void keepsRejectingUnquotedDecimalCommasEvenWithEmptyHeaderExtras() {
        invalid("date,description,amount\n2026-10-14,Compra,-10,25\n",
                "CSV", "esperadas 3, encontradas 4");
        invalid("date,description,amount,,\n2026-10-14,Compra,-10,25,\n",
                "CSV", "esperadas 3, encontradas 5");
    }

    @Test public void reportsColumnCountsAndDelimiterWithoutEchoingTransactionData() {
        String[] separators = {";", ","};
        for (String separator : separators) {
            String csv = "date" + separator + "description" + separator + "amount\n"
                    + "2026-10-14" + separator + "Descrição confidencial fictícia"
                    + separator + "-10" + separator + "Texto adicional fictício\n";
            try {
                StatementImporter.parse(csv, "extrato.csv");
                fail("Esperava rejeição da coluna adicional com conteúdo.");
            } catch (IllegalArgumentException error) {
                assertTrue(error.getMessage().contains("Linha 2"));
                assertTrue(error.getMessage().contains("esperadas 3, encontradas 4"));
                assertTrue(error.getMessage().contains("separador detectado '" + separator + "'"));
                assertFalse(error.getMessage().contains("confidencial"));
                assertFalse(error.getMessage().contains("adicional fictício"));
            }
        }
    }

    @Test public void rejectsStructuredEmptyRowsRatherThanDiscardingThemAsExtraCells() {
        invalid("date;description;amount;;\n;;;\n", "CSV", "Linha 2");
        invalid("date;description;amount;;\n \t; \t; \t;\" \t\";\n",
                "CSV", "Data inválida");
    }

    @Test public void enforcesRawColumnAndFieldLimitsBeforeIgnoringEmptyTrailingCells() {
        String excessColumns = repeat(';', 126);
        invalid("date;description;amount" + excessColumns + "\n2026-10-14;Compra;-1\n",
                "CSV", "128 colunas");
        invalid("date;description;amount\n2026-10-14;Compra;-1" + excessColumns + "\n",
                "CSV", "128 colunas");
        invalid("date;description;amount\n2026-10-14;Compra;-1;" + repeat(' ', 16_385) + "\n",
                "CSV", "16.384");
    }

    @Test public void trailingEmptyCellsPreserveFallbackOccurrenceKeysAndExternalIds() {
        String plain = "date;description;amount\n"
                + "2026-10-14;Café fictício;-5\n2026-10-14;Café fictício;-5\n";
        String padded = "date;description;amount;;\n"
                + "2026-10-14;Café fictício;-5;\n2026-10-14;Café fictício;-5;;; \t\n";
        List<StatementImporter.Row> before = StatementImporter.parse(plain, "um.csv");
        List<StatementImporter.Row> after = StatementImporter.parse(padded, "dois.csv");
        assertEquals(2, after.size());
        assertEquals(before.get(0).sourceKey, after.get(0).sourceKey);
        assertEquals(before.get(1).sourceKey, after.get(1).sourceKey);
        assertNotEquals(after.get(0).sourceKey, after.get(1).sourceKey);
        String withId = "date;description;amount;id\n2026-10-14;Compra;-5;DEMO-ID\n";
        String paddedId = "date;description;amount;id; \t;\n"
                + "2026-10-14;Compra;-5;DEMO-ID;;\n";
        assertEquals(StatementImporter.parse(withId, "um.csv").get(0).sourceKey,
                StatementImporter.parse(paddedId, "dois.csv").get(0).sourceKey);
    }

    @Test public void reportsPhysicalLineAfterAMultilineRecord() {
        String csv = "date;description;amount\n2026-10-14;\"Duas\nlinhas\";-1\n"
                + "2026-10-15;Inválido;erro\n";
        invalid(csv, "CSV", "Linha 4");
    }

    @Test public void rejectsMalformedQuoting() {
        invalid("date;description;amount\n2026-10-14;\"Não fechado;-1\n", "CSV", "aspas não fechadas");
        invalid("date;description;amount\n2026-10-14;Err\"ado;-1\n", "CSV", "Aspas em posição");
        invalid("date;description;amount\n2026-10-14;\"Compra\"lixo;-1\n", "CSV", "após o fechamento");
    }

    @Test public void rejectsImpossibleDatesAndZeroOrInvalidPrecisionAndOverflow() {
        invalid(csvRow("2026-02-29", "X", "-1"), "CSV", "Data inválida");
        invalid(csvRow("2024-02-29", "X", "0,00"), "CSV", "diferente de zero");
        invalid(csvRow("2026-10-14", "X", "1.001"), "CSV", "duas casas");
        invalid(csvRow("2026-10-14", "X", "1e2"), "CSV", "valor inválido");
        invalid(csvRow("2026-10-14", "X", "92233720368547758.08"), "CSV", "fora do limite");
        invalid(csvRow("2026-10-14", "X", "-92233720368547758.08"), "CSV", "fora do limite");
        invalid(csvRow("2026-10-14", "X", "1.23,45"), "CSV", "valor inválido");
    }

    @Test public void stableExternalIdsSurviveFilenameAmountAndOrderChanges() {
        String first = "date;description;amount;id\n2026-10-14;Compra;-1;DEMO-ID\n";
        String second = "date;description;amount;FITID\n2026-10-15;Compra ajustada;-2;DEMO-ID\n";
        assertEquals(StatementImporter.parse(first, "um.csv").get(0).sourceKey,
                StatementImporter.parse(second, "dois.csv").get(0).sourceKey);
        String ofx = "<OFX>" + ofxTransaction("20261014", "-1", "<FITID>DEMO-ID<NAME>Compra") + "</OFX>";
        assertEquals(StatementImporter.parse(first, "um.csv").get(0).sourceKey,
                StatementImporter.parse(ofx, "um.ofx").get(0).sourceKey);
    }

    @Test public void fallbackKeysPreserveRepeatedIdenticalTransactionsAndAreStableOnReimport() {
        String csv = "date;description;amount\n"
                + "2026-10-14;Café fictício;-5\n"
                + "2026-10-14;Café fictício;-5\n"
                + "2026-10-14;Almoço fictício;-20\n";
        List<StatementImporter.Row> first = StatementImporter.parse(csv, "um.csv");
        List<StatementImporter.Row> second = StatementImporter.parse(csv, "renomeado.csv");
        Set<String> keys = new HashSet<>();
        for (int i = 0; i < first.size(); i++) {
            keys.add(first.get(i).sourceKey);
            assertEquals(first.get(i).sourceKey, second.get(i).sourceKey);
        }
        assertEquals(3, keys.size());
        String extra = "date;description;amount\n2026-10-13;Outra;-2\n"
                + csv.substring(csv.indexOf('\n') + 1);
        List<StatementImporter.Row> afterInsert = StatementImporter.parse(extra, "um.csv");
        assertEquals(first.get(0).sourceKey, afterInsert.get(1).sourceKey);
        assertEquals(first.get(1).sourceKey, afterInsert.get(2).sourceKey);
    }

    @Test public void fallbackIdentityNormalizesWhitespaceButKeepsCardsAndAmountsDistinct() {
        String first = "date;description;amount;category;card\n2026-10-14;Café  fictício;-5;Alimentação;1234\n";
        String categoryChange = "date;description;amount;category;card\n2026-10-14;Café fictício;-5;Lazer;1234\n";
        String anotherCard = "date;description;amount;card\n2026-10-14;Café fictício;-5;9876\n";
        String differentAmount = "date;description;amount;card\n2026-10-14;Café fictício;-6;1234\n";
        String key = StatementImporter.parse(first, "um.csv").get(0).sourceKey;
        assertEquals(key, StatementImporter.parse(categoryChange, "dois.csv").get(0).sourceKey);
        assertNotEquals(key, StatementImporter.parse(anotherCard, "tres.csv").get(0).sourceKey);
        assertNotEquals(key, StatementImporter.parse(differentAmount, "quatro.csv").get(0).sourceKey);
    }

    @Test public void readsLegacySgmlOfxWithHeaderAndUnclosedLeafTags() {
        String ofx = "OFXHEADER:100\nDATA:OFXSGML\nVERSION:102\nENCODING:UTF-8\n\n"
                + "<OFX><BANKMSGSRSV1><STMTTRNRS><STMTRS><BANKTRANLIST>\n"
                + ofxTransaction("20261014123000.000[-3:BRT]", "-145.99", "<FITID>DEMO-1<NAME>Loja fictícia<MEMO>Compra exemplo")
                + ofxTransaction("20261015", "2000.00", "<FITID>DEMO-2<MEMO>Salário fictício")
                + "</BANKTRANLIST></STMTRS></STMTTRNRS></BANKMSGSRSV1></OFX>";
        List<StatementImporter.Row> rows = StatementImporter.parse(ofx, "extrato.ofx");
        assertEquals(2, rows.size());
        assertEquals("2026-10-14", rows.get(0).date);
        assertEquals("Loja fictícia — Compra exemplo", rows.get(0).description);
        assertEquals(14599L, rows.get(0).amountCents);
        assertEquals("expense", rows.get(0).type);
        assertEquals("Salário fictício", rows.get(1).description);
        assertEquals("income", rows.get(1).type);
    }

    @Test public void readsXmlEntitiesNumericUnicodeAndCdataWithoutResolvingResources() {
        String ofx = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<OFX xmlns=\"urn:example:ofx\"><!-- exemplo fictício -->"
                + "<STMTTRN><DTPOSTED>20261014</DTPOSTED><TRNAMT>-10.25</TRNAMT>"
                + "<NAME>João &amp; Filhos &#x1F600;</NAME>"
                + "<MEMO><![CDATA[Teste &amp; literal <cartão>]]></MEMO><FITID>DEMO-XML</FITID>"
                + "</STMTTRN></OFX>";
        StatementImporter.Row row = StatementImporter.parse(ofx, "extrato.xml").get(0);
        assertEquals("João & Filhos 😀 — Teste &amp; literal <cartão>", row.description);
        assertEquals(1025L, row.amountCents);
    }

    @Test public void ofxCreditCardKeepsStandardDebitSignRegardlessOfCsvOption() {
        String ofx = "<OFX><CREDITCARDMSGSRSV1><CCSTMTTRNRS><CCSTMTRS><BANKTRANLIST>"
                + ofxTransaction("20261014", "-30.00", "<NAME>Compra")
                + ofxTransaction("20261015", "5.00", "<NAME>Estorno")
                + "</BANKTRANLIST></CCSTMTRS></CCSTMTTRNRS></CREDITCARDMSGSRSV1></OFX>";
        List<StatementImporter.Row> rows = StatementImporter.parse(ofx, "cartao.ofx", true);
        assertEquals("expense", rows.get(0).type);
        assertEquals("income", rows.get(1).type);
    }

    @Test public void ofxCalendarDateDoesNotMoveWhenTimezoneCrossesUtcMidnight() {
        String ofx = "<OFX>"
                + ofxTransaction("20261014235959.000[-3:BRT]", "-1", "<NAME>Noite")
                + ofxTransaction("20261014000001[14:TEST]", "-1", "<NAME>Madrugada")
                + "</OFX>";
        List<StatementImporter.Row> rows = StatementImporter.parse(ofx, "extrato.ofx");
        assertEquals("2026-10-14", rows.get(0).date);
        assertEquals("2026-10-14", rows.get(1).date);
    }

    @Test public void rejectsOfxCurrencyConversionAndMultipleDestinationAccounts() {
        String transaction = ofxTransaction("20261014", "-10", "<NAME>Compra fictícia");
        invalid("<OFX><STMTRS><CURDEF>USD<BANKTRANLIST>" + transaction
                + "</BANKTRANLIST></STMTRS></OFX>", "OFX", "moeda CURDEF deve ser BRL");
        invalid("<OFX><STMTRS><CURDEF>BRL<BANKTRANLIST>" + transaction
                + "</BANKTRANLIST></STMTRS><CCSTMTRS><CURDEF>BRL<BANKTRANLIST>"
                + transaction + "</BANKTRANLIST></CCSTMTRS></OFX>", "OFX", "vários extratos/contas");
        assertEquals(1, StatementImporter.parse("<OFX><STMTRS><CURDEF>BRL<BANKTRANLIST>"
                + transaction + "</BANKTRANLIST></STMTRS></OFX>", "extrato.ofx").size());
    }

    @Test public void rejectsDoctypeExternalAndUnknownEntitiesAndInvalidNumericEntities() {
        invalid("<!DOCTYPE OFX [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]><OFX>"
                + ofxTransaction("20261014", "-1", "<NAME>&xxe;") + "</OFX>", "OFX", "DOCTYPE");
        invalid("<!doctype OFX SYSTEM \"https://example.invalid/example.dtd\"><OFX></OFX>", "OFX", "DOCTYPE");
        invalid("<OFX>" + ofxTransaction("20261014", "-1", "<NAME>&custom;") + "</OFX>", "OFX", "Entidade XML");
        invalid("<OFX>" + ofxTransaction("20261014", "-1", "<NAME>&#xD800;") + "</OFX>", "OFX", "numérica");
        invalid("<OFX>" + ofxTransaction("20261014", "-1", "<NAME>&#0;") + "</OFX>", "OFX", "numérica");
    }

    @Test public void rejectsIncompleteOfxAndInvalidTransactionFields() {
        invalid("<OFX>" + ofxTransaction("20261014", "-1", "<NAME>X"), "OFX", "incompleta");
        invalid("<OFX><STMTTRN><DTPOSTED>20261014<TRNAMT>-1</OFX>", "OFX", "incompleta");
        invalid("<OFX><STMTTRN><DTPOSTED>20261014<NAME>X</STMTTRN></OFX>", "OFX", "valor inválido");
        invalid("<OFX>" + ofxTransaction("20260229", "-1", "<NAME>X") + "</OFX>", "OFX", "DTPOSTED inválida");
        invalid("<OFX>" + ofxTransaction("20261014250000", "-1", "<NAME>X") + "</OFX>", "OFX", "DTPOSTED inválida");
        invalid("<OFX>" + ofxTransaction("20261014", "0", "<NAME>X") + "</OFX>", "OFX", "diferente de zero");
        invalid("<OFX>" + ofxTransaction("20261014", "-1", "<DTPOSTED>20261015") + "</OFX>", "OFX", "repete o campo");
        invalid("<OFX><STMTTRN><STMTTRN></STMTTRN></OFX>", "OFX", "anterior não foi fechada");
    }

    @Test public void enforcesSizeColumnFieldAndTransactionLimits() {
        String huge = repeat('a', StatementImporter.MAX_FILE_BYTES + 1);
        invalid(huge, "CSV", "8 MiB");
        String multibyte = repeat('é', StatementImporter.MAX_FILE_BYTES / 2 + 1);
        invalid(multibyte, "CSV", "8 MiB");
        invalid(csvRow("2026-10-14", repeat('a', 16_385), "-1"), "CSV", "16.384");
        StringBuilder tooManyColumns = new StringBuilder("date;description;amount");
        for (int i = 0; i < 126; i++) tooManyColumns.append(";extra").append(i);
        invalid(tooManyColumns.toString(), "CSV", "128 colunas");
        StringBuilder records = new StringBuilder("date;description;amount;id\n");
        for (int i = 0; i < StatementImporter.MAX_TRANSACTIONS; i++) {
            records.append("2026-10-14;Exemplo;-1;DEMO-").append(i).append('\n');
        }
        assertEquals(StatementImporter.MAX_TRANSACTIONS,
                StatementImporter.parse(records.toString(), "limite.csv").size());
        records.append("2026-10-14;Excesso;-1;DEMO-EXCESSO\n");
        invalid(records.toString(), "CSV", "20.000");
    }

    @Test public void emptyInputsAndStatementsWithoutTransactionsHaveHelpfulErrors() {
        invalid(null, "CSV", "vazio");
        invalid("\n  \n", "CSV", "vazio");
        invalid("date;description;amount\n", "CSV", "sem lançamentos");
        invalid("<OFX></OFX>", "OFX", "não contém lançamentos");
    }

    private static String csvRow(String date, String description, String amount) {
        return "date;description;amount\n" + date + ";" + description + ";" + amount + "\n";
    }

    private static String ofxTransaction(String date, String amount, String extras) {
        return "<STMTTRN><DTPOSTED>" + date + "<TRNAMT>" + amount + extras + "</STMTTRN>";
    }

    private static String repeat(char ch, int count) {
        char[] values = new char[count];
        java.util.Arrays.fill(values, ch);
        return new String(values);
    }

    private static void invalid(String content, String format, String expectedMessage) {
        try {
            StatementImporter.parse(content, "exemplo." + format.toLowerCase(java.util.Locale.ROOT));
            fail("Esperava rejeição: " + expectedMessage);
        } catch (IllegalArgumentException error) {
            assertTrue("Mensagem recebida: " + error.getMessage(), error.getMessage().contains(expectedMessage));
        }
    }
}
