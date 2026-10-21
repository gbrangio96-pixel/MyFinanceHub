package br.com.myfinancehub;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Last public unit prices only. This cache is independent of financial records and backups. */
public final class InvestmentQuoteCache {
    static final String PREFERENCES = "investment_quote_cache";
    static final String KEY = "quotes_json";
    static final int MAX_SYMBOLS = 200;
    static final int MAX_JSON_BYTES = 256 * 1024;

    private InvestmentQuoteCache() {}

    public static final class Value {
        public final String symbol, source;
        public final BigDecimal price;
        public final Instant asOf;

        private Value(String symbol, BigDecimal price, Instant asOf, String source) {
            this.symbol = symbol;
            this.price = price;
            this.asOf = asOf;
            this.source = source;
        }
    }

    /** Invalid or damaged cache data is treated as unavailable, never as a zero price. */
    public static synchronized Value read(Context context, String symbol) {
        String normalized = normalizeSymbol(symbol);
        if (context == null || normalized == null) return null;
        return load(context).get(normalized);
    }

    public static Value get(Context context, String symbol) { return read(context, symbol); }

    /** Parse once when rendering many positions; the returned snapshot never changes underneath a view. */
    public static synchronized Map<String, Value> snapshot(Context context) {
        if (context == null) return Collections.emptyMap();
        return Collections.unmodifiableMap(new LinkedHashMap<>(load(context)));
    }

    /** Returns whether a strictly newer valid quote was retained and persisted. */
    public static synchronized boolean store(Context context, InvestmentQuotes.Quote quote) {
        if (context == null || quote == null) return false;
        String symbol = normalizeSymbol(quote.symbol);
        if (symbol == null || !"BRL".equals(quote.currency) || !"brapi".equals(quote.source)
                || !validPrice(quote.price) || !validTime(quote.asOf)) return false;
        try {
            Map<String, Value> values = load(context);
            Value previous = values.get(symbol);
            if (previous != null && !quote.asOf.isAfter(previous.asOf)) return false;
            values.put(symbol, new Value(symbol, quote.price, quote.asOf, quote.source));
            if (values.size() > MAX_SYMBOLS) {
                Value oldest = null;
                for (Value value : values.values()) {
                    if (oldest == null || value.asOf.isBefore(oldest.asOf)
                            || (value.asOf.equals(oldest.asOf) && value.symbol.compareTo(oldest.symbol) < 0))
                        oldest = value;
                }
                if (oldest != null) {
                    values.remove(oldest.symbol);
                    if (oldest.symbol.equals(symbol)) return false;
                }
            }
            String json = encode(values);
            if (json.getBytes(StandardCharsets.UTF_8).length > MAX_JSON_BYTES) return false;
            return preferences(context).edit().putString(KEY, json).commit();
        } catch (JSONException | RuntimeException error) {
            // A nonessential quote cache cannot block the user's financial workflows.
            return false;
        }
    }

    public static void put(Context context, InvestmentQuotes.Quote quote) { store(context, quote); }

    public static synchronized void clear(Context context) {
        if (context == null) return;
        preferences(context).edit().remove(KEY).apply();
    }

    private static Map<String, Value> load(Context context) {
        Map<String, Value> values = new LinkedHashMap<>();
        try {
            String json = preferences(context).getString(KEY, "");
            if (json == null || json.isEmpty()) return values;
            if (json.length() > MAX_JSON_BYTES || json.getBytes(StandardCharsets.UTF_8).length > MAX_JSON_BYTES
                    || !boundedNesting(json)) return values;
            JSONObject root = new JSONObject(json);
            if (root.length() != 2 || root.getInt("version") != 1) return values;
            JSONArray rows = root.getJSONArray("quotes");
            if (rows.length() > MAX_SYMBOLS) return values;
            for (int index = 0; index < rows.length(); index++) {
                JSONObject row = rows.getJSONObject(index);
                String symbol = string(row, "symbol");
                if (symbol == null || !symbol.equals(normalizeSymbol(symbol)) || values.containsKey(symbol)
                        || !"BRL".equals(string(row, "currency")) || !"brapi".equals(string(row, "source")))
                    return new LinkedHashMap<>();
                String rawPrice = string(row, "price"), rawDate = string(row, "asOf");
                if (rawPrice == null || rawPrice.length() > 64 || rawDate == null || rawDate.length() > 40)
                    return new LinkedHashMap<>();
                BigDecimal price = new BigDecimal(rawPrice);
                Instant asOf = Instant.parse(rawDate);
                if (!validPrice(price) || !validTime(asOf)) return new LinkedHashMap<>();
                values.put(symbol, new Value(symbol, price, asOf, "brapi"));
            }
            return values;
        } catch (JSONException | RuntimeException error) {
            return new LinkedHashMap<>();
        }
    }

    private static String encode(Map<String, Value> values) throws JSONException {
        JSONArray rows = new JSONArray();
        for (Value value : values.values()) {
            JSONObject row = new JSONObject();
            row.put("symbol", value.symbol);
            row.put("price", value.price.toPlainString());
            row.put("asOf", value.asOf.toString());
            row.put("source", value.source);
            row.put("currency", "BRL");
            rows.put(row);
        }
        return new JSONObject().put("version", 1).put("quotes", rows).toString();
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    private static String normalizeSymbol(String value) {
        String symbol = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        return symbol.matches("[A-Z0-9]{3,12}|[A-Z0-9]{2,12}-BRL") ? symbol : null;
    }

    private static boolean validPrice(BigDecimal price) {
        return price != null && price.signum() > 0 && price.precision() <= 30
                && Math.abs((long) price.scale()) <= 12;
    }

    private static boolean validTime(Instant asOf) {
        return asOf != null && asOf.isAfter(Instant.EPOCH)
                && !asOf.isAfter(Instant.now().plus(Duration.ofMinutes(10)));
    }

    private static String string(JSONObject object, String key) throws JSONException {
        Object value = object.get(key);
        return value instanceof String ? (String) value : null;
    }

    /** Refuse deeply nested corrupt data before handing it to Android's recursive JSON reader. */
    private static boolean boundedNesting(String text) {
        int depth = 0;
        boolean quoted = false, escaped = false;
        for (int index = 0; index < text.length(); index++) {
            char value = text.charAt(index);
            if (quoted) {
                if (escaped) escaped = false;
                else if (value == '\\') escaped = true;
                else if (value == '"') quoted = false;
            } else if (value == '"') quoted = true;
            else if (value == '{' || value == '[') {
                if (++depth > 8) return false;
            } else if (value == '}' || value == ']') {
                if (--depth < 0) return false;
            }
        }
        return depth == 0 && !quoted;
    }
}
