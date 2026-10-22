package br.com.myfinancehub;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.provider.Settings;

/** Local authentication has a ten-minute grace period after the app leaves the foreground. */
public final class AuthSession {
    public static final long BACKGROUND_GRACE_MILLIS=10*60*1000L;
    private static final String PREFERENCES="auth_session";
    interface TimeSource {long elapsedRealtime();int bootCount();}
    private static SharedPreferences preferences;
    private static TimeSource time;
    private static boolean unlocked,foreground;
    private static long startedAt,expiresAt;
    private static int leaseBoot=-1;
    private AuthSession(){}

    /** Restore only a lease from this boot. Financial backups never include these preferences. */
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
        preferences=context.getApplicationContext().getSharedPreferences(PREFERENCES,Context.MODE_PRIVATE);
        time=source;unlocked=false;foreground=false;startedAt=0;expiresAt=0;leaseBoot=-1;
        try{
            if(preferences.getInt("version",0)==1){
                startedAt=preferences.getLong("started_elapsed",-1);expiresAt=preferences.getLong("expires_elapsed",-1);leaseBoot=preferences.getInt("boot_count",-1);
                unlocked=leaseBoot>=0&&validLease();
            }
        }catch(ClassCastException ignored){/* Corrupt state cannot grant access. */}
        if(!unlocked)clearLease();
    }
    public static synchronized boolean isUnlocked(){
        if(!unlocked)return false;
        if(!foreground&&!validLease()){unlocked=false;clearLease();return false;}
        return true;
    }
    /** Only successful password or authenticated biometric callbacks call this method. */
    public static synchronized void unlock(){
        unlocked=true;
        if(time!=null)renewLease();
    }
    /** Explicit blocking and credential recovery always invalidate the persisted grace period. */
    public static synchronized void lock(){unlocked=false;clearLease();}

    public static synchronized void onForeground(){
        if(foreground)return;
        boolean valid=isUnlocked();foreground=true;
        // Refresh the crash-recovery bound while an authenticated interface becomes active.
        if(valid&&time!=null)renewLease();
    }
    public static synchronized void onBackground(){
        if(!foreground)return;
        boolean valid=isUnlocked();foreground=false;
        if(valid&&time!=null)renewLease();
    }
    private static boolean validLease(){
        if(time==null)return false;
        long now=time.elapsedRealtime();int boot=time.bootCount();
        return startedAt>=0&&expiresAt>=startedAt&&expiresAt-startedAt==BACKGROUND_GRACE_MILLIS
                &&now>=startedAt&&now<expiresAt&&boot==leaseBoot;
    }
    // The lease must reach storage before Android can end the background process.
    @android.annotation.SuppressLint("ApplySharedPref")
    private static void renewLease(){
        long now=time.elapsedRealtime();
        if(now<0||now>Long.MAX_VALUE-BACKGROUND_GRACE_MILLIS){unlocked=false;clearLease();return;}
        startedAt=now;expiresAt=now+BACKGROUND_GRACE_MILLIS;leaseBoot=time.bootCount();
        if(preferences!=null){
            if(leaseBoot>=0){
                // A failed write keeps the current process session, but cannot create a future one.
                preferences.edit().putInt("version",1).putLong("started_elapsed",startedAt)
                        .putLong("expires_elapsed",expiresAt).putInt("boot_count",leaseBoot).commit();
            }else preferences.edit().clear().commit();
        }
    }
    // An explicit lock must invalidate the persisted grant synchronously.
    @android.annotation.SuppressLint("ApplySharedPref")
    private static void clearLease(){
        startedAt=0;expiresAt=0;leaseBoot=-1;
        if(preferences!=null)preferences.edit().clear().commit();
    }
}
