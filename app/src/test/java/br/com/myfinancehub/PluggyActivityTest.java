package br.com.myfinancehub;
import android.app.*;
import android.content.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import org.json.*;
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
    @Before public void before(){context=RuntimeEnvironment.getApplication();context.deleteDatabase("myfinancehub.db");context.getSharedPreferences("pluggy_private",0).edit().clear().commit();controller=Robolectric.buildActivity(PluggyActivity.class).setup().visible();activity=controller.get();store=new FinanceStore(context);idle();}
    @After public void after(){AlertDialog d=ShadowAlertDialog.getLatestAlertDialog();if(d!=null)d.dismiss();controller.pause().stop().destroy();store.close();context.deleteDatabase("myfinancehub.db");context.getSharedPreferences("pluggy_private",0).edit().clear().commit();}
    @Test public void openingRequiresPersonalConfigurationAndProtectsSecretFromSavedState(){assertTrue(store.entries().isEmpty());assertEquals(0,store.totalBalance());EditText token=(EditText)find(screen(),"Client Secret pessoal");assertNotNull(token);assertFalse(token.isSaveEnabled());assertEquals(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS,token.getImportantForAutofill());assertNull(find(screen(),"URL HTTPS do backend"));assertNull(find(screen(),"Token de acesso do app"));assertNotEquals(0,activity.getWindow().getAttributes().flags&WindowManager.LayoutParams.FLAG_SECURE);assertNull(shadowOf(activity).getNextStartedActivity());}
    @Test public void postedExpenseRequiresExplicitMappingReviewAndConfirmation()throws Exception {
        PluggyClient.Snapshot snapshot=PluggyFixtures.snapshot();activity.showSnapshot(snapshot);idle();assertTrue(store.entries().isEmpty());assertEquals(0,store.totalBalance());
        activity.review(snapshot.transactions.get(0));idle();AlertDialog form=dialog();
        click(form,-1);AlertDialog error=dialog();assertTrue(error.isShowing());assertTrue(form.isShowing());assertTrue(store.entries().isEmpty());click(error,-1);
        choose(form,"Conta local",1);click(form,-1);AlertDialog confirmation=dialog();assertNotSame(form,confirmation);assertTrue(store.entries().isEmpty());assertEquals("Confirmar e salvar",confirmation.getButton(-1).getText().toString());
        click(confirmation,-2);assertTrue(form.isShowing());assertTrue(store.entries().isEmpty());click(form,-1);click(dialog(),-1);
        assertEquals(1,store.entries().size());assertEquals(-2590,store.totalBalance());Models.Entry entry=store.entries().get(0);assertEquals(snapshot.transactions.get(0).sourceKey,entry.sourceKey);assertEquals("pix",entry.method);
        activity.review(snapshot.transactions.get(0));idle();assertEquals(1,store.entries().size());assertEquals(-2590,store.totalBalance());
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
    View screen(){return activity.findViewById(android.R.id.content);}
    static View find(View view,String label){if(view.getContentDescription()!=null&&label.contentEquals(view.getContentDescription()))return view;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){View found=find(((ViewGroup)view).getChildAt(i),label);if(found!=null)return found;}return null;}
    static AlertDialog dialog(){return ShadowAlertDialog.getLatestAlertDialog();}
    static void choose(AlertDialog form,String label,int position){idle();Spinner spinner=(Spinner)find(form.getWindow().getDecorView(),label);assertNotNull(spinner);spinner.setSelection(position);idle();}
    static void click(AlertDialog form,int button){idle();assertTrue(form.getButton(button).performClick());idle();}
    static void idle(){shadowOf(Looper.getMainLooper()).idle();}
}
