package br.com.myfinancehub;

import org.junit.Test;
import java.time.Instant;
import java.time.ZoneId;
import static org.junit.Assert.*;

public final class NotificationParserTest {
    private static final ZoneId SAO_PAULO = ZoneId.of("America/Sao_Paulo");
    private static final long POSTED = Instant.parse("2026-10-15T15:00:00Z").toEpochMilli();

    @Test public void approvedCreditPurchaseUsesExactCentsAndExplicitCardSuffix() {
        NotificationParser.Suggestion result = parse("Compra aprovada",
                "Compra de R$ 1.234,56 no cartão de crédito final 4321.");
        assertNotNull(result);
        assertEquals(123456, result.amountCents);
        assertEquals("expense", result.type);
        assertEquals("credit", result.method);
        assertEquals("4321", result.lastFour);
        assertEquals("2026-10-15", result.date);
        assertEquals("Compra no cartão", result.description);
        assertTrue(result.reviewNote.contains("Confira"));
    }

    @Test public void debitAndGenericCardAreNeverConfusedWithCredit() {
        NotificationParser.Suggestion debit = parse("Compra realizada",
                "R$123,45 no cartão de débito terminado em 7654.");
        assertNotNull(debit);
        assertEquals("debit", debit.method);
        assertEquals("7654", debit.lastFour);
        NotificationParser.Suggestion generic = parse("Compra aprovada",
                "Compra de R$ 25,90 no cartão final 1234.");
        assertNotNull(generic);
        assertEquals("other", generic.method);
        assertTrue(generic.reviewNote.contains("crédito ou no débito"));
        assertNull(parse("Compra aprovada", "R$10,00 no cartão de crédito e débito."));
    }

    @Test public void pixReceiptAndPaymentHaveExplicitOppositeDirections() {
        NotificationParser.Suggestion received = parse("PIX recebido", "Você recebeu um PIX de R$10,00.");
        assertNotNull(received);
        assertEquals("income", received.type);
        assertEquals("pix", received.method);
        assertEquals("PIX recebido", received.description);
        assertEquals(1000, received.amountCents);
        for (String title : new String[]{"PIX enviado", "Pix efetuado", "Pagamento realizado", "Pagamento via Pix realizado"}) {
            NotificationParser.Suggestion sent = parse(title, "Pix: R$10,00.");
            assertNotNull(title, sent);
            assertEquals(title, "expense", sent.type);
            assertEquals(title, "pix", sent.method);
            assertEquals(title, "PIX enviado", sent.description);
        }
    }

    @Test public void transferAndDepositMustExplicitlySayReceived() {
        NotificationParser.Suggestion transfer = parse("Transferência recebida", "Valor de R$ 200,00.");
        assertNotNull(transfer);
        assertEquals("income", transfer.type);
        assertEquals("other", transfer.method);
        assertEquals("Transferência recebida", transfer.description);
        NotificationParser.Suggestion deposit = parse("Depósito recebido", "R$ 850,30 na sua conta.");
        assertNotNull(deposit);
        assertEquals("Depósito recebido", deposit.description);
        assertEquals("other", deposit.method);
        assertNull(parse("Transferência", "R$ 200,00"));
        assertNull(parse("Depósito", "R$ 850,30"));
    }

    @Test public void localDateUsesDeviceZoneAcrossUtcMidnight() {
        long midnight = Instant.parse("2026-10-16T01:30:00Z").toEpochMilli();
        NotificationParser.Suggestion local = NotificationParser.parse("PIX recebido", "R$10,00", midnight, SAO_PAULO);
        NotificationParser.Suggestion utc = NotificationParser.parse("PIX recebido", "R$10,00", midnight, ZoneId.of("UTC"));
        assertNotNull(local);
        assertNotNull(utc);
        assertEquals("2026-10-15", local.date);
        assertEquals("2026-10-16", utc.date);
    }

    @Test public void rejectsCredentialsEvenWhenTheyMentionAnApprovedTransaction() {
        for (String credential : new String[]{"Código 123456", "Seu token é 123456", "Senha temporária 123456",
                "OTP 123456", "Código de segurança", "verificação", "autenticação", "Cód. 123456", "PIN 1234"})
            assertNull(credential, parse("Compra aprovada", credential + " para R$10,00."));
    }

    @Test public void rejectsOffersLimitsBalancesInvoicesAndFutureRequests() {
        for (String notification : new String[]{"Oferta: compra aprovada com desconto de R$10,00",
                "Seu limite é R$10,00", "Saldo disponível R$10,00", "Fatura de R$10,00",
                "Pague R$10,00", "Confirme o Pix enviado de R$10,00", "Pix agendado de R$10,00",
                "Pix enviado de R$10,00 será realizado amanhã", "Pix enviado: solicitação de R$10,00",
                "Pagamento realizado: vencimento de R$10,00", "Seu código para Pix recebido de R$10,00"})
            assertNull(notification, parse("Banco", notification));
    }

    @Test public void rejectsDeclinedCancelledRefundedAndConflictingEvents() {
        for (String state : new String[]{"negada", "recusada", "cancelada", "estornada", "não aprovada",
                "não foi realizada", "não foi possível realizar", "reembolso", "devolução"})
            assertNull(state, parse("Compra aprovada", "R$10,00: " + state));
        assertNull(parse("PIX recebido", "PIX enviado de R$10,00"));
        assertNull(parse("Compra aprovada", "PIX recebido de R$10,00"));
    }

    @Test public void rejectsTwoDifferentAmountsAndAllowsRepeatedSameAmount() {
        assertNull(parse("PIX recebido de R$10,00", "Saldo R$99,00"));
        assertNull(parse("Compra aprovada R$10,00", "Compra de R$ 20,00 no cartão final 1234"));
        assertNull(parse("Compra aprovada R$10,00", "Em 2 parcelas de 5,00 no cartão final 1234"));
        NotificationParser.Suggestion result = parse("PIX recebido de R$10,00", "Recebeu um PIX de R$ 10,00.");
        assertNotNull(result);
        assertEquals(1000, result.amountCents);
    }

    @Test public void amountsAreExactAndMalformedOrOverflowingValuesAreRejected() {
        for (String invalid : new String[]{"R$0,00", "R$10", "R$ 1,234", "R$12.34,56", "R$1.00",
                "R$1,,00", "R$10,001", "R$10,00abc", "R$10,00 e R$", "R$ 92233720368547758,08",
                "R$ -92233720368547758,08", "US$10,00 e R$50,00"})
            assertNull(invalid, parse("Compra aprovada", invalid));
        NotificationParser.Suggestion maximum = parse("PIX recebido", "R$ 92233720368547758,07");
        assertNotNull(maximum);
        assertEquals(Long.MAX_VALUE, maximum.amountCents);
        assertEquals(1, parse("PIX recebido", "R$0,01").amountCents);
        assertEquals(123456700, parse("PIX recebido", "R$1.234.567,00").amountCents);
    }

    @Test public void signedAmountsMustAgreeWithExplicitTransactionDirection() {
        assertEquals(1234, parse("Compra aprovada", "-R$12,34").amountCents);
        assertEquals(1234, parse("Compra aprovada", "R$ -12,34").amountCents);
        assertEquals(1234, parse("PIX recebido", "+R$12,34").amountCents);
        assertNull(parse("PIX recebido", "-R$12,34"));
        assertNull(parse("Compra aprovada", "+R$12,34"));
        assertNull(parse("Compra aprovada", "-R$-12,34"));
    }

    @Test public void cardNumbersRequireAnExplicitLabelAndDoNotLeakFullNumbers() {
        assertEquals("", parse("Compra aprovada", "R$10,00 em 1234").lastFour);
        assertEquals("", parse("Compra aprovada", "R$10,00 no cartão 1234567890123456").lastFour);
        assertEquals("1234", parse("Compra aprovada", "R$10,00 no cartão ****1234").lastFour);
        assertEquals("4321", parse("Compra aprovada", "R$10,00 no cartão terminado em 4321").lastFour);
        assertNull(parse("Compra aprovada", "R$10,00 cartão final 1234 e cartão final 5678"));
    }

    @Test public void merchantRequiresLabelAndUnstructuredNotificationIsNeverPersisted() {
        NotificationParser.Suggestion merchant = parse("Compra aprovada",
                "R$10,00 no cartão final 1234. Estabelecimento: Padaria São José; crédito.");
        assertNotNull(merchant);
        assertEquals("Compra em Padaria São José", merchant.description);
        NotificationParser.Suggestion anonymous = parse("PIX recebido",
                "Você recebeu um PIX de R$10,00 de Nome Pessoal, CPF 123.456.789-00.");
        assertNotNull(anonymous);
        assertEquals("PIX recebido", anonymous.description);
        NotificationParser.Suggestion numbered = parse("Compra aprovada",
                "R$10,00. Estabelecimento: CPF 12345678900");
        assertNotNull(numbered);
        assertEquals("Compra aprovada", numbered.description);
        assertTrue(merchant.description.length() <= 500);
    }

    @Test public void nullEmptyOversizedAndInvalidDateInputsNeverThrow() {
        assertNull(parse(null, null));
        assertNull(parse("", ""));
        assertNull(parse("PIX recebido", null));
        assertNull(parse("PIX recebido", "R$10,00 " + "x".repeat(16_000)));
        assertNull(NotificationParser.parse("PIX recebido", "R$10,00", POSTED, null));
        assertNull(NotificationParser.parse("PIX recebido", "R$10,00", Long.MAX_VALUE, SAO_PAULO));
        assertNull(NotificationParser.parse("PIX recebido", "R$10,00", Long.MIN_VALUE, SAO_PAULO));
        assertNotNull(parse(null, "PIX recebido de R$10,00"));
    }

    @Test public void unicodeWhitespaceAndAccentsAreSupported() {
        NotificationParser.Suggestion result = parse("COMPRA REALIZADA", "R$\u00a01.234,56 no CARTÃO de DÉBITO final 1234.");
        assertNotNull(result);
        assertEquals(123456, result.amountCents);
        assertEquals("debit", result.method);
        assertNotNull(parse("Pix recebido", "r$\u202f10,00"));
    }

    @Test public void syntheticPriorityAppExamplesAreSuggestionsRatherThanDirectWalletIntegration() {
        // Synthetic examples only: these are not captured or official vendor templates.
        NotificationParser.Suggestion xpCard = parse("XP — Compra aprovada",
                "Compra aprovada de R$ 42,35 no cartão final 1234.");
        NotificationParser.Suggestion itauPix = parse("Itaú — Pix recebido", "Pix recebido R$ 75,00.");
        NotificationParser.Suggestion mercadoPago = parse("Mercado Pago — Pagamento aprovado", "R$ 28,50.");
        NotificationParser.Suggestion samsungWallet = parse("Samsung Wallet — Compra aprovada",
                "R$ 42,35 no cartão final 5678.");
        assertNotNull(xpCard);
        assertNotNull(itauPix);
        assertNotNull(mercadoPago);
        assertNotNull(samsungWallet);
        assertEquals("income", itauPix.type);
        assertEquals("expense", mercadoPago.type);
        assertEquals("other", samsungWallet.method);
        assertEquals("5678", samsungWallet.lastFour);
        assertTrue(samsungWallet.reviewNote.contains("crédito ou no débito"));
    }

    @Test public void approvedCardPaymentsKeepExplicitCreditAndDebitCashTreatment() {
        NotificationParser.Suggestion credit = parse("Pagamento aprovado", "R$ 28,50 no cartão de crédito final 1234.");
        assertNotNull(credit);
        assertEquals("credit", credit.method);
        assertEquals("expense", credit.type);
        assertEquals("1234", credit.lastFour);
        assertEquals("debit", parse("Pagamento aprovado", "R$ 28,50 no cartão de débito final 5678.").method);
        NotificationParser.Suggestion unknown = parse("Pagamento aprovado", "R$ 28,50 no cartão final 1234.");
        assertNotNull(unknown);
        assertEquals("other", unknown.method);
        assertTrue(unknown.reviewNote.contains("crédito ou no débito"));
        assertNull(parse("Pagamento aprovado", "R$ 28,50 no cartão de crédito e débito final 1234."));
        assertNull(parse("Pagamento via Pix realizado", "R$ 28,50 no cartão de crédito final 1234."));
    }

    @Test public void investmentOrdersQuotesAndAssetMovementsAreNotLedgerEntries() {
        for (String text : new String[]{"Ordem executada: compra realizada de R$ 100,00",
                "Compra realizada de 10 ações a R$10,00", "Compra aprovada de cotas R$100,00",
                "Investimento realizado R$100,00", "Sua cotação variou R$10,00",
                "XP: variação do ativo R$10,00", "Aplicação: pagamento realizado de R$100,00",
                "Resgate: transferência recebida de R$100,00"})
            assertNull(text, parse("XP", text));
    }

    private static NotificationParser.Suggestion parse(String title, String text) {
        return NotificationParser.parse(title, text, POSTED, SAO_PAULO);
    }
}
