package br.com.myfinancehub;

import org.junit.Test;
import static org.junit.Assert.*;

public final class MoneyTest {
    @Test public void parsesBrazilianAndDecimalAmountsExactly() {
        expect(0, "0"); expect(123456, "1.234,56"); expect(123456, "1234.56");
        expect(123456, "R$ 1.234,56"); expect(123450, "1234,5");
        expect(123400, "1.234"); expect(123456700, "1.234.567");
        expect(-150, "-R$ 1,50"); expect(-150, "R$ -1,50"); expect(1, "0.01");
    }
    @Test public void supportsFullLongRangeWithoutRounding() {
        expect(Long.MAX_VALUE, "92233720368547758,07");
        expect(Long.MIN_VALUE, "-92233720368547758,08");
        reject("92233720368547758,08"); reject("-92233720368547758,09");
    }
    @Test public void rejectsAmbiguousAndMalformedAmounts() {
        reject(null); reject(""); reject("NaN"); reject("1e3"); reject("1,234");
        reject("12.34,56"); reject("1,234.56"); reject("1 234,56"); reject("1,");
        reject("--1"); reject("-R$ -1"); reject("1.2345");
    }
    @Test public void formattedAmountsRoundTrip() {
        for (long amount : new long[]{0, 1, -1, 123456, -123456, Long.MAX_VALUE, Long.MIN_VALUE})
            expect(amount, Money.format(amount));
    }
    private static void expect(long cents, String input) {
        assertEquals(input, cents, Money.parseCents(input));
    }
    private static void reject(String input) {
        try { Money.parseCents(input); }
        catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Accepted malformed amount: " + input);
    }
}
