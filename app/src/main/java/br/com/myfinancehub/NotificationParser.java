package br.com.myfinancehub;

import java.text.Normalizer;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Stateless, deliberately conservative interpretation of Portuguese financial notifications.
 * A result is a suggestion for human review, never authorization to create a ledger entry.
 * Notification text, personal identifiers and unrelated numbers are not retained in a result.
 */
public final class NotificationParser {
    private static final int MAX_INPUT_LENGTH = 16_000;
    private static final String SPACE = "[\\p{Zs}\\t]*";
    private static final Pattern MONEY = Pattern.compile(
            "(?i)(?<![\\p{L}\\p{N}])([+-]?" + SPACE + "R\\$" + SPACE
                    + "[+-]?" + SPACE + "[0-9]+(?:[.,][0-9]+)*)");
    private static final Pattern CURRENCY = Pattern.compile("(?i)R\\$");
    private static final Pattern BRAZILIAN_AMOUNT = Pattern.compile(
            "[+-]?(?:[0-9]+|[0-9]{1,3}(?:\\.[0-9]{3})+),[0-9]{2}");
    private static final Pattern DECIMAL_NUMBER = Pattern.compile(
            "(?<![\\p{L}\\p{N}.,])([+-]?(?:[0-9]+|[0-9]{1,3}(?:\\.[0-9]{3})+),[0-9]{2})"
                    + "(?![\\p{L}\\p{N}.,])");
    private static final Pattern UNTRUSTED_CONTEXT = pattern(
            "\\b(?:senha|senhas|token|tokens|otp|codigo|codigos|cod|pin|cvv|cvc|"
                    + "verificacao|autenticacao|autorizacao|confirmacao|"
                    + "validacao|seguranca|oferta|ofertas|promocao|promocoes|promocional|"
                    + "cashback|desconto|descontos|ganhe|aproveite|simule|emprestimo|limite|"
                    + "fatura|faturas|vencimento|vencimentos|vence|vencera|cobranca|cobrancas|"
                    + "boleto|boletos|agendamento|agendad[ao]s?|programad[ao]s?|pendentes?|"
                    + "solicitacao|solicitacoes|solicitad[ao]s?|solicite|pedido|pedidos|"
                    + "autorize|confirmar|confirme|aprovar|aprove|pague|pagar|lembrete|"
                    + "amanha|futur[ao]s?|sera|serao|tentativa|tentativas|"
                    + "ordem|ordens|corretagem|cotacao|cotacoes|acoes|cotas|fiis?|tesouro|"
                    + "investimento|investimentos|rentabilidade|variacao|aplicacao|resgate|"
                    + "negad[ao]s?|recusad[ao]s?|cancelad[ao]s?|estorn[a-z]*|"
                    + "reembols[a-z]*|devoluc[a-z]*|devolvid[ao]s?|bloquead[ao]s?)\\b"
                    + "|\\bnao\\s+(?:(?:foi|foram|pode|pudemos)\\s+)?"
                    + "(?:aprova[a-z]*|realiza[a-z]*|efetua[a-z]*|conclu[a-z]*|possivel)\\b"
                    + "|\\bpre[- ]aprovad[ao]\\b|us\\$|\\b(?:usd|eur|gbp)\\b|[€£]");
    private static final Pattern PURCHASE_DONE = pattern(
            "\\bcompra\\s+(?:(?:no|com\\s+o)\\s+cartao\\s+)?"
                    + "(?:foi\\s+)?(?:aprovada|realizada)\\b");
    private static final Pattern PIX_RECEIVED = pattern(
            "\\bpix[\\s:.-]+(?:foi\\s+)?(?:recebido|recebida|creditado)\\b"
                    + "|\\brecebeu\\s+(?:um\\s+)?pix\\b");
    private static final Pattern PIX_SENT = pattern(
            "\\bpix[\\s:.-]+(?:foi\\s+)?(?:enviado|efetuado|realizado|concluido)\\b"
                    + "|\\b(?:enviou|efetuou)\\s+(?:um\\s+)?pix\\b"
                    + "|\\bpagamento\\s+(?:(?:via|por|com)\\s+)?pix\\s+"
                    + "(?:foi\\s+)?(?:realizado|efetuado|concluido)\\b");
    private static final Pattern PAYMENT_DONE = pattern(
            "\\bpagamento\\s+(?:foi\\s+)?(?:realizado|efetuado|concluido|aprovado)\\b");
    private static final Pattern DEPOSIT_RECEIVED = pattern(
            "\\bdeposito\\s+(?:foi\\s+)?(?:recebido|creditado)\\b");
    private static final Pattern TRANSFER_RECEIVED = pattern(
            "\\btransferencia\\s+(?:(?:via|por)\\s+pix\\s+)?"
                    + "(?:foi\\s+)?recebida\\b");
    private static final Pattern PIX = pattern("\\bpix\\b");
    private static final Pattern CREDIT = pattern("\\bcredito\\b");
    private static final Pattern DEBIT = pattern("\\bdebito\\b");
    private static final Pattern CARD = pattern("\\bcartao\\b");
    private static final Pattern LAST_FOUR = pattern(
            "\\b(?:cartao(?:\\s+(?:de\\s+)?(?:credito|debito))?\\s+"
                    + "(?:(?:com\\s+)?final\\s*[:#-]?\\s*|"
                    + "(?:terminado|terminando)\\s+em\\s*|[x*•]{2,}\\s*)?"
                    + "|(?:final|terminado\\s+em|terminando\\s+em)\\s*[:#-]?\\s*)"
                    + "([0-9]{4})(?![0-9])");
    private static final Pattern MERCHANT = Pattern.compile(
            "(?iu)\\bestabelecimento\\s*:\\s*([^;\\n]{2,100})");
    private static final Pattern MERCHANT_SUFFIX = Pattern.compile(
            "(?iu)(?:[.!](?:\\s|$)|\\s+(?:valor|cart[aã]o|final|data|r\\$)\\b)");

    private NotificationParser() {}

    /** Returns null for unsupported, ambiguous, sensitive or malformed notifications. */
    public static Suggestion parse(String title, String text, long postedAtMillis, ZoneId zone) {
        if (zone == null) return null;
        if ((title != null && title.length() > MAX_INPUT_LENGTH)
                || (text != null && text.length() > MAX_INPUT_LENGTH)) return null;
        String original = (title == null ? "" : title) + "\n" + (text == null ? "" : text);
        if (original.length() > MAX_INPUT_LENGTH) return null;
        String normalized = normalize(original);
        if (UNTRUSTED_CONTEXT.matcher(normalized).find()) return null;

        boolean purchase = PURCHASE_DONE.matcher(normalized).find();
        boolean pixReceived = PIX_RECEIVED.matcher(normalized).find();
        boolean pixSent = PIX_SENT.matcher(normalized).find();
        boolean deposit = DEPOSIT_RECEIVED.matcher(normalized).find();
        boolean transfer = TRANSFER_RECEIVED.matcher(normalized).find();
        boolean payment = PAYMENT_DONE.matcher(normalized).find();
        boolean income = pixReceived || deposit || transfer;
        boolean expense = purchase || pixSent || payment;
        if (income == expense) return null;
        // A purchase and a PIX in the same notification can describe different events.
        if (purchase && (pixReceived || pixSent)) return null;

        Long amount = parseAmount(original, income);
        if (amount == null) return null;
        LocalDate date;
        try {
            date = Instant.ofEpochMilli(postedAtMillis).atZone(zone).toLocalDate();
        } catch (DateTimeException error) {
            return null;
        }
        if (date.getYear() < 1 || date.getYear() > 9999) return null;

        boolean hasPix = PIX.matcher(normalized).find();
        boolean credit = CREDIT.matcher(normalized).find();
        boolean debit = DEBIT.matcher(normalized).find();
        if (hasPix && CARD.matcher(normalized).find()) return null;
        boolean cardEvent = purchase || payment && CARD.matcher(normalized).find();
        if (cardEvent && (credit && debit || hasPix)) return null;
        String method = hasPix && !cardEvent ? "pix"
                : cardEvent && credit ? "credit" : cardEvent && debit ? "debit" : "other";
        String lastFour = "";
        if (purchase || CARD.matcher(normalized).find()) {
            Matcher cards = LAST_FOUR.matcher(normalized);
            while (cards.find()) {
                if (!lastFour.isEmpty() && !lastFour.equals(cards.group(1))) return null;
                lastFour = cards.group(1);
            }
        }
        String description;
        if (purchase) {
            String merchant = merchant(original);
            description = merchant.isEmpty()
                    ? CARD.matcher(normalized).find() ? "Compra no cartão" : "Compra aprovada"
                    : "Compra em " + merchant;
        } else if (hasPix) {
            description = income ? "PIX recebido" : "PIX enviado";
        } else if (deposit) {
            description = "Depósito recebido";
        } else if (transfer) {
            description = "Transferência recebida";
        } else {
            description = "Pagamento realizado";
        }
        String note = "Confira data, valor, conta, categoria e cartão antes de salvar.";
        if (cardEvent && "other".equals(method))
            note += " Confirme se a compra foi no crédito ou no débito.";
        return new Suggestion(description, date.toString(), income ? "income" : "expense",
                method, lastFour, note, amount);
    }

    private static Long parseAmount(String original, boolean income) {
        Matcher currencies = CURRENCY.matcher(original);
        int currencyCount = 0;
        while (currencies.find()) currencyCount++;
        if (currencyCount == 0) return null;
        Matcher values = MONEY.matcher(original);
        Long result = null;
        int count = 0;
        while (values.find()) {
            count++;
            String token = values.group(1).replace("r$", "R$")
                    .replace('\u00a0', ' ').replace('\u202f', ' ').trim();
            String number = token.replace("R$", "").replaceAll("[\\p{Zs}\\t]", "");
            if (!BRAZILIAN_AMOUNT.matcher(number).matches()) return null;
            if (values.end() < original.length()
                    && Character.isLetterOrDigit(original.charAt(values.end()))) return null;
            // A signed direction must agree with the explicit event, never override it.
            if (income && number.startsWith("-") || !income && number.startsWith("+")) return null;
            long signed;
            try {
                signed = Money.parseCents(token);
            } catch (IllegalArgumentException error) {
                return null;
            }
            if (signed == Long.MIN_VALUE) return null;
            long amount = signed < 0 ? -signed : signed;
            if (amount == 0 || result != null && result != amount) return null;
            result = amount;
        }
        if (count != currencyCount || result == null) return null;
        // A second Brazilian decimal amount may omit R$ (e.g. an installment or balance).
        // Refuse it when it differs rather than guessing which amount is the transaction.
        Matcher decimals = DECIMAL_NUMBER.matcher(original);
        while (decimals.find()) {
            long decimal;
            try {
                decimal = Money.parseCents(decimals.group(1));
            } catch (IllegalArgumentException error) {
                return null;
            }
            if (decimal == Long.MIN_VALUE || (decimal < 0 ? -decimal : decimal) != result) return null;
        }
        return result;
    }

    /** Only a specifically labelled establishment can become a persisted description. */
    private static String merchant(String original) {
        Matcher matcher = MERCHANT.matcher(original);
        if (!matcher.find()) return "";
        String candidate = matcher.group(1).trim();
        Matcher suffix = MERCHANT_SUFFIX.matcher(candidate);
        if (suffix.find()) candidate = candidate.substring(0, suffix.start()).trim();
        // Drop identifiers, numbers, links and arbitrary raw notification text.
        if (candidate.length() < 2 || candidate.length() > 80
                || !candidate.matches("[\\p{L}][\\p{L} &'’.-]*")) return "";
        String normalized = normalize(candidate);
        if (UNTRUSTED_CONTEXT.matcher(normalized).find()
                || normalized.matches(".*\\b(?:conta|agencia|cpf|cnpj|pix|final)\\b.*")) return "";
        return candidate;
    }

    private static Pattern pattern(String value) {
        return Pattern.compile(value);
    }

    private static String normalize(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT)
                .replace('\u00a0', ' ').replace('\u202f', ' ');
    }

    public static final class Suggestion {
        public final String description, date, type, method, lastFour, reviewNote;
        public final long amountCents;

        private Suggestion(String description, String date, String type, String method,
                           String lastFour, String reviewNote, long amountCents) {
            this.description = description;
            this.date = date;
            this.type = type;
            this.method = method;
            this.lastFour = lastFour;
            this.reviewNote = reviewNote;
            this.amountCents = amountCents;
        }
    }
}
