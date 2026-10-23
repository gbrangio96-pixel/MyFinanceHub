package br.com.myfinancehub;

import android.content.Context;
import android.os.SystemClock;
import java.lang.ref.WeakReference;

/** Last complete provider response, shared by native screens only for this app process. */
public final class PluggySnapshotCache {
    private static WeakReference<Context> owner=new WeakReference<>(null);
    private static PluggyClient.Snapshot snapshot;
    private static String revision="";
    private static long receivedAt;
    private PluggySnapshotCache(){}

    public static synchronized PluggyClient.Snapshot get(Context context){
        Context app=context.getApplicationContext();
        String current=new PersonalPluggySettings(app).revision();
        if(snapshot==null)return null;
        if(owner.get()!=app||current.isEmpty()||!current.equals(revision)
                ||SystemClock.elapsedRealtime()<receivedAt){clear(context);return null;}
        return snapshot;
    }
    /** A late response from an older configuration must never replace the current owner's data. */
    public static synchronized boolean publish(Context context,String expectedRevision,PluggyClient.Snapshot value){
        Context app=context.getApplicationContext();
        String current=new PersonalPluggySettings(app).revision();
        long now=SystemClock.elapsedRealtime();
        if(value==null||expectedRevision==null||expectedRevision.isEmpty()
                ||!expectedRevision.equals(current)||now<0)return false;
        owner=new WeakReference<>(app);snapshot=value;revision=current;receivedAt=now;return true;
    }
    public static synchronized boolean isRecent(Context context,long maxAgeMillis){
        return maxAgeMillis>0&&get(context)!=null&&SystemClock.elapsedRealtime()-receivedAt<maxAgeMillis;
    }
    public static synchronized void clear(Context context){
        owner.clear();snapshot=null;revision="";receivedAt=0;
    }
}
