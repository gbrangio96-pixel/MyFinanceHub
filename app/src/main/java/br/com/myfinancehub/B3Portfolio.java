package br.com.myfinancehub;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** A report of B3 custody positions, kept separately from manually recorded investments. */
public final class B3Portfolio {
    public static final int MAX_POSITIONS = 5000;
    private B3Portfolio() {}

    /** Values in a position report do not establish the historical cost of an investment. */
    public static final class Position {
        public final String product, institution, account, ticker, isin, kind, quantity;
        /** Unknown valuation remains null; it must never be displayed as a zero valuation. */
        public final Long currentCents;

        public Position(String product, String institution, String account, String ticker,
                        String isin, String kind, String quantity, Long currentCents) {
            this.product = text(product, "Produto", 300, false);
            this.institution = text(institution, "Instituição", 200, true);
            this.account = text(account, "Conta", 80, true);
            this.ticker = text(ticker, "Código", 80, true);
            this.isin = text(isin, "ISIN", 40, true);
            this.kind = text(kind, "Tipo", 100, true);
            String decimal = text(quantity, "Quantidade", 40, false);
            if (!decimal.matches("[0-9]{1,18}(?:\\.[0-9]{1,12})?"))
                throw invalid("Quantidade B3 inválida: use um decimal não negativo com até 12 casas.");
            this.quantity = new BigDecimal(decimal).stripTrailingZeros().toPlainString();
            if (currentCents != null && currentCents < 0)
                throw invalid("O valor atual da posição B3 não pode ser negativo.");
            this.currentCents = currentCents;
        }

        // Length prefixes keep the tuple unambiguous even when source text contains separators.
        String identityKey() {
            return identityKeys().get(0);
        }

        /** Every known instrument alias must be unique within the institution/account scope. */
        List<String> identityKeys() {
            String scope = part(institution) + part(account);
            List<String> keys = new ArrayList<>(2);
            if (!isin.isEmpty()) keys.add(part("isin:" + isin) + scope);
            if (!ticker.isEmpty()) keys.add(part("ticker:" + ticker) + scope);
            if (keys.isEmpty()) keys.add(part("product:" + product) + scope);
            return keys;
        }
    }

    /** Immutable report, including its explicit valuation date and provenance. */
    public static final class Snapshot {
        public final String asOfDate, importedAt, sourceHash;
        public final List<Position> positions;
        private final long total;
        private final int unvalued;

        public Snapshot(String asOfDate, String importedAt, String sourceHash, List<Position> positions) {
            this.asOfDate = validatedDate(asOfDate);
            try {
                if (importedAt == null || importedAt.length() > 40)
                    throw invalid("Instante de importação B3 inválido.");
                this.importedAt = Instant.parse(importedAt).toString();
            } catch (DateTimeParseException error) { throw invalid("Instante de importação B3 inválido."); }
            if (sourceHash == null || !sourceHash.matches("[0-9a-fA-F]{64}"))
                throw invalid("Identificador do arquivo B3 inválido.");
            this.sourceHash = sourceHash.toLowerCase(Locale.ROOT);
            if (positions == null || positions.size() > MAX_POSITIONS)
                throw invalid("O relatório B3 deve ter no máximo 5.000 posições.");
            List<Position> copy = new ArrayList<>(positions.size());
            Set<String> identities = new HashSet<>();
            BigInteger total = BigInteger.ZERO;
            int unknown = 0;
            for (Position position : positions) {
                if (position == null) throw invalid("Posição B3 inválida.");
                // Reconstruct to validate every boundary, then retain an immutable copy.
                Position validated = new Position(position.product, position.institution, position.account,
                        position.ticker, position.isin, position.kind, position.quantity, position.currentCents);
                for (String identity : validated.identityKeys()) {
                    if (!identities.add(identity))
                        throw invalid("O relatório B3 contém posições repetidas ou ambíguas. Confira produto, instituição e conta.");
                }
                if (validated.currentCents == null) unknown++;
                else total = total.add(BigInteger.valueOf(validated.currentCents));
                copy.add(validated);
            }
            if (total.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0)
                throw invalid("O total da posição B3 excede o limite monetário suportado.");
            this.positions = Collections.unmodifiableList(copy);
            this.total = total.longValue();
            this.unvalued = unknown;
        }

        /** Sum of reported valuations only. This is not a cost or a return calculation. */
        public long totalCents() { return total; }
        public int unvaluedCount() { return unvalued; }
    }

    private static String validatedDate(String value) {
        try {
            if (value == null || !value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"))
                throw invalid("Informe a data da posição B3 no formato AAAA-MM-DD.");
            LocalDate date = LocalDate.parse(value);
            if (date.getYear() < 1) throw invalid("Data da posição B3 inválida.");
            return date.toString();
        } catch (DateTimeParseException error) { throw invalid("Data da posição B3 inválida."); }
    }

    /** Apply at import/restore boundaries; saved reports remain readable after a clock change. */
    static void requireNotFutureDate(String asOfDate) {
        if (LocalDate.parse(asOfDate).isAfter(LocalDate.now()))
            throw invalid("A data da posição B3 não pode ser futura.");
    }

    private static String text(String value, String field, int max, boolean optional) {
        if (value != null && value.indexOf('\u0000') >= 0)
            throw invalid(field + " da posição B3 inválido.");
        String result = value == null ? "" : value.trim();
        if ((!optional && result.isEmpty()) || result.length() > max || result.indexOf('\u0000') >= 0)
            throw invalid(field + " da posição B3 inválido.");
        return result;
    }
    private static String part(String value) {
        String normalized = value.toLowerCase(Locale.ROOT);
        return normalized.length() + ":" + normalized;
    }
    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
}
