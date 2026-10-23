package br.com.myfinancehub;

import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

/** Retired compatibility class. It is deliberately absent from the application manifest. */
public final class NotificationCaptureService extends NotificationListenerService {
    @Override public void onNotificationPosted(StatusBarNotification ignored) {
        // Never inspect, parse, persist or forward notification contents.
    }

    public static boolean hasAccess(Context context) { return false; }

    /** No longer opens a permission page for notification collection. */
    public static Intent accessSettingsIntent(Context context) { return new Intent(Settings.ACTION_SETTINGS); }
}
