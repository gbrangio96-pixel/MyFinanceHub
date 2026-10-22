package br.com.myfinancehub;

import android.app.Notification;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.UserHandle;
import android.provider.Settings;
import android.service.notification.StatusBarNotification;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;

import java.util.Collections;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public final class NotificationCaptureServiceTest {
    private static final String APP = "br.com.bank.example";
    private Context context;
    private NotificationInbox inbox;
    private ServiceController<NotificationCaptureService> controller;
    private NotificationCaptureService service;
    private long now;

    @Before public void before() {
        context = RuntimeEnvironment.getApplication();
        context.deleteDatabase(NotificationInbox.DATABASE_NAME);
        inbox = new NotificationInbox(context);
        now = System.currentTimeMillis();
        controller = Robolectric.buildService(NotificationCaptureService.class).create();
        service = controller.get();
    }

    @After public void after() {
        if (controller != null) controller.destroy();
        if (inbox != null) inbox.close();
        if (context != null) {
            context.deleteDatabase(NotificationInbox.DATABASE_NAME);
            Settings.Secure.putString(context.getContentResolver(), "enabled_notification_listeners", "");
        }
    }

    @Test public void disabledAndUnselectedAppsDoNotCapture() {
        service.onNotificationPosted(post(APP, 1, purchase()));
        assertEquals(0, inbox.pendingCount());
        inbox.setEnabled(true);
        service.onNotificationPosted(post(APP, 1, purchase()));
        assertEquals(0, inbox.pendingCount());
        inbox.setAllowedPackages(Collections.singleton(APP));
        service.onNotificationPosted(post("br.com.another.bank", 1, purchase()));
        assertEquals(0, inbox.pendingCount());
    }

    @Test public void selectedAppQueuesReviewWithoutCreatingFinancialEntryAndResendDeduplicates() {
        enable();
        service.onNotificationPosted(post(APP, 1, purchase()));
        assertEquals(1, inbox.pendingCount());
        NotificationInbox.Candidate row = inbox.pending().get(0);
        assertEquals(2590, row.amountCents); assertEquals("1234", row.lastFour);
        service.onNotificationPosted(post(APP, 1, purchase()));
        assertEquals(1, inbox.pendingCount());
        inbox.dismiss(row.id);
        service.onNotificationPosted(post(APP, 1, purchase()));
        assertEquals(0, inbox.pendingCount());
        service.onNotificationPosted(post(APP, 2, purchase()));
        assertEquals(1, inbox.pendingCount());
    }

    @Test public void ownSummaryOngoingAndProgressNotificationsAreIgnored() {
        enable();
        inbox.setAllowedPackages(new java.util.HashSet<>(java.util.Arrays.asList(APP, context.getPackageName())));
        service.onNotificationPosted(post(context.getPackageName(), 1, purchase()));
        Notification summary = purchase(); summary.flags |= Notification.FLAG_GROUP_SUMMARY;
        service.onNotificationPosted(post(APP, 2, summary));
        Notification ongoing = purchase(); ongoing.flags |= Notification.FLAG_ONGOING_EVENT;
        service.onNotificationPosted(post(APP, 3, ongoing));
        Notification progress = purchase(); progress.extras.putInt(Notification.EXTRA_PROGRESS_MAX, 100);
        service.onNotificationPosted(post(APP, 4, progress));
        Notification indeterminate = purchase(); indeterminate.extras.putBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE, true);
        service.onNotificationPosted(post(APP, 5, indeterminate));
        assertEquals(0, inbox.pendingCount());
    }

    @Test public void passwordsFailuresAndOversizedBodiesNeverEnterInbox() {
        enable();
        Notification code = purchase();
        code.extras.putCharSequence(Notification.EXTRA_TEXT,
                "Código de segurança 123456 para autorizar compra de R$ 25,90 no cartão final 1234.");
        service.onNotificationPosted(post(APP, 1, code));
        Notification failure = purchase(); failure.extras.putCharSequence(Notification.EXTRA_TITLE, "Compra recusada");
        service.onNotificationPosted(post(APP, 2, failure));
        Notification huge = purchase();
        huge.extras.putCharSequence(Notification.EXTRA_TEXT, "Compra de R$ 25,90 no cartão final 1234. " + "x".repeat(5000));
        service.onNotificationPosted(post(APP, 3, huge));
        assertEquals(0, inbox.pendingCount());
    }

    @Test public void bigTextAndTextLinesCanProvideTheBody() {
        enable();
        Notification big = purchase();
        big.extras.putCharSequence(Notification.EXTRA_TEXT, "Abra o aplicativo");
        big.extras.putCharSequence(Notification.EXTRA_BIG_TEXT, "Compra de R$ 25,90 no cartão final 1234.");
        service.onNotificationPosted(post(APP, 1, big));
        Notification lines = purchase(); lines.extras.remove(Notification.EXTRA_TEXT);
        lines.extras.putCharSequenceArray(Notification.EXTRA_TEXT_LINES,
                new CharSequence[]{"Compra de R$ 25,90", "no cartão final 1234."});
        service.onNotificationPosted(post(APP, 2, lines));
        assertEquals(2, inbox.pendingCount());
    }

    @Test public void listenerConnectionDoesNotReplayNotifications() {
        enable(); service.onListenerConnected();
        assertEquals(0, inbox.pendingCount());
    }

    @Test public void accessChecksExactListenerComponentAndSettingsIntentUsesSystemPage() {
        assertFalse(NotificationCaptureService.hasAccess(context));
        Settings.Secure.putString(context.getContentResolver(), "enabled_notification_listeners",
                "br.com.unrelated/.Listener");
        assertFalse(NotificationCaptureService.hasAccess(context));
        Settings.Secure.putString(context.getContentResolver(), "enabled_notification_listeners",
                "br.com.unrelated/.Listener:" + new ComponentName(context, NotificationCaptureService.class).flattenToString());
        assertTrue(NotificationCaptureService.hasAccess(context));
        Intent intent = NotificationCaptureService.accessSettingsIntent(context);
        assertEquals(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS, intent.getAction());
    }

    private void enable() { inbox.setEnabled(true); inbox.setAllowedPackages(Collections.singleton(APP)); }

    private Notification purchase() {
        Notification notification = new Notification();
        notification.extras = new Bundle();
        notification.extras.putCharSequence(Notification.EXTRA_TITLE, "Compra aprovada");
        notification.extras.putCharSequence(Notification.EXTRA_TEXT, "Compra de R$ 25,90 no cartão final 1234.");
        return notification;
    }

    private StatusBarNotification post(String packageName, int id, Notification notification) {
        return new StatusBarNotification(packageName, packageName, id, null, 10000, 0, 0,
                notification, android.os.Process.myUserHandle(), now);
    }
}
