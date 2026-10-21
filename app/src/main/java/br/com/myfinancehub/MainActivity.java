package br.com.myfinancehub;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.content.Intent;
import android.content.ClipData;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.database.Cursor;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;

/** Offline Android client. Money is stored as integer cents; documents use Android's file picker. */
public final class MainActivity extends Activity {
    private AppAppearance.Palette colors;
    private int BG, GREEN, LIME, TEXT, MUTED, LINE, RED, WHITE;
    private boolean darkTheme;
    private static final int PAGE_SIZE=30;
    private static final int IMPORT=41, BACKUP=42, RESTORE=43, CSV=44, B3_IMPORT=45, REPORT_PDF=46, REPORT_CSV=47;
    private static final String[] METHOD_KEYS={"other","pix","debit","credit","cash","transfer","card_payment"};
    private static final String[] METHOD_NAMES={"Outro / extrato","PIX (registro manual)","Débito","Crédito","Dinheiro","Transferência entre minhas contas","Pagamento de fatura"};
    private FinanceStore store;
    private LinearLayout root, content, navigation;
    private ScrollView screenScroll;
    private int renderedPage=-1;
    private final int[] pageScroll=new int[8];
    private boolean firstResume=true;
    private YearMonth month=YearMonth.now();
    private int page=0, filter=0;
    private boolean hidden;
    private String search="";
    private String categorySearch="";
    private int visibleEntries=PAGE_SIZE;
    private String b3Search="";
    private int visibleB3Positions=PAGE_SIZE;
    private LocalDate reportFrom=YearMonth.now().atDay(1),reportTo=YearMonth.now().atEndOfMonth();
    private int reportPreset=0;
    private long reportAccountId=0;
    private boolean allReportCategories=false;
    private PendingReport pendingReport;
    private static final class PendingReport {
        final FinanceReport report;final int request;
        PendingReport(FinanceReport report,int request){this.report=report;this.request=request;}
    }
    private List<Models.Category> categoryCache=Collections.emptyList();
    private List<Models.Account> accountCache=Collections.emptyList();
    private List<Models.Card> cardCache=Collections.emptyList();
    private List<Models.Entry> entryCache=Collections.emptyList();
    private Map<String,InvestmentQuoteCache.Value> marketCache=Collections.emptyMap();
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private AlertDialog activeProgress;
    private final android.os.Handler quoteHandler=new android.os.Handler(android.os.Looper.getMainLooper());
    private boolean quoteRefreshRunning=false;
    private Future<?> automaticQuoteTask;
    private int quoteGeneration=0;
    private String lastQuoteSummary="";
    private final Runnable quoteTick=new Runnable(){@Override public void run(){maybeAutomaticQuotes();quoteHandler.postDelayed(this,60_000);}};

    @Override public void onCreate(Bundle state) {
        setTheme(AppAppearance.themeRes(this));
        super.onCreate(state);
        colors=AppAppearance.palette(this);darkTheme=AppAppearance.isDark(this);
        BG=colors.background;GREEN=colors.primary;LIME=colors.accent;TEXT=colors.text;
        MUTED=colors.muted;LINE=colors.line;RED=colors.expense;WHITE=colors.surface;
        store=new FinanceStore(this);
        hidden=getPreferences(0).getBoolean("hidden",false);
        if(state!=null){ page=state.getInt("page"); filter=state.getInt("filter");
            month=YearMonth.parse(state.getString("month",month.toString())); search=state.getString("search","");categorySearch=state.getString("categorySearch","");
            int[] savedScroll=state.getIntArray("page_scroll");if(savedScroll!=null)System.arraycopy(savedScroll,0,pageScroll,0,Math.min(savedScroll.length,pageScroll.length));
            visibleEntries=state.getInt("visible_entries",PAGE_SIZE);b3Search=state.getString("b3_search","");visibleB3Positions=state.getInt("b3_visible",PAGE_SIZE);}
        if(state!=null){try{reportFrom=LocalDate.parse(state.getString("report_from",reportFrom.toString()));reportTo=LocalDate.parse(state.getString("report_to",reportTo.toString()));}catch(java.time.DateTimeException ignored){reportFrom=YearMonth.now().atDay(1);reportTo=YearMonth.now().atEndOfMonth();}
            reportPreset=Math.max(0,Math.min(3,state.getInt("report_preset",0)));reportAccountId=state.getLong("report_account",0);allReportCategories=state.getBoolean("report_all_categories",false);}
        Object retained=getLastNonConfigurationInstance();if(retained instanceof PendingReport)pendingReport=(PendingReport)retained;
        if(page<0||page>=pageScroll.length||page==5)page=0;
        render();
    }
    @Override protected void onSaveInstanceState(Bundle state){
        super.onSaveInstanceState(state); state.putInt("page",page); state.putInt("filter",filter);
        state.putString("month",month.toString()); state.putString("search",search);
        state.putString("categorySearch",categorySearch);
        rememberScroll();state.putIntArray("page_scroll",pageScroll);state.putInt("visible_entries",visibleEntries);
        state.putString("b3_search",b3Search);state.putInt("b3_visible",visibleB3Positions);
        state.putString("report_from",reportFrom.toString());state.putString("report_to",reportTo.toString());state.putInt("report_preset",reportPreset);state.putLong("report_account",reportAccountId);state.putBoolean("report_all_categories",allReportCategories);
    }
    @Override public Object onRetainNonConfigurationInstance(){return pendingReport;}
    @Override protected void onResume(){super.onResume();if(AppAppearance.isDark(this)!=darkTheme){recreate();return;}if(firstResume)firstResume=false;else if(store!=null)render();quoteHandler.removeCallbacks(quoteTick);quoteHandler.post(quoteTick);}
    @Override protected void onPause(){quoteHandler.removeCallbacksAndMessages(null);quoteGeneration++;if(automaticQuoteTask!=null)automaticQuoteTask.cancel(true);quoteRefreshRunning=false;super.onPause();}
    @Override protected void onDestroy(){quoteHandler.removeCallbacksAndMessages(null);if(activeProgress!=null)activeProgress.dismiss();worker.execute(()->store.close());worker.shutdown();super.onDestroy();}

    private void maybeAutomaticQuotes(){
        if(isFinishing()||isDestroyed()||quoteRefreshRunning||activeProgress!=null||!(page==0||page==2||page==6)||!InvestmentQuoteUpdater.isAutomaticRefreshDue(this))return;
        quoteRefreshRunning=true;final int generation=++quoteGeneration;
        automaticQuoteTask=worker.submit(()->{String summary;try{summary=new InvestmentQuoteUpdater(getApplicationContext()).refresh(store,new InvestmentQuotes(),true).summary;}catch(Exception ex){summary="Cotações indisponíveis. Os valores anteriores foram mantidos.";}
            final String result=summary;runOnUiThread(()->{if(generation!=quoteGeneration)return;quoteRefreshRunning=false;if(!isFinishing()&&!isDestroyed()){lastQuoteSummary=result;render();}});});
    }

    private void render(){
        rememberScroll();
        categoryCache=store.categories();accountCache=store.accounts();cardCache=store.cards();entryCache=store.entries();
        marketCache=InvestmentQuoteCache.snapshot(this);
        root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(BG);
        if(android.os.Build.VERSION.SDK_INT>=30){
            getWindow().setDecorFitsSystemWindows(false);
            root.setOnApplyWindowInsetsListener((v,insets)->{android.graphics.Insets bars=insets.getInsets(android.view.WindowInsets.Type.systemBars()|android.view.WindowInsets.Type.displayCutout());v.setPadding(bars.left,bars.top,bars.right,bars.bottom);return insets;});
        }else root.setFitsSystemWindows(true);
        setContentView(root);applySystemBars();
        LinearLayout header=row();header.setPadding(dp(22),dp(15),dp(22),dp(13));
        TextView brand=text("myfinancehub",22,GREEN,true);header.addView(brand,new LinearLayout.LayoutParams(0,-2,1));
        TextView privacy=text(hidden?"◉  Mostrar":"◉  Ocultar",12,GREEN,true);privacy.setPadding(dp(12),dp(12),dp(8),dp(12));privacy.setMinHeight(dp(48));privacy.setGravity(Gravity.CENTER);
        privacy.setBackground(feedback(colors.surfaceAlt,14));
        privacy.setContentDescription(hidden?"Mostrar valores":"Ocultar valores");
        privacy.setOnClickListener(v->{hidden=!hidden;getPreferences(0).edit().putBoolean("hidden",hidden).apply();render();});
        header.addView(privacy);root.addView(header);
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);screenScroll=scroll;renderedPage=page;
        content=column();content.setPadding(dp(22),dp(8),dp(22),dp(28));scroll.addView(content);
        root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        if(page==0)dashboard();else if(page==1)transactions();else if(page==2)wallet();else if(page==4)categories();else if(page==6)b3Screen();else if(page==7)reports();else settings();
        navigation=row();navigation.setBackgroundColor(WHITE);navigation.setPadding(dp(6),dp(6),dp(6),dp(6));
        String[] labels={"Painel","Lançamentos","Relatórios","Carteira","Ajustes"};int[] targets={0,1,7,2,3};
        for(int i=0;i<labels.length;i++){final int p=targets[i];boolean selected=p==page||page==4&&p==1||page==6&&p==2;LinearLayout item=column();item.setGravity(Gravity.CENTER);item.setPadding(0,dp(8),0,dp(6));
            item.setBackground(feedback(selected?colors.surfaceAlt:WHITE,16));item.setSelected(selected);
            item.addView(new NavIcon(this,p,selected?GREEN:MUTED),new LinearLayout.LayoutParams(dp(25),dp(25)));
            TextView label=text(labels[i],11,selected?GREEN:MUTED,selected);label.setGravity(Gravity.CENTER);label.setMaxLines(1);label.setHorizontallyScrolling(false);label.setEllipsize(android.text.TextUtils.TruncateAt.END);label.setAutoSizeTextTypeUniformWithConfiguration(9,11,1,android.util.TypedValue.COMPLEX_UNIT_SP);item.addView(label);
            item.setContentDescription(labels[i]);item.setOnClickListener(v->{if(page!=p){page=p;render();}});
            item.setMinimumHeight(dp(68));navigation.addView(item,new LinearLayout.LayoutParams(0,-2,1));}
        root.addView(navigation);
        if(page==0||page==2||page==6)quoteHandler.post(this::maybeAutomaticQuotes);
        final int restoreY=pageScroll[page];
        scroll.getViewTreeObserver().addOnGlobalLayoutListener(new android.view.ViewTreeObserver.OnGlobalLayoutListener(){public void onGlobalLayout(){scroll.getViewTreeObserver().removeOnGlobalLayoutListener(this);if(screenScroll==scroll)scroll.scrollTo(0,restoreY);}});
    }
    private void rememberScroll(){if(screenScroll!=null&&renderedPage>=0&&renderedPage<pageScroll.length)pageScroll[renderedPage]=screenScroll.getScrollY();}
    private void applySystemBars(){
        getWindow().setStatusBarColor(BG);getWindow().setNavigationBarColor(BG);
        if(android.os.Build.VERSION.SDK_INT>=30){android.view.WindowInsetsController bars=getWindow().getInsetsController();if(bars!=null){int light=android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS|android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;bars.setSystemBarsAppearance(darkTheme?0:light,light);}}
        else{int light=View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;int current=getWindow().getDecorView().getSystemUiVisibility();getWindow().getDecorView().setSystemUiVisibility(darkTheme?current&~light:current|light);}
    }

    private void dashboard(){
        eyebrow("VISÃO GERAL");title("Seu dinheiro");
        add(text("Saldos e investimentos em um só lugar.",14,MUTED,false),0,6,0,20);
        LinearLayout balance=box(colors.hero);balance.setPadding(dp(22),dp(22),dp(22),dp(22));
        balance.addView(text("SALDO NAS CONTAS",12,colors.heroMuted,true));
        addTo(balance,text(money(store.totalBalance()),34,colors.heroText,true),0,10,0,8);
        balance.addView(text("Disponível nas contas, sem somar os investimentos.",12,colors.heroMuted,false));
        add(balance,0,0,0,4);

        section("Investimentos","Carteira cadastrada no app");
        List<Models.Investment> positions=store.investments();long invested=0,current=0;
        for(Models.Investment investment:positions){invested=Math.addExact(invested,investment.investedCents);current=Math.addExact(current,investment.currentCents);}
        long variation=Math.subtractExact(current,invested);
        LinearLayout investments=box(WHITE);
        investments.addView(text("VALOR ATUAL",12,MUTED,true));
        addTo(investments,text(money(current),30,TEXT,true),0,9,0,18);
        LinearLayout totals=row();
        LinearLayout applied=column();applied.addView(text("Total aplicado",12,MUTED,false));addTo(applied,text(money(invested),16,TEXT,true),0,5,0,0);
        LinearLayout change=column();change.addView(text("Rentabilidade simples",12,MUTED,false));addTo(change,text((!hidden&&variation>0?"+ ":"")+money(variation),16,variation<0?RED:colors.income,true),0,5,0,0);addTo(change,text(returnPercent(invested,current),13,MUTED,false),0,5,0,0);
        totals.addView(applied,new LinearLayout.LayoutParams(0,-2,1));totals.addView(change,new LinearLayout.LayoutParams(0,-2,1));investments.addView(totals);
        if(positions.isEmpty())addTo(investments,text("Cadastre seus investimentos em Carteira para acompanhar as posições aqui.",13,MUTED,false),0,18,0,0);
        else{
            addTo(investments,text(positions.size()+" "+(positions.size()==1?"investimento cadastrado":"investimentos cadastrados"),12,MUTED,false),0,18,0,10);
            for(int i=0;i<Math.min(3,positions.size());i++){
                Models.Investment investment=positions.get(i);divider(investments);LinearLayout item=row();item.setPadding(0,dp(14),0,dp(14));
                TextView name=text(investment.name+(investment.ticker.isEmpty()?"":" · "+investment.ticker),14,TEXT,true);name.setMaxLines(2);
                item.addView(name,new LinearLayout.LayoutParams(0,-2,1));TextView value=text(money(investment.currentCents),14,TEXT,true);value.setPadding(dp(12),0,0,0);item.addView(value);
                item.setContentDescription("Editar investimento "+investment.name+", "+money(investment.currentCents));item.setBackground(feedback(WHITE,10));item.setOnClickListener(v->editInvestment(investment));investments.addView(item);
            }
        }
        add(investments,0,0,0,14);
        Button wallet=button("Ver carteira",GREEN,colors.onPrimary);wallet.setOnClickListener(v->{page=2;render();});content.addView(wallet);
        add(text("Rentabilidade simples compara valor atual e total aplicado, sem rendimentos pagos, impostos ou taxa anualizada. Cotações configuradas atualizam esta soma; posições manuais mantêm o valor informado.",12,MUTED,false),0,12,0,0);
        b3Summary(false);

    }

    private void monthlySummary(){
        long income=0,expense=0;
        for(Models.Entry e:monthlyEntries()){if(e.method.equals("card_payment")||e.method.equals("transfer"))continue;if(e.type.equals("income"))income=Math.addExact(income,e.amountCents);else expense=Math.addExact(expense,e.amountCents);}
        LinearLayout metrics=row();metrics.addView(metric("↗  Entradas",income,colors.income),new LinearLayout.LayoutParams(0,-2,1));
        Space spacer=new Space(this);metrics.addView(spacer,new LinearLayout.LayoutParams(dp(12),1));
        metrics.addView(metric("↘  Saídas",expense,RED),new LinearLayout.LayoutParams(0,-2,1));content.addView(metrics);
        add(text("Inclui crédito. Pagamentos de fatura e transferências próprias ficam fora destes totais.",12,MUTED,false),0,8,0,2);
    }

    private void transactions(){
        eyebrow("ENTRADAS E SAÍDAS");title("Seus lançamentos");
        LinearLayout actions=row();Button add=button("+  Novo lançamento",GREEN,colors.onPrimary);Button imp=button("Importar extrato",colors.surfaceAlt,GREEN);
        add.setContentDescription("Novo lançamento");imp.setContentDescription("Importar extrato");
        add.setOnClickListener(v->editEntry(null));imp.setOnClickListener(v->chooseDocument(IMPORT));
        actions.addView(add,new LinearLayout.LayoutParams(0,-2,1));Space s=new Space(this);actions.addView(s,new LinearLayout.LayoutParams(dp(8),1));actions.addView(imp,new LinearLayout.LayoutParams(0,-2,1));add(actions,0,16,0,12);
        action("Gerenciar categorias","Criar, editar e excluir categorias.",()->{page=4;render();});monthSelector();monthlySummary();
        LinearLayout chips=row();String[] names={"Todos","Entradas","Saídas"};
        for(int i=0;i<3;i++){final int f=i;TextView chip=text(names[i],13,filter==i?colors.onPrimary:GREEN,true);chip.setGravity(Gravity.CENTER);chip.setPadding(dp(12),dp(12),dp(12),dp(12));chip.setMinHeight(dp(48));chip.setSelected(filter==i);chip.setContentDescription(names[i]);chip.setBackground(feedback(filter==i?GREEN:colors.surfaceAlt,16));chip.setOnClickListener(v->{if(filter!=f){filter=f;visibleEntries=PAGE_SIZE;render();}});
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,1);lp.setMargins(i==0?0:dp(6),dp(14),0,dp(12));chips.addView(chip,lp);}content.addView(chips);
        EditText searchField=input("Buscar descrição",search,InputType.TYPE_CLASS_TEXT);searchField.setSingleLine(true);
        content.addView(searchField);LinearLayout list=box(WHITE);add(list,0,12,0,0);fillEntries(list,search);
        searchField.addTextChangedListener(new android.text.TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int c,int f){}public void onTextChanged(CharSequence s,int st,int before,int count){search=s.toString();visibleEntries=PAGE_SIZE;fillEntries(list,search);}public void afterTextChanged(android.text.Editable e){}});
        Button detail=button("Ver relatório do mês",colors.surfaceAlt,GREEN);detail.setOnClickListener(v->{reportPreset=3;reportFrom=month.atDay(1);reportTo=month.atEndOfMonth();reportAccountId=0;pageScroll[7]=0;page=7;render();});add(detail,0,16,0,0);
    }
    private void fillEntries(LinearLayout list,String query){list.removeAllViews();int count=0;
        for(Models.Entry e:monthlyEntries())if((filter==0||filter==1&&e.type.equals("income")||filter==2&&e.type.equals("expense"))&&normalize(e.description).contains(normalize(query))){if(count<visibleEntries)entryRow(list,e);count++;}
        if(count==0)empty(list,"Nenhum lançamento encontrado","Escolha outro mês, ajuste os filtros ou adicione um registro.");
        if(count>visibleEntries){Button more=button("Mostrar mais ("+visibleEntries+" de "+count+")",WHITE,GREEN);more.setOnClickListener(v->{visibleEntries+=PAGE_SIZE;fillEntries(list,query);});list.addView(more);}
    }

    private FinanceReport buildReport(){
        return FinanceReport.build(reportFrom,reportTo,reportAccountId,store.entries(),store.accounts(),store.categories(),store.cards(),store.investments(),store.b3Snapshot());
    }
    private void reports(){
        eyebrow("CLAREZA PARA DECIDIR");title("Relatórios");
        add(text("Entradas, despesas e resultado do período.",14,MUTED,false),0,6,0,18);
        if(reportAccountId!=0&&accountCache.stream().noneMatch(a->a.id==reportAccountId))reportAccountId=0;
        LinearLayout filters=box(WHITE);
        Spinner period=select(filters,"Período do relatório",new String[]{"Este mês","Últimos 3 meses","Este ano","Personalizado"},reportPreset);
        ArrayList<String> accounts=new ArrayList<>();accounts.add("Todas as contas");int selectedAccount=0;
        for(Models.Account account:accountCache){accounts.add(account.name);if(account.id==reportAccountId)selectedAccount=accounts.size()-1;}
        Spinner account=select(filters,"Conta do relatório",accounts.toArray(new String[0]),selectedAccount);
        addTo(filters,text("De "+fullDate(reportFrom)+" a "+fullDate(reportTo),13,GREEN,true),0,16,0,2);
        if(reportPreset==3){
            Button from=button("De: "+fullDate(reportFrom),colors.surfaceAlt,GREEN);from.setContentDescription("Data inicial do relatório");from.setOnClickListener(v->chooseReportDate(true));
            Button to=button("Até: "+fullDate(reportTo),colors.surfaceAlt,GREEN);to.setContentDescription("Data final do relatório");to.setOnClickListener(v->chooseReportDate(false));
            addTo(filters,from,0,12,0,8);filters.addView(to,new LinearLayout.LayoutParams(-1,-2));
        }
        add(filters,0,0,0,18);
        period.setOnItemSelectedListener(selected(()->{int value=period.getSelectedItemPosition();if(value!=reportPreset){reportPreset=value;setReportPreset(value);allReportCategories=false;pageScroll[7]=0;render();}}));
        account.setOnItemSelectedListener(selected(()->{int index=account.getSelectedItemPosition();long id=index==0?0:accountCache.get(index-1).id;if(id!=reportAccountId){reportAccountId=id;allReportCategories=false;render();}}));
        final FinanceReport report;
        try{report=buildReport();}catch(IllegalArgumentException ex){LinearLayout problem=box(WHITE);empty(problem,"Confira o período",safeError(ex));content.addView(problem);return;}
        LinearLayout result=box(colors.hero);result.addView(text("RESULTADO DO PERÍODO",12,colors.heroMuted,true));
        addTo(result,text(money(report.resultCents),32,colors.heroText,true),0,10,0,8);
        result.addView(text("Entradas menos despesas · "+report.transactions.size()+" lançamentos",12,colors.heroMuted,false));add(result,0,0,0,12);
        LinearLayout metrics=getResources().getConfiguration().fontScale>1.3f?column():row();
        if(metrics.getOrientation()==LinearLayout.VERTICAL){addTo(metrics,metric("Entradas",report.incomeCents,colors.income),0,0,0,10);metrics.addView(metric("Despesas",report.expenseCents,RED));}
        else{metrics.addView(metric("Entradas",report.incomeCents,colors.income),new LinearLayout.LayoutParams(0,-2,1));metrics.addView(new Space(this),new LinearLayout.LayoutParams(dp(10),1));metrics.addView(metric("Despesas",report.expenseCents,RED),new LinearLayout.LayoutParams(0,-2,1));}
        content.addView(metrics);
        add(text("Compras no crédito entram nas despesas. Transferências próprias e pagamentos de fatura ficam fora do resultado.",12,MUTED,false),0,10,0,0);
        if(report.transactions.isEmpty()){LinearLayout empty=box(WHITE);empty(empty,"Seu relatório começa aqui","Adicione lançamentos ou importe um extrato. Você também pode escolher outro período ou conta.");add(empty,0,18,0,0);}
        section("Exportar relatório","O arquivo usa o período e a conta selecionados acima");
        Button pdf=button("Salvar PDF",GREEN,colors.onPrimary);pdf.setOnClickListener(v->exportReport(report,REPORT_PDF,false));content.addView(pdf,new LinearLayout.LayoutParams(-1,-2));
        LinearLayout export=row();Button share=button("Compartilhar PDF",colors.surfaceAlt,GREEN);share.setOnClickListener(v->exportReport(report,REPORT_PDF,true));
        Button csv=button("Exportar CSV",colors.surfaceAlt,GREEN);csv.setOnClickListener(v->exportReport(report,REPORT_CSV,false));
        if(getResources().getConfiguration().fontScale>1.3f){add(share,0,10,0,8);content.addView(csv,new LinearLayout.LayoutParams(-1,-2));}
        else{export.addView(share,new LinearLayout.LayoutParams(0,-2,1));export.addView(new Space(this),new LinearLayout.LayoutParams(dp(8),1));export.addView(csv,new LinearLayout.LayoutParams(0,-2,1));add(export,0,10,0,0);}
        add(text("PDF: resumo e lançamentos detalhados. CSV: lançamentos para planilha. Os arquivos contêm os valores completos.",12,MUTED,false),0,10,0,0);
        section("Gastos por categoria","Despesas do período, incluindo compras no crédito");
        LinearLayout categories=box(WHITE);
        if(report.expenseCategories.isEmpty())empty(categories,"Nenhuma despesa no período","As categorias aparecerão quando houver saídas nos filtros selecionados.");
        else{
            int shown=allReportCategories?report.expenseCategories.size():Math.min(8,report.expenseCategories.size());
            for(int i=0;i<shown;i++){FinanceReport.CategoryTotal category=report.expenseCategories.get(i);LinearLayout heading=row();TextView name=text(category.categoryName,14,TEXT,true);heading.addView(name,new LinearLayout.LayoutParams(0,-2,1));
                TextView value=text(money(category.amountCents),14,TEXT,true);value.setPadding(dp(10),0,0,0);heading.addView(value);categories.addView(heading);
                double ratio=report.expenseCents==0?0:(double)category.amountCents/report.expenseCents;
                LinearLayout bar=row();bar.setBackground(round(colors.surfaceAlt,4));View fill=new View(this);fill.setBackground(round(GREEN,4));bar.addView(fill,new LinearLayout.LayoutParams(0,dp(6),(float)Math.max(.005,ratio)));bar.addView(new Space(this),new LinearLayout.LayoutParams(0,1,(float)Math.max(.001,1-ratio)));bar.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);addTo(categories,bar,0,9,0,16);
            }
            if(shown<report.expenseCategories.size()){Button more=button("Mostrar todas as categorias",colors.surfaceAlt,GREEN);more.setOnClickListener(v->{allReportCategories=true;render();});categories.addView(more);}
        }
        content.addView(categories);
        if(report.months.size()>1){
            section("Comparação mensal","Entradas, despesas e resultado em cada mês");LinearLayout comparison=box(WHITE);
            int first=Math.max(0,report.months.size()-12);
            for(int i=first;i<report.months.size();i++){FinanceReport.MonthTotal m=report.months.get(i);if(i>first)divider(comparison);
                addTo(comparison,text(m.month.format(DateTimeFormatter.ofPattern("MMM/yyyy",new Locale("pt","BR"))),14,TEXT,true),0,12,0,8);
                comparison.addView(text("Entradas  "+money(m.incomeCents)+"\nDespesas  "+money(m.expenseCents),13,MUTED,false));
                addTo(comparison,text("Resultado  "+money(m.resultCents),15,m.resultCents<0?RED:colors.income,true),0,7,0,14);
            }
            if(first>0)comparison.addView(text("Últimos 12 meses na tela. O PDF inclui todos os meses selecionados.",12,MUTED,false));content.addView(comparison);
        }
        section("Movimentação nas contas","Separada do resultado para evitar duplicidade");
        LinearLayout cash=box(WHITE);cash.addView(text("Movimento de caixa  "+money(report.cashMovementCents),16,TEXT,true));
        addTo(cash,text("Entradas menos saídas que afetam as contas. Inclui faturas e transferências; exclui compras e estornos no crédito. Não é o saldo inicial ou final do período.",12,MUTED,false),0,8,0,14);
        cash.addView(text("Transferências recebidas  "+money(report.transferIncomeCents)+"\nTransferências enviadas  "+money(report.transferExpenseCents)+"\nFaturas pagas  "+money(report.cardPaymentExpenseCents),13,MUTED,false));
        if(report.cardPaymentIncomeCents>0)addTo(cash,text("Entradas de pagamento de fatura  "+money(report.cardPaymentIncomeCents),13,MUTED,false),0,4,0,0);content.addView(cash);
    }
    private String fullDate(LocalDate date){return date.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"));}
    private void setReportPreset(int preset){YearMonth now=YearMonth.now();if(preset==0){reportFrom=now.atDay(1);reportTo=now.atEndOfMonth();}else if(preset==1){reportFrom=now.minusMonths(2).atDay(1);reportTo=now.atEndOfMonth();}else if(preset==2){reportFrom=LocalDate.of(now.getYear(),1,1);reportTo=LocalDate.of(now.getYear(),12,31);}}
    private void chooseReportDate(boolean first){LocalDate date=first?reportFrom:reportTo;new DatePickerDialog(this,(picker,y,m,d)->{LocalDate chosen=LocalDate.of(y,m+1,d);if(first)reportFrom=chosen;else reportTo=chosen;allReportCategories=false;render();},date.getYear(),date.getMonthValue()-1,date.getDayOfMonth()).show();}
    private void exportReport(FinanceReport report,int request,boolean share){
        Runnable export=()->{
            if(request==REPORT_PDF&&report.transactions.size()>FinanceReportPdf.MAX_TRANSACTIONS){message("O PDF aceita até 5.000 lançamentos. Reduza o período ou use Exportar CSV para todos os registros.");return;}
            if(share){background(()->{File file=ReportShareProvider.newPdfFile(this);try{try(OutputStream output=new FileOutputStream(file)){FinanceReportPdf.write(report,output);}return ReportShareProvider.uriFor(this,file);}catch(Exception ex){file.delete();throw ex;}},uri->{
                Intent send=new Intent(Intent.ACTION_SEND).setType("application/pdf").putExtra(Intent.EXTRA_STREAM,uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);send.setClipData(ClipData.newRawUri("Relatório MyFinanceHub",uri));
                try{startActivity(Intent.createChooser(send,"Compartilhar relatório"));}catch(android.content.ActivityNotFoundException ex){message("Nenhum aplicativo disponível para compartilhar PDF. Use Salvar PDF.");}
            });}
            else{pendingReport=new PendingReport(report,request);createDocument(request,request==REPORT_PDF?"application/pdf":"text/csv","MyFinanceHub-relatorio-"+report.from+"-a-"+report.to+(request==REPORT_PDF?".pdf":".csv"));}
        };
        if(hidden)new AlertDialog.Builder(this).setTitle("Exportar valores visíveis?").setMessage("O arquivo terá os valores completos mesmo com os números ocultos na tela. Ao compartilhar, você escolhe quem receberá o relatório.").setNegativeButton("Cancelar",null).setPositiveButton("Continuar",(d,w)->export.run()).show();else export.run();
    }

    private void wallet(){
        eyebrow("PATRIMÔNIO E ORGANIZAÇÃO");title("Sua carteira");
        action("Cotações e atualização","Pesquisar ativos e atualizar os valores dos investimentos.",()->startActivity(new Intent(this,QuotesActivity.class)));
        if(quoteRefreshRunning)add(text("Atualizando cotações…",12,GREEN,true),0,0,0,12);
        else if(!lastQuoteSummary.isEmpty())add(text(lastQuoteSummary,12,MUTED,false),0,0,0,12);
        section("Contas","Saldo local por conta");
        for(Models.Account a:store.accounts()){LinearLayout card=box(WHITE);LinearLayout line=row();line.addView(text(a.name,16,TEXT,true),new LinearLayout.LayoutParams(0,-2,1));line.addView(text(money(store.accountBalance(a.id)),17,GREEN,true));card.addView(line);
            addTo(card,text("Saldo inicial: "+money(a.openingCents)+"  ·  Toque para editar",12,MUTED,false),0,8,0,0);card.setOnClickListener(v->editAccount(a));add(card,0,0,0,10);}
        Button account=button("+ Adicionar conta",WHITE,GREEN);account.setOnClickListener(v->editAccount(null));content.addView(account);
        section("Cartões","Compras identificadas por cartão");
        if(store.cards().isEmpty()){LinearLayout empty=box(WHITE);empty(empty,"Cada cartão no seu lugar","Cadastre nome e últimos 4 dígitos. Nunca pedimos número completo, senha ou CVV.");content.addView(empty);}
        for(Models.Card c:store.cards()){LinearLayout card=box(colors.hero);card.addView(text(c.name,18,colors.heroText,true));addTo(card,text("••••  "+c.lastFour,19,LIME,true),0,16,0,16);
            long spent=0;for(Models.Entry e:monthlyEntries())if(e.cardId==c.id&&e.method.equals("credit"))spent=Math.addExact(spent,e.type.equals("expense")?e.amountCents:-e.amountCents);
            card.addView(text("Movimentação em "+month.format(DateTimeFormatter.ofPattern("MMM/yyyy",new Locale("pt","BR")))+": "+money(spent),12,colors.heroText,false));
            addTo(card,text("Fecha dia "+c.closingDay+" · vence dia "+c.dueDay+" · editar",12,colors.heroMuted,false),0,8,0,0);card.setOnClickListener(v->editCard(c));add(card,0,0,0,12);}
        Button credit=button("+ Adicionar cartão",WHITE,GREEN);credit.setOnClickListener(v->editCard(null));add(credit,0,10,0,0);
        TextView creditNote=text("Crédito não reduz o saldo da conta. Registre o pagamento como saída com a forma “Pagamento de fatura”: ela reduz o saldo sem repetir as despesas do painel. Os valores acima são movimentos do mês civil, não uma fatura calculada.",12,MUTED,false);add(creditNote,0,10,0,0);
        section("Investimentos","Carteira cadastrada no app");long invested=0,current=0;
        for(Models.Investment i:store.investments()){invested=Math.addExact(invested,i.investedCents);current=Math.addExact(current,i.currentCents);}
        LinearLayout summary=box(colors.surfaceAlt);summary.addView(text("TOTAL DOS INVESTIMENTOS CADASTRADOS",11,GREEN,true));addTo(summary,text(money(current),27,GREEN,true),0,8,0,6);summary.addView(text("Aplicado: "+money(invested)+"\nRentabilidade simples: "+money(current-invested)+" · "+returnPercent(invested,current),13,GREEN,false));content.addView(summary);
        for(Models.Investment i:store.investments()){LinearLayout card=box(WHITE);card.addView(text(i.name+(i.ticker.isEmpty()?"":" · "+i.ticker),15,TEXT,true));
            addTo(card,text(i.kind+"  ·  Atual: "+money(i.currentCents),13,TEXT,false),0,7,0,0);
            addTo(card,text("Rentabilidade simples: "+money(i.currentCents-i.investedCents)+" · "+returnPercent(i.investedCents,i.currentCents),13,i.currentCents<i.investedCents?RED:colors.income,true),0,7,0,0);
            addTo(card,text(investmentQuoteLabel(i),12,MUTED,false),0,9,0,0);card.setContentDescription("Editar investimento "+i.name+", "+money(i.currentCents)+", rentabilidade simples "+returnPercent(i.investedCents,i.currentCents));card.setBackground(feedback(WHITE,20));card.setOnClickListener(v->editInvestment(i));add(card,0,10,0,0);}
        Button invest=button("+ Adicionar investimento",GREEN,colors.onPrimary);invest.setOnClickListener(v->editInvestment(null));add(invest,0,14,0,0);
        add(text("O total é recalculado com os valores de cada investimento, incluindo cotações configuradas e valores manuais. A rentabilidade simples não representa retorno do período, não inclui proventos e não é anualizada. Aportes e resgates também precisam dos lançamentos na conta.",12,MUTED,false),0,10,0,0);
        b3Summary(true);
    }

    private String returnPercent(long invested,long current){if(hidden)return "••••%";if(invested==0)return "Percentual indisponível";return java.math.BigDecimal.valueOf(current).subtract(java.math.BigDecimal.valueOf(invested)).multiply(java.math.BigDecimal.valueOf(100)).divide(java.math.BigDecimal.valueOf(invested),2,java.math.RoundingMode.HALF_UP).toPlainString().replace('.',',')+"%";}
    private String quoteTime(String time){try{return java.time.Instant.parse(time).atZone(java.time.ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"));}catch(java.time.DateTimeException ex){return "Horário indisponível";}}
    private String investmentQuoteLabel(Models.Investment investment){return investment.quoteAt.isEmpty()?(investment.autoQuote?"Aguardando cotação · configure a atualização em Cotações":"Valor informado manualmente"):("brapi · cotação em "+quoteTime(investment.quoteAt)+(investment.autoQuote?" · atualização habilitada":" · atualização pausada"));}

    private void estimatedB3(B3Portfolio.Snapshot snapshot){
        if(snapshot==null)return;java.math.BigInteger total=java.math.BigInteger.ZERO;int count=0;java.time.Instant oldest=null;
        for(B3Portfolio.Position position:snapshot.positions){if(!InvestmentQuoteUpdater.supportsB3Position(position))continue;InvestmentQuoteCache.Value quote=marketCache.get(position.ticker.trim().toUpperCase(Locale.ROOT));if(quote==null)continue;
            try{java.math.BigDecimal quantity=new java.math.BigDecimal(position.quantity);if(quantity.signum()<=0)continue;java.math.BigInteger value=quantity.multiply(quote.price).movePointRight(2).setScale(0,java.math.RoundingMode.HALF_UP).toBigIntegerExact();total=total.add(value);count++;if(oldest==null||quote.asOf.isBefore(oldest))oldest=quote.asOf;}catch(ArithmeticException|NumberFormatException ignored){}}
        if(count==0)return;
        if(total.bitLength()>63){add(text("Estimativa B3 acima do limite suportado. Consulte as posições individuais.",12,MUTED,false),0,10,0,12);return;}final long cents=total.longValue();
        LinearLayout estimate=box(WHITE);estimate.addView(text(count==snapshot.positions.size()?"B3 · ESTIMATIVA POR COTAÇÕES":"B3 · ESTIMATIVA PARCIAL POR COTAÇÕES",11,GREEN,true));
        addTo(estimate,text(money(cents),26,TEXT,true),0,8,0,8);
        estimate.addView(text(count+" de "+snapshot.positions.size()+" posições cotadas · brapi\nCotação mais antiga: "+quoteTime(oldest.toString()),12,MUTED,false));
        addTo(estimate,text("Estimativa bruta com as quantidades importadas em "+b3Date(snapshot.asOfDate)+". Não inclui posições sem cotação nem mudanças posteriores de quantidade. O valor original do relatório B3 e o total dos cadastros ficam separados.",12,MUTED,false),0,10,0,0);add(estimate,0,0,0,12);
    }

    private void openB3(){page=6;render();}
    private String b3Date(String value){return LocalDate.parse(value).format(DateTimeFormatter.ofPattern("dd/MM/yyyy"));}
    private String b3Quantity(String value){return hidden?"••••":value.replace('.',',');}
    private String b3Total(B3Portfolio.Snapshot snapshot){return snapshot.unvaluedCount()==snapshot.positions.size()?"Não informado":money(snapshot.totalCents());}
    private String b3Count(int count){return count+(count==1?" posição":" posições");}
    private void b3Summary(boolean inWallet){
        B3Portfolio.Snapshot snapshot=store.b3Snapshot();
        section("Posição B3",snapshot==null?"Relatório da Área do Investidor":"Posição em "+b3Date(snapshot.asOfDate));
        if(snapshot!=null){LinearLayout card=box(colors.surfaceAlt);
            card.addView(text(snapshot.unvaluedCount()>0?"VALOR INFORMADO · PARCIAL":"VALOR INFORMADO NO RELATÓRIO",11,GREEN,true));
            addTo(card,text(b3Total(snapshot),26,GREEN,true),0,8,0,6);
            card.addView(text(b3Count(snapshot.positions.size())+(snapshot.positions.size()==1?" importada":" importadas")+(snapshot.unvaluedCount()>0?" · "+snapshot.unvaluedCount()+" sem valor informado":""),12,MUTED,false));
            add(card,0,0,0,10);estimatedB3(snapshot);}
        action(inWallet?"Abrir posição B3":"Posição B3",snapshot==null?"Importe o Excel da B3 e revise seus investimentos.":"Consultar ativos ou importar um relatório mais recente.",this::openB3);
        add(text("A posição B3 fica separada dos investimentos manuais e do saldo das contas, evitando contar o mesmo ativo duas vezes.",12,MUTED,false),0,0,0,0);
    }
    private void b3Screen(){
        eyebrow("ÁREA DO INVESTIDOR");title("Posição B3");
        add(text("Seus ativos a partir do relatório que você baixar na B3.",14,MUTED,false),0,7,0,18);
        Button imp=button("Importar posição B3",GREEN,colors.onPrimary);imp.setOnClickListener(v->chooseDocument(B3_IMPORT));content.addView(imp);
        Button help=button("Como baixar na B3",colors.surfaceAlt,GREEN);help.setOnClickListener(v->b3Guide());add(help,0,10,0,14);
        B3Portfolio.Snapshot snapshot=store.b3Snapshot();
        if(snapshot==null){LinearLayout empty=box(WHITE);empty(empty,"Nenhum relatório importado","Baixe sua posição em Excel na Área do Investidor. Escolha o arquivo aqui e confira os dados antes de salvar.");content.addView(empty);}
        else{
            LinearLayout summary=box(WHITE);summary.addView(text("POSIÇÃO EM "+b3Date(snapshot.asOfDate),12,GREEN,true));
            addTo(summary,text(b3Total(snapshot),30,TEXT,true),0,10,0,6);
            summary.addView(text(snapshot.unvaluedCount()>0?"Soma parcial: "+b3Count(snapshot.unvaluedCount())+" sem valor informado.":"Soma dos valores informados no relatório.",12,MUTED,false));
            addTo(summary,text(b3Count(snapshot.positions.size())+" · custo de aquisição e rentabilidade não informados.",12,MUTED,false),0,10,0,0);add(summary,0,0,0,16);estimatedB3(snapshot);
            EditText searchField=input("Buscar ativo ou instituição",b3Search,InputType.TYPE_CLASS_TEXT);content.addView(searchField);
            LinearLayout list=column();add(list,0,12,0,0);fillB3Positions(list,snapshot,b3Search);
            searchField.addTextChangedListener(new android.text.TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int c,int f){}public void onTextChanged(CharSequence s,int st,int before,int count){b3Search=s.toString();visibleB3Positions=PAGE_SIZE;fillB3Positions(list,snapshot,b3Search);}public void afterTextChanged(android.text.Editable e){}});
            Button remove=button("Remover relatório B3",WHITE,RED);remove.setOnClickListener(v->new AlertDialog.Builder(this).setTitle("Remover posição B3?")
                .setMessage("Remove somente o relatório B3 importado. Seus investimentos manuais, contas e lançamentos serão preservados.").setNegativeButton("Cancelar",null)
                .setPositiveButton("Remover",(d,w)->background(()->{store.clearB3Snapshot();return "Relatório B3 removido.";},result->{render();message(result);})).show());add(remove,0,18,0,0);
        }
        add(text("Atualização por arquivo, sem conexão automática. Valores são referências da data do relatório e podem diferir dos valores de resgate. A posição importada não altera seus lançamentos ou investimentos manuais.",12,MUTED,false),0,18,0,0);
    }
    private void fillB3Positions(LinearLayout list,B3Portfolio.Snapshot snapshot,String query){
        list.removeAllViews();int count=0;
        for(B3Portfolio.Position position:snapshot.positions){
            if(!normalize(position.product+" "+position.ticker+" "+position.institution+" "+position.kind).contains(normalize(query)))continue;
            if(count++>=visibleB3Positions)continue;
            LinearLayout card=box(WHITE);card.addView(text(position.product+(position.ticker.isEmpty()?"":" · "+position.ticker),15,TEXT,true));
            if(!position.institution.isEmpty()||!position.kind.isEmpty())addTo(card,text(position.institution+(position.institution.isEmpty()||position.kind.isEmpty()?"":" · ")+position.kind,12,MUTED,false),0,6,0,0);
            addTo(card,text("Quantidade: "+b3Quantity(position.quantity),13,MUTED,false),0,12,0,4);
            card.addView(text(position.currentCents==null?"Valor não informado":money(position.currentCents),19,position.currentCents==null?MUTED:GREEN,true));
            InvestmentQuoteCache.Value quote=InvestmentQuoteUpdater.supportsB3Position(position)?marketCache.get(position.ticker.trim().toUpperCase(Locale.ROOT)):null;if(quote!=null){try{long estimate=new java.math.BigDecimal(position.quantity).multiply(quote.price).movePointRight(2).setScale(0,java.math.RoundingMode.HALF_UP).longValueExact();addTo(card,text("Estimativa por cotação: "+money(estimate)+"\nbrapi · "+quoteTime(quote.asOf.toString()),12,MUTED,false),0,9,0,0);}catch(ArithmeticException|NumberFormatException ignored){}}
            card.setContentDescription("Detalhes de "+position.product+", "+(position.currentCents==null?"valor não informado":money(position.currentCents)));card.setBackground(feedback(WHITE,20));card.setOnClickListener(v->b3PositionDetails(position,snapshot.asOfDate));addTo(list,card,0,0,0,10);
        }
        if(count==0){LinearLayout empty=box(WHITE);empty(empty,"Nenhuma posição encontrada","Tente outro ativo ou nome da instituição.");list.addView(empty);}
        if(count>visibleB3Positions){Button more=button("Mostrar mais ("+visibleB3Positions+" de "+count+")",WHITE,GREEN);more.setOnClickListener(v->{visibleB3Positions+=PAGE_SIZE;fillB3Positions(list,snapshot,query);});list.addView(more);}
    }
    private void b3PositionDetails(B3Portfolio.Position position,String asOfDate){
        LinearLayout form=form();form.addView(text(position.product,18,TEXT,true));
        String[] labels={"Código", "ISIN", "Tipo", "Instituição", "Conta", "Quantidade", "Valor informado", "Data da posição"};
        String account=position.account.isEmpty()?"Não informada":"Final "+position.account.substring(Math.max(0,position.account.length()-4));
        String[] values={position.ticker,position.isin,position.kind,position.institution,account,b3Quantity(position.quantity),position.currentCents==null?"Não informado":money(position.currentCents),b3Date(asOfDate)};
        for(int i=0;i<labels.length;i++){addTo(form,text(labels[i],12,MUTED,true),0,15,0,4);form.addView(text(values[i].isEmpty()?"Não informado":values[i],15,TEXT,false));}
        addTo(form,text("Custo de aquisição não informado. Esta posição não calcula lucro ou rentabilidade. Para atualizar, importe outro relatório da B3.",12,MUTED,false),0,20,0,0);
        new AlertDialog.Builder(this).setTitle("Detalhes da posição").setView(wrap(form)).setPositiveButton("Fechar",null).show();
    }
    private void b3Guide(){
        new AlertDialog.Builder(this).setTitle("Baixar posição na B3").setMessage("1. Abra o site oficial e entre com sua conta da B3 no navegador.\n\n2. Acesse Carteira → Investimentos → Posição e escolha Baixar → Excel. Os nomes podem mudar no portal.\n\n3. Volte ao MyFinanceHub, toque em Importar posição B3 e selecione o arquivo .xlsx. Confira a data de referência do relatório.\n\nTambém aceitamos CSV com colunas de posição compatíveis. PDF, extrato de movimentações e arquivos .xls não são aceitos. Nunca informe sua senha ao MyFinanceHub. A atualização automática da B3 ainda não está disponível.")
            .setNegativeButton("Fechar",null).setPositiveButton("Abrir site oficial",(d,w)->{try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse("https://www.investidor.b3.com.br/")));}catch(android.content.ActivityNotFoundException ex){message("Abra https://www.investidor.b3.com.br/ no seu navegador.");}}).show();
    }
    private void settings(){
        eyebrow("DO SEU JEITO");title("Organize sua rotina");
        section("Aparência","Escolha como prefere visualizar o app");
        action("Aparência",appearanceLabel(AppAppearance.getMode(this))+" · sua escolha fica salva neste aparelho.",this::chooseAppearance);
        section("Acesso rápido","Suas preferências e organização");
        action("Gerenciar categorias","Criar, buscar, editar ou excluir em uma tela própria.",()->{page=4;render();});
        section("Seus dados","Arquivos sob seu controle");
        action("Importar extrato CSV ou OFX","Escolha uma conta, revise e confirme os registros.",()->chooseDocument(IMPORT));
        action("Posição B3","Importar e consultar o relatório da Área do Investidor.",this::openB3);
        action("Exportar lançamentos em CSV","Para consultar em uma planilha.",()->createDocument(CSV,"text/csv","myfinancehub-lancamentos-"+LocalDate.now()+".csv"));
        action("Salvar backup completo","JSON sem criptografia. Guarde em local seguro.",()->createDocument(BACKUP,"application/json","myfinancehub-backup-"+LocalDate.now()+".json"));
        action("Restaurar backup","Substitui os dados locais após confirmação.",()->new AlertDialog.Builder(this).setTitle("Restaurar um backup?").setMessage("O arquivo válido substituirá todos os dados atuais. Antes, salve um backup do estado atual. Use apenas um arquivo JSON exportado pelo MyFinanceHub.").setNegativeButton("Cancelar",null).setPositiveButton("Escolher arquivo",(d,w)->chooseDocument(RESTORE)).show());
        add(text("MyFinanceHub · versão "+appVersion()+"\nSeus dados financeiros ficam no armazenamento privado do app. Cotações opcionais consultam códigos de ativos pela internet. Sem anúncios ou rastreamento. Desinstalar apaga os dados locais; exporte um backup antes.",12,MUTED,false),0,22,0,0);
    }
    private String appearanceLabel(AppAppearance.Mode mode){return mode==AppAppearance.Mode.LIGHT?"Claro":mode==AppAppearance.Mode.DARK?"Escuro":"Seguir o sistema";}
    private void chooseAppearance(){
        AppAppearance.Mode[] modes={AppAppearance.Mode.SYSTEM,AppAppearance.Mode.LIGHT,AppAppearance.Mode.DARK};
        AppAppearance.Mode current=AppAppearance.getMode(this);int selected=0;for(int i=0;i<modes.length;i++)if(modes[i]==current)selected=i;
        new AlertDialog.Builder(this).setTitle("Aparência").setSingleChoiceItems(new String[]{"Seguir o sistema","Claro","Escuro"},selected,(dialog,which)->{dialog.dismiss();if(modes[which]!=current){AppAppearance.setMode(this,modes[which]);recreate();}}).setNegativeButton("Cancelar",null).show();
    }

    private void categories(){
        eyebrow("ORGANIZE DO SEU JEITO");title("Categorias");
        add(text("Crie, edite ou exclua suas categorias aqui.",13,MUTED,false),0,5,0,16);
        Button addCategory=button("+ Nova categoria",GREEN,colors.onPrimary);addCategory.setOnClickListener(v->editCategory(null));content.addView(addCategory);
        EditText query=input("Buscar categoria",categorySearch,InputType.TYPE_CLASS_TEXT);add(query,0,15,0,12);
        LinearLayout list=column();content.addView(list);fillCategories(list,categorySearch);
        query.addTextChangedListener(new android.text.TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int c,int f){}public void onTextChanged(CharSequence s,int st,int before,int count){categorySearch=s.toString();fillCategories(list,categorySearch);}public void afterTextChanged(android.text.Editable e){}});
    }
    private void fillCategories(LinearLayout list,String query){
        list.removeAllViews();int found=0;
        for(Models.Category category:categoryCache){
            if(!normalize(category.name).contains(normalize(query)))continue;found++;
            LinearLayout card=box(WHITE);LinearLayout heading=row();LinearLayout labels=column();
            labels.addView(text(category.name,16,TEXT,true));int count=categoryEntryCount(category.id);
            addTo(labels,text((category.type.equals("income")?"Entradas":category.type.equals("expense")?"Saídas":"Entradas e saídas")+" · "+count+" lançamentos",12,MUTED,false),0,5,0,0);
            heading.addView(labels,new LinearLayout.LayoutParams(0,-2,1));card.addView(heading);
            LinearLayout actions=row();Button edit=button("Editar",colors.surfaceAlt,GREEN);edit.setContentDescription("Editar categoria "+category.name);edit.setOnClickListener(v->editCategory(category));
            Button remove=button("Excluir",WHITE,RED);remove.setContentDescription("Excluir categoria "+category.name);remove.setOnClickListener(v->deleteCategory(category));
            actions.addView(edit,new LinearLayout.LayoutParams(0,-2,1));actions.addView(remove,new LinearLayout.LayoutParams(0,-2,1));addTo(card,actions,0,13,0,0);
            labels.setOnClickListener(v->editCategory(category));addTo(list,card,0,0,0,10);
        }
        if(found==0)empty(list,"Nenhuma categoria encontrada","Ajuste a busca ou crie uma categoria usando o botão acima.");
    }
    private int categoryEntryCount(long id){int count=0;for(Models.Entry e:entryCache)if(e.categoryId==id)count++;return count;}
    private void deleteCategory(Models.Category category){
        new AlertDialog.Builder(this).setTitle("Excluir a categoria “"+category.name+"”?")
            .setMessage(categoryEntryCount(category.id)+" lançamentos serão preservados e ficarão sem categoria. A exclusão pode ser recuperada apenas com um backup anterior.")
            .setNegativeButton("Cancelar",null).setPositiveButton("Excluir",(d,w)->{try{store.deleteCategory(category.id);render();}catch(Exception ex){message(safeError(ex));}}).show();
    }
    private void quickCategory(String direction,Result<Models.Category> done){
        LinearLayout form=form();EditText name=field(form,"Nome","",InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        Spinner type=select(form,"Aplicar a",new String[]{"Saídas","Entradas","Ambas"},direction.equals("income")?1:0);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Nova categoria").setView(wrap(form)).setNegativeButton("Cancelar",null).setPositiveButton("Salvar",null).create();
        dialog.setOnShowListener(d->dialog.getButton(-1).setOnClickListener(v->{try{
            Models.Category category=new Models.Category();category.name=name.getText().toString().trim();category.type=new String[]{"expense","income","both"}[type.getSelectedItemPosition()];
            if(!category.type.equals("both")&&!category.type.equals(direction))throw new IllegalArgumentException("Escolha um tipo compatível com o lançamento atual.");
            store.saveCategory(category);done.accept(category);dialog.dismiss();
        }catch(Exception ex){message(safeError(ex));}}));dialog.show();
    }

    private void editEntry(Models.Entry original){
        Models.Entry e=original==null?new Models.Entry():original;LinearLayout form=form();
        EditText description=field(form,"Descrição",e.description==null?"":e.description,InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        EditText amount=field(form,"Valor (R$)",e.amountCents==0?"":decimal(e.amountCents),InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL);
        Spinner type=select(form,"Tipo",new String[]{"Saída","Entrada"},"income".equals(e.type)?1:0);
        EditText date=field(form,"Data",e.date==null||e.date.isEmpty()?LocalDate.now().toString():e.date,InputType.TYPE_NULL);date.setFocusable(false);date.setOnClickListener(v->{LocalDate day=LocalDate.parse(date.getText());new DatePickerDialog(this,(p,y,m,d)->date.setText(LocalDate.of(y,m+1,d).toString()),day.getYear(),day.getMonthValue()-1,day.getDayOfMonth()).show();});
        List<Models.Account> accounts=store.accounts();if(accounts.isEmpty()){message("Adicione uma conta antes de lançar movimentações.");return;}
        Spinner account=select(form,"Conta",accountNames(accounts),indexAccount(accounts,e.accountId));
        List<Models.Category> categories=store.categories();Spinner category=select(form,"Categoria",new String[]{"Sem categoria"},0);List<Models.Category> available=new ArrayList<>();long[] preferredCategory={e.categoryId};
        Runnable updateCategories=()->{available.clear();String direction=type.getSelectedItemPosition()==1?"income":"expense";ArrayList<String> labels=new ArrayList<>();labels.add("Sem categoria");int selected=0;
            for(Models.Category c:categories)if(c.type.equals("both")||c.type.equals(direction)){available.add(c);labels.add(c.name);if(c.id==preferredCategory[0])selected=available.size();}
            setItems(category,labels.toArray(new String[0]),selected);};
        type.setOnItemSelectedListener(selected(updateCategories));updateCategories.run();
        Button newCategory=button("+ Nova categoria",colors.surfaceAlt,GREEN);newCategory.setOnClickListener(v->quickCategory(type.getSelectedItemPosition()==1?"income":"expense",created->{preferredCategory[0]=created.id;categories.clear();categories.addAll(store.categories());updateCategories.run();}));form.addView(newCategory);
        Spinner method=select(form,"Forma de pagamento",METHOD_NAMES,indexOf(METHOD_KEYS,e.method));
        List<Models.Card> cards=store.cards();ArrayList<String> cardLabels=new ArrayList<>();cardLabels.add("Selecione um cartão");int selectedCard=0;
        for(Models.Card c:cards){cardLabels.add(c.name+" · "+c.lastFour);if(c.id==e.cardId)selectedCard=cardLabels.size()-1;}
        Spinner card=select(form,"Cartão (somente para crédito)",cardLabels.toArray(new String[0]),selectedCard);
        TextView hint=text("PIX é identificado manualmente. Compras no crédito não alteram o saldo da conta. Para quitar o cartão, use uma saída com a forma “Pagamento de fatura”.",12,MUTED,false);form.addView(hint);
        dialog(original==null||e.id==0?"Novo lançamento":"Editar lançamento",form,()->{
            Models.Entry updated=new Models.Entry();updated.id=e.id;updated.description=description.getText().toString().trim();updated.amountCents=Money.parseCents(amount.getText().toString());
            updated.type=type.getSelectedItemPosition()==1?"income":"expense";updated.date=date.getText().toString();int selectedAccount=account.getSelectedItemPosition();updated.accountId=accounts.get(selectedAccount).id;
            updated.categoryId=category.getSelectedItemPosition()==0?0:available.get(category.getSelectedItemPosition()-1).id;
            int selectedMethod=method.getSelectedItemPosition();if(selectedMethod<0)throw new IllegalArgumentException("Escolha a forma de pagamento. Confira se foi crédito ou débito.");updated.method=METHOD_KEYS[selectedMethod];updated.cardId=updated.method.equals("credit")&&card.getSelectedItemPosition()>0?cards.get(card.getSelectedItemPosition()-1).id:0;
            if(updated.method.equals("credit")&&updated.cardId==0)throw new IllegalArgumentException("Selecione um cartão cadastrado para lançar no crédito.");
            if(updated.method.equals("card_payment")&&updated.type.equals("income"))throw new IllegalArgumentException("O pagamento de fatura deve ser uma saída.");
            updated.sourceKey=e.sourceKey;store.saveEntry(updated);month=YearMonth.from(LocalDate.parse(updated.date));},original==null||e.id==0?null:()->confirmDelete("Excluir este lançamento?",()->store.deleteEntry(e.id)));
    }

    private void editCategory(Models.Category old){LinearLayout form=form();EditText name=field(form,"Nome",old==null?"":old.name,InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        Spinner type=select(form,"Aplicar a",new String[]{"Saídas","Entradas","Ambas"},old==null?0:old.type.equals("income")?1:old.type.equals("both")?2:0);
        form.addView(text("Ao excluir, os lançamentos são preservados e ficam sem categoria. Alterar o tipo exige compatibilidade com os registros existentes.",12,MUTED,false));
        dialog(old==null?"Nova categoria":"Editar categoria",form,()->{Models.Category c=new Models.Category();c.id=old==null?0:old.id;c.name=name.getText().toString().trim();c.type=new String[]{"expense","income","both"}[type.getSelectedItemPosition()];store.saveCategory(c);},old==null?null:()->deleteCategory(old));}
    private void editAccount(Models.Account old){LinearLayout form=form();EditText name=field(form,"Nome da conta",old==null?"":old.name,InputType.TYPE_CLASS_TEXT);
        EditText opening=field(form,"Saldo inicial (R$)",old==null?"0,00":decimal(old.openingCents),InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL|InputType.TYPE_NUMBER_FLAG_SIGNED);
        form.addView(text("Transferências entre suas contas precisam de dois registros: uma saída na origem e uma entrada no destino. Exclua uma conta somente depois de remover ou mover os lançamentos.",12,MUTED,false));
        dialog(old==null?"Nova conta":"Editar conta",form,()->{Models.Account a=new Models.Account();a.id=old==null?0:old.id;a.name=name.getText().toString().trim();a.openingCents=Money.parseCents(opening.getText().toString());store.saveAccount(a);},old==null?null:()->confirmDelete("Excluir esta conta?",()->store.deleteAccount(old.id)));}
    private void editCard(Models.Card old){LinearLayout form=form();EditText name=field(form,"Nome (ex.: Meu cartão)",old==null?"":old.name,InputType.TYPE_CLASS_TEXT);
        EditText digits=field(form,"Últimos 4 dígitos",old==null?"":old.lastFour,InputType.TYPE_CLASS_NUMBER);digits.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(4)});
        EditText closing=field(form,"Dia do fechamento (1 a 31)",old==null?"":String.valueOf(old.closingDay),InputType.TYPE_CLASS_NUMBER);
        EditText due=field(form,"Dia do vencimento (1 a 31)",old==null?"":String.valueOf(old.dueDay),InputType.TYPE_CLASS_NUMBER);
        form.addView(text("Apenas identificação local. Não é uma conexão com o banco. Ao excluir, os lançamentos permanecem sem vínculo ao cartão.",12,MUTED,false));
        dialog(old==null?"Novo cartão":"Editar cartão",form,()->{Models.Card c=new Models.Card();c.id=old==null?0:old.id;c.name=name.getText().toString().trim();c.lastFour=digits.getText().toString();c.closingDay=number(closing);c.dueDay=number(due);store.saveCard(c);},old==null?null:()->confirmDelete("Excluir este cartão?",()->store.deleteCard(old.id)));}
    private void editInvestment(Models.Investment old){LinearLayout form=form();EditText name=field(form,"Nome do investimento",old==null?"":old.name,InputType.TYPE_CLASS_TEXT);
        EditText ticker=field(form,"Código / ticker (opcional)",old==null?"":old.ticker,InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        EditText kind=field(form,"Tipo (ex.: CDB, ação, Tesouro)",old==null?"":old.kind,InputType.TYPE_CLASS_TEXT);
        EditText quantity=field(form,"Quantidade (opcional)",old==null?"":old.quantity.replace('.',','),InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL);
        EditText invested=field(form,"Total aplicado (R$)",old==null?"":decimal(old.investedCents),InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL);
        EditText current=field(form,"Valor atual (R$)",old==null?"":decimal(old.currentCents),InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL);
        CheckBox automatic=new CheckBox(this);automatic.setText(R.string.quote_auto_position);automatic.setTextColor(TEXT);automatic.setContentDescription(getString(R.string.quote_auto_position));automatic.setMinHeight(dp(48));automatic.setChecked(old!=null&&old.autoQuote);addTo(form,automatic,0,12,0,8);
        form.addView(text("Informe o ticker e a quantidade, por exemplo PETR4 e 10, ou BTC-BRL e 0,01. A cotação recalcula quantidade × preço; o total aplicado permanece como custo. Configure a fonte e a atualização em Carteira → Cotações e atualização. Ativos sem cotação suportada continuam manuais.",12,MUTED,false));
        dialog(old==null?"Novo investimento":"Editar investimento",form,()->{
            Models.Investment i=new Models.Investment();i.id=old==null?0:old.id;i.name=name.getText().toString().trim();i.ticker=ticker.getText().toString().trim().toUpperCase(Locale.ROOT);i.kind=kind.getText().toString().trim();
            String exact=quantity.getText().toString().trim().replace(',','.');if(!exact.isEmpty()&&!exact.matches("[0-9]{1,24}(\\.[0-9]{1,8})?"))throw new IllegalArgumentException("Informe a quantidade sem separador de milhares, com até 8 casas decimais.");
            i.quantity=exact.isEmpty()?"":new java.math.BigDecimal(exact).stripTrailingZeros().toPlainString();i.autoQuote=automatic.isChecked();i.investedCents=invested.getText().toString().trim().isEmpty()?0:Money.parseCents(invested.getText().toString());i.currentCents=current.getText().toString().trim().isEmpty()?0:Money.parseCents(current.getText().toString());
            synchronized(store){if(old!=null&&i.ticker.equals(old.ticker)&&i.quantity.equals(old.quantity)&&i.currentCents==old.currentCents){for(Models.Investment fresh:store.investments())if(fresh.id==old.id&&fresh.ticker.equals(i.ticker)&&fresh.quantity.equals(i.quantity)){i.currentCents=fresh.currentCents;i.quotePrice=fresh.quotePrice;i.quoteAt=fresh.quoteAt;i.quoteSource=fresh.quoteSource;break;}}
                store.saveInvestment(i);}
        },old==null?null:()->confirmDelete("Excluir este investimento?",()->store.deleteInvestment(old.id)));}

    private void chooseDocument(int code){Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT);intent.addCategory(Intent.CATEGORY_OPENABLE);intent.setType("*/*");
        if(code==B3_IMPORT)intent.putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet","text/csv","text/comma-separated-values","application/octet-stream"});
        try{startActivityForResult(intent,code);}catch(android.content.ActivityNotFoundException ex){message("Nenhum seletor de arquivos disponível neste dispositivo.");}}
    private void createDocument(int code,String mime,String name){Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT);intent.addCategory(Intent.CATEGORY_OPENABLE);intent.setType(mime);intent.putExtra(Intent.EXTRA_TITLE,name);
        try{startActivityForResult(intent,code);}catch(android.content.ActivityNotFoundException ex){message("Nenhum seletor de arquivos disponível neste dispositivo.");}}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);
        if(request==REPORT_PDF||request==REPORT_CSV){PendingReport saved=pendingReport;pendingReport=null;if(result!=RESULT_OK||data==null||data.getData()==null)return;
            if(saved==null||saved.request!=request){message("O pedido de exportação foi interrompido. Volte a Relatórios e gere o arquivo novamente.");return;}
            Uri destination=data.getData();background(()->{try(OutputStream output=getContentResolver().openOutputStream(destination,"wt")){if(output==null)throw new IOException("Não foi possível abrir o destino.");if(request==REPORT_PDF)FinanceReportPdf.write(saved.report,output);else output.write(saved.report.toCsv().getBytes(StandardCharsets.UTF_8));}return "Relatório salvo.";},this::message);return;}
        if(result!=RESULT_OK||data==null||data.getData()==null)return;Uri uri=data.getData();
        if(request==IMPORT||request==RESTORE){background(()->readDocument(uri),text->{if(request==IMPORT)importOptions(text,fileName(uri));else restorePreview(text);});}
        else if(request==B3_IMPORT){background(()->{byte[] bytes=readDocumentBytes(uri);B3PositionImporter.Report report=B3PositionImporter.parse(bytes,fileName(uri));return new B3ImportResult(report,sha256(bytes));},value->b3Preview(value.report,value.hash));}
        else if(request==BACKUP||request==CSV){background(()->{String text=request==BACKUP?store.exportBackup():store.exportCsv();try(OutputStream out=getContentResolver().openOutputStream(uri,"wt")){if(out==null)throw new IOException("Não foi possível abrir o destino.");out.write(text.getBytes(StandardCharsets.UTF_8));}return "Arquivo salvo. Guarde-o em local seguro.";},this::message);}}
    private String fileName(Uri uri){try(Cursor c=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){if(c!=null&&c.moveToFirst())return c.getString(0);}catch(Exception ignored){}return "extrato";}
    private byte[] readDocumentBytes(Uri uri)throws IOException{try(InputStream in=getContentResolver().openInputStream(uri);ByteArrayOutputStream out=new ByteArrayOutputStream()){
        if(in==null)throw new IOException("Não foi possível ler o arquivo.");byte[] buffer=new byte[8192];int read;
        while((read=in.read(buffer))!=-1){if(out.size()+read>8*1024*1024)throw new IOException("O limite por arquivo é de 8 MB.");out.write(buffer,0,read);}return out.toByteArray();}}
    private String readDocument(Uri uri)throws IOException{byte[] bytes=readDocumentBytes(uri);
        try{return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();}catch(CharacterCodingException ex){return Charset.forName("windows-1252").decode(ByteBuffer.wrap(bytes)).toString();}}
    private static final class B3ImportResult{final B3PositionImporter.Report report;final String hash;B3ImportResult(B3PositionImporter.Report report,String hash){this.report=report;this.hash=hash;}}
    private static String sha256(byte[] bytes)throws java.security.NoSuchAlgorithmException{StringBuilder result=new StringBuilder();for(byte value:java.security.MessageDigest.getInstance("SHA-256").digest(bytes))result.append(String.format(Locale.ROOT,"%02x",value&255));return result.toString();}
    private void b3Preview(B3PositionImporter.Report report,String sourceHash){
        if(report.positions.isEmpty()){message("O relatório não contém posições para importar.");return;}
        LinearLayout form=form();long total=0;int missing=0;for(B3Portfolio.Position position:report.positions)if(position.currentCents==null)missing++;else total=Math.addExact(total,position.currentCents);
        form.addView(text(b3Count(report.positions.size())+(report.positions.size()==1?" encontrada":" encontradas"),17,TEXT,true));addTo(form,text(missing==report.positions.size()?"Valor atual não informado":"Valor informado"+(missing>0?" (parcial)":"")+": "+money(total),15,GREEN,true),0,9,0,4);
        if(missing>0)form.addView(text(b3Count(missing)+" sem valor informado. "+(missing==1?"Não será tratada":"Não serão tratadas")+" como valor zero.",12,MUTED,false));
        EditText date=field(form,"Data da posição (dd/MM/aaaa)",report.asOfDate.isEmpty()?"":b3Date(report.asOfDate),InputType.TYPE_CLASS_DATETIME|InputType.TYPE_DATETIME_VARIATION_DATE);
        date.setKeyListener(null);date.setFocusable(false);date.setHint("Toque para escolher a data");
        if(report.asOfDate.isEmpty()){date.setOnClickListener(v->{LocalDate initial=LocalDate.now();try{initial=LocalDate.parse(date.getText().toString(),DateTimeFormatter.ofPattern("dd/MM/uuuu").withResolverStyle(java.time.format.ResolverStyle.STRICT));}catch(java.time.DateTimeException ignored){}
            DatePickerDialog picker=new DatePickerDialog(this,(view,year,monthOfYear,day)->date.setText(LocalDate.of(year,monthOfYear+1,day).format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))),initial.getYear(),initial.getMonthValue()-1,initial.getDayOfMonth());picker.getDatePicker().setMaxDate(System.currentTimeMillis());picker.show();});}
        else date.setEnabled(false);
        addTo(form,text(report.asOfDate.isEmpty()?"Escolha a data de referência indicada no relatório. A data do download não é necessariamente a data da posição.":"Data de referência identificada no conteúdo do relatório.",12,MUTED,false),0,7,0,14);
        form.addView(text("Esta importação substitui somente o relatório B3 anterior, sem alterar seus investimentos manuais, saldo ou lançamentos. Um arquivo parcial continua sendo uma posição parcial; confira se selecionou todo o relatório desejado.",12,MUTED,false));
        addTo(form,text("Custo de aquisição e rentabilidade não são calculados com os valores de posição.",12,MUTED,false),0,10,0,12);
        for(int index=0;index<Math.min(10,report.positions.size());index++){B3Portfolio.Position position=report.positions.get(index);divider(form);addTo(form,text(position.product+(position.ticker.isEmpty()?"":" · "+position.ticker),14,TEXT,true),0,10,0,4);form.addView(text("Qtd. "+b3Quantity(position.quantity)+" · "+(position.currentCents==null?"Valor não informado":money(position.currentCents)),12,MUTED,false));}
        if(report.positions.size()>10)addTo(form,text("Amostra das primeiras 10 posições. A lista completa estará disponível após confirmar.",12,MUTED,false),0,10,0,0);
        AlertDialog preview=new AlertDialog.Builder(this).setTitle("Revisar posição B3").setView(wrap(form)).setNegativeButton("Cancelar",null).setPositiveButton("Confirmar importação",null).create();
        preview.setOnShowListener(dialog->preview.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view->{
            LocalDate asOf;try{asOf=LocalDate.parse(date.getText().toString(),DateTimeFormatter.ofPattern("dd/MM/uuuu").withResolverStyle(java.time.format.ResolverStyle.STRICT));}catch(java.time.DateTimeException error){message("Escolha uma data da posição válida antes de confirmar.");return;}
            if(asOf.isAfter(LocalDate.now())){message("A data da posição não pode estar no futuro.");return;}
            Runnable save=()->{preview.dismiss();background(()->{store.replaceB3Snapshot(report.positions,asOf.toString(),sourceHash);return "Posição B3 importada. Seus registros manuais foram preservados.";},result->{b3Search="";visibleB3Positions=PAGE_SIZE;pageScroll[6]=0;page=6;render();message(result);});};
            B3Portfolio.Snapshot previous=store.b3Snapshot();
            if(previous!=null&&asOf.toString().compareTo(previous.asOfDate)<0)new AlertDialog.Builder(this).setTitle("Substituir por posição anterior?").setMessage("O relatório salvo é de "+b3Date(previous.asOfDate)+". O selecionado é mais antigo e substituirá essa posição B3.").setNegativeButton("Cancelar",null).setPositiveButton("Substituir",(d,w)->save.run()).show();else save.run();
        }));preview.show();
    }
    private interface Job<T>{T run()throws Exception;} private interface Result<T>{void accept(T result);}
    private <T> void background(Job<T> job,Result<T> done){AlertDialog progress=new AlertDialog.Builder(this).setMessage("Processando arquivo…").setCancelable(false).create();activeProgress=progress;progress.show();worker.execute(()->{try{T value=job.run();runOnUiThread(()->{if(!isFinishing()&&!isDestroyed()){progress.dismiss();activeProgress=null;try{done.accept(value);}catch(Exception ex){message(safeError(ex));}}});}catch(Exception ex){runOnUiThread(()->{if(!isFinishing()&&!isDestroyed()){progress.dismiss();activeProgress=null;message(safeError(ex));}});}});}

    private void importOptions(String source,String filename){List<Models.Account> accounts=store.accounts();List<Models.Card> cards=store.cards();
        if(accounts.isEmpty()){message("Cadastre uma conta antes de importar.");return;}LinearLayout form=form();form.addView(text(filename,14,GREEN,true));
        Spinner account=select(form,"Conta de destino",accountNames(accounts),0);CheckBox credit=new CheckBox(this);credit.setText(R.string.credit_statement);credit.setTextColor(TEXT);credit.setChecked(source.toUpperCase(Locale.ROOT).contains("<CCSTMTRS"));form.addView(credit);
        ArrayList<String> cardNames=new ArrayList<>();cardNames.add("Selecione se for cartão");for(Models.Card c:cards)cardNames.add(c.name+" · "+c.lastFour);
        Spinner card=select(form,"Cartão",cardNames.toArray(new String[0]),0);Spinner category=select(form,"Categoria quando não identificada",categoryLabels(store.categories()),0);
        form.addView(text("CSV: data, descrição e valor; despesas negativas e entradas positivas. Em CSV de cartão, despesas positivas e estornos negativos. OFX segue os sinais do banco. Categorias do arquivo são associadas por nome; nenhuma conexão bancária é feita.",12,MUTED,false));
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Importar extrato").setView(wrap(form)).setNegativeButton("Cancelar",null).setPositiveButton("Ver prévia",null).create();dialog.setOnShowListener(d->dialog.getButton(-1).setOnClickListener(v->{
            if(credit.isChecked()&&card.getSelectedItemPosition()==0){message("Selecione um cartão cadastrado para este extrato.");return;}
            long accountId=accounts.get(account.getSelectedItemPosition()).id;long cardId=credit.isChecked()?cards.get(card.getSelectedItemPosition()-1).id:0;
            List<Models.Category> all=store.categories();long categoryId=category.getSelectedItemPosition()==0?0:all.get(category.getSelectedItemPosition()-1).id;
            boolean isCredit=credit.isChecked();dialog.dismiss();background(()->StatementImporter.parse(source,filename,isCredit),rows->importPreview(rows,accountId,cardId,categoryId));}));dialog.show();}
    private void importPreview(List<StatementImporter.Row> rows,long accountId,long cardId,long defaultCategory){
        if(rows.isEmpty()){message("O extrato não contém transações para importar.");return;}LinearLayout form=form();long incoming=0,outgoing=0;ArrayList<Models.Entry> entries=new ArrayList<>();List<Models.Category> categories=store.categories();
        for(StatementImporter.Row row:rows){Models.Entry e=new Models.Entry();e.accountId=accountId;e.cardId=cardId;e.description=row.description;e.date=row.date;e.type=row.type;e.amountCents=row.amountCents;e.sourceKey=row.sourceKey;e.method=cardId==0?"other":"credit";
            long chosen=0;for(Models.Category c:categories)if(c.id==defaultCategory&&(c.type.equals("both")||c.type.equals(e.type)))chosen=c.id;
            if(row.suggestedCategory!=null&&!row.suggestedCategory.isEmpty())for(Models.Category c:categories)if(normalize(c.name).equals(normalize(row.suggestedCategory))&&(c.type.equals("both")||c.type.equals(e.type)))chosen=c.id;
            e.categoryId=chosen;entries.add(e);if(e.type.equals("income"))incoming=Math.addExact(incoming,e.amountCents);else outgoing=Math.addExact(outgoing,e.amountCents);}
        String firstDate=entries.get(0).date,lastDate=firstDate;for(Models.Entry entry:entries){if(entry.date.compareTo(firstDate)<0)firstDate=entry.date;if(entry.date.compareTo(lastDate)>0)lastDate=entry.date;}
        final YearMonth importMonth=YearMonth.from(LocalDate.parse(lastDate));
        form.addView(text(rows.size()+" lançamentos encontrados",19,GREEN,true));addTo(form,text("Destino: "+accountName(accountId)+(cardId==0?"":" · "+cardName(cardId)),12,GREEN,true),0,10,0,0);addTo(form,text("Entradas: "+money(incoming)+"\nSaídas: "+money(outgoing),14,TEXT,false),0,12,0,14);
        DateTimeFormatter fullDate=DateTimeFormatter.ofPattern("dd/MM/yyyy");addTo(form,text("Período: "+LocalDate.parse(firstDate).format(fullDate)+" a "+LocalDate.parse(lastDate).format(fullDate),12,MUTED,false),0,0,0,12);
        form.addView(text("Revise a amostra abaixo. Duplicatas conhecidas na mesma conta serão ignoradas. Registros sem categoria podem ser editados após importar. Revise pagamentos de fatura e transferências próprias após importar. Arquivos diferentes sem identificador bancário podem exigir revisão manual de duplicatas.",12,MUTED,false));
        for(int i=0;i<Math.min(12,entries.size());i++){Models.Entry e=entries.get(i);addTo(form,text(displayDate(e.date)+" · "+e.description+"\n"+(e.type.equals("income")?"+ ":"− ")+money(e.amountCents)+" · "+categoryName(e.categoryId),12,TEXT,false),0,14,0,0);}
        if(entries.size()>12)addTo(form,text("Amostra: 12 de "+entries.size()+" registros.",12,MUTED,false),0,12,0,0);
        new AlertDialog.Builder(this).setTitle("Revisar importação").setView(wrap(form)).setNegativeButton("Cancelar",null).setPositiveButton("Importar "+entries.size(),(d,w)->background(()->store.importEntries(entries),count->{month=importMonth;filter=0;search="";visibleEntries=PAGE_SIZE;if(renderedPage==1&&screenScroll!=null)screenScroll.scrollTo(0,0);pageScroll[1]=0;page=1;render();message(count+" lançamentos importados. "+(entries.size()-count)+" duplicatas ignoradas.");})).show();
    }
    private void restorePreview(String source){new AlertDialog.Builder(this).setTitle("Substituir todos os dados?").setMessage("Esta operação substitui contas, categorias, cartões, lançamentos, investimentos e a posição B3. Um backup antigo sem posição B3 remove o relatório atual. Um arquivo inválido não altera os dados atuais. Confirme somente se guardou uma cópia dos dados atuais.").setNegativeButton("Cancelar",null).setPositiveButton("Restaurar",(d,w)->background(()->{store.restoreBackup(source);return "Backup restaurado.";},result->{render();message(result);})).show();}

    private void dialog(String title,LinearLayout form,Runnable save,Runnable delete){AlertDialog.Builder builder=new AlertDialog.Builder(this).setTitle(title).setView(wrap(form)).setNegativeButton("Cancelar",null).setPositiveButton("Salvar",null);if(delete!=null)builder.setNeutralButton("Excluir",(d,w)->delete.run());AlertDialog d=builder.create();
        d.setOnShowListener(x->d.getButton(-1).setOnClickListener(v->{try{save.run();d.dismiss();render();}catch(Exception ex){message(safeError(ex));}}));d.show();}
    private void confirmDelete(String title,Runnable operation){new AlertDialog.Builder(this).setTitle(title).setMessage("Esta ação não pode ser desfeita sem restaurar um backup.").setNegativeButton("Cancelar",null).setPositiveButton("Excluir",(d,w)->{try{operation.run();render();}catch(Exception ex){message(safeError(ex));}}).show();}
    private void message(String message){AlertDialog d=new AlertDialog.Builder(this).setTitle("MyFinanceHub").setMessage(message).setPositiveButton("OK",null).show();TextView body=d.findViewById(android.R.id.message);if(body!=null)body.setTextIsSelectable(true);}
    private String appVersion(){try{return getPackageManager().getPackageInfo(getPackageName(),0).versionName;}catch(android.content.pm.PackageManager.NameNotFoundException ex){return "—";}}
    private String safeError(Exception ex){return ex.getMessage()==null?"Não foi possível concluir a operação. Confira os dados e tente novamente.":ex.getMessage();}
    private int number(EditText edit){try{return Integer.parseInt(edit.getText().toString().trim());}catch(NumberFormatException ex){throw new IllegalArgumentException("Informe um dia válido entre 1 e 31.");}}

    private List<Models.Entry> monthlyEntries(){ArrayList<Models.Entry> result=new ArrayList<>();String prefix=month.toString();for(Models.Entry e:entryCache)if(e.date.startsWith(prefix))result.add(e);return result;}
    private String categoryName(long id){for(Models.Category c:categoryCache)if(c.id==id)return c.name;return "Sem categoria";}
    private String accountName(long id){for(Models.Account a:accountCache)if(a.id==id)return a.name;return "Conta";}
    private String cardName(long id){for(Models.Card c:cardCache)if(c.id==id)return c.name+" •"+c.lastFour;return "Cartão removido";}
    private String methodName(String key){int i=indexOf(METHOD_KEYS,key);return METHOD_NAMES[i];}
    private String money(long cents){return hidden?"R$ ••••":Money.format(cents);}
    private String decimal(long cents){return java.math.BigDecimal.valueOf(cents,2).toPlainString().replace('.',',');}
    private String normalize(String text){return java.text.Normalizer.normalize(text==null?"":text,java.text.Normalizer.Form.NFD).replaceAll("\\p{M}","").toLowerCase(Locale.ROOT).trim();}
    private String displayDate(String date){try{return LocalDate.parse(date).format(DateTimeFormatter.ofPattern("dd/MM"));}catch(Exception ex){return date;}}
    private String[] accountNames(List<Models.Account> list){String[] result=new String[list.size()];for(int i=0;i<list.size();i++)result[i]=list.get(i).name;return result;}
    private String[] categoryLabels(List<Models.Category> list){String[] result=new String[list.size()+1];result[0]="Sem categoria";for(int i=0;i<list.size();i++)result[i+1]=list.get(i).name;return result;}
    private int indexAccount(List<Models.Account> list,long id){for(int i=0;i<list.size();i++)if(list.get(i).id==id)return i;return 0;}
    private int indexOf(String[] values,String item){for(int i=0;i<values.length;i++)if(values[i].equals(item))return i;return 0;}
    private AdapterView.OnItemSelectedListener selected(Runnable r){return new AdapterView.OnItemSelectedListener(){public void onNothingSelected(AdapterView<?> p){}public void onItemSelected(AdapterView<?> p,View v,int i,long id){r.run();}};}

    private void entryRow(LinearLayout parent,Models.Entry e){LinearLayout line=row();line.setPadding(0,dp(13),0,dp(13));TextView icon=text(e.type.equals("income")?"↗":"↘",21,e.type.equals("income")?GREEN:RED,true);icon.setGravity(Gravity.CENTER);icon.setBackground(round(e.type.equals("income")?colors.incomeSurface:colors.expenseSurface,13));line.addView(icon,new LinearLayout.LayoutParams(dp(39),dp(41)));
        LinearLayout description=column();TextView label=text(e.description,14,TEXT,true);label.setMaxLines(2);description.addView(label);String details=displayDate(e.date)+" · "+categoryName(e.categoryId)+"\n"+(e.method.equals("credit")?cardName(e.cardId):accountName(e.accountId))+" · "+methodName(e.method);
        addTo(description,text(details,12,MUTED,false),0,4,0,0);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,1);lp.setMargins(dp(10),0,dp(8),0);line.addView(description,lp);
        TextView amount=text((e.type.equals("income")?"+ ":"− ")+money(e.amountCents),13,e.type.equals("income")?GREEN:TEXT,true);line.addView(amount);line.setContentDescription(e.description+", "+details+", "+(e.type.equals("income")?"entrada ":"saída ")+money(e.amountCents));line.setBackground(feedback(WHITE,10));line.setOnClickListener(v->editEntry(e));parent.addView(line);divider(parent);}
    private void monthSelector(){LinearLayout row=row();row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(0,dp(12),0,dp(12));Button back=button("‹",BG,GREEN);back.setContentDescription("Mês anterior");back.setOnClickListener(v->{month=month.minusMonths(1);render();});row.addView(back,new LinearLayout.LayoutParams(dp(48),dp(48)));
        TextView monthLabel=text(month.format(DateTimeFormatter.ofPattern("MMMM 'de' yyyy",new Locale("pt","BR"))),14,TEXT,true);monthLabel.setGravity(Gravity.CENTER);row.addView(monthLabel,new LinearLayout.LayoutParams(0,-2,1));Button next=button("›",BG,GREEN);next.setContentDescription("Próximo mês");next.setOnClickListener(v->{month=month.plusMonths(1);render();});row.addView(next,new LinearLayout.LayoutParams(dp(48),dp(48)));content.addView(row);}
    private LinearLayout metric(String label,long value,int color){LinearLayout card=box(WHITE);card.addView(text(label,12,color,true));addTo(card,text(money(value),20,TEXT,true),0,12,0,0);return card;}
    private void action(String title,String subtitle,Runnable run){LinearLayout card=box(WHITE);card.addView(text(title+"  →",14,GREEN,true));addTo(card,text(subtitle,12,MUTED,false),0,6,0,0);card.setContentDescription(title);card.setBackground(feedback(WHITE,20));card.setOnClickListener(v->run.run());add(card,0,0,0,10);}
    private void eyebrow(String s){TextView t=text(s,11,MUTED,true);t.setLetterSpacing(.14f);content.addView(t);}
    private void title(String s){add(text(s,29,TEXT,true),0,8,0,0);}
    private void section(String title,String subtitle){add(text(title,19,TEXT,true),0,25,0,4);add(text(subtitle,12,MUTED,false),0,0,0,13);}
    private void empty(LinearLayout container,String heading,String body){TextView mark=text("＋",27,GREEN,false);addTo(container,mark,0,2,0,10);container.addView(text(heading,15,TEXT,true));addTo(container,text(body,12,MUTED,false),0,8,0,6);}
    private LinearLayout form(){LinearLayout form=column();form.setPadding(dp(24),dp(8),dp(24),dp(16));return form;}
    private ScrollView wrap(LinearLayout view){ScrollView scroll=new ScrollView(this);scroll.addView(view);return scroll;}
    private EditText field(LinearLayout form,String label,String value,int input){addTo(form,text(label,12,MUTED,true),0,15,0,6);EditText edit=input(label,value,input);form.addView(edit,new LinearLayout.LayoutParams(-1,-2));return edit;}
    private EditText input(String hint,String value,int type){EditText edit=new EditText(this);edit.setTextSize(15);edit.setTextColor(TEXT);edit.setHintTextColor(MUTED);edit.setHint(hint);edit.setContentDescription(hint);edit.setInputType(type);edit.setSingleLine(true);edit.setMinHeight(dp(48));edit.setText(value);edit.setBackground(inputBackground());edit.setPadding(dp(12),dp(8),dp(12),dp(8));return edit;}
    private Spinner select(LinearLayout form,String label,String[] values,int selected){addTo(form,text(label,12,MUTED,true),0,15,0,6);Spinner spinner=new Spinner(this);spinner.setContentDescription(label);spinner.setMinimumHeight(dp(48));setItems(spinner,values,selected);form.addView(spinner,new LinearLayout.LayoutParams(-1,-2));return spinner;}
    private void setItems(Spinner spinner,String[] values,int selected){ArrayAdapter<String> adapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,values);spinner.setAdapter(adapter);spinner.setSelection(Math.max(0,Math.min(selected,values.length-1)));}
    private void divider(LinearLayout parent){View line=new View(this);line.setBackgroundColor(LINE);parent.addView(line,new LinearLayout.LayoutParams(-1,dp(1)));}
    private LinearLayout row(){LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.HORIZONTAL);row.setGravity(Gravity.CENTER_VERTICAL);return row;}
    private LinearLayout column(){LinearLayout col=new LinearLayout(this);col.setOrientation(LinearLayout.VERTICAL);return col;}
    private LinearLayout box(int color){LinearLayout box=column();box.setBackground(round(color,20));box.setPadding(dp(18),dp(18),dp(18),dp(18));return box;}
    private TextView text(String s,int size,int color,boolean bold){TextView text=new TextView(this);text.setText(s);text.setTextSize(size);text.setTextColor(color);text.setFontFeatureSettings("tnum");if(bold)text.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));text.setLineSpacing(dp(2),1);return text;}
    private Button button(String label,int bg,int fg){Button b=new Button(this);b.setText(label);b.setTextSize(13);b.setTextColor(fg);b.setAllCaps(false);b.setContentDescription(label);b.setMinHeight(dp(48));b.setMinimumHeight(dp(48));b.setPadding(dp(12),dp(6),dp(12),dp(6));b.setBackground(feedback(bg,13));return b;}
    private RippleDrawable feedback(int color,int radius){return new RippleDrawable(ColorStateList.valueOf(darkTheme?0x33FFFFFF:0x1F163A32),round(color,radius),round(Color.WHITE,radius));}
    private android.graphics.drawable.StateListDrawable inputBackground(){android.graphics.drawable.StateListDrawable states=new android.graphics.drawable.StateListDrawable();GradientDrawable focus=round(colors.surfaceAlt,10);focus.setStroke(dp(2),GREEN);GradientDrawable normal=round(colors.surfaceAlt,10);normal.setStroke(dp(1),LINE);states.addState(new int[]{android.R.attr.state_focused},focus);states.addState(new int[]{},normal);return states;}
    private GradientDrawable round(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private void add(View view,int l,int t,int r,int b){addTo(content,view,l,t,r,b);}
    private void addTo(LinearLayout parent,View view,int l,int t,int r,int b){LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(dp(l),dp(t),dp(r),dp(b));parent.addView(view,lp);}

    private static final class NavIcon extends View {
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);private final int kind,color;
        NavIcon(android.content.Context context,int kind,int color){super(context);this.kind=kind;this.color=color;setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}
        @Override protected void onDraw(Canvas c){super.onDraw(c);float s=getWidth()/24f;c.save();c.scale(s,s);paint.setColor(color);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(1.7f);paint.setStrokeCap(Paint.Cap.ROUND);
            if(kind==0){c.drawRoundRect(3,3,10,10,2,2,paint);c.drawRoundRect(14,3,21,10,2,2,paint);c.drawRoundRect(3,14,10,21,2,2,paint);c.drawRoundRect(14,14,21,21,2,2,paint);}
            else if(kind==1){c.drawLine(5,5,19,5,paint);c.drawLine(5,12,19,12,paint);c.drawLine(5,19,15,19,paint);}
            else if(kind==2){c.drawRoundRect(2,5,22,20,3,3,paint);c.drawRoundRect(15,10,22,16,2,2,paint);c.drawCircle(18,13,.8f,paint);}
            else if(kind==7){c.drawLine(4,20,4,5,paint);c.drawLine(4,20,21,20,paint);c.drawLine(9,16,9,11,paint);c.drawLine(14,16,14,6,paint);c.drawLine(19,16,19,3,paint);}
            else if(kind==4){c.drawCircle(6,6,3,paint);c.drawCircle(18,6,3,paint);c.drawCircle(6,18,3,paint);c.drawCircle(18,18,3,paint);}
            else{c.drawCircle(12,12,5,paint);for(int i=0;i<8;i++){double a=Math.PI*i/4;c.drawLine(12+(float)Math.cos(a)*8,12+(float)Math.sin(a)*8,12+(float)Math.cos(a)*10,12+(float)Math.sin(a)*10,paint);}}c.restore();}
    }
}
