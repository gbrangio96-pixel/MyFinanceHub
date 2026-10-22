package br.com.myfinancehub;

import android.app.Notification;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.time.ZoneId;

/** Opt-in listener. It only queues a minimal suggestion; it never creates financial entries. */
public final class NotificationCaptureService extends NotificationListenerService {
    private static final int MAX_TITLE = 256;
    private static final int MAX_BODY = 4096;

    @Override public void onNotificationPosted(StatusBarNotification posted) {
        if (posted == null) return;
        String packageName = posted.getPackageName();
        if (packageName == null || getPackageName().equals(packageName)) return;
        try (NotificationInbox inbox = new NotificationInbox(this)) {
            // Check both switches before inspecting extras, including text/progress fields.
            if (!inbox.isEnabled() || !inbox.allowedPackages().contains(packageName)) return;
            Notification notification = posted.getNotification();
            if (notification == null || (notification.flags & (Notification.FLAG_GROUP_SUMMARY
                    | Notification.FLAG_ONGOING_EVENT | Notification.FLAG_FOREGROUND_SERVICE)) != 0) return;
            Bundle extras = notification.extras;
            if (extras == null || extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE, false)
                    || extras.getInt(Notification.EXTRA_PROGRESS_MAX, 0) > 0) return;
            String title = bounded(extras.getCharSequence(Notification.EXTRA_TITLE), MAX_TITLE);
            String body = notificationBody(extras);
            if (title == null || body == null) return;
            NotificationParser.Suggestion suggestion = NotificationParser.parse(title, body,
                    posted.getPostTime(), ZoneId.systemDefault());
            if (suggestion == null) return;
            inbox.enqueue(packageName, appLabel(packageName), posted.getKey(), posted.getPostTime(), suggestion);
        } catch (RuntimeException ignored) {
            // Malformed remote extras or unavailable storage must not crash the listener.
            // Notification content is deliberately never logged.
        }
    }

    // Do not replay active notifications on listener connection: capture starts with new posts.

    public static boolean hasAccess(Context context) {
        try {
            String enabled = Settings.Secure.getString(context.getContentResolver(), "enabled_notification_listeners");
            if (enabled == null || enabled.isEmpty()) return false;
            ComponentName own = new ComponentName(context, NotificationCaptureService.class);
            for (String component : enabled.split(":")) {
                if (own.equals(ComponentName.unflattenFromString(component))) return true;
            }
        } catch (SecurityException ignored) { /* Settings may be restricted by device policy. */ }
        return false;
    }

    public static Intent accessSettingsIntent(Context context) {
        return new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                .putExtra("android.provider.extra.NOTIFICATION_LISTENER_COMPONENT_NAME",
                        new ComponentName(context, NotificationCaptureService.class).flattenToString());
    }

    private String appLabel(String packageName) {
        try {
            PackageManager manager = getPackageManager();
            ApplicationInfo info = manager.getApplicationInfo(packageName, 0);
            CharSequence label = manager.getApplicationLabel(info);
            return label == null ? packageName : label.toString();
        } catch (PackageManager.NameNotFoundException missing) { return packageName; }
    }

    private static String notificationBody(Bundle extras) {
        CharSequence big = extras.getCharSequence(Notification.EXTRA_BIG_TEXT);
        if (big != null && big.length() != 0) return bounded(big, MAX_BODY);
        CharSequence text = extras.getCharSequence(Notification.EXTRA_TEXT);
        if (text != null && text.length() != 0) return bounded(text, MAX_BODY);
        CharSequence[] lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
        if (lines == null) return "";
        StringBuilder result = new StringBuilder();
        for (CharSequence line : lines) {
            if (line == null) continue;
            if (result.length() + line.length() + 1 > MAX_BODY) return null;
            if (result.length() != 0) result.append('\n');
            result.append(line);
        }
        return result.toString();
    }

    private static String bounded(CharSequence value, int max) {
        if (value == null) return "";
        return value.length() > max ? null : value.toString();
    }
}
