package io.github.emmanuel11003.tvnotifications;

import android.app.Notification;
import android.app.PendingIntent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * System-bound NotificationListenerService for Android TV.
 * Caches active notifications without retaining heavy Android Notification objects or Bitmaps.
 * Bound and started only by the OS via BIND_NOTIFICATION_LISTENER_SERVICE.
 */
public final class Notifications extends NotificationListenerService {

    public interface Callback {
        void onNotificationsChanged();
    }

    private static volatile Notifications instance;
    private static final NotificationCache cache = new NotificationCache();
    private static final List<Callback> callbacks = new CopyOnWriteArrayList<>();
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    public static boolean isConnected() {
        return instance != null;
    }

    public static void registerCallback(Callback cb) {
        if (cb != null && !callbacks.contains(cb)) {
            callbacks.add(cb);
        }
    }

    public static void unregisterCallback(Callback cb) {
        if (cb != null) {
            callbacks.remove(cb);
        }
    }

    public static List<NotificationItem> snapshot() {
        return cache.snapshot();
    }

    public static void dismiss(String key) {
        if (key == null) return;
        cache.remove(key);
        Notifications s = instance;
        if (s != null) {
            try {
                s.cancelNotification(key);
            } catch (Throwable ignored) {
            }
        }
        dispatchChanged();
    }

    public static void dismissAll() {
        cache.clear();
        Notifications s = instance;
        if (s != null) {
            try {
                s.cancelAllNotifications();
            } catch (Throwable ignored) {
            }
        }
        dispatchChanged();
    }

    private static void dispatchChanged() {
        mainHandler.post(() -> {
            for (Callback cb : callbacks) {
                cb.onNotificationsChanged();
            }
        });
    }

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        instance = this;
        cache.clear();
        try {
            StatusBarNotification[] active = getActiveNotifications();
            if (active != null) {
                for (StatusBarNotification sbn : active) {
                    NotificationItem item = extractItem(sbn);
                    if (item != null) {
                        cache.addOrUpdate(item);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        dispatchChanged();
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        super.onNotificationPosted(sbn);
        if (sbn == null) return;
        NotificationItem item = extractItem(sbn);
        if (item != null) {
            cache.addOrUpdate(item);
        } else {
            cache.remove(sbn.getKey());
        }
        dispatchChanged();
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        super.onNotificationRemoved(sbn);
        if (sbn == null) return;
        cache.remove(sbn.getKey());
        dispatchChanged();
    }

    @Override
    public void onListenerDisconnected() {
        super.onListenerDisconnected();
        instance = null;
        cache.clear();
        dispatchChanged();
    }

    @Override
    public void onDestroy() {
        if (instance == this) {
            instance = null;
        }
        super.onDestroy();
    }

    private static NotificationItem extractItem(StatusBarNotification sbn) {
        if (sbn == null) return null;
        Notification notif = sbn.getNotification();
        if (notif == null) return null;

        if (!NotificationCache.isEligible(notif.flags)) {
            return null;
        }

        String key = sbn.getKey();
        String packageName = sbn.getPackageName();
        long postTime = sbn.getPostTime();
        if (postTime <= 0) {
            postTime = notif.when > 0 ? notif.when : System.currentTimeMillis();
        }

        CharSequence title = null;
        CharSequence text = null;
        Bundle extras = notif.extras;
        if (extras != null) {
            title = extras.getCharSequence(Notification.EXTRA_TITLE);
            text = extras.getCharSequence(Notification.EXTRA_TEXT);
            if (text == null) {
                text = extras.getCharSequence(Notification.EXTRA_BIG_TEXT);
            }
        }

        PendingIntent contentIntent = notif.contentIntent;

        return new NotificationItem(key, packageName, title, text, postTime, contentIntent);
    }
}
