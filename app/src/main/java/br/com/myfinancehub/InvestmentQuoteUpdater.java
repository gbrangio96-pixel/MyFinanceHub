package br.com.myfinancehub;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.IOException;
import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Opt-in foreground refresh. Only asset symbols and the provider key leave the device. */
public final class InvestmentQuoteUpdater {
    public static final long REFRESH_INTERVAL_MILLIS = 60 * 60 * 1000L;
    public static final int MAX_SYMBOLS_PER_REFRESH = 200;
    private static final String PREFERENCES = "investment_quotes";
    private static final Object GATE = new Object();
    private static boolean running;
    private final Context context;
    private final Clock clock;

    public InvestmentQuoteUpdater(Context context) { this(context, Clock.systemUTC()); }
    InvestmentQuoteUpdater(Context context, Clock clock) {
        if (context == null || clock == null) throw new IllegalArgumentException("Configuração de cotações inválida.");
        this.context = context.getApplicationContext(); this.clock = clock;
    }

    public static String token(Context context) { return preferences(context).getString("token", ""); }
    public static boolean autoRefreshEnabled(Context context) { return preferences(context).getBoolean("automatic", false); }
    public static void saveConfiguration(Context context, String token, boolean automatic) {
        String key = token == null ? "" : token.trim();
        if (key.length() > 2048 || !key.matches("[^\\s\\p{Cntrl}]*"))
            throw new IllegalArgumentException("Confira a chave brapi antes de salvar.");
        SharedPreferences preferences = preferences(context);
        boolean changed = !key.equals(preferences.getString("token", ""))
                || (automatic && !preferences.getBoolean("automatic", false));
        SharedPreferences.Editor edit = preferences.edit().putString("token", key).putBoolean("automatic", automatic);
        if (changed) edit.remove("last_attempt");
        edit.apply();
    }
    public static boolean isAutomaticRefreshDue(Context context) {
        return due(context, System.currentTimeMillis());
    }
    private static boolean due(Context context, long now) {
        if (!autoRefreshEnabled(context)) return false;
        long last = preferences(context).getLong("last_attempt", 0);
        return last == 0 || now < last || now - last >= REFRESH_INTERVAL_MILLIS;
    }
    private static SharedPreferences preferences(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    /** Blocking: call on a worker. Manual refresh also respects each manual position's opt-in. */
    public Result refresh(FinanceStore store, InvestmentQuotes api, boolean automatic) {
        if (store == null || api == null) throw new IllegalArgumentException("Serviço de cotações inválido.");
        synchronized (GATE) {
            if (running) return new Result(0, 0, 0, 0, "Uma atualização já está em andamento.");
            if (automatic && !autoRefreshEnabled(context))
                return new Result(0, 0, 0, 0, "Atualização automática desativada.");
            if (automatic && !due(context, clock.millis()))
                return new Result(0, 0, 0, 0, "A próxima atualização automática estará disponível após uma hora.");
            running = true;
        }
        try { return refreshSelected(store, api, automatic); }
        finally { synchronized (GATE) { running = false; } }
    }

    private Result refreshSelected(FinanceStore store, InvestmentQuotes api, boolean automatic) {
        Map<String, Group> groups = new LinkedHashMap<>();
        int skipped = 0, updated = 0, cached = 0, failed = 0;
        for (Models.Investment investment : store.investments()) {
            if (!investment.autoQuote) continue;
            String symbol = symbol(investment.ticker);
            if (symbol == null || !positive(investment.quantity)) { skipped++; continue; }
            groups.computeIfAbsent(symbol, Group::new).manual.add(investment);
        }
        B3Portfolio.Snapshot snapshot = store.b3Snapshot();
        if (snapshot != null) for (B3Portfolio.Position position : snapshot.positions) {
            String symbol = symbol(position.ticker);
            if (!supportsB3Position(position)) { skipped++; continue; }
            groups.computeIfAbsent(symbol, Group::new).b3Positions++;
        }
        if (groups.isEmpty()) return new Result(0, 0, 0, skipped,
                "Nenhum ativo elegível. Cadastre código e quantidade e marque a atualização do investimento; "
                + "posições B3 com código de bolsa também podem receber uma estimativa.");

        // Attempts are throttled even when offline or over quota, preventing a retry on every render.
        preferences(context).edit().putLong("last_attempt", clock.millis()).apply();
        String key = token(context);
        int queried = 0;
        boolean limited = false, interrupted = false;
        for (Group group : groups.values()) {
            if (queried >= MAX_SYMBOLS_PER_REFRESH) { skipped += group.count(); limited = true; continue; }
            if (Thread.currentThread().isInterrupted() || (automatic && !autoRefreshEnabled(context))) {
                skipped += group.count(); interrupted = true; continue;
            }
            queried++;
            InvestmentQuotes.Quote quote;
            try { quote = api.quote(group.symbol, key); }
            catch (IOException | IllegalArgumentException error) {
                if (Thread.currentThread().isInterrupted()) { skipped += group.count(); interrupted = true; }
                else failed += group.count();
                continue;
            }
            if (Thread.currentThread().isInterrupted() || (automatic && !autoRefreshEnabled(context))) {
                skipped += group.count(); interrupted = true; continue;
            }
            try {
                if (InvestmentQuoteCache.store(context, quote)) cached++;
            } catch (IllegalArgumentException error) {
                failed += group.count(); continue;
            }
            synchronized (store) {
                Map<Long, Models.Investment> latest = new LinkedHashMap<>();
                for (Models.Investment investment : store.investments()) latest.put(investment.id, investment);
                for (Models.Investment expected : group.manual) {
                    if (Thread.currentThread().isInterrupted()) { skipped++; interrupted = true; continue; }
                    Models.Investment current = latest.get(expected.id);
                    // A manual edit made during the request must not be overwritten by its response.
                    if (current == null || !current.autoQuote || current.currentCents != expected.currentCents
                            || !current.quoteAt.equals(expected.quoteAt)) { skipped++; continue; }
                    try {
                        boolean applied = store.applyInvestmentQuote(expected.id, expected.ticker, expected.quantity,
                                expected.currentCents, expected.quoteAt, quote.price.stripTrailingZeros().toPlainString(),
                                quote.asOf.toString(), quote.source);
                        if (applied) updated++; else skipped++;
                    } catch (IllegalArgumentException | ArithmeticException error) { failed++; }
                }
            }
        }
        if (updated > 0 || cached > 0) preferences(context).edit().putLong("last_success", clock.millis()).apply();
        String note = limited ? "Limite de 200 códigos por rodada; a cobertura pode ser parcial. " : "";
        if (interrupted) note += "Atualização interrompida; os últimos valores foram preservados. ";
        if (failed > 0) note += "Confira a conexão, a chave e a cota do provedor. Valores anteriores foram preservados nas falhas.";
        return new Result(updated, cached, failed, skipped, note);
    }

    private static String symbol(String value) {
        String symbol = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        return symbol.matches("[A-Z][A-Z0-9]{3}[0-9]{1,2}|[A-Z0-9]{2,12}-BRL") ? symbol : null;
    }
    public static boolean supportsB3Position(B3Portfolio.Position position) {
        if (position == null) return false;
        String symbol = symbol(position.ticker);
        return symbol != null && !symbol.endsWith("-BRL") && positive(position.quantity) && !fixedIncome(position.kind);
    }
    private static boolean positive(String value) {
        try { return value != null && new BigDecimal(value).signum() > 0; }
        catch (NumberFormatException error) { return false; }
    }
    private static boolean fixedIncome(String value) {
        String kind = Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
        return kind.contains("renda fixa") || kind.contains("tesouro") || kind.contains("debentur")
                || kind.matches(".*\\b(?:cdb|lci|lca|cri|cra|coe)\\b.*");
    }

    private static final class Group {
        final String symbol;
        final List<Models.Investment> manual = new ArrayList<>();
        int b3Positions;
        Group(String symbol) { this.symbol = symbol; }
        int count() { return manual.size() + b3Positions; }
    }

    public static final class Result {
        public final int updated, cached, failed, skipped;
        public final String summary;
        private Result(int updated, int cached, int failed, int skipped, String note) {
            this.updated = updated; this.cached = cached; this.failed = failed; this.skipped = skipped;
            summary = updated + " investimentos atualizados · " + cached + " cotações armazenadas para estimativas · "
                    + failed + " falhas · " + skipped + " ignorados." + (note.isEmpty() ? "" : "\n\n" + note);
        }
    }
}
