package br.com.myfinancehub;

import android.app.Notification;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.provider.Settings;
import android.service.notification.StatusBarNotification;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import java.time.ZoneOffset;
import java.util.Collections;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=28)
public final class NotificationCaptureServiceTest {
    private Context context;private NotificationInbox inbox;private ServiceController<NotificationCaptureService> controller;private NotificationCaptureService service;private long now;
    @Before public void before(){context=RuntimeEnvironment.getApplication();context.deleteDatabase(NotificationInbox.DATABASE_NAME);context.deleteDatabase("myfinancehub.db");inbox=new NotificationInbox(context);now=System.currentTimeMillis();controller=Robolectric.buildService(NotificationCaptureService.class).create();service=controller.get();}
    @After public void after(){controller.destroy();inbox.close();context.deleteDatabase(NotificationInbox.DATABASE_NAME);context.deleteDatabase("myfinancehub.db");Settings.Secure.putString(context.getContentResolver(),"enabled_notification_listeners","");}

    @Test public void listenerIsAbsentFromTheManifestAndCannotBeResolved(){
        try{context.getPackageManager().getServiceInfo(new ComponentName(context,NotificationCaptureService.class),0);fail("Retired listener must not be exposed to Android");}catch(PackageManager.NameNotFoundException expected){}
        assertTrue(context.getPackageManager().queryIntentServices(new Intent("android.service.notification.NotificationListenerService").setPackage(context.getPackageName()),0).isEmpty());
    }
    @Test public void oldPermissionAndEnabledFlagsCannotCaptureOrChangePreviouslySavedData(){
        NotificationParser.Suggestion parsed=NotificationParser.parse("Compra aprovada","Compra de R$ 25,90 no cartão final 1234.",now,ZoneOffset.UTC);assertNotNull(parsed);
        NotificationInbox.Candidate old=NotificationLegacyFixtures.seed(context,"old",now,parsed);
        inbox.setAllowedPackages(Collections.singleton(NotificationLegacyFixtures.APP));NotificationLegacyFixtures.legacyEnabled(context,true);
        Settings.Secure.putString(context.getContentResolver(),"enabled_notification_listeners",new ComponentName(context,NotificationCaptureService.class).flattenToString());
        try(FinanceStore store=new FinanceStore(context)){
            store.saveReviewedNotification(new Models.Entry(0,store.accounts().get(0).id,0,0,old.description,old.date,old.type,old.method,old.sourceKey,old.amountCents));String backup=store.exportBackup();
            assertFalse(NotificationCaptureService.hasAccess(context));assertFalse(inbox.isEnabled());service.onNotificationPosted(post(purchase()));service.onNotificationPosted(null);
            assertEquals(1,inbox.pendingCount());assertEquals(old.sourceKey,inbox.pending().get(0).sourceKey);assertEquals(backup,store.exportBackup());
        }
    }
    @Test public void notificationContentsAreNeverInspectedEvenByCompatibilityCallback(){
        Notification notification=purchase();notification.extras.putCharSequence(Notification.EXTRA_TEXT,new CharSequence(){public int length(){throw new AssertionError("Notification body must not be inspected");}public char charAt(int i){throw new AssertionError();}public CharSequence subSequence(int start,int end){throw new AssertionError();}public String toString(){throw new AssertionError();}});
        service.onNotificationPosted(post(notification));assertEquals(0,inbox.pendingCount());
    }
    @Test public void reconnectingCannotReplayOrResumeNotificationCollection(){
        NotificationLegacyFixtures.legacyEnabled(context,true);inbox.setAllowedPackages(Collections.singleton(NotificationLegacyFixtures.APP));service.onListenerConnected();service.onNotificationPosted(post(purchase()));assertEquals(0,inbox.pendingCount());assertFalse(inbox.isEnabled());
    }
    @Test public void compatibilitySettingsHelperDoesNotOpenANotificationPermissionPage(){
        Intent intent=NotificationCaptureService.accessSettingsIntent(context);assertEquals(Settings.ACTION_SETTINGS,intent.getAction());assertNull(intent.getExtras());assertFalse(NotificationCaptureService.hasAccess(context));
    }
    private Notification purchase(){Notification n=new Notification();n.extras=new Bundle();n.extras.putCharSequence(Notification.EXTRA_TITLE,"Compra aprovada");n.extras.putCharSequence(Notification.EXTRA_TEXT,"Compra de R$ 25,90 no cartão final 1234.");return n;}
    private StatusBarNotification post(Notification notification){return new StatusBarNotification(NotificationLegacyFixtures.APP,NotificationLegacyFixtures.APP,1,null,10000,0,0,notification,android.os.Process.myUserHandle(),now);}
}
