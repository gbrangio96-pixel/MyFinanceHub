package br.com.myfinancehub;

import android.app.AlertDialog;
import android.content.Context;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.Spinner;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/** The actual investment screen must organize exact stored values without modifying the portfolio. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=28,qualifiers="pt-rBR")
public final class InvestmentOrganizationActivityTest {
    private Context context;private FinanceStore store;private MainActivity activity;private ActivityController<MainActivity> controller;
    @Before public void before(){
        context=RuntimeEnvironment.getApplication();context.deleteDatabase("myfinancehub.db");context.getSharedPreferences("MainActivity",0).edit().clear().commit();store=new FinanceStore(context);
        seed("Fundo Beta","Fundo","daily","2035-01-01",10000);seed("CDB Alfa","CDB","at_maturity","2028-01-01",30000);seed("Sem dados","","unknown","",20000);
        controller=Robolectric.buildActivity(MainActivity.class).setup();activity=controller.get();idle();click(find(screen(),"Carteira"));
    }
    @After public void after()throws Exception {
        AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();if(dialog!=null&&dialog.isShowing())dialog.dismiss();controller.pause().stop().destroy();Field worker=MainActivity.class.getDeclaredField("worker");worker.setAccessible(true);assertTrue(((ExecutorService)worker.get(activity)).awaitTermination(10,TimeUnit.SECONDS));store.close();context.deleteDatabase("myfinancehub.db");context.getSharedPreferences("MainActivity",0).edit().clear().commit();
    }
    @Test public void eachOrganizationUsesTheRequestedFieldAndKeepsUninformedValuesLast(){
        String before=store.exportBackup();assertOrder("CDB Alfa","Fundo Beta","Sem dados");
        organize(1);assertOrder("Fundo Beta","CDB Alfa","Sem dados");
        organize(2);assertOrder("CDB Alfa","Fundo Beta","Sem dados");
        organize(3);assertOrder("CDB Alfa","Sem dados","Fundo Beta");
        assertEquals(before,store.exportBackup());assertEquals(0,store.totalBalance());
    }
    @Test public void organizationSelectionSurvivesActivityRecreation(){
        organize(3);controller.recreate();activity=controller.get();idle();assertEquals(3,((Spinner)find(screen(),"Organizar investimentos por")).getSelectedItemPosition());assertOrder("CDB Alfa","Sem dados","Fundo Beta");
    }
    @Test public void editingLiquidityAndMaturityPreservesAmountsAndCanClearTheDate(){
        Models.Investment original=byName("CDB Alfa");click(card("CDB Alfa"));AlertDialog form=ShadowAlertDialog.getLatestAlertDialog();
        Spinner liquidity=(Spinner)find(form.getWindow().getDecorView(),"Liquidez");liquidity.setSelection(2);((EditText)find(form.getWindow().getDecorView(),"Vencimento / validade (opcional)")).setText("2031-02-03");form.getButton(-1).performClick();idle();
        Models.Investment saved=byName("CDB Alfa");assertEquals("up_to30",saved.liquidity);assertEquals("2031-02-03",saved.maturityDate);assertEquals(original.investedCents,saved.investedCents);assertEquals(original.currentCents,saved.currentCents);assertEquals(original.kind,saved.kind);
        click(card("CDB Alfa"));form=ShadowAlertDialog.getLatestAlertDialog();click(find(form.getWindow().getDecorView(),"Limpar vencimento"));form.getButton(-1).performClick();idle();assertEquals("",byName("CDB Alfa").maturityDate);assertEquals("up_to30",byName("CDB Alfa").liquidity);
    }
    private void seed(String name,String kind,String liquidity,String maturity,long applied){Models.Investment investment=new Models.Investment(0,name,"",kind,applied,applied+100);investment.liquidity=liquidity;investment.maturityDate=maturity;store.saveInvestment(investment);}
    private Models.Investment byName(String name){for(Models.Investment investment:store.investments())if(investment.name.equals(name))return investment;throw new AssertionError(name);}
    private View screen(){return activity.findViewById(android.R.id.content);}
    private View card(String name){return cards(screen()).stream().filter(view->view.getContentDescription().toString().startsWith("Editar investimento "+name+",")).findFirst().orElseThrow(AssertionError::new);}
    private void organize(int position){Spinner spinner=(Spinner)find(screen(),"Organizar investimentos por");assertNotNull(spinner);spinner.setSelection(position);idle();}
    private void assertOrder(String... names){List<View> rows=cards(screen());assertEquals(names.length,rows.size());for(int index=0;index<names.length;index++)assertTrue(rows.get(index).getContentDescription().toString(),rows.get(index).getContentDescription().toString().startsWith("Editar investimento "+names[index]+","));}
    private static List<View> cards(View root){ArrayList<View> result=new ArrayList<>();collectCards(root,result);return result;}
    private static void collectCards(View view,List<View> rows){if(view.getContentDescription()!=null&&view.getContentDescription().toString().startsWith("Editar investimento "))rows.add(view);if(view instanceof ViewGroup)for(int index=0;index<((ViewGroup)view).getChildCount();index++)collectCards(((ViewGroup)view).getChildAt(index),rows);}
    private static View find(View view,String description){if(view.getContentDescription()!=null&&description.contentEquals(view.getContentDescription()))return view;if(view instanceof ViewGroup)for(int index=0;index<((ViewGroup)view).getChildCount();index++){View found=find(((ViewGroup)view).getChildAt(index),description);if(found!=null)return found;}return null;}
    private static void click(View view){assertNotNull(view);while(!view.isClickable()&&view.getParent() instanceof View)view=(View)view.getParent();assertTrue(view.performClick());idle();}
    private static void idle(){shadowOf(Looper.getMainLooper()).idle();}
}
