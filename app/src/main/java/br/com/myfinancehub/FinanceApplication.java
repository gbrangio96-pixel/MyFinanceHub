package br.com.myfinancehub;

import android.app.*;
import android.os.Bundle;
import android.view.WindowManager;

public class FinanceApplication extends Application {
    @Override public void onCreate(){super.onCreate();AuthSession.lock();registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks(){
        private int started;
        public void onActivityCreated(Activity activity,Bundle state){activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);}
        public void onActivityStarted(Activity activity){started++;}
        public void onActivityStopped(Activity activity){started=Math.max(0,started-1);if(started==0&&!activity.isChangingConfigurations())AuthSession.lock();}
        public void onActivityResumed(Activity a){}public void onActivityPaused(Activity a){}public void onActivitySaveInstanceState(Activity a,Bundle b){}public void onActivityDestroyed(Activity a){}
    });}
}
