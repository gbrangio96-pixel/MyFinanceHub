package br.com.myfinancehub;

import android.app.Activity;
import android.content.*;
import android.content.pm.*;
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
    @Before public void before(){context=RuntimeEnvironment.getApplication();AuthSession.lock();context.deleteDatabase("myfinancehub.db");context.getSharedPreferences("local_auth",0).edit().clear().commit();context.getSharedPreferences("personal_pluggy_private",0).edit().clear().commit();}
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
    @Test public void backgroundingLocksAndReturningWithoutAuthenticationKeepsFinancialScreenHidden(){
        AuthSession.unlock();ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).setup().visible();controllers.add(controller);MainActivity activity=controller.get();idle();assertEquals(View.VISIBLE,activity.getWindow().getDecorView().getVisibility());assertNull(shadowOf(activity).getNextStartedActivity());
        controller.pause().stop();idle();assertFalse(AuthSession.isUnlocked());assertEquals(View.INVISIBLE,activity.getWindow().getDecorView().getVisibility());assertEquals(View.INVISIBLE,activity.findViewById(android.R.id.content).getVisibility());
        controller.start().resume();idle();assertFalse(AuthSession.isUnlocked());assertEquals(View.INVISIBLE,activity.getWindow().getDecorView().getVisibility());assertEquals(View.INVISIBLE,activity.findViewById(android.R.id.content).getVisibility());assertEquals(LoginActivity.class.getName(),shadowOf(activity).getNextStartedActivity().getComponent().getClassName());
    }
    @Test public void explicitLockActionRequiresFreshLoginAndPreservesData()throws Exception {
        AuthSession.unlock();ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).setup().visible();controllers.add(controller);MainActivity activity=controller.get();idle();
        try(FinanceStore finance=new FinanceStore(context)){String before=finance.exportBackup();View screen=activity.findViewById(android.R.id.content);click(find(screen,"Ajustes"));screen=activity.findViewById(android.R.id.content);click(find(screen,"Bloquear aplicativo"));assertFalse(AuthSession.isUnlocked());Intent login=shadowOf(activity).getNextStartedActivity();assertNotNull(login);assertEquals(LoginActivity.class.getName(),login.getComponent().getClassName());assertEquals(before,finance.exportBackup());}
    }
    private static View find(View view,String label){if(view.getContentDescription()!=null&&label.contentEquals(view.getContentDescription()))return view;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){View found=find(((ViewGroup)view).getChildAt(i),label);if(found!=null)return found;}return null;}
    private static void click(View view){assertNotNull(view);while(!view.isClickable()&&view.getParent() instanceof View)view=(View)view.getParent();assertTrue(view.performClick());idle();}
    private static void idle(){shadowOf(android.os.Looper.getMainLooper()).idle();}
}
