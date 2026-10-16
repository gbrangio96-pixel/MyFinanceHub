package br.com.myfinancehub;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Looper;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import org.junit.After;
import org.junit.Assume;
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
import java.time.ZoneId;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/** The local APK remains usable without exposing the optional notification listener. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, qualifiers = "pt-rBR")
public final class LocalBuildTest {
    private Context context;
    private FinanceStore store;
    private NotificationInbox inbox;
    private ActivityController<MainActivity> controller;
    private MainActivity activity;

    @Before public void before() {
        Assume.assumeFalse("These checks apply to the local APK", BuildConfig.NOTIFICATION_CAPTURE_ENABLED);
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("appearance", Context.MODE_PRIVATE).edit().clear().commit();
        context.getSharedPreferences("MainActivity", Context.MODE_PRIVATE).edit().clear().commit();
        context.deleteDatabase("myfinancehub.db");
        context.deleteDatabase("myfinancehub-notifications.db");
        store = new FinanceStore(context);
        inbox = new NotificationInbox(context);
    }

    @After public void after() throws Exception {
        if (controller != null) {
            controller.pause().stop().destroy();
            Field field = MainActivity.class.getDeclaredField("worker");
            field.setAccessible(true);
            assertTrue("Activity must finish closing its database helpers",
                    ((ExecutorService) field.get(activity)).awaitTermination(10, TimeUnit.SECONDS));
        }
        if (store != null) store.close();
        if (inbox != null) inbox.close();
        if (context != null) {
            Settings.Secure.putString(context.getContentResolver(), "enabled_notification_listeners", "");
            context.deleteDatabase("myfinancehub.db");
            context.deleteDatabase("myfinancehub-notifications.db");
        }
    }

    @Test public void manifestDeclaresNoPermissionsAndNoNotificationListener() throws Exception {
        PackageManager manager = context.getPackageManager();
        PackageInfo installed = manager.getPackageInfo(context.getPackageName(),
                PackageManager.GET_PERMISSIONS | PackageManager.GET_SERVICES);
        assertTrue("Local APK must not request any uses-permission",
                installed.requestedPermissions == null || installed.requestedPermissions.length == 0);
        assertTrue("Local APK must not register a background listener service",
                installed.services == null || installed.services.length == 0);
        assertTrue(manager.queryIntentServices(new Intent(NotificationListenerService.SERVICE_INTERFACE)
                .setPackage(context.getPackageName()), 0).isEmpty());
        try {
            manager.getServiceInfo(new ComponentName(context, NotificationCaptureService.class), 0);
            fail("Notification listener must not be registered in the local manifest");
        } catch (PackageManager.NameNotFoundException expected) {
            // Having Java code in the shared source set must not register an Android component.
        }
    }

    @Test public void oldCapturePreferencesAndPendingSuggestionsNeverExposeCaptureOrChangeMoney() {
        seedOldNotificationConfiguration();
        store.saveEntry(new Models.Entry(0, store.accounts().get(0).id, 0, 0, "Registro preservado",
                LocalDate.now().toString(), "expense", "pix", "", 1234));
        String financialBackup = store.exportBackup();
        long pendingId = inbox.pending().get(0).id;
        start(null);

        assertNotNull(byText(screen(), "Seu dinheiro"));
        assertNull("The home screen is reserved for cash and investment positions",
                byDescription(screen(), "Importar extrato"));
        click(byDescription(screen(), "Lançamentos"));
        assertNotNull("Statement import remains directly accessible in transactions",
                byDescription(screen(), "Importar extrato"));
        assertNotNull(byDescription(screen(), "Novo lançamento"));
        assertNull(byDescription(screen(), "Revisar notificações (1)"));
        assertNoCaptureControls();
        click(byDescription(screen(), "Ajustes"));
        assertNotNull(byText(screen(), "Leitura de notificações"));
        assertNotNull(byDescription(screen(), "Gerenciar categorias"));
        assertNotNull(byDescription(screen(), "Importar extrato CSV ou OFX"));
        assertNoCaptureControls();
        assertNull("Local UI must not start the Android notification permission settings",
                shadowOf(activity).getNextStartedActivity());
        assertEquals(financialBackup, store.exportBackup());
        assertEquals(-1234, store.totalBalance());
        assertEquals("Switching builds must not delete the old queue", pendingId, inbox.pending().get(0).id);
        assertTrue(inbox.isEnabled());

        controller.recreate();
        activity = controller.get();
        idle();
        assertNoCaptureControls();
        assertEquals(financialBackup, store.exportBackup());
        assertEquals(1, inbox.pendingCount());
    }

    @Test public void restoringTheOldNotificationPageShowsOnlyAFutureModule() {
        seedOldNotificationConfiguration();
        Bundle previousActivity = new Bundle();
        previousActivity.putInt("page", 5);
        start(previousActivity);
        assertNotNull(byText(screen(), "MÓDULO FUTURO"));
        assertNotNull(byText(screen(), "Leitura de notificações"));
        assertNoCaptureControls();
        assertNull(byText(screen(), "PIX recebido"));
        assertNull(shadowOf(activity).getNextStartedActivity());
        assertEquals(1, inbox.pendingCount());
        assertTrue(store.entries().isEmpty());
        assertEquals(0, store.totalBalance());
    }

    private void seedOldNotificationConfiguration() {
        String sourcePackage = "com.example.finance";
        inbox.setAllowedPackages(Collections.singleton(sourcePackage));
        inbox.setEnabled(true);
        long postedAt = System.currentTimeMillis();
        NotificationParser.Suggestion suggestion = NotificationParser.parse("PIX recebido",
                "Você recebeu um Pix de R$ 25,90.", postedAt, ZoneId.systemDefault());
        assertNotNull(suggestion);
        assertTrue(inbox.enqueue(sourcePackage, "Banco de demonstração", "previous-build-alert", postedAt, suggestion));
        Settings.Secure.putString(context.getContentResolver(), "enabled_notification_listeners",
                new ComponentName(context, NotificationCaptureService.class).flattenToString());
    }

    private void start(Bundle state) {
        controller = Robolectric.buildActivity(MainActivity.class).create(state).start().resume().visible();
        activity = controller.get();
        idle();
    }

    private void assertNoCaptureControls() {
        for (String description : new String[]{"Notificações de transações", "Capturar novas notificações",
                "1. Escolher apps", "2. Permitir acesso no Android", "Revisar notificações (1)"}) {
            assertNull("Local build must not expose " + description, byDescription(screen(), description));
        }
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
