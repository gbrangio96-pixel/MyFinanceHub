package br.com.myfinancehub;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.*;
import android.content.pm.*;
import android.os.PowerManager;
import android.view.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,qualifiers="pt-rBR",application=FinanceApplication.class)
public final class AuthGateTest {
    private Context context;private final List<ActivityController<? extends Activity>> controllers=new ArrayList<>();
    @Before public void before(){context=RuntimeEnvironment.getApplication();deviceAvailable();AuthSession.lock();context.deleteDatabase("myfinancehub.db");context.getSharedPreferences("local_auth",0).edit().clear().commit();context.getSharedPreferences("personal_pluggy_private",0).edit().clear().commit();}
    @After public void after()throws Exception {for(ActivityController<? extends Activity> controller:controllers){Activity activity=controller.get();controller.pause().stop().destroy();Field worker=activity.getClass().getDeclaredField("worker");worker.setAccessible(true);assertTrue(((ExecutorService)worker.get(activity)).awaitTermination(10,TimeUnit.SECONDS));}controllers.clear();context.deleteDatabase("myfinancehub.db");context.getSharedPreferences("local_auth",0).edit().clear().commit();context.getSharedPreferences("personal_pluggy_private",0).edit().clear().commit();AuthSession.lock();}
    @Test public void loginIsTheOnlyExportedFinancialLauncher()throws Exception {
        PackageManager manager=context.getPackageManager();Intent launch=manager.getLaunchIntentForPackage(context.getPackageName());assertNotNull(launch);assertEquals(LoginActivity.class.getName(),launch.getComponent().getClassName());
        assertTrue(manager.getActivityInfo(new ComponentName(context,LoginActivity.class),0).exported);
        for(Class<? extends Activity> type:new Class[]{MainActivity.class,QuotesActivity.class,PluggyActivity.class})assertFalse(type.getSimpleName()+" must not be externally opened",manager.getActivityInfo(new ComponentName(context,type),0).exported);
    }
    @Test public void allFinancialActivitiesHideTheirDecorAndRequestLoginWhileLocked(){
        for(Class<? extends Activity> type:new Class[]{MainActivity.class,QuotesActivity.class,PluggyActivity.class}){
            AuthSession.lock();ActivityController<? extends Activity> controller=Robolectric.buildActivity(type).setup().visible();controllers.add(controller);Activity activity=controller.get();idle();
            // Android's makeVisible may reset DecorView after onResume. The financial content
            // must remain hidden even after that platform step, until a fresh login succeeds.
            assertEquals(type.getSimpleName()+" must keep its financial content hidden",View.INVISIBLE,activity.findViewById(android.R.id.content).getVisibility());
            Intent login=shadowOf(activity).getNextStartedActivity();assertNotNull(login);assertEquals(LoginActivity.class.getName(),login.getComponent().getClassName());assertTrue(login.getBooleanExtra("return_to_activity",false));assertFalse(AuthSession.isUnlocked());
        }
    }
    @Test public void shortBackgroundReturnKeepsLoginButExpiredGraceRequestsAuthentication(){
        AuthSessionTest.FakeTime clock=new AuthSessionTest.FakeTime();AuthSession.initialize(context,clock);
        AuthSession.unlock();ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).setup().visible();controllers.add(controller);MainActivity activity=controller.get();idle();assertNull(shadowOf(activity).getNextStartedActivity());
        controller.pause().stop();idle();assertTrue(AuthSession.isUnlocked());assertEquals(View.INVISIBLE,activity.findViewById(android.R.id.content).getVisibility());
        clock.advance(60_000);controller.restart().resume();idle();assertTrue(AuthSession.isUnlocked());assertEquals(View.VISIBLE,activity.findViewById(android.R.id.content).getVisibility());assertNull(shadowOf(activity).getNextStartedActivity());
        clock.advance(20);controller.pause().stop();idle();
        clock.advance(AuthSession.BACKGROUND_GRACE_MILLIS);
        assertFalse("A full background grace period must expire before resuming",AuthSession.isUnlocked());
        // restart() performs Android performRestart(true), including performStart.
        controller.restart().resume();idle();assertFalse(AuthSession.isUnlocked());assertEquals(View.INVISIBLE,activity.findViewById(android.R.id.content).getVisibility());assertEquals(LoginActivity.class.getName(),shadowOf(activity).getNextStartedActivity().getComponent().getClassName());
    }
    @Test public void explicitLockActionRequiresFreshLoginAndPreservesData()throws Exception {
        AuthSession.unlock();ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).setup().visible();controllers.add(controller);MainActivity activity=controller.get();idle();
        try(FinanceStore finance=new FinanceStore(context)){String before=finance.exportBackup();View screen=activity.findViewById(android.R.id.content);click(find(screen,"Ajustes"));screen=activity.findViewById(android.R.id.content);click(find(screen,"Bloquear aplicativo"));assertFalse(AuthSession.isUnlocked());Intent login=shadowOf(activity).getNextStartedActivity();assertNotNull(login);assertEquals(LoginActivity.class.getName(),login.getComponent().getClassName());assertEquals(before,finance.exportBackup());}
    }
    @Test @Config(sdk={28,35}) public void screenOffHidesAndFinishesAllFinancialWindowsWithoutChangingData(){
        AuthSession.unlock();
        for(Class<? extends Activity> type:new Class[]{MainActivity.class,QuotesActivity.class,PluggyActivity.class}){
            ActivityController<? extends Activity> controller=Robolectric.buildActivity(type).setup().visible();controllers.add(controller);idle();
            assertEquals(View.VISIBLE,controller.get().findViewById(android.R.id.content).getVisibility());
        }
        try(FinanceStore finance=new FinanceStore(context)){
            String before=finance.exportBackup();context.sendBroadcast(new Intent(Intent.ACTION_SCREEN_OFF));idle();
            assertFalse(AuthSession.isUnlocked());
            for(ActivityController<? extends Activity> controller:controllers){Activity activity=controller.get();assertTrue(activity.getClass().getSimpleName()+" must close its owned windows",activity.isFinishing());assertEquals(View.INVISIBLE,activity.findViewById(android.R.id.content).getVisibility());}
            assertEquals(before,finance.exportBackup());
            context.sendBroadcast(new Intent(Intent.ACTION_SCREEN_ON));context.sendBroadcast(new Intent(Intent.ACTION_USER_PRESENT));idle();assertFalse(AuthSession.isUnlocked());
        }
    }
    @Test public void returningToAKeyguardLockedPhoneCannotExposeFinancialContent(){
        AuthSessionTest.FakeTime clock=new AuthSessionTest.FakeTime();AuthSession.initialize(context,clock);AuthSession.unlock();
        ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).setup().visible();controllers.add(controller);MainActivity activity=controller.get();idle();
        controller.pause().stop();idle();clock.advance(60_000);
        // The state check must protect the screen even if Android has deferred SCREEN_OFF.
        shadowOf((KeyguardManager)context.getSystemService(Context.KEYGUARD_SERVICE)).setIsDeviceLocked(true);
        controller.restart().resume();idle();assertFalse(AuthSession.isUnlocked());assertTrue(activity.isFinishing());assertEquals(View.INVISIBLE,activity.findViewById(android.R.id.content).getVisibility());
    }
    @Test public void movingBetweenFinancialActivitiesDoesNotStartAnInactivityCountdown(){
        AuthSessionTest.FakeTime clock=new AuthSessionTest.FakeTime();AuthSession.initialize(context,clock);AuthSession.unlock();
        ActivityController<MainActivity> main=Robolectric.buildActivity(MainActivity.class).setup().visible();controllers.add(main);idle();
        ActivityController<QuotesActivity> quotes=Robolectric.buildActivity(QuotesActivity.class).setup().visible();controllers.add(quotes);idle();
        main.pause().stop();idle();clock.advance(20*60*1000);assertTrue(AuthSession.isUnlocked());
        quotes.pause().stop();idle();clock.advance(AuthSession.BACKGROUND_GRACE_MILLIS-1);assertTrue(AuthSession.isUnlocked());clock.advance(1);assertFalse(AuthSession.isUnlocked());
    }
    private void deviceAvailable(){shadowOf((PowerManager)context.getSystemService(Context.POWER_SERVICE)).setIsInteractive(true);shadowOf((KeyguardManager)context.getSystemService(Context.KEYGUARD_SERVICE)).setIsDeviceLocked(false);shadowOf((KeyguardManager)context.getSystemService(Context.KEYGUARD_SERVICE)).setKeyguardLocked(false);}
    private static View find(View view,String label){if(view.getContentDescription()!=null&&label.contentEquals(view.getContentDescription()))return view;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){View found=find(((ViewGroup)view).getChildAt(i),label);if(found!=null)return found;}return null;}
    private static void click(View view){assertNotNull(view);while(!view.isClickable()&&view.getParent() instanceof View)view=(View)view.getParent();assertTrue(view.performClick());idle();}
    private static void idle(){shadowOf(android.os.Looper.getMainLooper()).idle();}
}
