package br.com.myfinancehub;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Spinner;
import android.widget.TextView;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.URI;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/** The real MainActivity must present provider data without replaying the local ledger. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,qualifiers="pt-rBR",application=TestFinanceApplication.class)
public final class MainPluggyDashboardTest {
    private Context context;
    private MainActivity activity;
    private ActivityController<MainActivity> controller;
    private FinanceStore store;
    private PersonalPluggySettings settings;

    @Before public void before(){
        context=RuntimeEnvironment.getApplication();
        context.deleteDatabase("myfinancehub.db");
        context.getSharedPreferences("personal_pluggy_private",0).edit().clear().commit();
        context.getSharedPreferences("MainActivity",0).edit().clear().commit();
        PluggySnapshotCache.clear(context);AuthSession.unlock();store=new FinanceStore(context);
    }
    @After public void after()throws Exception {
        if(controller!=null){controller.pause().stop().destroy();assertTrue(worker().awaitTermination(10,TimeUnit.SECONDS));}
        store.close();context.deleteDatabase("myfinancehub.db");
        context.getSharedPreferences("personal_pluggy_private",0).edit().clear().commit();
        context.getSharedPreferences("MainActivity",0).edit().clear().commit();
        PluggySnapshotCache.clear(context);AuthSession.unlock();
    }

    @Test public void panelUsesOnlyProviderBalancesAndKeepsAccountsWithTheSameIdSeparate()throws Exception {
        configure("item-a","item-b");PluggyClient.Snapshot snapshot=accountSnapshot();assertTrue(PluggySnapshotCache.publish(context,settings.revision(),snapshot));
        store.saveEntry(new Models.Entry(0,store.accounts().get(0).id,0,0,"Movimento local antigo",LocalDate.now().toString(),"expense","other","legacy-expense",99999));
        store.saveInvestment(new Models.Investment(0,"Investimento local antigo","","CDB",400000,425000));String backup=store.exportBackup();
        start();
        assertContains(screen(),"Contas do Pluggy");assertContains(screen(),"22/10/2026 09:00");
        View alpha=accountCard("Banco Alpha"),beta=accountCard("Banco Beta"),credit=accountCard("Cartão externo");
        assertContains(alpha,"R$ 9.876,54");assertContains(beta,"R$ 123,45");assertContains(credit,"R$ 543,21");
        assertContains(alpha,"Compra Alpha");assertContains(alpha,"Pendente Alpha");assertContains(alpha,"Alpha recente");
        assertFalse(contains(alpha,"Compra Beta"));assertFalse(contains(beta,"Compra Alpha"));assertContains(beta,"Compra Beta");
        assertFalse("Only the most recent provider movements belong in the preview",contains(alpha,"Alpha antiga"));
        assertFalse(contains(screen(),"Movimento local antigo"));assertFalse(contains(screen(),"Investimento local antigo"));
        assertFalse(contains(screen(),"R$ 4.250,00"));assertFalse(contains(screen(),"R$ 999,99"));
        assertEquals(backup,store.exportBackup());assertEquals(1,store.entries().size());
    }
    @Test public void selectingAnAccountAndHidingValuesDoesNotExposeAnotherAccountsData()throws Exception {
        configure("item-a","item-b");assertTrue(PluggySnapshotCache.publish(context,settings.revision(),accountSnapshot()));start();
        selectAccount("Banco Alpha");assertNotNull(accountCard("Banco Alpha"));assertNull(find(screen(),"Conta do painel Banco Beta"));assertNull(find(screen(),"Conta do painel Cartão externo"));
        click(find(screen(),"Ocultar valores"));
        for(String privateValue:new String[]{"9.876,54","123,45","543,21","25,90","12,99","Compra Alpha","Pendente Alpha","Alpha recente"})
            assertFalse("Hidden values must stay out of text and accessibility: "+privateValue,allContent(screen()).contains(privateValue));
        assertNull(find(screen(),"Conta do painel Banco Beta"));assertNull(find(screen(),"Conta do painel Cartão externo"));
        assertTrue(((Spinner)find(screen(),"Conta do painel")).getSelectedItem().toString().contains("Banco Alpha"));
        click(find(screen(),"Mostrar valores"));assertContains(accountCard("Banco Alpha"),"R$ 9.876,54");
    }
    @Test public void refreshUsesTheRealApiClientAndDoesNotImportOrModifyExistingEntries()throws Exception {
        configure();store.saveEntry(new Models.Entry(0,store.accounts().get(0).id,0,0,"Valor antigo ignorado",LocalDate.now().toString(),"income","other","legacy-income",700000));String backup=store.exportBackup();
        start();NativeApi api=new NativeApi();set("pluggyClient",new PluggyPersonalClient(api,Clock.systemUTC()));
        activity.refreshPluggyPanel();drainWorker();
        assertEquals(Arrays.asList("/auth","/items/item-a","/accounts","/v2/transactions"),api.paths);
        assertContains(accountCard("Conta recebida da API"),"R$ 2.345,67");assertContains(screen(),"Despesa recebida da API");
        assertFalse(contains(screen(),"Valor antigo ignorado"));assertFalse(contains(screen(),"R$ 7.000,00"));
        assertEquals(backup,store.exportBackup());assertEquals(1,store.entries().size());
        assertNotNull(PluggySnapshotCache.get(context));assertNull(shadowOf(activity).getNextStartedActivity());
    }
    @Test public void enteringTheConfiguredPanelAutomaticallyReadsTheApiAndReusesTheRecentQuery()throws Exception {
        configure();settings.setConsultOnEntry(true);NativeApi api=new NativeApi();
        controller=Robolectric.buildActivity(MainActivity.class).create().start();activity=controller.get();
        set("pluggySettings",settings);set("pluggyClient",new PluggyPersonalClient(api,Clock.systemUTC()));
        controller.resume().visible();drainWorker();
        assertEquals(Arrays.asList("/auth","/items/item-a","/accounts","/v2/transactions"),api.paths);
        assertContains(accountCard("Conta recebida da API"),"R$ 2.345,67");assertTrue(store.entries().isEmpty());
        controller.pause().stop();controller.restart().resume();drainWorker();
        assertEquals("A recent complete query is shared instead of requesting the same accounts again",4,api.paths.size());
        assertContains(accountCard("Conta recebida da API"),"R$ 2.345,67");
    }
    @Test public void anUnreadableSavedConnectionDoesNotLoopOrFallBackToLocalBalances()throws Exception {
        java.util.concurrent.atomic.AtomicInteger opens=new java.util.concurrent.atomic.AtomicInteger();
        settings=new PersonalPluggySettings(context,new PluggySettings.Vault(){
            public String seal(String value,String purpose){return "unreadable-private-ciphertext";}
            public String open(String value,String purpose)throws Exception{opens.incrementAndGet();throw new IOException("private-fixture-secret");}
        });
        settings.save("fixture-client","fixture-secret",Arrays.asList("item-a"));settings.setConsultOnEntry(true);
        store.saveEntry(new Models.Entry(0,store.accounts().get(0).id,0,0,"Teste local excluído",LocalDate.now().toString(),"income","other","old-test",990000));String backup=store.exportBackup();
        controller=Robolectric.buildActivity(MainActivity.class).create().start();activity=controller.get();set("pluggySettings",settings);
        controller.resume().visible();drainWorker();
        assertEquals("One failed automatic attempt must not rerender into an endless retry",1,opens.get());
        assertContains(screen(),"Não foi possível abrir a conexão");assertFalse(contains(screen(),"private-fixture-secret"));
        assertFalse(contains(screen(),"R$ 9.900,00"));assertFalse(contains(screen(),"Teste local excluído"));assertEquals(backup,store.exportBackup());
    }
    @Test public void failedRefreshRetainsThePreviousProviderSnapshotAndItsObservationTime()throws Exception {
        configure("item-a","item-b");PluggyClient.Snapshot before=accountSnapshot();assertTrue(PluggySnapshotCache.publish(context,settings.revision(),before));start();
        set("pluggyClient",new PluggyPersonalClient((method,uri,headers,body)->{throw new IOException("private-provider-payload");},Clock.systemUTC()));
        activity.refreshPluggyPanel();drainWorker();
        assertSame(before,PluggySnapshotCache.get(context));assertContains(accountCard("Banco Alpha"),"R$ 9.876,54");
        assertContains(screen(),"Não foi possível atualizar");assertContains(screen(),"Exibindo a última consulta.");assertContains(screen(),"22/10/2026 09:00");
        assertFalse(contains(screen(),"private-provider-payload"));assertTrue(store.entries().isEmpty());
    }
    @Test public void unconfiguredOrLockedPanelNeverRequestsProviderData()throws Exception {
        start();NativeApi api=new NativeApi();set("pluggyClient",new PluggyPersonalClient(api,Clock.systemUTC()));activity.refreshPluggyPanel();drainWorker();
        assertTrue(api.paths.isEmpty());assertContains(screen(),"Configure o Meu Pluggy para consultar suas contas.");
        configure();set("pluggySettings",settings);AuthSession.lock();activity.refreshPluggyPanel();drainWorker();
        assertTrue(api.paths.isEmpty());assertTrue(store.entries().isEmpty());assertNull(PluggySnapshotCache.get(context));
    }
    @Test public void screenLockRejectsAResponseThatCompletesAfterThePanelWasClosed()throws Exception {
        configure();start();NativeApi api=new NativeApi();api.entered=new CountDownLatch(1);api.release=new CountDownLatch(1);set("pluggyClient",new PluggyPersonalClient(api,Clock.systemUTC()));
        String backup=store.exportBackup();activity.refreshPluggyPanel();assertTrue(api.entered.await(10,TimeUnit.SECONDS));
        try{context.sendBroadcast(new Intent(Intent.ACTION_SCREEN_OFF));idle();assertFalse(AuthSession.isUnlocked());assertTrue(activity.isFinishing());}
        finally{api.release.countDown();}
        drainWorker();assertNull(PluggySnapshotCache.get(context));assertEquals(backup,store.exportBackup());assertEquals(View.INVISIBLE,screen().getVisibility());
    }

    private void configure(String... itemIds)throws Exception {
        if(itemIds.length==0)itemIds=new String[]{"item-a"};
        List<String> items=Arrays.asList(itemIds);
        String raw=new JSONObject().put("clientId","fixture-client").put("clientSecret","fixture-secret").put("itemIds",new JSONArray(items)).toString();
        settings=new PersonalPluggySettings(context,new PluggySettings.Vault(){public String seal(String value,String purpose){return "fictional-panel-ciphertext";}public String open(String value,String purpose){return raw;}});
        settings.save("fixture-client","fixture-secret",items);settings.setConsultOnEntry(false);
    }
    private void start()throws Exception {
        controller=Robolectric.buildActivity(MainActivity.class).setup().visible();activity=controller.get();if(settings!=null)set("pluggySettings",settings);idle();
    }
    private void set(String name,Object value)throws Exception {Field f=MainActivity.class.getDeclaredField(name);f.setAccessible(true);f.set(activity,value);}
    private ExecutorService worker()throws Exception {Field f=MainActivity.class.getDeclaredField("worker");f.setAccessible(true);return (ExecutorService)f.get(activity);}
    private void drainWorker()throws Exception {idle();worker().submit(()->{}).get(10,TimeUnit.SECONDS);idle();}
    private View screen(){return activity.findViewById(android.R.id.content);}
    private View accountCard(String name){View card=find(screen(),"Conta do painel "+name);assertNotNull("Provider account missing: "+name,card);return card;}
    private void selectAccount(String name){Spinner spinner=(Spinner)find(screen(),"Conta do painel");assertNotNull(spinner);for(int i=1;i<spinner.getCount();i++)if(spinner.getItemAtPosition(i).toString().contains(name)){spinner.setSelection(i);idle();return;}fail("Account not in filter: "+name);}
    private static void click(View view){assertNotNull(view);while(!view.isClickable()&&view.getParent() instanceof View)view=(View)view.getParent();assertTrue(view.performClick());idle();}
    private static void idle(){shadowOf(Looper.getMainLooper()).idle();}
    private static View find(View view,String description){if(view.getContentDescription()!=null&&description.contentEquals(view.getContentDescription()))return view;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){View found=find(((ViewGroup)view).getChildAt(i),description);if(found!=null)return found;}return null;}
    private static String allContent(View view){StringBuilder b=new StringBuilder();if(view instanceof TextView)b.append(((TextView)view).getText()).append('\n');if(view.getContentDescription()!=null)b.append(view.getContentDescription()).append('\n');if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)b.append(allContent(((ViewGroup)view).getChildAt(i)));return b.toString().replace('\u00a0',' ').replace('\u202f',' ');}
    private static boolean contains(View view,String text){return allContent(view).contains(text);}
    private static void assertContains(View view,String text){assertTrue("Missing provider text: "+text+" in "+allContent(view),contains(view,text));}
    private static PluggyClient.Snapshot accountSnapshot()throws Exception {
        LocalDate from=PluggyFixtures.DAY.minusDays(2),to=PluggyFixtures.DAY.plusDays(2);JSONObject root=PluggyFixtures.data();root.put("range",new JSONObject().put("from",from.toString()).put("to",to.toString()));
        root.getJSONArray("connections").put(new JSONObject().put("externalId","item-b").put("status","UPDATED").put("providerUpdatedAt","2026-10-21T12:00:00Z"));
        root.put("accounts",new JSONArray().put(account("bank-a","item-a","Banco Alpha","BANK",987654)).put(account("bank-a","item-b","Banco Beta","BANK",12345)).put(account("credit-a","item-a","Cartão externo","CREDIT",54321)));
        root.put("transactions",new JSONArray().put(row("alpha-old","bank-a","item-a","Alpha antiga",from,"POSTED",1000)).put(row("alpha-one","bank-a","item-a","Compra Alpha",PluggyFixtures.DAY,"POSTED",2590)).put(row("alpha-pending","bank-a","item-a","Pendente Alpha",to.minusDays(1),"PENDING",1299)).put(row("alpha-recent","bank-a","item-a","Alpha recente",to,"POSTED",555)).put(row("beta-one","bank-a","item-b","Compra Beta",to,"POSTED",4567)).put(row("credit-one","credit-a","item-a","Compra no cartão externo",to,"POSTED",987)));
        return PluggyClient.parse(root.toString(),from,to);
    }
    private static JSONObject account(String id,String item,String name,String kind,long balance)throws JSONException{return new JSONObject().put("externalId",id).put("itemId",item).put("name",name).put("kind",kind).put("currency","BRL").put("reportedBalanceCents",Long.toString(balance));}
    private static JSONObject row(String id,String account,String item,String description,LocalDate day,String status,long amount)throws Exception{return new JSONObject().put("externalId",id).put("externalAccountId",account).put("itemId",item).put("providerTransactionId",JSONObject.NULL).put("identityKind","id").put("sourceKey",PluggyFixtures.key(item,account,"id",id)).put("direction","expense").put("status",status).put("amountCents",Long.toString(amount)).put("date",day.toString()).put("sourceDate",day+"T00:00:00Z").put("description",description).put("method","other");}
    private static final class NativeApi implements PluggyPersonalClient.Transport {
        final List<String> paths=new ArrayList<>();CountDownLatch entered,release;
        @Override public PluggyPersonalClient.Response request(String method,URI uri,Map<String,String> headers,String body)throws IOException {
            paths.add(uri.getPath());assertEquals("https",uri.getScheme());assertEquals("api.pluggy.ai",uri.getHost());assertNull(uri.getUserInfo());
            try{
                if(uri.getPath().equals("/auth")){
                    assertEquals("POST",method);JSONObject auth=new JSONObject(body);assertEquals("fixture-client",auth.getString("clientId"));assertEquals("fixture-secret",auth.getString("clientSecret"));assertFalse(headers.containsKey("X-API-KEY"));
                    if(entered!=null){entered.countDown();long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);boolean done=false;while(!done&&System.nanoTime()<deadline)try{done=release.await(100,TimeUnit.MILLISECONDS);}catch(InterruptedException ignored){}assertTrue("Release pending transport",done);}
                    return json(new JSONObject().put("apiKey","fixture-api-key"));
                }
                assertEquals("GET",method);assertEquals("fixture-api-key",headers.get("X-API-KEY"));assertNull(body);
                if(uri.getPath().equals("/items/item-a"))return json(new JSONObject().put("id","item-a").put("status","UPDATED").put("lastUpdatedAt","2026-10-22T12:00:00Z"));
                if(uri.getPath().equals("/accounts"))return json(new JSONObject().put("results",new JSONArray().put(new JSONObject().put("id","api-bank").put("itemId","item-a").put("name","Conta recebida da API").put("type","BANK").put("currencyCode","BRL").put("balance","2345.67"))).put("total",1));
                if(uri.getPath().equals("/v2/transactions")){LocalDate day=LocalDate.now(ZoneId.of("America/Sao_Paulo")).minusDays(1);return json(new JSONObject().put("results",new JSONArray().put(new JSONObject().put("id","api-expense").put("accountId","api-bank").put("type","DEBIT").put("status","POSTED").put("currencyCode","BRL").put("amount","25.90").put("date",day+"T00:00:00Z").put("description","Despesa recebida da API"))).put("next",JSONObject.NULL));}
                throw new AssertionError("Unexpected endpoint: "+uri);
            }catch(JSONException ex){throw new IOException("Invalid fictional API response",ex);}
        }
        private static PluggyPersonalClient.Response json(JSONObject object){return new PluggyPersonalClient.Response(200,object.toString());}
    }
}
