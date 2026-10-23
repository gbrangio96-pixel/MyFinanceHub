package br.com.myfinancehub;
import android.app.*;
import android.content.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.URI;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;
@RunWith(RobolectricTestRunner.class) @Config(sdk=28,qualifiers="pt-rBR")
public class PluggyActivityTest {
    Context context;FinanceStore store;PluggyActivity activity;ActivityController<PluggyActivity> controller;
    @Before public void before(){context=RuntimeEnvironment.getApplication();context.deleteDatabase("myfinancehub.db");context.getSharedPreferences("personal_pluggy_private",0).edit().clear().commit();controller=Robolectric.buildActivity(PluggyActivity.class).setup().visible();activity=controller.get();store=new FinanceStore(context);idle();}
    @After public void after(){AlertDialog d=ShadowAlertDialog.getLatestAlertDialog();if(d!=null)d.dismiss();controller.pause().stop().destroy();store.close();context.deleteDatabase("myfinancehub.db");context.getSharedPreferences("personal_pluggy_private",0).edit().clear().commit();}
    @Test public void openingShowsNativeDashboardWithoutPortalOrCredentialFields() {
        assertTrue(store.entries().isEmpty());assertEquals(0,store.totalBalance());
        assertTrue(containsText(allTexts(screen()),"Suas contas, no app"));
        assertNotNull(find(screen(),"Atualizar dados"));assertNotNull(find(screen(),"Configurar conexão"));
        assertNull(find(screen(),"Client Secret pessoal"));assertNull(find(screen(),"Client ID pessoal"));
        assertNull(find(screen(),"Autorizar bancos no Meu Pluggy"));assertNull(find(screen(),"URL HTTPS do backend"));assertNull(find(screen(),"Token de acesso do app"));
        assertNotEquals(0,activity.getWindow().getAttributes().flags&WindowManager.LayoutParams.FLAG_SECURE);
        assertNull("Opening the dashboard must not launch a website",shadowOf(activity).getNextStartedActivity());
    }
    @Test public void nativeConnectionDialogProtectsCredentialsAndCancellingDoesNotConnect() {
        activity.showConnectionSettings();idle();AlertDialog configuration=dialog();
        for(String label:new String[]{"Client ID pessoal","Client Secret pessoal","IDs das conexões MeuPluggy (separados por vírgula)"}){
            View found=find(configuration.getWindow().getDecorView(),label);assertTrue(found instanceof EditText);EditText field=(EditText)found;
            assertFalse(field.isSaveEnabled());assertEquals(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS,field.getImportantForAutofill());assertTrue(field.getFilterTouchesWhenObscured());
        }
        click(configuration,-2);assertFalse(activity.settings.configured());assertTrue(store.entries().isEmpty());assertNull(shadowOf(activity).getNextStartedActivity());
    }
    @Test public void connectionGuideShowsExactDashboardLocationsWithoutOpeningAWebsite() {
        View help=find(screen(),"Onde encontrar os dados da conexão");assertNotNull(help);assertTrue(help.performClick());idle();
        AlertDialog guide=dialog();assertTrue(guide.isShowing());List<String> text=allTexts(guide.getWindow().getDecorView());
        for(String required:new String[]{"Criar a conta Meu Pluggy não configura automaticamente", "Applications", "Client ID fica na linha", "Client Secret aparece somente uma vez", "Generate new secret", "invalida imediatamente o anterior", "Demo → Connect Account", "MeuPluggy, não o banco nem Sandbox", "Customization → Connectors → Personal → Direct Connectors", "Connected Items", "itemId"})
            assertTrue("Guide must explain: "+required,containsText(text,required));
        assertNull("Reading the native guide must not open the browser",shadowOf(activity).getNextStartedActivity());assertFalse(activity.settings.configured());assertTrue(store.entries().isEmpty());
    }
    @Test public void settingsReuseGuideAndOfficialLinksOpenOnlyAfterExplicitTap() {
        activity.showConnectionSettings();idle();AlertDialog configuration=dialog();
        ((EditText)find(configuration.getWindow().getDecorView(),"Client Secret pessoal")).setText("fixture-secret-not-for-browser");
        assertTrue(find(configuration.getWindow().getDecorView(),"Onde encontrar os dados da conexão").performClick());idle();AlertDialog guide=dialog();
        assertNotSame(configuration,guide);assertTrue(configuration.isShowing());assertNull(shadowOf(activity).getNextStartedActivity());
        String[][] links={{"Abrir Meu Pluggy","https://meu.pluggy.ai"},{"Abrir Dashboard Pluggy","https://dashboard.pluggy.ai"},{"Abrir guia oficial em português","https://meu.pluggy.ai/api-guide"}};
        for(String[] link:links){
            View button=find(guide.getWindow().getDecorView(),link[0]);assertNotNull(button);assertTrue(button.performClick());idle();
            Intent opened=shadowOf(activity).getNextStartedActivity();assertNotNull(opened);assertEquals(Intent.ACTION_VIEW,opened.getAction());assertEquals(link[1],opened.getDataString());assertNull(opened.getExtras());assertNull(opened.getData().getQuery());
            assertNull("One explicit tap opens only its official destination",shadowOf(activity).getNextStartedActivity());
        }
        assertFalse(activity.settings.configured());assertTrue(store.entries().isEmpty());click(guide,-1);click(configuration,-2);
    }
    @Test public void screenOffRejectsStaleGuideLinksAndConfigurationSave()throws Exception {
        assertTrue(find(screen(),"Onde encontrar os dados da conexão").performClick());idle();AlertDialog guide=dialog();View oldLink=find(guide.getWindow().getDecorView(),"Abrir Dashboard Pluggy");click(guide,-1);
        activity.showConnectionSettings();idle();AlertDialog configuration=dialog();
        ((EditText)find(configuration.getWindow().getDecorView(),"Client ID pessoal")).setText("fixture-client");
        ((EditText)find(configuration.getWindow().getDecorView(),"Client Secret pessoal")).setText("fixture-secret");
        ((EditText)find(configuration.getWindow().getDecorView(),"IDs das conexões MeuPluggy (separados por vírgula)")).setText("item-a");
        String before=store.exportBackup();context.sendBroadcast(new Intent(Intent.ACTION_SCREEN_OFF));idle();assertFalse(AuthSession.isUnlocked());assertTrue(activity.isFinishing());
        assertTrue(oldLink.performClick());click(configuration,-1);
        assertNull(shadowOf(activity).getNextStartedActivity());assertFalse(activity.settings.configured());assertEquals(before,store.exportBackup());
        Field task=PluggyActivity.class.getDeclaredField("task");task.setAccessible(true);assertNull("A stale save must not start a provider query",task.get(activity));
    }
    @Test public void typedProjectKeysCreateConnectTokenWithoutItemIdsOrPersistence()throws Exception {
        TokenApi api=new TokenApi();activity.client=tokenClient(api);String before=store.exportBackup();
        AlertDialog configuration=requestToken("fixture-client","fixture-secret");drainWorker();AlertDialog result=dialog();
        assertNotSame(configuration,result);assertEquals(Arrays.asList("/auth","/connect_token"),api.paths);
        TextView value=(TextView)find(result.getWindow().getDecorView(),"Connect Token gerado");assertEquals(TokenApi.TOKEN,value.getText().toString());assertFalse(value.isTextSelectable());assertFalse(value.isSaveEnabled());assertNotEquals(0,result.getWindow().getAttributes().flags&WindowManager.LayoutParams.FLAG_SECURE);
        assertEquals("",((EditText)find(configuration.getWindow().getDecorView(),"Client ID pessoal")).getText().toString());assertEquals("",((EditText)find(configuration.getWindow().getDecorView(),"Client Secret pessoal")).getText().toString());
        assertTrue(find(result.getWindow().getDecorView(),"Copiar Connect Token").performClick());idle();ClipboardManager clipboard=(ClipboardManager)context.getSystemService(Context.CLIPBOARD_SERVICE);assertEquals(TokenApi.TOKEN,clipboard.getPrimaryClip().getItemAt(0).getText().toString());assertTrue(clipboard.getPrimaryClipDescription().getExtras().getBoolean("android.content.extra.IS_SENSITIVE"));
        assertFalse(activity.settings.configured());assertTrue(context.getSharedPreferences("personal_pluggy_private",0).getAll().isEmpty());assertEquals(before,store.exportBackup());assertNull(shadowOf(activity).getNextStartedActivity());
        click(result,-1);assertEquals("",value.getText().toString());
    }
    @Test public void tokenGenerationReusesPrivateSavedKeysWithoutChangingSettings()throws Exception {
        activity.settings=fictionalSettings();Map<String,?> saved=new HashMap<>(context.getSharedPreferences("personal_pluggy_private",0).getAll());TokenApi api=new TokenApi();activity.client=tokenClient(api);
        requestToken("","");drainWorker();AlertDialog result=dialog();assertEquals(TokenApi.TOKEN,((TextView)find(result.getWindow().getDecorView(),"Connect Token gerado")).getText().toString());
        assertEquals(Arrays.asList("/auth","/connect_token"),api.paths);assertEquals(saved,context.getSharedPreferences("personal_pluggy_private",0).getAll());assertTrue(store.entries().isEmpty());assertNull(shadowOf(activity).getNextStartedActivity());click(result,-1);
    }
    @Test public void tokenGenerationRequiresBothProjectKeysBeforeAnyNetworkRequest()throws Exception {
        TokenApi api=new TokenApi();activity.client=tokenClient(api);AlertDialog empty=requestToken("","");assertTrue(containsText(allTexts(dialog().getWindow().getDecorView()),"Preencha Client ID e Client Secret"));assertTrue(api.paths.isEmpty());click(dialog(),-1);click(empty,-2);
        AlertDialog partial=requestToken("fixture-client","");assertTrue(containsText(allTexts(dialog().getWindow().getDecorView()),"Preencha Client ID e Client Secret juntos"));assertTrue(api.paths.isEmpty());assertFalse(activity.settings.configured());assertTrue(store.entries().isEmpty());click(dialog(),-1);click(partial,-2);
    }
    @Test public void displayedConnectTokenExpiresAndIsWipedOnPauseAndScreenOff()throws Exception {
        TokenApi api=new TokenApi();activity.client=tokenClient(api);requestToken("fixture-client","fixture-secret");drainWorker();AlertDialog first=dialog();TextView expired=(TextView)find(first.getWindow().getDecorView(),"Connect Token gerado");View expiredCopy=find(first.getWindow().getDecorView(),"Copiar Connect Token");
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(30));assertEquals("",expired.getText().toString());assertFalse(expiredCopy.isEnabled());assertTrue(containsText(allTexts(first.getWindow().getDecorView()),"Token expirado"));click(first,-1);
        requestToken("fixture-client","fixture-secret");drainWorker();AlertDialog second=dialog();TextView paused=(TextView)find(second.getWindow().getDecorView(),"Connect Token gerado");View pausedCopy=find(second.getWindow().getDecorView(),"Copiar Connect Token");controller.pause();idle();assertEquals("",paused.getText().toString());assertFalse(pausedCopy.isEnabled());assertFalse(second.isShowing());controller.resume();idle();
        requestToken("fixture-client","fixture-secret");drainWorker();AlertDialog third=dialog();TextView blocked=(TextView)find(third.getWindow().getDecorView(),"Connect Token gerado");context.sendBroadcast(new Intent(Intent.ACTION_SCREEN_OFF));idle();assertEquals("",blocked.getText().toString());assertFalse(third.isShowing());assertTrue(activity.isFinishing());assertFalse(AuthSession.isUnlocked());assertTrue(store.entries().isEmpty());
    }
    @Test public void screenOffCancelsTokenGenerationAndRejectsItsLateResult()throws Exception {
        TokenApi api=new TokenApi();api.entered=new CountDownLatch(1);api.release=new CountDownLatch(1);activity.client=tokenClient(api);String before=store.exportBackup();
        AlertDialog configuration=requestToken("fixture-client","fixture-secret");assertTrue(api.entered.await(10,TimeUnit.SECONDS));
        try{
            assertFalse(find(screen(),"Atualizar dados").isEnabled());assertFalse(find(screen(),"Configurar conexão").isEnabled());
            assertTrue(configuration.getButton(-1).performClick());assertTrue(find(configuration.getWindow().getDecorView(),"Gerar Connect Token").performClick());idle();
            context.sendBroadcast(new Intent(Intent.ACTION_SCREEN_OFF));idle();assertTrue(activity.isFinishing());assertFalse(AuthSession.isUnlocked());
        }finally{api.release.countDown();}
        drainWorker();assertEquals(Arrays.asList("/auth","/connect_token"),api.paths);assertSame("Cancelled work must not display another dialog",configuration,dialog());assertFalse(activity.settings.configured());assertEquals(before,store.exportBackup());assertNull(shadowOf(activity).getNextStartedActivity());
        Field displayed=PluggyActivity.class.getDeclaredField("connectTokenValue");displayed.setAccessible(true);assertNull(displayed.get(activity));
    }
    @Test public void actualPersonalApiQueryRendersNativeAccountsAndTransactionsWithoutOpeningAWebsite()throws Exception {
        NativeApi api=prepareNativeQuery();activity.query();drainWorker();
        assertEquals("Authentication, item, accounts and both transaction pages",5,api.calls.size());
        View accounts=find(screen(),"Contas consultadas pela API");assertNotNull(accounts);
        assertEquals(2,((ViewGroup)accounts).getChildCount());
        View bank=find(accounts,"Conta API Conta nativa de teste"),credit=find(accounts,"Conta API Cartão nativo de teste");assertNotNull(bank);assertNotNull(credit);
        assertTrue(containsText(allTexts(bank),"Conta bancária"));assertTrue(containsText(allTexts(bank),"R$ 9.876,54"));
        assertTrue(containsText(allTexts(credit),"Cartão de crédito"));assertTrue(containsText(allTexts(credit),"R$ 321,45"));
        List<String> rows=transactionRows();assertEquals(2,rows.size());assertTrue(containsText(rows,"Mercado pela API nativa"));assertTrue(containsText(rows,"Compra pendente pela API"));
        assertTrue(containsText(allTexts(find(screen(),"Resumo de transações Meu Pluggy")),"Movimentações: 2 · Pendentes: 1 · Já salvas: 0"));
        assertTrue(containsText(allTexts(find(screen(),"Estado da consulta Meu Pluggy")),"22/10/2026 09:00"));
        assertNull("Reading the provider API must remain inside the app",shadowOf(activity).getNextStartedActivity());assertTrue(store.entries().isEmpty());assertEquals(0,store.totalBalance());
    }
    @Test public void enteringConfiguredDashboardConsultsApiOnceAndStaysInsideTheApp()throws Exception {
        NativeApi api=prepareNativeQuery();controller.pause().resume();drainWorker();
        assertEquals(5,api.calls.size());assertEquals(2,transactionRows().size());assertNull(shadowOf(activity).getNextStartedActivity());assertTrue(store.entries().isEmpty());
        controller.pause().resume();drainWorker();assertEquals("Returning from a short pause must not duplicate the entry query",5,api.calls.size());assertEquals(0,store.totalBalance());
    }
    @Test public void nativeFiltersKeepPendingReadOnlyAndOnlyExplicitConfirmationSavesPostedMovement()throws Exception {
        prepareNativeQuery();activity.query();drainWorker();
        selectFilter("Filtrar situação",2);assertEquals(1,transactionRows().size());assertTrue(containsText(transactionRows(),"Compra pendente pela API"));
        clickFirstTransaction();AlertDialog pending=dialog();assertEquals("OK",pending.getButton(-1).getText().toString());assertTrue(store.entries().isEmpty());click(pending,-1);
        selectFilter("Filtrar situação",1);assertEquals(1,transactionRows().size());assertTrue(containsText(transactionRows(),"Mercado pela API nativa"));
        selectFilter("Filtrar conta",2);assertTrue("The posted bank movement must not appear under the card account",transactionRows().isEmpty());
        selectFilter("Filtrar conta",0);clickFirstTransaction();AlertDialog form=dialog();choose(form,"Conta local",1);click(form,-1);assertTrue(store.entries().isEmpty());click(dialog(),-1);
        assertEquals(1,store.entries().size());assertEquals(2590,store.entries().get(0).amountCents);assertEquals(-2590,store.totalBalance());
        selectFilter("Filtrar situação",3);assertEquals(1,transactionRows().size());assertTrue(containsText(transactionRows(),"Já salvo"));
        selectFilter("Filtrar situação",2);assertEquals(1,transactionRows().size());assertTrue(containsText(transactionRows(),"Pendente"));assertEquals(1,store.entries().size());
        assertNull(shadowOf(activity).getNextStartedActivity());
    }
    @Test public void failedRefreshKeepsPreviousNativeValuesAndMarksTheirOriginalConsultationTime()throws Exception {
        prepareNativeQuery();activity.query();drainWorker();List<String> previous=transactionRows();
        activity.client=new PluggyPersonalClient((method,uri,headers,body)->{throw new IOException("private-fixture-secret");},Clock.fixed(Instant.parse("2026-10-22T12:00:00Z"),ZoneOffset.UTC));
        activity.query();drainWorker();assertEquals(previous,transactionRows());
        List<String> status=allTexts(find(screen(),"Estado da consulta Meu Pluggy"));assertTrue(containsText(status,"Mantidos os dados da consulta de 22/10/2026 09:00"));assertFalse(containsText(status,"private-fixture-secret"));assertTrue(store.entries().isEmpty());assertNull(shadowOf(activity).getNextStartedActivity());
    }
    @Test public void postedExpenseRequiresExplicitMappingReviewAndConfirmation()throws Exception {
        PluggyClient.Snapshot snapshot=PluggyFixtures.snapshot();activity.showSnapshot(snapshot);idle();assertTrue(store.entries().isEmpty());assertEquals(0,store.totalBalance());
        activity.review(snapshot.transactions.get(0));idle();AlertDialog form=dialog();
        click(form,-1);AlertDialog error=dialog();assertTrue(error.isShowing());assertTrue(form.isShowing());assertTrue(store.entries().isEmpty());click(error,-1);
        choose(form,"Conta local",1);click(form,-1);AlertDialog confirmation=dialog();assertNotSame(form,confirmation);assertTrue(store.entries().isEmpty());assertEquals("Confirmar e salvar",confirmation.getButton(-1).getText().toString());
        click(confirmation,-2);assertTrue(form.isShowing());assertTrue(store.entries().isEmpty());click(form,-1);click(dialog(),-1);
        assertEquals(1,store.entries().size());assertEquals(-2590,store.totalBalance());Models.Entry entry=store.entries().get(0);assertEquals(snapshot.transactions.get(0).sourceKey,entry.sourceKey);assertEquals("pix",entry.method);
        activity.review(snapshot.transactions.get(0));idle();assertEquals(1,store.entries().size());assertEquals(-2590,store.totalBalance());
    }
    @Test public void screenOffRevokesPostedTransactionConfirmationWithoutChangingSavedData()throws Exception {
        String before=store.exportBackup();PluggyClient.Snapshot snapshot=PluggyFixtures.snapshot();activity.showSnapshot(snapshot);idle();
        activity.review(snapshot.transactions.get(0));idle();AlertDialog form=dialog();choose(form,"Conta local",1);click(form,-1);AlertDialog confirmation=dialog();
        assertEquals("Confirmar e salvar",confirmation.getButton(-1).getText().toString());assertTrue(AuthSession.isUnlocked());assertTrue(store.entries().isEmpty());
        context.sendBroadcast(new Intent(Intent.ACTION_SCREEN_OFF));idle();
        assertFalse(AuthSession.isUnlocked());assertTrue(activity.isFinishing());assertEquals(View.INVISIBLE,screen().getVisibility());
        // Simulate a stale dialog callback retained while Android tears down its window.
        confirmation.getButton(-1).performClick();idle();
        assertTrue(store.entries().isEmpty());assertEquals(before,store.exportBackup());assertFalse(confirmation.isShowing());assertFalse(form.isShowing());
    }
    @Test public void pendingTransactionCannotOpenSavingForm()throws Exception {JSONObject root=PluggyFixtures.data();root.getJSONArray("transactions").getJSONObject(0).put("status","PENDING");PluggyClient.Snapshot snapshot=PluggyClientTest.parse(root);activity.showSnapshot(snapshot);idle();activity.review(snapshot.transactions.get(0));idle();assertEquals("OK",dialog().getButton(-1).getText().toString());assertTrue(store.entries().isEmpty());assertEquals(0,store.totalBalance());}
    @Test public void creditRequiresCardAndDoesNotAffectCash()throws Exception {store.saveCard(new Models.Card(0,"Meu cartão","1234",20,5));JSONObject root=PluggyFixtures.data();root.getJSONArray("accounts").getJSONObject(0).put("kind","CREDIT");PluggyClient.Snapshot snapshot=PluggyClientTest.parse(root);activity.showSnapshot(snapshot);idle();activity.review(snapshot.transactions.get(0));idle();AlertDialog form=dialog();choose(form,"Conta local",1);click(form,-1);assertTrue(form.isShowing());assertTrue(store.entries().isEmpty());click(dialog(),-1);choose(form,"Cartão local",1);click(form,-1);click(dialog(),-1);assertEquals("credit",store.entries().get(0).method);assertEquals(0,store.totalBalance());assertEquals(store.cards().get(0).id,store.entries().get(0).cardId);}
    @Test public void possibleDuplicateNeedsExplicitReconciliationAndCancelPreservesLedger()throws Exception {
        Models.Entry existing=new Models.Entry(0,store.accounts().get(0).id,0,0,"Compra já lançada",PluggyFixtures.DAY.toString(),"expense","other","statement-fixture",2590);store.saveEntry(existing);String backup=store.exportBackup();
        PluggyClient.Snapshot snapshot=PluggyFixtures.snapshot();activity.showSnapshot(snapshot);idle();activity.review(snapshot.transactions.get(0));idle();AlertDialog form=dialog();choose(form,"Conta local",1);click(form,-1);assertTrue(form.isShowing());assertEquals(backup,store.exportBackup());click(dialog(),-1);click(form,-2);assertEquals(backup,store.exportBackup());
    }
    @Test public void recreationDoesNotConfirmDraft()throws Exception {PluggyClient.Snapshot snapshot=PluggyFixtures.snapshot();activity.showSnapshot(snapshot);idle();activity.review(snapshot.transactions.get(0));idle();AlertDialog form=dialog();choose(form,"Conta local",1);click(form,-1);controller.recreate();activity=controller.get();idle();assertTrue(store.entries().isEmpty());assertEquals(0,store.totalBalance());}
    @Test public void bankInvoicePaymentIsExplicitlyClassifiedOutsideExpenseResult()throws Exception {PluggyClient.Snapshot snapshot=PluggyFixtures.snapshot();activity.showSnapshot(snapshot);idle();activity.review(snapshot.transactions.get(0));idle();AlertDialog form=dialog();choose(form,"Conta local",1);choose(form,"Forma de pagamento",5);click(form,-1);click(dialog(),-1);assertEquals("card_payment",store.entries().get(0).method);assertEquals(-2590,store.totalBalance());}
    @Test public void missingPersonalConfigurationDoesNotStartAQueryOrChangeLedger()throws Exception {
        String backup=store.exportBackup();
        activity.query();idle();
        AlertDialog error=dialog();assertNotNull(error);assertTrue(error.isShowing());
        java.lang.reflect.Field task=PluggyActivity.class.getDeclaredField("task");task.setAccessible(true);
        assertNull("Configuration errors must be resolved before any network task",task.get(activity));
        assertEquals(backup,store.exportBackup());
    }
    private NativeApi prepareNativeQuery()throws Exception {
        NativeApi api=new NativeApi();activity.settings=fictionalSettings();activity.client=new PluggyPersonalClient(api,Clock.fixed(Instant.parse("2026-10-22T12:00:00Z"),ZoneOffset.UTC));
        ((EditText)find(screen(),"Data inicial")).setText("2026-10-20");((EditText)find(screen(),"Data final")).setText("2026-10-22");return api;
    }
    private AlertDialog requestToken(String clientId,String secret){
        activity.showConnectionSettings();idle();AlertDialog configuration=dialog();((EditText)find(configuration.getWindow().getDecorView(),"Client ID pessoal")).setText(clientId);((EditText)find(configuration.getWindow().getDecorView(),"Client Secret pessoal")).setText(secret);assertTrue(find(configuration.getWindow().getDecorView(),"Gerar Connect Token").performClick());idle();return configuration;
    }
    private PluggyPersonalClient tokenClient(TokenApi api){return new PluggyPersonalClient(api,Clock.fixed(Instant.parse("2026-10-22T12:00:00Z"),ZoneOffset.UTC));}
    private static final class TokenApi implements PluggyPersonalClient.Transport {
        static final String TOKEN="fixture-connect-token";final List<String> paths=new ArrayList<>();CountDownLatch entered,release;
        public PluggyPersonalClient.Response request(String method,URI uri,Map<String,String> headers,String body)throws IOException {
            assertEquals("https",uri.getScheme());assertEquals("api.pluggy.ai",uri.getHost());assertEquals("POST",method);assertNull(uri.getQuery());paths.add(uri.getPath());
            try{
                JSONObject payload=new JSONObject(body);
                if(uri.getPath().equals("/auth")){assertEquals(2,payload.length());assertEquals("fixture-client",payload.getString("clientId"));assertEquals("fixture-secret",payload.getString("clientSecret"));assertFalse(headers.containsKey("X-API-KEY"));return new PluggyPersonalClient.Response(200,"{\"apiKey\":\"fixture-api-key\"}");}
                assertEquals("/connect_token",uri.getPath());assertEquals(0,payload.length());assertEquals("fixture-api-key",headers.get("X-API-KEY"));
                if(entered!=null){entered.countDown();long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);boolean done=false;while(!done&&System.nanoTime()<deadline){try{done=release.await(100,TimeUnit.MILLISECONDS);}catch(InterruptedException ignored){/* Simulate a transport that returns after cancellation. */}}assertTrue("Test must release the pending response",done);}
                return new PluggyPersonalClient.Response(200,new JSONObject().put("accessToken",TOKEN).toString());
            }catch(JSONException ex){throw new IOException("Invalid fictional token response",ex);}
        }
    }
    private List<String> transactionRows(){
        ListView list=(ListView)find(screen(),"Transações Meu Pluggy");List<String> rows=new ArrayList<>();
        for(int i=list.getHeaderViewsCount();i<list.getAdapter().getCount();i++)rows.add(list.getAdapter().getItem(i).toString());return rows;
    }
    private void selectFilter(String description,int position){Spinner filter=(Spinner)find(screen(),description);assertNotNull(filter);filter.setSelection(position);idle();}
    private void clickFirstTransaction(){ListView list=(ListView)find(screen(),"Transações Meu Pluggy");assertFalse(transactionRows().isEmpty());assertTrue(list.performItemClick(null,list.getHeaderViewsCount(),0));idle();}
    private static final class NativeApi implements PluggyPersonalClient.Transport {
        final List<URI> calls=new ArrayList<>();
        @Override public PluggyPersonalClient.Response request(String method,URI uri,Map<String,String> headers,String body)throws IOException {
            calls.add(uri);assertEquals("https",uri.getScheme());assertEquals("api.pluggy.ai",uri.getHost());assertFalse(uri.toString().contains("fixture-secret"));
            try {
                if(uri.getPath().equals("/auth")){assertEquals("POST",method);JSONObject credentials=new JSONObject(body);assertEquals("fixture-client",credentials.getString("clientId"));assertEquals("fixture-secret",credentials.getString("clientSecret"));assertFalse(headers.containsKey("X-API-KEY"));return json(new JSONObject().put("apiKey","fixture-api-key"));}
                assertEquals("GET",method);assertEquals("fixture-api-key",headers.get("X-API-KEY"));assertNull(body);
                if(uri.getPath().equals("/items/item-a"))return json(new JSONObject().put("id","item-a").put("status","UPDATED").put("lastUpdatedAt","2026-10-21T12:00:00Z"));
                if(uri.getPath().equals("/accounts"))return json(new JSONObject().put("results",new JSONArray().put(account("bank-a","Conta nativa de teste","BANK","9876.54")).put(account("credit-a","Cartão nativo de teste","CREDIT","321.45"))).put("total",2));
                if(uri.getPath().equals("/v2/transactions")){
                    boolean bank=uri.getQuery().contains("accountId=bank-a");
                    JSONObject row=new JSONObject().put("id",bank?"posted-a":"pending-a").put("accountId",bank?"bank-a":"credit-a").put("type","DEBIT").put("status",bank?"POSTED":"PENDING").put("currencyCode","BRL").put("amount",bank?"25.90":"12.99").put("date",bank?"2026-10-20T00:00:00Z":"2026-10-21T00:00:00Z").put("description",bank?"Mercado pela API nativa":"Compra pendente pela API");
                    if(bank)row.put("operationType","PIX");
                    return json(new JSONObject().put("results",new JSONArray().put(row)).put("next",JSONObject.NULL));
                }
                throw new AssertionError("Unexpected endpoint "+uri);
            }catch(JSONException ex){throw new IOException("Invalid fictional test response",ex);}
        }
        private JSONObject account(String id,String name,String kind,String balance)throws JSONException {return new JSONObject().put("id",id).put("itemId","item-a").put("name",name).put("type",kind).put("currencyCode","BRL").put("balance",balance);}
        private PluggyPersonalClient.Response json(JSONObject body){return new PluggyPersonalClient.Response(200,body.toString());}
    }
    private PersonalPluggySettings fictionalSettings()throws Exception {
        String fixture=new JSONObject().put("clientId","fixture-client").put("clientSecret","fixture-secret").put("itemIds",new JSONArray().put("item-a")).toString();
        PersonalPluggySettings configured=new PersonalPluggySettings(context,new PluggySettings.Vault(){public String seal(String value,String purpose){return "fictional-ciphertext";}public String open(String value,String purpose){return fixture;}});
        configured.save("fixture-client","fixture-secret",Collections.singletonList("item-a"));return configured;
    }
    private void drainWorker()throws Exception {idle();Field field=PluggyActivity.class.getDeclaredField("worker");field.setAccessible(true);((ExecutorService)field.get(activity)).submit(()->{}).get(10,TimeUnit.SECONDS);idle();}
    private static List<String> allTexts(View view){List<String> values=new ArrayList<>();collectTexts(view,values);return values;}
    private static void collectTexts(View view,List<String> values){if(view instanceof TextView)values.add(((TextView)view).getText().toString());if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)collectTexts(((ViewGroup)view).getChildAt(i),values);}
    private static boolean containsText(List<String> texts,String search){for(String value:texts)if(value.replace('\u00a0',' ').replace('\u202f',' ').contains(search))return true;return false;}
    View screen(){return activity.findViewById(android.R.id.content);}
    static View find(View view,String label){if(view.getContentDescription()!=null&&label.contentEquals(view.getContentDescription()))return view;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){View found=find(((ViewGroup)view).getChildAt(i),label);if(found!=null)return found;}return null;}
    static AlertDialog dialog(){return ShadowAlertDialog.getLatestAlertDialog();}
    static void choose(AlertDialog form,String label,int position){idle();Spinner spinner=(Spinner)find(form.getWindow().getDecorView(),label);assertNotNull(spinner);spinner.setSelection(position);idle();}
    static void click(AlertDialog form,int button){idle();assertTrue(form.getButton(button).performClick());idle();}
    static void idle(){shadowOf(Looper.getMainLooper()).idle();}
}
