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

/** Capture was retired; existing records and old sidecar files survive the app update. */
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

    @Test public void manifestDeclaresOnlyInternetAndNoNotificationListener() throws Exception {
        PackageManager manager = context.getPackageManager();
        PackageInfo installed = manager.getPackageInfo(context.getPackageName(),
                PackageManager.GET_PERMISSIONS | PackageManager.GET_SERVICES);
        if (installed.requestedPermissions != null) for (String permission : installed.requestedPermissions) {
            assertEquals("Only optional market-price lookup may request network access",
                    "android.permission.INTERNET", permission);
        }
        assertTrue("APK must not register a background listener service",
                installed.services == null || installed.services.length == 0);
        assertTrue(manager.queryIntentServices(new Intent(NotificationListenerService.SERVICE_INTERFACE)
                .setPackage(context.getPackageName()), 0).isEmpty());
        try {
            manager.getServiceInfo(new ComponentName(context.getPackageName(), OLD_SERVICE), 0);
            fail("Retired notification listener must not be registered");
        } catch (PackageManager.NameNotFoundException expected) {
            // Previously granted access cannot expose a component removed from the manifest.
        }
        start(null);
        assertFalse("A fresh install must not recreate the retired notification queue",
                context.getDatabasePath(OLD_QUEUE).exists());
    }

    @Test public void oldQueueAndConfirmedFinancialRecordsSurviveNavigationAndRecreation() {
        seedOldNotificationConfiguration();
        String historicalKey = "notification:" + new String(new char[64]).replace('\0', 'a');
        store.saveReviewedNotification(new Models.Entry(0, store.accounts().get(0).id, 0, 0,
                "Registro preservado", LocalDate.now().toString(), "expense", "pix", historicalKey, 1234));
        String financialBackup = store.exportBackup();
        start(null);

        assertNotNull(byText(screen(), "Seu dinheiro"));
        assertNull("Home remains focused on balances and investments", byDescription(screen(), "Importar extrato"));
        click(byDescription(screen(), "Lançamentos"));
        assertNotNull("Statement import remains visible", byDescription(screen(), "Importar extrato"));
        assertNotNull(byDescription(screen(), "Novo lançamento"));
        assertNoCaptureControls();
        click(byDescription(screen(), "Ajustes"));
        assertNotNull(byDescription(screen(), "Gerenciar categorias"));
        assertNotNull(byDescription(screen(), "Importar extrato CSV ou OFX"));
        assertNoCaptureControls();
        assertNull("Navigation must not request retired Android notification access",
                shadowOf(activity).getNextStartedActivity());
        assertEquals(financialBackup, store.exportBackup());
        assertEquals(-1234, store.totalBalance());
        assertTrue(store.hasNotificationSource(historicalKey));
        assertOldQueueUntouched();

        controller.recreate();
        activity = controller.get();
        idle();
        assertNoCaptureControls();
        assertEquals(financialBackup, store.exportBackup());
        assertOldQueueUntouched();
    }

    @Test public void restoringRetiredNotificationPageReturnsToUsableHomeWithoutCapture() {
        seedOldNotificationConfiguration();
        Bundle previousActivity = new Bundle();
        previousActivity.putInt("page", 5);
        start(previousActivity);
        assertNotNull(byText(screen(), "Seu dinheiro"));
        assertNoCaptureControls();
        assertNull(byText(screen(), "PIX recebido"));
        assertNull(shadowOf(activity).getNextStartedActivity());
        assertOldQueueUntouched();
        assertTrue(store.entries().isEmpty());
        assertEquals(0, store.totalBalance());
    }

    private void seedOldNotificationConfiguration() {
        // An inert v1 sidecar models data left by an older installation. No retired code is loaded.
        try (SQLiteDatabase database = context.openOrCreateDatabase(OLD_QUEUE, 0, null)) {
            database.execSQL("CREATE TABLE settings (name TEXT PRIMARY KEY, value TEXT NOT NULL)");
            database.execSQL("CREATE TABLE allowed_packages (package_name TEXT PRIMARY KEY)");
            database.execSQL("CREATE TABLE candidates (_id INTEGER PRIMARY KEY, description TEXT, pending INTEGER)");
            database.execSQL("INSERT INTO settings VALUES ('enabled','1')");
            database.execSQL("INSERT INTO allowed_packages VALUES ('com.example.finance')");
            database.execSQL("INSERT INTO candidates VALUES (7,'PIX recebido',1)");
            database.setVersion(1);
        }
        Settings.Secure.putString(context.getContentResolver(), "enabled_notification_listeners",
                new ComponentName(context.getPackageName(), OLD_SERVICE).flattenToString());
    }

    private void assertOldQueueUntouched() {
        try (SQLiteDatabase database = context.openOrCreateDatabase(OLD_QUEUE, 0, null);
             Cursor settings = database.rawQuery("SELECT value FROM settings WHERE name='enabled'", null);
             Cursor candidate = database.rawQuery("SELECT _id,description,pending FROM candidates", null)) {
            assertTrue(settings.moveToFirst());
            assertEquals("1", settings.getString(0));
            assertTrue(candidate.moveToFirst());
            assertEquals(7, candidate.getLong(0));
            assertEquals("PIX recebido", candidate.getString(1));
            assertEquals(1, candidate.getInt(2));
            assertFalse(candidate.moveToNext());
        }
    }

    private void start(Bundle state) {
        controller = Robolectric.buildActivity(MainActivity.class).create(state).start().resume().visible();
        activity = controller.get();
        idle();
    }

    private void assertNoCaptureControls() {
        for (String description : new String[]{"Notificações de transações", "Capturar novas notificações",
                "1. Escolher apps", "2. Permitir acesso no Android", "Revisar notificações (1)"}) {
            assertNull("UI must not expose " + description, byDescription(screen(), description));
        }
        assertNull(byText(screen(), "Leitura de notificações"));
        assertNull(byText(screen(), "Atualizar lista"));
        assertNull(byText(screen(), "Descartar todas as sugestões"));
        assertNull(byText(screen(), "Para revisar (1)"));
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
