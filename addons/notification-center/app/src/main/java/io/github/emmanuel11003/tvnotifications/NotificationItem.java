package io.github.emmanuel11003.tvnotifications;

import java.util.Objects;

/**
 * Plain, immutable data model holding only the minimal fields extracted from a notification.
 * Retains no Android Notification/StatusBarNotification/RemoteViews objects or bitmaps,
 * preventing memory leaks on TVs with 1 GB RAM.
 */
public final class NotificationItem {
    public final String key;
    public final String packageName;
    public final CharSequence title;
    public final CharSequence text;
    public final long postTime;
    public final Object contentIntent;

    public NotificationItem(String key, String packageName, CharSequence title,
                            CharSequence text, long postTime, Object contentIntent) {
        this.key = key;
        this.packageName = packageName;
        this.title = title;
        this.text = text;
        this.postTime = postTime;
        this.contentIntent = contentIntent;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        NotificationItem that = (NotificationItem) o;
        return Objects.equals(key, that.key);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(key);
    }

    @Override
    public String toString() {
        // Privacy rule: Never log notification title or text.
        return "NotificationItem{key='" + key + "', pkg='" + packageName + "', postTime=" + postTime + "}";
    }
}
