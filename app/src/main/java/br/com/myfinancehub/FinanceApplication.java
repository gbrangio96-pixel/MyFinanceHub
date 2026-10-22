package br.com.myfinancehub;

import android.app.*;
import android.content.*;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import java.util.HashSet;
import java.util.Set;

public class FinanceApplication extends Application {
    private final Set<Activity> activities=new HashSet<>();
    @Override public void onCreate(){
        super.onCreate();AuthSession.initialize(this);
        IntentFilter screen=new IntentFilter(Intent.ACTION_SCREEN_OFF);screen.addAction(Intent.ACTION_SCREEN_ON);
        BroadcastReceiver receiver=new BroadcastReceiver(){
            @Override public void onReceive(Context context,Intent intent){
                if(Intent.ACTION_SCREEN_OFF.equals(intent.getAction())||!AuthSession.deviceAvailable())blockForDevice();
            }
        };
        // Both actions are protected system broadcasts; no custom/exported entry point.
        if(Build.VERSION.SDK_INT>=33)registerReceiver(receiver,screen,Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(receiver,screen);
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks(){
            private int started;
            public void onActivityCreated(Activity activity,Bundle state){activities.add(activity);activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);}
            public void onActivityStarted(Activity activity){if(started++==0)AuthSession.onForeground();checkDevice();}
            public void onActivityStopped(Activity activity){started=Math.max(0,started-1);if(started==0&&!activity.isChangingConfigurations())AuthSession.onBackground();}
            public void onActivityResumed(Activity activity){checkDevice();}
            public void onActivityPaused(Activity activity){}
            public void onActivitySaveInstanceState(Activity activity,Bundle state){}
            public void onActivityDestroyed(Activity activity){activities.remove(activity);}
        });
    }
    private void checkDevice(){if(!AuthSession.deviceAvailable())blockForDevice();}
    private void blockForDevice(){
        AuthSession.lock();LocalBiometrics.cancelPending();
        for(Activity activity:new HashSet<>(activities)){
            activity.getWindow().getDecorView().setVisibility(View.INVISIBLE);
            View content=activity.findViewById(android.R.id.content);if(content!=null)content.setVisibility(View.INVISIBLE);
            // Finishing also removes every owned dialog window, including financial reviews
            // and recovery codes. Do not launch an activity over the phone lock screen.
            activity.finish();
        }
    }
}
