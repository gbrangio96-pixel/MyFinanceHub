package br.com.myfinancehub;

import android.app.*;
import android.os.*;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/** Explicit, foreground-only connection. A downloaded snapshot never writes to the ledger. */
public final class PluggyActivity extends LockedActivity {
    private AppAppearance.Palette colors;
    private FinanceStore store;private PersonalPluggySettings settings;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private Future<?> task;private int generation;private boolean busy,destroyed,entryConsulted;
    private LinearLayout header;private ListView list;private TextView status,summary;
    private EditText origin,token,itemIds,from,to;private Button fetch,save,disconnect;
    private PluggyClient.Snapshot snapshot;
    private CheckBox consultOnEntry;
    @Override public void onCreate(Bundle state){
        setTheme(AppAppearance.themeRes(this));super.onCreate(state);colors=AppAppearance.palette(this);store=new FinanceStore(this);settings=new PersonalPluggySettings(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        LinearLayout root=column();root.setBackgroundColor(colors.background);
        if(Build.VERSION.SDK_INT>=30){getWindow().setDecorFitsSystemWindows(false);root.setOnApplyWindowInsetsListener((v,insets)->{android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());v.setPadding(bars.left,bars.top,bars.right,bars.bottom);return insets;});}else root.setFitsSystemWindows(true);
        list=new ListView(this);list.setContentDescription("Transações Meu Pluggy");header=column();header.setPadding(dp(22),dp(16),dp(22),dp(24));list.addHeaderView(header,null,false);root.addView(list,new LinearLayout.LayoutParams(-1,-1));setContentView(root);
        getWindow().setStatusBarColor(colors.background);getWindow().setNavigationBarColor(colors.background);
        Button back=button("Voltar");header.addView(back);back.setOnClickListener(v->finish());
        label(header,"Meu Pluggy",26);label(header,"Consulte suas conexões pessoais já autorizadas. Sem servidor próprio ou hospedagem. Cada transação exige revisão e confirmação; a consulta não movimenta dinheiro.",14);
        Button portal=button("Autorizar bancos no Meu Pluggy");portal.setOnClickListener(v->{try{startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW,android.net.Uri.parse("https://meu.pluggy.ai")));}catch(android.content.ActivityNotFoundException ex){error("Abra https://meu.pluggy.ai no navegador para gerenciar seus consentimentos.");}});header.addView(portal);
        origin=field(header,"Client ID pessoal","",InputType.TYPE_CLASS_TEXT);origin.setSaveEnabled(false);origin.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        token=field(header,"Client Secret pessoal","",InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);token.setSaveEnabled(false);token.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);token.setFilterTouchesWhenObscured(true);
        itemIds=field(header,"IDs das conexões MeuPluggy (separados por vírgula)","",InputType.TYPE_CLASS_TEXT);itemIds.setSaveEnabled(false);itemIds.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        label(header,"Após autorizar os bancos no Meu Pluggy, abra Dashboard → aplicação → Demo → Connect Account e escolha MeuPluggy. Copie seu Client ID, Client Secret e os itemId espelhados. Preencha somente aqui; nunca envie pelo chat. Não use a senha do banco ou do portal nestes campos.",13);
        label(header,"As credenciais são suas e ficam criptografadas neste aparelho. Campos vazios mantêm a configuração já salva. A gratuidade e cobertura dependem das condições do Meu Pluggy; o app não contrata planos.",13);
        consultOnEntry=new CheckBox(this);consultOnEntry.setText(R.string.pluggy_consult_entry);consultOnEntry.setTextColor(colors.text);consultOnEntry.setChecked(!settings.configured()||settings.consultOnEntry());header.addView(consultOnEntry);
        save=button("Salvar conexão");save.setOnClickListener(v->saveSettings());header.addView(save);
        disconnect=button("Desconectar Meu Pluggy");disconnect.setOnClickListener(v->new AlertDialog.Builder(this).setTitle("Desconectar?").setMessage("Remove a configuração deste aparelho e a consulta da tela. Os lançamentos confirmados permanecem. Para revogar consentimentos ou chaves, use Meu Pluggy e o Dashboard da Pluggy.").setNegativeButton("Cancelar",null).setPositiveButton("Desconectar",(d,w)->{try{cancel();settings.clear();origin.setText("");token.setText("");itemIds.setText("");snapshot=null;renderRows();status.setText(R.string.pluggy_disconnected);}catch(Exception ex){error(ex.getMessage());}}).show());header.addView(disconnect);
        LocalDate today=LocalDate.now(ZoneId.of("America/Sao_Paulo"));from=field(header,"Data inicial (AAAA-MM-DD)",today.minusDays(29).toString(),InputType.TYPE_CLASS_DATETIME);to=field(header,"Data final (AAAA-MM-DD)",today.toString(),InputType.TYPE_CLASS_DATETIME);
        label(header,"Até 90 dias por consulta. O Meu Pluggy costuma atualizar as conexões a cada 24 horas; consultar não força atualização no banco. A sincronização aqui é manual.",13);
        fetch=button("Consultar transações");fetch.setOnClickListener(v->query());header.addView(fetch);
        status=label(header,settings.configured()?"Configuração protegida no aparelho. Consulte para verificar o acesso.":"Configure suas credenciais pessoais para consultar. Nenhum dado bancário foi consultado.",14);
        summary=label(header,"",14);renderRows();
        list.setOnItemClickListener((parent,view,position,id)->{int index=position-list.getHeaderViewsCount();if(snapshot!=null&&index>=0&&index<snapshot.transactions.size())review(snapshot.transactions.get(index));});
    }
    private void saveSettings(){try{if(busy)return;
        String id=origin.getText().toString().trim(),secret=token.getText().toString(),ids=itemIds.getText().toString().trim();
        if(id.isEmpty()&&secret.isEmpty()&&ids.isEmpty()){if(!settings.configured())throw new IllegalArgumentException("Preencha suas credenciais pessoais e os IDs das conexões.");settings.setConsultOnEntry(consultOnEntry.isChecked());status.setText(R.string.pluggy_config_kept);return;}
        if(id.isEmpty()||secret.isEmpty()||ids.isEmpty())throw new IllegalArgumentException("Ao alterar a conexão, preencha Client ID, Client Secret e os IDs das conexões juntos.");
        settings.save(id,secret,PluggyPersonalClient.itemIds(ids));settings.setConsultOnEntry(consultOnEntry.isChecked());token.setText("");origin.setText("");itemIds.setText("");snapshot=null;renderRows();status.setText(R.string.pluggy_config_saved);
    }catch(Exception ex){error(ex.getMessage());}}
    protected void query(){
        if(busy||!AuthSession.isUnlocked())return;
        try {
            if(!token.getText().toString().isEmpty()||!origin.getText().toString().trim().isEmpty()||!itemIds.getText().toString().trim().isEmpty())throw new IllegalArgumentException("Salve a configuração antes de consultar.");
            PersonalPluggySettings.Credentials credentials=settings.load();LocalDate start=PluggyClient.day(from.getText().toString().trim()),end=PluggyClient.day(to.getText().toString().trim());
            snapshot=null;renderRows();setBusy(true);status.setText(R.string.pluggy_consulting);int request=++generation;
            task=worker.submit(()->{try{PluggyClient.Snapshot result=new PluggyPersonalClient().fetch(credentials.clientId,credentials.clientSecret,credentials.itemIds,start,end);runOnUiThread(()->{if(destroyed||generation!=request)return;setBusy(false);showSnapshot(result);});}catch(Exception ex){runOnUiThread(()->{if(destroyed||generation!=request)return;setBusy(false);status.setText(ex instanceof java.io.IOException?ex.getMessage():"Não foi possível consultar. Confira suas credenciais, conexões e período de até 90 dias.");});}});
        }catch(Exception ex){error(ex.getMessage());}
    }
    void showSnapshot(PluggyClient.Snapshot value){snapshot=value;status.setText(getString(R.string.pluggy_observed,value.observedAt));renderRows();}
    private void renderRows(){
        ArrayList<String> rows=new ArrayList<>();StringBuilder info=new StringBuilder();
        if(snapshot!=null){info.append("Período: ").append(snapshot.from).append(" a ").append(snapshot.to).append('\n');for(String connection:snapshot.connections)info.append(connection).append('\n');
            for(PluggyClient.Account a:snapshot.accounts)info.append(a.name).append(a.kind.equals("CREDIT")?" · crédito":" · conta").append("\nSaldo informado: ").append(Money.format(a.balance)).append(" (não aplicado ao saldo local)\n");
            info.append("Revise a origem, a conta/cartão e pagamentos de fatura ou transferências próprias. CSV, notificações e conexões recriadas podem conter o mesmo movimento com outra identidade.\n");
            for(PluggyClient.Transaction t:snapshot.transactions){boolean saved=store.hasPluggySource(t.sourceKey);rows.add((saved?"Já salvo":t.status.equals("PENDING")?"Pendente · não pode salvar":"Revisar")+" · "+snapshot.account(t).name+"\n"+t.date+" · "+t.description+"\n"+(t.direction.equals("income")?"+ ":"− ")+Money.format(t.amount));}
            if(rows.isEmpty())info.append("Nenhuma transação neste período.");
        }
        summary.setText(info);list.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_list_item_1,rows){@Override public View getView(int p,View recycled,ViewGroup parent){TextView row=(TextView)super.getView(p,recycled,parent);row.setTextColor(colors.text);row.setTextSize(14);row.setPadding(dp(22),dp(16),dp(22),dp(16));return row;}});
    }
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
            confirmation.setOnShowListener(shown->{confirmation.getButton(-1).setFilterTouchesWhenObscured(true);confirmation.getButton(-1).setOnClickListener(button->{try{store.saveReviewedPluggy(entry);confirmation.dismiss();editor.dismiss();renderRows();status.setText(R.string.pluggy_saved);}catch(Exception ex){error(ex.getMessage());}});});confirmation.show();
        }catch(Exception ex){error(ex.getMessage());}}));editor.show();
    }
    private void setBusy(boolean value){busy=value;fetch.setEnabled(!value);save.setEnabled(!value);origin.setEnabled(!value);token.setEnabled(!value);itemIds.setEnabled(!value);from.setEnabled(!value);to.setEnabled(!value);}
    private void cancel(){generation++;if(task!=null)task.cancel(true);if(busy){setBusy(false);status.setText(R.string.pluggy_interrupted);}}
    @Override protected void onResume(){super.onResume();if(AuthSession.isUnlocked()&&!entryConsulted&&getIntent().getBooleanExtra("consult_on_entry",false)&&settings.consultOnEntry()){entryConsulted=true;query();}}
    @Override protected void onPause(){cancel();super.onPause();}
    @Override protected void onDestroy(){destroyed=true;cancel();worker.shutdownNow();store.close();super.onDestroy();}
    private void error(String message){new AlertDialog.Builder(this).setTitle("Meu Pluggy").setMessage(message==null?"Não foi possível concluir. Nenhum dado anterior foi alterado.":message).setPositiveButton("OK",null).show();}
    private LinearLayout column(){LinearLayout view=new LinearLayout(this);view.setOrientation(LinearLayout.VERTICAL);return view;}
    private TextView label(LinearLayout parent,String value,int size){TextView view=new TextView(this);view.setText(value);view.setTextColor(colors.text);view.setTextSize(size);view.setPadding(0,dp(10),0,dp(10));parent.addView(view);return view;}
    private EditText field(LinearLayout parent,String hint,String value,int type){label(parent,hint,13);EditText edit=new EditText(this);edit.setContentDescription(hint);edit.setHint(hint);edit.setText(value);edit.setTextColor(colors.text);edit.setHintTextColor(colors.muted);edit.setSingleLine(true);edit.setInputType(type);edit.setMinHeight(dp(48));parent.addView(edit);return edit;}
    private Spinner spinner(LinearLayout parent,String hint,List<String> values){label(parent,hint,13);Spinner select=new Spinner(this);select.setContentDescription(hint);select.setMinimumHeight(dp(48));select.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,values));select.setSelection(0);parent.addView(select);return select;}
    private Button button(String text){Button b=new Button(this);b.setText(text);b.setAllCaps(false);b.setContentDescription(text);b.setMinHeight(dp(48));return b;}
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
}
