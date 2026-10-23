package br.com.myfinancehub;

import android.app.AlertDialog;
import android.content.Context;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/** Notification capture is retired; old queue rows remain a read-only archive. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, qualifiers = "pt-rBR")
public final class NotificationFlowTest {
    private Context context;
    private ActivityController<MainActivity> controller;
    private MainActivity activity;
    private FinanceStore store;
    private NotificationInbox inbox;

    @Before public void before() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("appearance", Context.MODE_PRIVATE).edit().clear().commit();
        context.getSharedPreferences("MainActivity", Context.MODE_PRIVATE).edit().clear().commit();
        context.deleteDatabase("myfinancehub.db");
        context.deleteDatabase(NotificationInbox.DATABASE_NAME);
        store = new FinanceStore(context);
        inbox = new NotificationInbox(context);
    }

    @After public void after() throws Exception {
        AlertDialog latest = ShadowAlertDialog.getLatestAlertDialog();
        if (latest != null && latest.isShowing()) latest.dismiss();
        if (controller != null) {
            controller.pause().stop().destroy();
            Field field = MainActivity.class.getDeclaredField("worker");
            field.setAccessible(true);
            assertTrue("Activity must finish closing its database helpers",
                    ((ExecutorService) field.get(activity)).awaitTermination(10, TimeUnit.SECONDS));
        }
        if (store != null) store.close();
        if (inbox != null) inbox.close();
        if (context != null) {
            context.deleteDatabase("myfinancehub.db");
            context.deleteDatabase(NotificationInbox.DATABASE_NAME);
        }
    }

    @Test public void archiveHasNoCaptureSetupAndDoesNotRequestAndroidAccess() {
        start();openArchive();
        assertFalse(inbox.isEnabled());
        assertNoCaptureOrWriteControls();
        assertNull("Archive must never request notification permissions",
                shadowOf(activity).getNextStartedActivity());
        assertTrue(inbox.pending().isEmpty());
        assertTrue(store.entries().isEmpty());
    }

    @Test public void legacyPendingAlertIsReadableWithoutPromotingItToTheLedger() {
        NotificationInbox.Candidate candidate=legacyPix("old-pix");
        String financialBackup=store.exportBackup();
        start();openArchive();
        assertContains(screen(),"PIX recebido");
        assertContains(screen(),"R$ 25,90");
        assertContains(screen(),"Banco de demonstração");
        assertNoCaptureOrWriteControls();
        assertNull(byDescription(screen(),"Revisar notificação "+candidate.id));
        assertNull(byDescription(screen(),"Descartar notificação "+candidate.id));
        assertEquals(candidate.id,inbox.pending().get(0).id);
        assertEquals(financialBackup,store.exportBackup());
        assertTrue(store.entries().isEmpty());
        assertNull(shadowOf(activity).getNextStartedActivity());
    }

    @Test public void openingArchivePreservesAnAlertEvenWhenItsSourceWasAlreadyConfirmed() {
        NotificationInbox.Candidate candidate=legacyPix("already-confirmed");
        Models.Entry historical=new Models.Entry(0,store.accounts().get(0).id,0,0,
                candidate.description,candidate.date,candidate.type,candidate.method,
                candidate.sourceKey,candidate.amountCents);
        store.saveReviewedNotification(historical);
        String financialBackup=store.exportBackup();
        start();openArchive();
        assertContains(screen(),"PIX recebido");
        assertEquals("Opening an archive must not dismiss or mutate legacy rows",1,inbox.pendingCount());
        assertEquals(candidate.id,inbox.pending().get(0).id);
        assertEquals(financialBackup,store.exportBackup());
        assertEquals(1,store.entries().size());
        assertNoCaptureOrWriteControls();
    }

    @Test public void navigationAndRecreationPreserveLegacyQueueAndConfirmedData() {
        NotificationInbox.Candidate candidate=legacyPix("survive-recreation");
        String key="notification:"+"b".repeat(64);
        store.saveReviewedNotification(new Models.Entry(0,store.accounts().get(0).id,0,0,
                "Registro histórico confirmado",LocalDate.now().toString(),"expense","pix",key,1234));
        String financialBackup=store.exportBackup();
        NotificationLegacyFixtures.legacyEnabled(context,true);
        start();openArchive();
        controller.recreate();activity=controller.get();idle();
        assertContains(screen(),"HISTÓRICO PRESERVADO");
        assertContains(screen(),"PIX recebido");
        assertEquals(candidate.id,inbox.pending().get(0).id);
        assertEquals(financialBackup,store.exportBackup());
        assertFalse(inbox.isEnabled());
        assertNoCaptureOrWriteControls();
    }

    @Test public void transactionsCannotLaunchTheRetiredNotificationReviewFlow() {
        legacyPix("not-a-new-review");start();
        click(byDescription(screen(),"Lançamentos"));
        assertNull(byDescription(screen(),"Revisar notificações (1)"));
        assertFalse(texts(screen()).stream().anyMatch(text->text.startsWith("Revisar notificações")));
        assertEquals(1,inbox.pendingCount());
        assertTrue(store.entries().isEmpty());
    }

    private NotificationInbox.Candidate legacyPix(String key) {
        long postedAt=System.currentTimeMillis();
        NotificationParser.Suggestion suggestion=NotificationParser.parse("PIX recebido",
                "Você recebeu um Pix de R$ 25,90.",postedAt,ZoneId.systemDefault());
        assertNotNull(suggestion);
        return NotificationLegacyFixtures.seed(context,key,postedAt,suggestion);
    }

    private void assertNoCaptureOrWriteControls() {
        for(String label:new String[]{"Capturar novas notificações","1. Escolher apps",
                "2. Permitir acesso no Android","Abrir configurações","Salvar seleção",
                "Revisar","Confirmar e salvar","Descartar todas as sugestões"}) {
            assertNull("Retired notification action must be absent: "+label,byDescription(screen(),label));
            assertFalse("Retired notification label must be absent: "+label,texts(screen()).contains(label));
        }
        assertEquals("Archived alerts must not expose an editable finance form",0,countEditors(screen()));
    }

    private void start() {
        controller=Robolectric.buildActivity(MainActivity.class).setup().visible();
        activity=controller.get();idle();
    }
    private void openArchive() {
        click(byDescription(screen(),"Ajustes"));
        click(byDescription(screen(),"Histórico de notificações"));
        assertContains(screen(),"HISTÓRICO PRESERVADO");
    }
    private View screen(){return activity.findViewById(android.R.id.content);}
    private static void click(View view){
        assertNotNull("Missing clickable view",view);
        while(!view.isClickable()&&view.getParent() instanceof View)view=(View)view.getParent();
        assertTrue(view.isClickable());assertTrue(view.performClick());idle();
    }
    private static View byDescription(View view,String label){
        if(view.getContentDescription()!=null&&label.contentEquals(view.getContentDescription()))return view;
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){
            View found=byDescription(((ViewGroup)view).getChildAt(i),label);if(found!=null)return found;
        }
        return null;
    }
    private static int countEditors(View view){
        int count=view instanceof EditText?1:0;
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)count+=countEditors(((ViewGroup)view).getChildAt(i));
        return count;
    }
    private static List<String> texts(View view){List<String> result=new ArrayList<>();collect(view,result);return result;}
    private static void collect(View view,List<String> result){
        if(view instanceof TextView)result.add(((TextView)view).getText().toString().replace('\u00a0',' ').replace('\u202f',' '));
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)collect(((ViewGroup)view).getChildAt(i),result);
    }
    private static void assertContains(View view,String expected){
        for(String text:texts(view))if(text.contains(expected))return;
        fail("Missing text containing '"+expected+"' in "+texts(view));
    }
    private static void idle(){shadowOf(Looper.getMainLooper()).idle();}
}
