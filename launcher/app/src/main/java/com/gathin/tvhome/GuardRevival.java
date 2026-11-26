package com.gathin.tvhome;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Starts this launcher's process after the firmware has killed it and caches active notifications
 * locally for the built-in {@link NotificationPanelActivity} without retaining heavy bitmaps.
 *
 * Enable with {@code cmd notification allow_listener com.gathin.tvhome/.GuardRevival}.
 */
public class GuardRevival extends NotificationListenerService {
    private static final int MAX_CAPACITY = 20;

    public static final class Item {
        public final String key;
        public final String packageName;
        public final CharSequence title;
        public final CharSequence text;
        public final long postTime;
        public final PendingIntent contentIntent;

        public Item(String key, String packageName, CharSequence title,
                    CharSequence text, long postTime, PendingIntent contentIntent) {
            this.key = key;
            this.packageName = packageName;
            this.title = title;
            this.text = text;
            this.postTime = postTime;
            this.contentIntent = contentIntent;
        }
    }

    public interface Callback {
        void onNotificationsChanged();
    }

    private static volatile GuardRevival instance;
    private static final List<Item> items = new ArrayList<>();
    private static final List<Callback> callbacks = new CopyOnWriteArrayList<>();

    private BroadcastReceiver unlocked;

    static boolean isConnected() {
        return instance != null;
    }

    static boolean isEligible(int flags) {
        return (flags & (Notification.FLAG_ONGOING_EVENT | Notification.FLAG_GROUP_SUMMARY)) == 0;
    }

    static void registerCallback(Callback cb) {
        if (cb != null && !callbacks.contains(cb)) {
            callbacks.add(cb);
        }
    }

    static void unregisterCallback(Callback cb) {
        if (cb != null) {
            callbacks.remove(cb);
        }
    }

    static synchronized List<Item> snapshot() {
        return Collections.unmodifiableList(new ArrayList<>(items));
    }

    static void dismiss(String key) {
        if (key == null) return;
        synchronized (GuardRevival.class) {
            for (int i = 0; i < items.size(); i++) {
                if (key.equals(items.get(i).key)) {
                    items.remove(i);
                    break;
                }
            }
        }
        GuardRevival s = instance;
        if (s != null) {
            try {
                s.cancelNotification(key);
            } catch (Throwable ignored) {
            }
        }
        dispatchChanged();
    }

    static void dismissAll() {
        synchronized (GuardRevival.class) {
            items.clear();
        }
        GuardRevival s = instance;
        if (s != null) {
            try {
                s.cancelAllNotifications();
            } catch (Throwable ignored) {
            }
        }
        dispatchChanged();
    }

    private static synchronized void addOrUpdateLocked(Item item) {
        if (item == null || item.key == null) return;
        for (int i = 0; i < items.size(); i++) {
            if (item.key.equals(items.get(i).key)) {
                items.remove(i);
                break;
            }
        }
        int insertIndex = 0;
        while (insertIndex < items.size() && items.get(insertIndex).postTime >= item.postTime) {
            insertIndex++;
        }
        items.add(insertIndex, item);
        while (items.size() > MAX_CAPACITY) {
            items.remove(items.size() - 1);
        }
    }

    private static synchronized void removeByKeyLocked(String key) {
        if (key == null) return;
        for (int i = 0; i < items.size(); i++) {
            if (key.equals(items.get(i).key)) {
                items.remove(i);
                return;
            }
        }
    }

    private static void dispatchChanged() {
        if (callbacks.isEmpty()) return;
        LauncherExecutors.main().post(() -> {
            for (Callback cb : callbacks) {
                cb.onNotificationsChanged();
            }
        });
    }

    private static Item extractItem(StatusBarNotification sbn) {
        if (sbn == null) return null;
        Notification notif = sbn.getNotification();
        if (notif == null || !isEligible(notif.flags)) return null;

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

        return new Item(key, packageName, title, text, postTime, notif.contentIntent);
    }

    @Override
    public void onListenerConnected() {
        unlocked = DirectBoot.runWhenUnlocked(this, () -> {
            unlocked = null;
            GuardKeep.ensureHomeKey(this);
        });
        instance = this;
        synchronized (GuardRevival.class) {
            items.clear();
            try {
                StatusBarNotification[] active = getActiveNotifications();
                if (active != null) {
                    for (StatusBarNotification sbn : active) {
                        addOrUpdateLocked(extractItem(sbn));
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        dispatchChanged();
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null) return;
        Item item = extractItem(sbn);
        if (item != null) {
            addOrUpdateLocked(item);
        } else {
            removeByKeyLocked(sbn.getKey());
        }
        dispatchChanged();
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        if (sbn == null) return;
        removeByKeyLocked(sbn.getKey());
        dispatchChanged();
    }

    @Override
    public void onListenerDisconnected() {
        instance = null;
        synchronized (GuardRevival.class) {
            items.clear();
        }
        dispatchChanged();
        super.onListenerDisconnected();
    }

    private void stopWaiting() {
        DirectBoot.cancel(this, unlocked);
        unlocked = null;
    }

    @Override
    public void onDestroy() {
        if (instance == this) {
            instance = null;
        }
        stopWaiting();
        super.onDestroy();
    }
}
