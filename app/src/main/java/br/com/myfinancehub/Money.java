package br.com.myfinancehub;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.Locale;

/** Exact monetary parsing and presentation without floating point arithmetic. */
public final class Money {
    private static final Locale BRAZIL = new Locale("pt", "BR");
    private Money() {}

    /**
     * Accepts 1234, 1234.56, 1234,56, or 1.234,56, optionally signed and prefixed
     * with R$. A single dot followed by three digits is a thousands separator.
     * Rejects malformed grouping, excess decimals and values outside signed long cents.
     */
    public static long parseCents(String text) {
        if (text == null) throw invalid();
        String value = text.replace('\u00a0', ' ').trim();
        String sign = "";
        if (value.startsWith("-") || value.startsWith("+")) {
            sign = value.substring(0, 1);
            value = value.substring(1).trim();
        }
        if (value.startsWith("R$")) value = value.substring(2).trim();
        if (sign.isEmpty() && (value.startsWith("-") || value.startsWith("+"))) {
            sign = value.substring(0, 1);
            value = value.substring(1).trim();
        }
        String normalized;
        if (value.indexOf(',') >= 0) {
            if (!value.matches("(?:[0-9]+|[0-9]{1,3}(?:\\.[0-9]{3})+),[0-9]{1,2}"))
                throw invalid();
            normalized = value.replace(".", "").replace(',', '.');
        } else if (value.matches("[0-9]+\\.[0-9]{1,2}")) {
            normalized = value;
        } else if (value.matches("[0-9]{1,3}(?:\\.[0-9]{3})+")) {
            normalized = value.replace(".", "");
        } else if (value.matches("[0-9]+")) {
            normalized = value;
        } else {
            throw invalid();
        }
        try {
            return new BigDecimal(sign + normalized).movePointRight(2).longValueExact();
        } catch (ArithmeticException | NumberFormatException error) {
            throw invalid();
        }
    }

    /** Formats any signed long amount in Brazilian reais with exactly two decimals. */
    public static String format(long cents) {
        return NumberFormat.getCurrencyInstance(BRAZIL).format(BigDecimal.valueOf(cents, 2));
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Valor inválido. Use, por exemplo, 1.234,56 (até duas casas decimais).");
    }
}
