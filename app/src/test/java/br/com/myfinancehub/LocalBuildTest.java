package br.com.myfinancehub;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import android.os.Looper;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.view.View;
import android.view.ViewGroup;
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

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/** Single APK restores optional capture while preserving confirmed financial records. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, qualifiers = "pt-rBR")
public final class LocalBuildTest {
    private static final String OLD_QUEUE = "myfinancehub-notifications.db";
    private static final String OLD_SERVICE = "br.com.myfinancehub.NotificationCaptureService";
    private Context context;
    private FinanceStore store;
    private ActivityController<MainActivity> controller;
    private MainActivity activity;

    @Before public void before() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("appearance", Context.MODE_PRIVATE).edit().clear().commit();
        context.getSharedPreferences("MainActivity", Context.MODE_PRIVATE).edit().clear().commit();
        context.deleteDatabase("myfinancehub.db");
        context.deleteDatabase(OLD_QUEUE);
        store = new FinanceStore(context);
    }

    @After public void after() throws Exception {
        if (controller != null) {
            controller.pause().stop().destroy();
            Field field = MainActivity.class.getDeclaredField("worker");
            field.setAccessible(true);
            assertTrue("Activity must finish closing its database helper",
                    ((ExecutorService) field.get(activity)).awaitTermination(10, TimeUnit.SECONDS));
        }
        if (store != null) store.close();
        if (context != null) {
            Settings.Secure.putString(context.getContentResolver(), "enabled_notification_listeners", "");
            context.deleteDatabase("myfinancehub.db");
            context.deleteDatabase(OLD_QUEUE);
        }
    }

    @Test public void listenerIsPermissionProtectedAndCaptureIsInitiallyDisabled() throws Exception {
        android.content.pm.ServiceInfo service=context.getPackageManager().getServiceInfo(new ComponentName(context,NotificationCaptureService.class),0);
        assertTrue(service.exported);assertEquals("android.permission.BIND_NOTIFICATION_LISTENER_SERVICE",service.permission);
        try(NotificationInbox inbox=new NotificationInbox(context)) {
            assertFalse(inbox.isEnabled());assertTrue(inbox.allowedPackages().isEmpty());
        }
        start(null);assertTrue(store.entries().isEmpty());assertEquals(0,store.totalBalance());
    }

    @Test public void historicalConfirmedRecordsSurviveNavigationAndRecreation() {
        String key="notification:"+new String(new char[64]).replace('\0','a');
        store.saveReviewedNotification(new Models.Entry(0,store.accounts().get(0).id,0,0,"Registro preservado",LocalDate.now().toString(),"expense","pix",key,1234));
        String backup=store.exportBackup();start(null);
        click(byDescription(screen(),"Lançamentos"));click(byDescription(screen(),"Revisar notificações (0)"));
        assertNotNull(byText(screen(),"Nenhuma sugestão pendente"));
        controller.recreate();activity=controller.get();idle();
        assertEquals(backup,store.exportBackup());assertEquals(-1234,store.totalBalance());assertTrue(store.hasNotificationSource(key));
    }

    private void start(Bundle state) {
        controller = Robolectric.buildActivity(MainActivity.class).create(state).start().resume().visible();
        activity = controller.get();
        idle();
    }

    private View screen() { return activity.findViewById(android.R.id.content); }

    private static void click(View view) {
        assertNotNull("Missing clickable view", view);
        while (!view.isClickable() && view.getParent() instanceof View) view = (View) view.getParent();
        assertTrue(view.isClickable());
        assertTrue(view.performClick());
        idle();
    }

    private static View byDescription(View view, String description) {
        if (view.getContentDescription() != null && description.contentEquals(view.getContentDescription())) return view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            View found = byDescription(((ViewGroup) view).getChildAt(i), description);
            if (found != null) return found;
        }
        return null;
    }

    private static View byText(View view, String text) {
        if (view instanceof TextView && text.contentEquals(((TextView) view).getText())) return view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            View found = byText(((ViewGroup) view).getChildAt(i), text);
            if (found != null) return found;
        }
        return null;
    }

    private static void idle() { shadowOf(Looper.getMainLooper()).idle(); }
}
