package br.com.myfinancehub;

import android.app.*;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.*;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import android.graphics.drawable.GradientDrawable;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;

/** Native account/transaction dashboard backed by the personal Pluggy API. */
public final class PluggyActivity extends LockedActivity {
    private static final long CONNECT_TOKEN_LIFETIME_MILLIS=30*60*1000L;
    private AppAppearance.Palette colors;
    private FinanceStore store;
    PersonalPluggySettings settings;
    PluggyPersonalClient client=new PluggyPersonalClient();
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private Future<?> task;private int generation;private boolean busy,destroyed,entryConsulted,updatingFilters,generatingConnectToken;
    private final Handler tokenHandler=new Handler(Looper.getMainLooper());
    private AlertDialog connectTokenDialog;
    private TextView connectTokenValue,connectTokenLifetime;
    private Button copyConnectToken;
    private long connectTokenDeadline;
    private final Runnable tokenExpiry=this::expireConnectToken;
    private final BroadcastReceiver screenOff=new BroadcastReceiver(){@Override public void onReceive(Context context,Intent intent){dismissConnectToken();cancel();}};
    private LinearLayout header,accountCards;
    private ListView list;private TextView status,summary,emptyState,dataRange;
    private EditText from,to,search;
    private Button fetch,configure,connectionDetails;
    private Spinner accountFilter,statusFilter;
    private PluggyClient.Snapshot snapshot;
    private final List<PluggyClient.Transaction> filteredRows=new ArrayList<>();
    @Override public void onCreate(Bundle state){
        setTheme(AppAppearance.themeRes(this));super.onCreate(state);colors=AppAppearance.palette(this);store=new FinanceStore(this);settings=new PersonalPluggySettings(this);
        if(Build.VERSION.SDK_INT>=33)registerReceiver(screenOff,new IntentFilter(Intent.ACTION_SCREEN_OFF),Context.RECEIVER_NOT_EXPORTED);else registerReceiver(screenOff,new IntentFilter(Intent.ACTION_SCREEN_OFF));
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        LinearLayout root=column();root.setBackgroundColor(colors.background);
        if(Build.VERSION.SDK_INT>=30){getWindow().setDecorFitsSystemWindows(false);root.setOnApplyWindowInsetsListener((v,insets)->{android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());v.setPadding(bars.left,bars.top,bars.right,bars.bottom);return insets;});}else root.setFitsSystemWindows(true);
        list=new ListView(this);list.setContentDescription("Transações Meu Pluggy");list.setDivider(null);header=column();header.setPadding(dp(22),dp(16),dp(22),dp(20));list.addHeaderView(header,null,false);root.addView(list,new LinearLayout.LayoutParams(-1,-1));setContentView(root);
        getWindow().setStatusBarColor(colors.background);getWindow().setNavigationBarColor(colors.background);
        Button back=button("‹ Voltar");header.addView(back);back.setOnClickListener(v->finish());
        label(header,"FINANÇAS CONECTADAS",12);label(header,"Suas contas, no app",27);
        label(header,"Consulte saldos e movimentações pela API Meu Pluggy. Revise cada lançamento antes de salvar.",14);
        LinearLayout actions=new LinearLayout(this);actions.setOrientation(LinearLayout.HORIZONTAL);
        fetch=button("Atualizar dados");fetch.setOnClickListener(v->{if(settings.configured())query();else showConnectionSettings();});actions.addView(fetch,new LinearLayout.LayoutParams(0,-2,1));
        configure=button("Configurar conexão");configure.setOnClickListener(v->showConnectionSettings());actions.addView(configure,new LinearLayout.LayoutParams(0,-2,1));header.addView(actions);
        Button connectionHelp=button("Onde encontrar os dados da conexão");connectionHelp.setOnClickListener(v->showConnectionGuide());header.addView(connectionHelp);
        status=label(header,settings.configured()?getString(R.string.pluggy_ready_native):getString(R.string.pluggy_connect_native),14);status.setContentDescription("Estado da consulta Meu Pluggy");
        label(header,"Contas e cartões",19);
        HorizontalScrollView accountsScroll=new HorizontalScrollView(this);accountsScroll.setHorizontalScrollBarEnabled(false);accountCards=new LinearLayout(this);accountCards.setOrientation(LinearLayout.HORIZONTAL);accountCards.setContentDescription("Contas consultadas pela API");accountsScroll.addView(accountCards);header.addView(accountsScroll);
        emptyState=label(header,getString(R.string.pluggy_empty_native),14);emptyState.setContentDescription("Resultado da consulta Meu Pluggy");
        LocalDate today=LocalDate.now(ZoneId.of("America/Sao_Paulo"));
        LinearLayout dates=new LinearLayout(this);dates.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout left=column(),right=column();from=field(left,"Data inicial",today.minusDays(29).toString(),InputType.TYPE_NULL);to=field(right,"Data final",today.toString(),InputType.TYPE_NULL);dates.addView(left,new LinearLayout.LayoutParams(0,-2,1));dates.addView(right,new LinearLayout.LayoutParams(0,-2,1));header.addView(dates);datePicker(from);datePicker(to);
        label(header,"Até 90 dias. O horário do banco pode ser anterior ao da consulta; consultar não força uma atualização na instituição.",12);
        connectionDetails=button("Detalhes das conexões");connectionDetails.setEnabled(false);connectionDetails.setOnClickListener(v->{if(canConfirm()&&snapshot!=null)new AlertDialog.Builder(this).setTitle("Atualização das conexões").setMessage(String.join("\n\n",snapshot.connections)).setPositiveButton("Fechar",null).show();});header.addView(connectionDetails);
        label(header,"Movimentações",20);summary=label(header,"",13);summary.setContentDescription("Resumo de transações Meu Pluggy");dataRange=label(header,"",12);dataRange.setContentDescription("Período dos dados consultados");
        accountFilter=spinner(header,"Filtrar conta",Collections.singletonList("Todas as contas"));
        statusFilter=spinner(header,"Filtrar situação",Arrays.asList("Todas as movimentações","Para revisar","Pendentes","Já salvas"));
        search=field(header,"Buscar movimentação","",InputType.TYPE_CLASS_TEXT);
        android.widget.AdapterView.OnItemSelectedListener listener=new android.widget.AdapterView.OnItemSelectedListener(){public void onNothingSelected(android.widget.AdapterView<?> parent){}public void onItemSelected(android.widget.AdapterView<?> parent,View view,int position,long id){if(!updatingFilters)renderRows();}};
        accountFilter.setOnItemSelectedListener(listener);statusFilter.setOnItemSelectedListener(listener);
        search.addTextChangedListener(new android.text.TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int count,int after){}public void afterTextChanged(android.text.Editable text){}public void onTextChanged(CharSequence s,int start,int before,int count){renderRows();}});
        list.setOnItemClickListener((parent,view,position,id)->{int index=position-list.getHeaderViewsCount();if(index>=0&&index<filteredRows.size())review(filteredRows.get(index));});renderRows();
    }
    private void datePicker(EditText input){input.setFocusable(false);input.setOnClickListener(v->{LocalDate day=LocalDate.parse(input.getText().toString());new DatePickerDialog(this,(picker,year,month,date)->input.setText(LocalDate.of(year,month+1,date).toString()),day.getYear(),day.getMonthValue()-1,day.getDayOfMonth()).show();});}
    void showConnectionSettings(){
        if(busy||!canConfirm())return;
        LinearLayout form=column();form.setPadding(dp(22),dp(8),dp(22),dp(18));
        label(form,"Conecte sua API pessoal uma vez. Contas, saldos e movimentações serão consultados e exibidos nesta tela.",14);
        EditText origin=field(form,"Client ID pessoal","",InputType.TYPE_CLASS_TEXT),token=field(form,"Client Secret pessoal","",InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD),itemIds=field(form,"IDs das conexões MeuPluggy (separados por vírgula)","",InputType.TYPE_CLASS_TEXT);
        for(EditText value:new EditText[]{origin,token,itemIds}){value.setSaveEnabled(false);value.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);value.setFilterTouchesWhenObscured(true);}
        label(form,"Use as credenciais da sua aplicação pessoal e os IDs das conexões espelhadas pelo conector MeuPluggy. Não use a senha do banco ou do portal. As credenciais ficam protegidas neste aparelho.",13);
        if(settings.configured())label(form,"Deixe os três campos vazios para manter a conexão. Para trocar as credenciais, preencha os três juntos.",13);
        CheckBox automatic=new CheckBox(this);automatic.setText(R.string.pluggy_consult_entry);automatic.setTextColor(colors.text);automatic.setChecked(!settings.configured()||settings.consultOnEntry());form.addView(automatic);
        Button help=button("Onde encontrar os dados da conexão");help.setOnClickListener(v->showConnectionGuide());form.addView(help);
        Button generate=button("Gerar Connect Token");form.addView(generate);
        label(form,"Gera um token temporário para o Pluggy Connect, usando somente Client ID e Client Secret. Não precisa dos IDs para gerar. O token não conecta bancos sozinho nem substitui a chave usada para consultar transações.",13);
        ScrollView scroll=new ScrollView(this);scroll.addView(form);
        AlertDialog.Builder builder=new AlertDialog.Builder(this).setTitle("Conectar API Meu Pluggy").setView(scroll).setNegativeButton("Cancelar",null).setPositiveButton("Salvar e consultar",null);
        if(settings.configured())builder.setNeutralButton("Desconectar",(d,w)->confirmDisconnect());AlertDialog dialog=builder.create();
        generate.setOnClickListener(v->generateConnectToken(origin,token,dialog));
        dialog.setOnShowListener(d->dialog.getButton(-1).setOnClickListener(v->{try{
            if(busy)return;
            if(!canConfirm()){token.setText("");origin.setText("");itemIds.setText("");dialog.dismiss();return;}
            String id=origin.getText().toString().trim(),secret=token.getText().toString(),ids=itemIds.getText().toString().trim();
            if(id.isEmpty()&&secret.isEmpty()&&ids.isEmpty()){if(!settings.configured())throw new IllegalArgumentException("Preencha Client ID, Client Secret e os IDs das conexões.");}
            else{if(id.isEmpty()||secret.isEmpty()||ids.isEmpty())throw new IllegalArgumentException("Preencha os três campos para alterar a conexão.");settings.save(id,secret,PluggyPersonalClient.itemIds(ids));}
            settings.setConsultOnEntry(automatic.isChecked());token.setText("");origin.setText("");itemIds.setText("");dialog.dismiss();entryConsulted=true;query();
        }catch(Exception ex){error(ex.getMessage());}}));dialog.show();
    }
    private void generateConnectToken(EditText origin,EditText secretField,AlertDialog configuration){
        if(busy||!canConfirm())return;
        String id=origin.getText().toString().trim(),secret=secretField.getText().toString();
        if(id.isEmpty()&&secret.isEmpty()){
            if(!settings.configured()){error("Preencha Client ID e Client Secret para gerar o Connect Token. Os IDs das conexões não são necessários nesta etapa.");return;}
            try{PersonalPluggySettings.Credentials saved=settings.load();id=saved.clientId;secret=saved.clientSecret;}
            catch(Exception ex){error("Não foi possível abrir as credenciais salvas. Informe Client ID e Client Secret novamente.");return;}
        }else if(id.isEmpty()||secret.isEmpty()){error("Preencha Client ID e Client Secret juntos, ou deixe os dois vazios para usar a conexão salva.");return;}
        try{PluggyPersonalClient.validateCredentials(id,secret);}
        catch(IllegalArgumentException ex){error("Confira Client ID e Client Secret. Não use a senha do banco ou do portal.");return;}
        String clientId=id,clientSecret=secret;long started=SystemClock.elapsedRealtime(),session=AuthSession.generation();
        origin.setText("");secretField.setText("");configuration.dismiss();dismissConnectToken();
        setBusy(true);generatingConnectToken=true;status.setText(R.string.pluggy_token_generating);int request=++generation;
        task=worker.submit(()->{try{String token=client.createConnectToken(clientId,clientSecret);runOnUiThread(()->{
            if(destroyed||generation!=request||session!=AuthSession.generation()||!canConfirm())return;
            setBusy(false);generatingConnectToken=false;status.setText(R.string.pluggy_token_generated);showConnectToken(token,started);
        });}catch(Exception ex){runOnUiThread(()->{
            if(destroyed||generation!=request||session!=AuthSession.generation()||!canConfirm())return;
            setBusy(false);generatingConnectToken=false;status.setText(R.string.pluggy_token_failed);error("Não foi possível gerar o Connect Token. Confira suas credenciais, sua conexão com a internet e tente novamente.");
        });}});
    }
    private void showConnectToken(String token,long started){
        if(!canConfirm())return;
        long now=SystemClock.elapsedRealtime();
        if(started<0||now<started||now-started>=CONNECT_TOKEN_LIFETIME_MILLIS||started>Long.MAX_VALUE-CONNECT_TOKEN_LIFETIME_MILLIS){error("O prazo desta solicitação terminou. Gere outro Connect Token.");return;}
        dismissConnectToken();connectTokenDeadline=started+CONNECT_TOKEN_LIFETIME_MILLIS;
        LinearLayout body=column();body.setPadding(dp(22),dp(12),dp(22),dp(18));body.setSaveFromParentEnabled(false);
        label(body,"Este token temporário serve para iniciar o Pluggy Connect. Não é a API Key de consulta e não conecta seus bancos sozinho. Nenhum lançamento foi salvo.",14);
        connectTokenLifetime=label(body,"Validade máxima de 30 minutos a partir do início da solicitação. O token desaparece ao fechar esta janela ou sair da tela. Copie somente se precisar e guarde em local privado.",13);
        // Copy only through the guarded button, so keyboard/selection menus cannot bypass
        // the expiry check or omit the sensitive-clipboard marker.
        connectTokenValue=label(body,token,13);connectTokenValue.setContentDescription("Connect Token gerado");connectTokenValue.setTextIsSelectable(false);connectTokenValue.setSaveEnabled(false);connectTokenValue.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);connectTokenValue.setFilterTouchesWhenObscured(true);
        copyConnectToken=button("Copiar Connect Token");copyConnectToken.setFilterTouchesWhenObscured(true);copyConnectToken.setOnClickListener(v->copyCurrentConnectToken());body.addView(copyConnectToken);
        ScrollView scroll=new ScrollView(this);scroll.addView(body);AlertDialog shown=new AlertDialog.Builder(this).setTitle("Connect Token temporário").setView(scroll).setPositiveButton("Fechar",null).create();connectTokenDialog=shown;
        shown.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);shown.setOnDismissListener(d->{if(connectTokenDialog==shown)wipeConnectToken();});shown.show();tokenHandler.postDelayed(tokenExpiry,Math.max(0,connectTokenDeadline-SystemClock.elapsedRealtime()));
    }
    private void copyCurrentConnectToken(){
        if(!canConfirm()||connectTokenValue==null)return;
        if(connectTokenDeadline==0||SystemClock.elapsedRealtime()>=connectTokenDeadline){expireConnectToken();return;}
        ClipData clip=ClipData.newPlainText("Connect Token MyFinanceHub",connectTokenValue.getText().toString());PersistableBundle extras=new PersistableBundle();extras.putBoolean("android.content.extra.IS_SENSITIVE",true);clip.getDescription().setExtras(extras);((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(clip);
    }
    private void expireConnectToken(){
        tokenHandler.removeCallbacks(tokenExpiry);connectTokenDeadline=0;
        if(connectTokenValue!=null)connectTokenValue.setText("");if(copyConnectToken!=null)copyConnectToken.setEnabled(false);
        if(connectTokenLifetime!=null)connectTokenLifetime.setText(R.string.pluggy_token_expired);
    }
    private void wipeConnectToken(){
        tokenHandler.removeCallbacks(tokenExpiry);connectTokenDeadline=0;
        if(connectTokenValue!=null)connectTokenValue.setText("");if(copyConnectToken!=null)copyConnectToken.setEnabled(false);
        connectTokenValue=null;copyConnectToken=null;connectTokenLifetime=null;connectTokenDialog=null;
    }
    private void dismissConnectToken(){AlertDialog dialog=connectTokenDialog;wipeConnectToken();if(dialog!=null)dialog.dismiss();}
    private void showConnectionGuide(){
        if(!canConfirm())return;
        LinearLayout guide=column();guide.setPadding(dp(22),dp(8),dp(22),dp(18));
        label(guide,"Esta edição consulta sua API pessoal. Criar a conta Meu Pluggy não configura automaticamente a conexão no MyFinanceHub: ainda é preciso preencher Client ID, Client Secret e itemId uma vez. A conexão sem esses campos depende de um serviço de integração do aplicativo, ainda não configurado.",14);
        label(guide,"Não é preciso digitar agência, número da conta ou lançamentos. Depois da configuração, a API lê suas contas e movimentações e mostra os dados aqui.",14);
        label(guide,"1. Autorize seus bancos no Meu Pluggy",18);
        label(guide,"Entre no Meu Pluggy e conecte cada banco. Conclua as autorizações no ambiente oficial do provedor e da instituição.",14);
        officialButton(guide,"Abrir Meu Pluggy","https://meu.pluggy.ai");
        label(guide,"2. Encontre Client ID e Client Secret",18);
        label(guide,"No Dashboard Pluggy, entre com a mesma conta do Meu Pluggy. Abra Applications: o Client ID fica na linha da sua aplicação.",14);
        label(guide,"O Client Secret aparece somente uma vez, quando é gerado. Se não o guardou, abra sua aplicação e use Generate new secret. Só o proprietário ou administrador pode gerar. Gerar outro invalida imediatamente o anterior: atualize as integrações que usavam a chave antiga.",14);
        officialButton(guide,"Abrir Dashboard Pluggy","https://dashboard.pluggy.ai");
        label(guide,"3. Espelhe suas conexões",18);
        label(guide,"Abra sua aplicação → Demo → Connect Account. Escolha MeuPluggy, não o banco nem Sandbox, e entre com sua conta Meu Pluggy. Isso permite ler as conexões que você já autorizou.",14);
        label(guide,"Se MeuPluggy não aparecer: Customization → Connectors → Personal → Direct Connectors. Procure MeuPluggy e ative o conector.",14);
        label(guide,"4. Copie os IDs das conexões",18);
        label(guide,"Na mesma página Demo, abra Connected Items e toque em cada conexão. Copie o itemId mostrado. No MyFinanceHub, preencha os IDs separados por vírgula, junto de Client ID e Client Secret, em Configurar conexão.",14);
        label(guide,"Use somente suas credenciais pessoais. Client Secret não é a senha do banco nem do portal. Não envie esses dados por chat. As consultas e a revisão dos lançamentos continuam dentro do aplicativo.",14);
        officialButton(guide,"Abrir guia oficial em português","https://meu.pluggy.ai/api-guide");
        ScrollView scroll=new ScrollView(this);scroll.addView(guide);
        new AlertDialog.Builder(this).setTitle("Como conectar minha conta").setView(scroll).setPositiveButton("Fechar",null).show();
    }
    private void officialButton(LinearLayout parent,String title,String url){Button link=button(title);link.setOnClickListener(v->{
        if(!canConfirm())return;
        try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(url)));}
        catch(ActivityNotFoundException ex){error("Abra "+url+" no navegador do aparelho.");}
    });parent.addView(link);}
    private void confirmDisconnect(){if(busy||!canConfirm())return;new AlertDialog.Builder(this).setTitle("Desconectar Meu Pluggy?").setMessage("Remove as credenciais e os dados consultados desta tela. Seus lançamentos confirmados permanecem. Para revogar consentimentos ou chaves, use o ambiente oficial do provedor.")
        .setNegativeButton("Cancelar",null).setPositiveButton("Desconectar",(d,w)->{try{if(busy||!canConfirm())return;cancel();settings.clear();snapshot=null;renderAccounts();resetAccountFilter();renderRows();status.setText(R.string.pluggy_disconnected);}catch(Exception ex){error(ex.getMessage());}}).show();}
    protected void query(){
        if(busy||!canConfirm())return;
        try{
            PersonalPluggySettings.Credentials credentials=settings.load();LocalDate start=PluggyClient.day(from.getText().toString()),end=PluggyClient.day(to.getText().toString());
            setBusy(true);status.setText(R.string.pluggy_consulting);int request=++generation;
            task=worker.submit(()->{try{PluggyClient.Snapshot result=client.fetch(credentials.clientId,credentials.clientSecret,credentials.itemIds,start,end);runOnUiThread(()->{if(destroyed||generation!=request||!canConfirm())return;setBusy(false);showSnapshot(result);});}
                catch(Exception ex){runOnUiThread(()->{if(destroyed||generation!=request||!canConfirm())return;setBusy(false);String message=ex instanceof java.io.IOException?ex.getMessage():getString(R.string.pluggy_query_invalid);status.setText(snapshot==null?message:getString(R.string.pluggy_previous_native,message,formatTime(snapshot.observedAt)));});}});
        }catch(Exception ex){error(ex.getMessage());}
    }
    void showSnapshot(PluggyClient.Snapshot value){snapshot=value;status.setText(getString(R.string.pluggy_observed_native,formatTime(value.observedAt)));renderAccounts();resetAccountFilter();renderRows();}
    private void renderAccounts(){accountCards.removeAllViews();connectionDetails.setEnabled(snapshot!=null);
        if(snapshot==null)return;
        for(PluggyClient.Account account:snapshot.accounts){LinearLayout card=column();card.setPadding(dp(16),dp(14),dp(16),dp(14));GradientDrawable bg=new GradientDrawable();bg.setColor(colors.surface);bg.setCornerRadius(dp(16));card.setBackground(bg);card.setContentDescription("Conta API "+account.name);
            label(card,account.name,16);label(card,account.kind.equals("CREDIT")?"Cartão de crédito":"Conta bancária",12);TextView balance=label(card,Money.format(account.balance),23);balance.setTextColor(colors.primary);label(card,"Saldo informado pelo banco",12);
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(dp(255),-2);lp.setMargins(0,dp(8),dp(10),dp(12));accountCards.addView(card,lp);}
    }
    private void resetAccountFilter(){updatingFilters=true;ArrayList<String> names=new ArrayList<>();names.add("Todas as contas");if(snapshot!=null)for(PluggyClient.Account account:snapshot.accounts)names.add(account.name+(account.kind.equals("CREDIT")?" · cartão":" · conta"));accountFilter.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,names));accountFilter.setSelection(0);updatingFilters=false;}
    private void renderRows(){
        if(summary==null)return;ArrayList<String> rows=new ArrayList<>();filteredRows.clear();int pending=0,savedCount=0;
        int accountIndex=accountFilter==null?0:Math.max(0,accountFilter.getSelectedItemPosition());int filter=statusFilter==null?0:Math.max(0,statusFilter.getSelectedItemPosition());String query=search==null?"":search.getText().toString().trim().toLowerCase(Locale.ROOT);
        if(snapshot!=null){
            dataRange.setText(getString(R.string.pluggy_range_native,snapshot.from,snapshot.to));
            for(PluggyClient.Transaction t:snapshot.transactions){boolean saved=store.hasPluggySource(t.sourceKey);if(t.status.equals("PENDING"))pending++;if(saved)savedCount++;
                if(accountIndex>0&&(accountIndex>snapshot.accounts.size()||!snapshot.accounts.get(accountIndex-1).id.equals(t.accountId)||!snapshot.accounts.get(accountIndex-1).itemId.equals(t.itemId)))continue;
                if(filter==1&&(saved||!t.status.equals("POSTED"))||filter==2&&!t.status.equals("PENDING")||filter==3&&!saved)continue;
                if(!(t.description+" "+t.date+" "+snapshot.account(t).name).toLowerCase(Locale.ROOT).contains(query))continue;
                filteredRows.add(t);rows.add((saved?"Já salvo":t.status.equals("PENDING")?"Pendente · somente consulta":"Revisar lançamento")+" · "+snapshot.account(t).name+"\n"+t.description+"\n"+t.date+"   "+(t.direction.equals("income")?"+ ":"− ")+Money.format(t.amount));}
            summary.setText(getString(R.string.pluggy_counts_native,snapshot.transactions.size(),pending,savedCount));
            emptyState.setText(snapshot.accounts.isEmpty()?getString(R.string.pluggy_no_accounts_native):getString(R.string.pluggy_balance_reference_native));
            if(rows.isEmpty())summary.setText(getString(R.string.pluggy_no_matches_native));
        }else{dataRange.setText("");summary.setText(R.string.pluggy_no_transactions_native);emptyState.setText(R.string.pluggy_empty_native);}
        list.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_list_item_1,rows){@Override public View getView(int p,View recycled,ViewGroup parent){TextView row=(TextView)super.getView(p,recycled,parent);row.setTextColor(colors.text);row.setTextSize(14);row.setPadding(dp(22),dp(16),dp(22),dp(16));return row;}});
    }
    private String formatTime(String value){try{return DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm",new Locale("pt","BR")).withZone(ZoneId.of("America/Sao_Paulo")).format(Instant.parse(value));}catch(Exception ex){return value;}}
    void review(PluggyClient.Transaction transaction){
        if(snapshot==null||!AuthSession.isUnlocked())return;
        if(!transaction.status.equals("POSTED")){error("Transação pendente: consulte novamente quando o provedor confirmar. Ela não altera seu saldo.");return;}
        if(store.hasPluggySource(transaction.sourceKey)){error("Esta transação já foi salva, inclusive se a conta local foi alterada.");return;}
        List<Models.Account> accounts=store.accounts();List<Models.Card> cards=store.cards();List<Models.Category> allCategories=store.categories(),categories=new ArrayList<>();
        PluggyClient.Account external=snapshot.account(transaction);boolean credit=external.kind.equals("CREDIT");
        if(accounts.isEmpty()||credit&&cards.isEmpty()){error("Cadastre "+(credit?"uma conta e um cartão":"uma conta")+" em Carteira antes de vincular esta transação.");return;}
        LinearLayout form=column();form.setPadding(dp(22),dp(12),dp(22),dp(20));
        label(form,external.name+"\n"+transaction.description+"\n"+transaction.date+" · "+(transaction.direction.equals("income")?"Entrada":"Saída")+" · "+Money.format(transaction.amount),17);
        ArrayList<String> accountLabels=new ArrayList<>();accountLabels.add("Escolha uma conta local");for(Models.Account a:accounts)accountLabels.add(a.name);Spinner account=spinner(form,"Conta local",accountLabels);
        ArrayList<String> cardLabels=new ArrayList<>();cardLabels.add("Escolha um cartão local");for(Models.Card c:cards)cardLabels.add(c.name+" · "+c.lastFour);Spinner card=credit?spinner(form,"Cartão local",cardLabels):null;
        ArrayList<String> categoryLabels=new ArrayList<>();categoryLabels.add("Sem categoria");for(Models.Category c:allCategories)if(c.type.equals("both")||c.type.equals(transaction.direction)){categories.add(c);categoryLabels.add(c.name);}Spinner category=spinner(form,"Categoria",categoryLabels);
        String[] keys={"other","pix","debit","cash","transfer","card_payment"};String[] names={"Outro / extrato","PIX","Débito","Dinheiro","Transferência entre minhas contas","Pagamento de fatura"};
        Spinner method=credit?null:spinner(form,"Forma de pagamento",Arrays.asList(names));if(method!=null)method.setSelection(transaction.method.equals("pix")?1:0);
        if(credit)label(form,"Compra ou estorno no crédito: vinculado ao cartão, sem alterar o caixa. Pagamento de fatura deve ser classificado na conta bancária.",13);
        boolean duplicate=false;for(Models.Entry e:store.entries())if(e.date.equals(transaction.date)&&e.type.equals(transaction.direction)&&e.amountCents==transaction.amount){duplicate=true;break;}
        CheckBox reconciled=new CheckBox(this);reconciled.setTextColor(colors.text);reconciled.setText(R.string.pluggy_reconciled);if(duplicate){label(form,"Há lançamento com mesma data, tipo e valor. Confira se já veio de extrato, notificação ou outra conexão.",14);form.addView(reconciled);}
        boolean possibleDuplicate=duplicate;
        ScrollView scroll=new ScrollView(this);scroll.addView(form);
        AlertDialog editor=new AlertDialog.Builder(this).setTitle("Revisar transação Meu Pluggy").setView(scroll).setNegativeButton("Cancelar",null).setPositiveButton("Revisar",null).create();
        editor.setOnShowListener(d->editor.getButton(-1).setOnClickListener(v->{try{
            if(account.getSelectedItemPosition()<=0)throw new IllegalArgumentException("Escolha a conta local explicitamente.");if(credit&&card.getSelectedItemPosition()<=0)throw new IllegalArgumentException("Escolha o cartão local explicitamente.");if(possibleDuplicate&&!reconciled.isChecked())throw new IllegalArgumentException("Confira a possível duplicidade antes de continuar.");
            Models.Entry entry=new Models.Entry();entry.accountId=accounts.get(account.getSelectedItemPosition()-1).id;entry.cardId=credit?cards.get(card.getSelectedItemPosition()-1).id:0;entry.categoryId=category.getSelectedItemPosition()==0?0:categories.get(category.getSelectedItemPosition()-1).id;entry.description=transaction.description;entry.date=transaction.date;entry.amountCents=transaction.amount;entry.type=transaction.direction;entry.sourceKey=transaction.sourceKey;entry.method=credit?"credit":keys[method.getSelectedItemPosition()];
            if(entry.method.equals("card_payment")&&entry.type.equals("income"))throw new IllegalArgumentException("Pagamento de fatura deve ser uma saída.");
            String details=transaction.description+"\n"+transaction.date+" · "+(entry.type.equals("income")?"Entrada":"Saída")+" · "+Money.format(entry.amountCents)+"\nOrigem: "+external.name+"\nConta: "+account.getSelectedItem()+"\nCategoria: "+category.getSelectedItem()+"\nForma: "+(credit?"Crédito\nCartão: "+card.getSelectedItem():method.getSelectedItem());
            AlertDialog confirmation=new AlertDialog.Builder(this).setTitle("Confirmar lançamento").setMessage(details).setNegativeButton("Voltar à revisão",null).setPositiveButton("Confirmar e salvar",null).create();
            confirmation.setOnShowListener(shown->{confirmation.getButton(-1).setFilterTouchesWhenObscured(true);confirmation.getButton(-1).setOnClickListener(button->{try{if(!canConfirm()){confirmation.dismiss();editor.dismiss();return;}store.saveReviewedPluggy(entry);confirmation.dismiss();editor.dismiss();renderRows();status.setText(R.string.pluggy_saved);}catch(Exception ex){error(ex.getMessage());}});});confirmation.show();
        }catch(Exception ex){error(ex.getMessage());}}));editor.show();
    }
    private void setBusy(boolean value){busy=value;fetch.setEnabled(!value);configure.setEnabled(!value);from.setEnabled(!value);to.setEnabled(!value);}
    private void cancel(){generation++;if(task!=null)task.cancel(true);if(busy){setBusy(false);status.setText(generatingConnectToken?R.string.pluggy_token_interrupted:R.string.pluggy_interrupted);}generatingConnectToken=false;}
    @Override protected void onResume(){super.onResume();if(AuthSession.isUnlocked()&&!entryConsulted&&settings.configured()){entryConsulted=true;query();}}
    @Override protected void onPause(){dismissConnectToken();cancel();super.onPause();}
    @Override protected void onStop(){dismissConnectToken();super.onStop();}
    @Override protected void onDestroy(){destroyed=true;dismissConnectToken();unregisterReceiver(screenOff);cancel();worker.shutdownNow();store.close();super.onDestroy();}
    private void error(String message){new AlertDialog.Builder(this).setTitle("Meu Pluggy").setMessage(message==null?"Não foi possível concluir. Nenhum dado anterior foi alterado.":message).setPositiveButton("OK",null).show();}
    private LinearLayout column(){LinearLayout view=new LinearLayout(this);view.setOrientation(LinearLayout.VERTICAL);return view;}
    private TextView label(LinearLayout parent,String value,int size){TextView view=new TextView(this);view.setText(value);view.setTextColor(colors.text);view.setTextSize(size);view.setPadding(0,dp(8),0,dp(8));parent.addView(view);return view;}
    private EditText field(LinearLayout parent,String hint,String value,int type){label(parent,hint,13);EditText edit=new EditText(this);edit.setContentDescription(hint);edit.setHint(hint);edit.setText(value);edit.setTextColor(colors.text);edit.setHintTextColor(colors.muted);edit.setSingleLine(true);edit.setInputType(type);edit.setMinHeight(dp(48));parent.addView(edit);return edit;}
    private Spinner spinner(LinearLayout parent,String hint,List<String> values){label(parent,hint,13);Spinner select=new Spinner(this);select.setContentDescription(hint);select.setMinimumHeight(dp(48));select.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,values));select.setSelection(0);parent.addView(select);return select;}
    private Button button(String text){Button b=new Button(this);b.setText(text);b.setAllCaps(false);b.setContentDescription(text);b.setMinHeight(dp(48));return b;}
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
}
