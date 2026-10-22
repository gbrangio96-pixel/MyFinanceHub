package br.com.myfinancehub;

import android.app.KeyguardManager;
import android.content.Context;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;

/** In-process access lasts until screen-off or ten minutes in the background. */
public final class AuthSession {
    public static final long BACKGROUND_GRACE_MILLIS=10*60*1000L;
    interface TimeSource {long elapsedRealtime();int bootCount();}
    private static Context application;
    private static TimeSource time;
    private static boolean unlocked,foreground,backgroundLeaseActive;
    private static long startedAt,expiresAt,generation;
    private static int leaseBoot=-1;
    private AuthSession(){}

    public static synchronized void initialize(Context context){
        Context app=context.getApplicationContext();
        initialize(app,new TimeSource(){
            public long elapsedRealtime(){return SystemClock.elapsedRealtime();}
            public int bootCount(){
                try{return Settings.Global.getInt(app.getContentResolver(),Settings.Global.BOOT_COUNT);}
                catch(Settings.SettingNotFoundException|SecurityException ignored){return -1;}
            }
        });
    }
    static synchronized void initialize(Context context,TimeSource source){
        application=context.getApplicationContext();time=source;foreground=false;lock();
        // A dead process cannot observe intervening phone locks. Never restore a grant,
        // including the persisted lease used by 0.6.1; financial data is untouched.
        application.getSharedPreferences("auth_session",Context.MODE_PRIVATE).edit().clear().apply();
    }
    static synchronized boolean deviceAvailable(){
        if(application==null)return false;
        try{
            PowerManager power=(PowerManager)application.getSystemService(Context.POWER_SERVICE);
            KeyguardManager keyguard=(KeyguardManager)application.getSystemService(Context.KEYGUARD_SERVICE);
            return power!=null&&power.isInteractive()&&keyguard!=null
                    &&!keyguard.isDeviceLocked()&&!keyguard.isKeyguardLocked();
        }catch(SecurityException ignored){return false;}
    }
    public static synchronized boolean isUnlocked(){
        expireBackgroundIfNecessary();
        if(!unlocked)return false;
        if(!deviceAvailable()){lock();return false;}
        return true;
    }
    /** Bind authentication work to the state under which the user initiated it. */
    public static synchronized long generation(){expireBackgroundIfNecessary();return generation;}
    public static synchronized boolean unlockIfCurrent(long expectedGeneration){
        expireBackgroundIfNecessary();
        if(expectedGeneration!=generation)return false;
        if(!deviceAvailable()){lock();return false;}
        unlocked=true;
        if(time!=null)renewLease();
        return unlocked;
    }
    /** Only successful password or authenticated biometric callbacks grant access. */
    public static synchronized void unlock(){unlockIfCurrent(generation);}
    public static synchronized void lock(){unlocked=false;backgroundLeaseActive=false;generation++;startedAt=0;expiresAt=0;leaseBoot=-1;}

    public static synchronized void onForeground(){
        if(foreground)return;
        isUnlocked();foreground=true;backgroundLeaseActive=false;
    }
    public static synchronized void onBackground(){
        if(!foreground)return;
        isUnlocked();foreground=false;
        // Also bound password work queued while the login form was in the background.
        if(time!=null)renewLease();
    }
    private static void expireBackgroundIfNecessary(){if(!foreground&&backgroundLeaseActive&&!validLease())lock();}
    private static boolean validLease(){
        if(time==null)return false;
        long now=time.elapsedRealtime();int boot=time.bootCount();
        return startedAt>=0&&expiresAt>=startedAt&&expiresAt-startedAt==BACKGROUND_GRACE_MILLIS
                &&now>=startedAt&&now<expiresAt&&boot==leaseBoot;
    }
    private static void renewLease(){
        long now=time.elapsedRealtime();
        if(now<0||now>Long.MAX_VALUE-BACKGROUND_GRACE_MILLIS){lock();return;}
        startedAt=now;expiresAt=now+BACKGROUND_GRACE_MILLIS;leaseBoot=time.bootCount();backgroundLeaseActive=true;
    }
}
