package br.com.myfinancehub;

import android.app.*;
import android.content.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,qualifiers="pt-rBR",application=FinanceApplication.class)
public final class LoginActivityTest {
    private static final String USER="usuario",PASSWORD="MinhaSenha123@",NEW_PASSWORD="OutraSenha456!";
    private Context context;private ActivityController<LoginActivity> controller;private LoginActivity activity;private LocalAuthStore auth;
    @Before public void before(){context=RuntimeEnvironment.getApplication();context.getSharedPreferences("local_auth",0).edit().clear().commit();context.getSharedPreferences("local_biometrics",0).edit().clear().commit();AuthSession.lock();auth=new LocalAuthStore(context);}
    @After public void after()throws Exception {AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();if(dialog!=null&&dialog.isShowing())dialog.dismiss();if(controller!=null){controller.pause().stop().destroy();worker().awaitTermination(10,TimeUnit.SECONDS);}context.getSharedPreferences("local_auth",0).edit().clear().commit();context.getSharedPreferences("local_biometrics",0).edit().clear().commit();AuthSession.initialize(context);AuthSession.lock();}
    @Test public void setupProtectsPasswordFieldsAndRejectsWeakPasswordWithoutCreatingAccess()throws Exception {
        start();assertNotNull(find(screen(),"Criar acesso local"));
        for(String label:new String[]{"Senha","Confirmar senha"}){EditText field=edit(screen(),label);assertFalse(field.isSaveEnabled());assertEquals(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS,field.getImportantForAutofill());assertTrue(field.getFilterTouchesWhenObscured());}
        assertNotEquals(0,activity.getWindow().getAttributes().flags&WindowManager.LayoutParams.FLAG_SECURE);
        fillSetup("fraca","fraca");click(find(screen(),"Criar acesso local"));drainWorker();
        assertFalse(auth.configured());assertFalse(AuthSession.isUnlocked());assertNull(shadowOf(activity).getNextStartedActivity());assertTrue(texts(dialogView()).contains("letra maiúscula"));
    }
    @Test public void setupRequiresMatchingPasswordsBeforeDerivation()throws Exception {
        start();fillSetup(PASSWORD,NEW_PASSWORD);click(find(screen(),"Criar acesso local"));idle();assertFalse(auth.configured());assertFalse(AuthSession.isUnlocked());assertTrue(texts(dialogView()).contains("não são iguais"));
    }
    @Test public void setupShowsOneTimeRecoveryCodeBeforeOpeningFinancialScreen()throws Exception {
        start();fillSetup(PASSWORD,PASSWORD);click(find(screen(),"Criar acesso local"));drainWorker();assertTrue(auth.configured());assertTrue(AuthSession.isUnlocked());assertNull(shadowOf(activity).getNextStartedActivity());
        AlertDialog recovery=ShadowAlertDialog.getLatestAlertDialog();String code=recoveryCode(recovery);assertTrue(code.matches("[0-9A-F]{4}(?:-[0-9A-F]{4}){7}"));assertFalse(context.getSharedPreferences("local_auth",0).getAll().toString().contains(code));
        assertTrue(auth.verify(USER,PASSWORD.toCharArray()));click(recovery.getButton(-1));
        Intent launched=shadowOf(activity).getNextStartedActivity();assertNotNull(launched);assertEquals(MainActivity.class.getName(),launched.getComponent().getClassName());
    }
    @Test public void wrongPasswordAndCancelledRecoveryNeverAuthorize()throws Exception {
        auth.create(USER,PASSWORD.toCharArray());start();edit(screen(),"Login").setText(USER);edit(screen(),"Senha").setText("Errada123!");click(find(screen(),"Entrar"));drainWorker();assertFalse(AuthSession.isUnlocked());assertNull(shadowOf(activity).getNextStartedActivity());
        click(ShadowAlertDialog.getLatestAlertDialog().getButton(-1));click(find(screen(),"Recuperar acesso com meu código"));idle();AlertDialog recovery=ShadowAlertDialog.getLatestAlertDialog();click(recovery.getButton(-2));assertFalse(AuthSession.isUnlocked());assertTrue(auth.verify(USER,PASSWORD.toCharArray()));assertNull(shadowOf(activity).getNextStartedActivity());
    }
    @Test public void recoveryRotatesCodeAndRequiresPasswordLoginAfterReset()throws Exception {
        String oldCode=auth.create(USER,PASSWORD.toCharArray());start();click(find(screen(),"Recuperar acesso com meu código"));idle();AlertDialog recovery=ShadowAlertDialog.getLatestAlertDialog();View form=recovery.getWindow().getDecorView();
        edit(form,"Código de recuperação").setText(oldCode);edit(form,"Nova senha").setText(NEW_PASSWORD);edit(form,"Confirmar nova senha").setText(NEW_PASSWORD);click(recovery.getButton(-1));drainWorker();
        assertFalse(AuthSession.isUnlocked());assertNull(shadowOf(activity).getNextStartedActivity());String newCode=recoveryCode(ShadowAlertDialog.getLatestAlertDialog());assertNotEquals(oldCode,newCode);assertFalse(auth.verify(USER,PASSWORD.toCharArray()));assertTrue(auth.verify(USER,NEW_PASSWORD.toCharArray()));
    }
    @Test public void completedPasswordWorkWhileBackgroundedDoesNotUnlockUntilResume()throws Exception {
        auth.create(USER,PASSWORD.toCharArray());start();CountDownLatch release=new CountDownLatch(1);worker().submit(()->{try{release.await(10,TimeUnit.SECONDS);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}});
        edit(screen(),"Senha").setText(PASSWORD);click(find(screen(),"Entrar"));controller.pause().stop();release.countDown();drainWorker();
        assertFalse(AuthSession.isUnlocked());assertNull(shadowOf(activity).getNextStartedActivity());controller.start().resume();idle();assertTrue(AuthSession.isUnlocked());assertNotNull(shadowOf(activity).getNextStartedActivity());
    }
    @Test public void unavailableBiometricsRemainDisabledAndBackLocksAccess()throws Exception {
        auth.create(USER,PASSWORD.toCharArray());start();assertFalse(find(screen(),"Entrar com biometria").isEnabled());activity.onBackPressed();assertFalse(AuthSession.isUnlocked());assertNull(shadowOf(activity).getNextStartedActivity());
    }
    @Test public void coldLauncherUsesValidPersistedGraceAndDoesNotRenderAnotherPasswordForm()throws Exception {
        auth.create(USER,PASSWORD.toCharArray());SessionClock clock=new SessionClock();AuthSession.initialize(context,clock);AuthSession.unlock();AuthSession.onForeground();AuthSession.onBackground();clock.elapsed+=AuthSession.BACKGROUND_GRACE_MILLIS-1;
        AuthSession.initialize(context,clock);assertTrue("A valid same-boot lease must survive launcher recreation",AuthSession.isUnlocked());start();
        Intent launched=shadowOf(activity).getNextStartedActivity();assertNotNull(launched);assertEquals(MainActivity.class.getName(),launched.getComponent().getClassName());assertTrue(activity.isFinishing());
        View content=screen();if(content!=null){assertNull(find(content,"Senha"));assertNull(find(content,"Entrar"));}assertTrue(AuthSession.isUnlocked());
    }
    @Test public void coldLauncherKeepsPasswordLoginWhenPersistedGraceHasExpired()throws Exception {
        auth.create(USER,PASSWORD.toCharArray());SessionClock clock=new SessionClock();AuthSession.initialize(context,clock);AuthSession.unlock();AuthSession.onForeground();AuthSession.onBackground();clock.elapsed+=AuthSession.BACKGROUND_GRACE_MILLIS;
        AuthSession.initialize(context,clock);assertFalse("Ten full minutes must expire the persisted lease",AuthSession.isUnlocked());start();
        assertNull(shadowOf(activity).getNextStartedActivity());assertNotNull(find(screen(),"Senha"));assertNotNull(find(screen(),"Entrar"));assertFalse(AuthSession.isUnlocked());assertFalse(activity.isFinishing());
    }
    private static final class SessionClock implements AuthSession.TimeSource {
        long elapsed=100_000;
        public long elapsedRealtime(){return elapsed;}
        public int bootCount(){return 7;}
    }
    private void start(){controller=Robolectric.buildActivity(LoginActivity.class).setup().visible();activity=controller.get();idle();}
    private View screen(){return activity.findViewById(android.R.id.content);}
    private void fillSetup(String password,String confirmation){edit(screen(),"Login").setText(USER);edit(screen(),"Senha").setText(password);edit(screen(),"Confirmar senha").setText(confirmation);}
    private ExecutorService worker()throws Exception {Field field=LoginActivity.class.getDeclaredField("worker");field.setAccessible(true);return (ExecutorService)field.get(activity);}
    private void drainWorker()throws Exception {idle();worker().submit(()->{}).get(10,TimeUnit.SECONDS);idle();}
    private static View dialogView(){return ShadowAlertDialog.getLatestAlertDialog().getWindow().getDecorView();}
    private static String recoveryCode(AlertDialog dialog){List<String> values=new ArrayList<>();collect(dialog.getWindow().getDecorView(),values);for(String value:values)if(value.matches("[0-9A-F]{4}(?:-[0-9A-F]{4}){7}"))return value;throw new AssertionError("One-time recovery code missing");}
    private static EditText edit(View root,String label){View view=find(root,label);assertTrue("Missing field "+label,view instanceof EditText);return (EditText)view;}
    private static View find(View view,String label){if(view.getContentDescription()!=null&&label.contentEquals(view.getContentDescription()))return view;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){View found=find(((ViewGroup)view).getChildAt(i),label);if(found!=null)return found;}return null;}
    private static String texts(View view){List<String> values=new ArrayList<>();collect(view,values);return values.toString();}
    private static void collect(View view,List<String> values){if(view instanceof TextView)values.add(((TextView)view).getText().toString());if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)collect(((ViewGroup)view).getChildAt(i),values);}
    private static void click(View view){idle();assertNotNull(view);assertTrue(view.performClick());idle();}
    private static void idle(){shadowOf(Looper.getMainLooper()).idle();}
}
