package br.com.myfinancehub;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.text.NumberFormat;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Optional public market-data lookup and foreground refresh settings. */
public final class QuotesActivity extends Activity {
    private static final int SEARCH_B3 = 1, SEARCH_CRYPTO = 2, QUOTE = 3, REFRESH = 4;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private AppAppearance.Palette colors;
    private LinearLayout content, results;
    private EditText keyInput, queryInput;
    private Switch automatic;
    private Spinner market;
    private Button searchButton, updateButton, saveButton;
    private TextView status;
    private ProgressBar progress;
    private boolean busy, destroyed;
    private Future<?> pending;
    private int generation;

    @Override public void onCreate(Bundle state) {
        setTheme(AppAppearance.themeRes(this));
        super.onCreate(state);
        colors = AppAppearance.palette(this);
        LinearLayout root = column(); root.setBackgroundColor(colors.background);
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            root.setOnApplyWindowInsetsListener((view, insets) -> {
                android.graphics.Insets bars = insets.getInsets(android.view.WindowInsets.Type.systemBars()
                        | android.view.WindowInsets.Type.displayCutout());
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom); return insets;
            });
        } else root.setFitsSystemWindows(true);
        getWindow().setStatusBarColor(colors.background); getWindow().setNavigationBarColor(colors.background);
        if (android.os.Build.VERSION.SDK_INT >= 30 && getWindow().getInsetsController() != null) {
            int light = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                    | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
            getWindow().getInsetsController().setSystemBarsAppearance(AppAppearance.isDark(this) ? 0 : light, light);
        }
        ScrollView scroll = new ScrollView(this); content = column();
        content.setPadding(dp(22), dp(18), dp(22), dp(32)); scroll.addView(content); root.addView(scroll);
        setContentView(root);
        Button back = button("‹  Voltar à carteira", false); back.setOnClickListener(view -> finish()); add(back, 0, 12);
        add(text("Cotações e atualização", 27, colors.text, true), 0, 8);
        add(text("Consulte preços e acompanhe uma estimativa da sua carteira.", 14, colors.muted, false), 0, 20);

        LinearLayout settings = card();
        settings.addView(text("Atualização da carteira", 19, colors.text, true));
        spaced(settings, text("Ative em cada investimento o uso de cotação e informe sua quantidade. "
                + "O valor atual será quantidade × preço em reais. O total aplicado permanece como você cadastrou.",
                13, colors.muted, false), 10, 16);
        TextView keyLabel = text("Chave brapi (opcional)", 13, colors.text, true); settings.addView(keyLabel);
        keyInput = input("Chave do provedor", true);
        keyInput.setText(InvestmentQuoteUpdater.token(this));
        keyInput.setSaveEnabled(false); keyInput.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        spaced(settings, keyInput, 8, 8);
        spaced(settings, text("Sem chave: PETR4, VALE3, ITUB4 e MGLU3 para teste. Outros ativos e criptomoedas "
                + "dependem da chave e do plano. A chave fica no armazenamento privado do app e fora do backup financeiro.",
                12, colors.muted, false), 0, 12);
        Button provider = button("Obter chave na brapi", false);
        provider.setOnClickListener(view -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://brapi.dev/dashboard"))));
        spaced(settings, provider, 0, 14);
        automatic = new Switch(this); automatic.setText(R.string.quotes_auto_refresh);
        automatic.setTextColor(colors.text); automatic.setTextSize(14); automatic.setMinHeight(dp(52));
        automatic.setChecked(InvestmentQuoteUpdater.autoRefreshEnabled(this)); settings.addView(automatic);
        spaced(settings, text("Quando ativado, consulta ao abrir a carteira e, no máximo, a cada hora enquanto "
                + "o app estiver aberto. Não executa em segundo plano. Cada código consultado consome a cota do seu plano.",
                12, colors.muted, false), 8, 14);
        saveButton = button("Salvar configuração", false);
        saveButton.setOnClickListener(view -> { if (saveConfiguration()) status.setText(R.string.quotes_config_saved); });
        spaced(settings, saveButton, 0, 10);
        updateButton = button("Atualizar investimentos agora", true);
        updateButton.setOnClickListener(view -> { if (saveConfiguration()) begin(REFRESH, ""); });
        settings.addView(updateButton);
        spaced(settings, text("A B3 importada mantém a posição original do documento. As cotações permitem uma "
                + "estimativa separada usando as quantidades daquela data; não atualizam sua custódia nem calculam rentabilidade.",
                12, colors.muted, false), 12, 0);
        add(settings, 0, 18);

        LinearLayout lookup = card(); lookup.addView(text("Pesquisar um ativo", 19, colors.text, true));
        spaced(lookup, text("Bolsa B3: ações, FIIs, ETFs, BDRs e units. Criptomoedas são consultadas em reais. "
                + "Renda fixa privada, CDB, LCI e LCA continuam com valor manual; CDI não determina o preço de cada título.",
                13, colors.muted, false), 10, 14);
        market = new Spinner(this);
        ArrayAdapter<String> choices = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item,
                new String[]{"Bolsa B3", "Criptomoedas"});
        choices.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item); market.setAdapter(choices);
        market.setContentDescription("Mercado do ativo"); market.setMinimumHeight(dp(48)); lookup.addView(market);
        queryInput = input("Código ou nome do ativo", false); queryInput.setSingleLine(true);
        if (state != null) { queryInput.setText(state.getString("query", "")); market.setSelection(state.getInt("market", 0)); }
        spaced(lookup, queryInput, 8, 12);
        searchButton = button("Pesquisar ativo", true);
        searchButton.setOnClickListener(view -> begin(market.getSelectedItemPosition() == 0 ? SEARCH_B3 : SEARCH_CRYPTO,
                queryInput.getText().toString().trim())); lookup.addView(searchButton);
        add(lookup, 0, 16);
        progress = new ProgressBar(this); progress.setVisibility(View.GONE); add(progress, 0, 10);
        status = text("", 14, colors.muted, false); status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        add(status, 0, 14);
        results = column(); add(results, 0, 18);
        LinearLayout delay = card(); delay.addView(text("Fonte e horário do preço", 17, colors.text, true));
        spaced(delay, text("Fonte: brapi. Os preços podem ter atraso: aproximadamente 30 min no Gratuito, "
                + "15 min no Startup e 5 min no Pro para bolsa. Não são preços em tempo real. Cripto exige plano compatível. "
                + "O horário mostrado é o do dado fornecido, não o momento da consulta.", 12, colors.muted, false), 10, 0);
        add(delay, 0, 0);
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putString("query", queryInput.getText().toString()); state.putInt("market", market.getSelectedItemPosition());
    }
    @Override protected void onPause() {
        super.onPause();
        if (pending != null) {
            pending.cancel(true); pending = null; generation++;
            setBusy(false); status.setText(R.string.quotes_interrupted);
        }
    }
    @Override protected void onDestroy() { destroyed = true; worker.shutdownNow(); super.onDestroy(); }

    private boolean saveConfiguration() {
        try {
            InvestmentQuoteUpdater.saveConfiguration(this, keyInput.getText().toString(), automatic.isChecked());
            return true;
        } catch (IllegalArgumentException error) { status.setText(R.string.quotes_key_invalid); return false; }
    }

    private void begin(int operation, String query) {
        if (busy) return;
        if ((operation == SEARCH_B3 || operation == SEARCH_CRYPTO) && (query.isEmpty() || query.length() > 80)) {
            status.setText(R.string.quotes_search_invalid); return;
        }
        setBusy(true); status.setText(operation == REFRESH ? "Atualizando preços e investimentos…" : "Consultando a brapi…");
        pending = worker.submit(new Job(this, generation, operation, query, keyInput.getText().toString().trim()));
    }
    private void setBusy(boolean busy) {
        this.busy = busy; progress.setVisibility(busy ? View.VISIBLE : View.GONE);
        searchButton.setEnabled(!busy); updateButton.setEnabled(!busy); saveButton.setEnabled(!busy);
    }

    private void deliver(int expectedGeneration, int operation, Object value, String error) {
        if (destroyed || isFinishing() || isDestroyed() || generation != expectedGeneration) return;
        pending = null;
        setBusy(false);
        if (error != null) { status.setText(error); return; }
        if (operation == REFRESH) { status.setText(((InvestmentQuoteUpdater.Result) value).summary); return; }
        results.removeAllViews();
        if (operation == QUOTE) {
            InvestmentQuotes.Quote quote = (InvestmentQuotes.Quote) value;
            NumberFormat money = NumberFormat.getCurrencyInstance(new Locale("pt", "BR"));
            money.setMaximumFractionDigits(12);
            LinearLayout result = card(); result.addView(text(quote.symbol, 21, colors.text, true));
            spaced(result, text(money.format(quote.price), 30, colors.primary, true), 10, 8);
            DateTimeFormatter time = DateTimeFormatter.ofPattern("dd/MM/uuuu HH:mm:ss", new Locale("pt", "BR"))
                    .withZone(ZoneId.of("America/Sao_Paulo"));
            result.addView(text("Fonte: brapi · " + time.format(quote.asOf) + " (Brasília)", 12, colors.muted, false));
            spaced(result, text("Preço unitário do horário informado, sujeito a atraso. Esta consulta não cadastra "
                    + "uma posição nem altera o valor aplicado.", 12, colors.muted, false), 10, 0);
            results.addView(result); status.setText(R.string.quotes_received);
        } else {
            @SuppressWarnings("unchecked") List<InvestmentQuotes.Asset> assets = (List<InvestmentQuotes.Asset>) value;
            status.setText(assets.isEmpty() ? "Nenhum ativo encontrado. Confira o código, a chave e o mercado selecionado."
                    : "Toque em um ativo para consultar o preço com horário.");
            for (InvestmentQuotes.Asset asset : assets) {
                Button row = button(asset.symbol + "  ·  " + asset.name + "\nConsultar preço", false);
                row.setAllCaps(false); row.setOnClickListener(view -> begin(QUOTE, asset.symbol));
                spaced(results, row, 0, 10);
            }
        }
    }

    /** The worker retains the application context; a destroyed screen is never retained or updated. */
    private static final class Job implements Runnable {
        private final WeakReference<QuotesActivity> owner;
        private final Context context;
        private final int operation, generation;
        private final String query, token;
        Job(QuotesActivity owner, int generation, int operation, String query, String token) {
            this.owner = new WeakReference<>(owner); this.context = owner.getApplicationContext();
            this.generation = generation; this.operation = operation; this.query = query; this.token = token;
        }
        @Override public void run() {
            Object result = null; String error = null;
            try {
                InvestmentQuotes api = new InvestmentQuotes();
                if (operation == SEARCH_B3) result = api.search(query, token);
                else if (operation == SEARCH_CRYPTO) result = api.searchCrypto(query, token);
                else if (operation == QUOTE) result = api.quote(query, token);
                else {
                    FinanceStore store = new FinanceStore(context);
                    try { result = new InvestmentQuoteUpdater(context).refresh(store, api, false); }
                    finally { store.close(); }
                }
            } catch (Exception failure) {
                // Provider errors are not copied: response text and credentials never enter the UI/logs.
                error = "Não foi possível consultar. Confira sua conexão, a chave, o plano e o limite de consultas da brapi. "
                        + "Os valores anteriores foram preservados.";
            }
            final Object value = result; final String message = error;
            new Handler(Looper.getMainLooper()).post(() -> {
                QuotesActivity activity = owner.get();
                if (activity != null) activity.deliver(generation, operation, value, message);
            });
        }
    }

    private LinearLayout column() { LinearLayout view = new LinearLayout(this); view.setOrientation(LinearLayout.VERTICAL); return view; }
    private LinearLayout card() {
        LinearLayout view = column(); view.setPadding(dp(18), dp(18), dp(18), dp(18));
        GradientDrawable background = new GradientDrawable(); background.setColor(colors.surface);
        background.setCornerRadius(dp(20)); background.setStroke(dp(1), colors.line); view.setBackground(background); return view;
    }
    private TextView text(String value, float size, int color, boolean bold) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(color);
        view.setTypeface(null, bold ? Typeface.BOLD : Typeface.NORMAL); view.setLineSpacing(dp(3), 1); return view;
    }
    private EditText input(String hint, boolean password) {
        EditText view = new EditText(this); view.setHint(hint); view.setTextSize(14);
        view.setTextColor(colors.text); view.setHintTextColor(colors.muted); view.setMinHeight(dp(52));
        view.setInputType(InputType.TYPE_CLASS_TEXT | (password ? InputType.TYPE_TEXT_VARIATION_PASSWORD : InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS));
        view.setSingleLine(true); return view;
    }
    private Button button(String label, boolean primary) {
        Button view = new Button(this); view.setText(label); view.setAllCaps(false); view.setTextSize(14);
        view.setTextColor(primary ? colors.onPrimary : colors.primary); view.setMinHeight(dp(52));
        view.setBackgroundTintList(ColorStateList.valueOf(primary ? colors.primary : colors.surfaceAlt)); return view;
    }
    private void add(View view, int top, int bottom) { spaced(content, view, top, bottom); }
    private void spaced(LinearLayout parent, View view, int top, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(0, dp(top), 0, dp(bottom)); parent.addView(view, params);
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
