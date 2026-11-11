package io.github.emmanuel11003.tvnotifications;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Plain, unit-testable cache for active notifications.
 *
 * Rules:
 * - Newest first (postTime descending).
 * - Capped at 20 items max.
 * - Filters out ongoing foreground service/media spam (FLAG_ONGOING_EVENT)
 *   and group summaries (FLAG_GROUP_SUMMARY).
 * - Zero Android imports so it runs in pure JVM unit tests without mocks.
 */
public final class NotificationCache {

    public static final int MAX_CAPACITY = 20;

    /** Notification.FLAG_ONGOING_EVENT (0x00000002). */
    public static final int FLAG_ONGOING_EVENT = 0x00000002;

    /** Notification.FLAG_GROUP_SUMMARY (0x00000200). */
    public static final int FLAG_GROUP_SUMMARY = 0x00000200;

    private final List<NotificationItem> items = new ArrayList<>();

    /**
     * Returns true if notification flags qualify for inclusion in the user-facing panel.
     * Skips ongoing events and group summaries.
     */
    public static boolean isEligible(int flags) {
        if ((flags & FLAG_ONGOING_EVENT) != 0) {
            return false;
        }
        if ((flags & FLAG_GROUP_SUMMARY) != 0) {
            return false;
        }
        return true;
    }

    /**
     * Adds or updates a notification item in the cache.
     * Re-sorts newest-first and caps at MAX_CAPACITY.
     *
     * @param item the notification item to store.
     * @return true if cache content changed.
     */
    public synchronized boolean addOrUpdate(NotificationItem item) {
        if (item == null || item.key == null) {
            return false;
        }

        // Remove previous entry with same key if present
        for (int i = 0; i < items.size(); i++) {
            if (item.key.equals(items.get(i).key)) {
                items.remove(i);
                break;
            }
        }

        // Insert in newest-first order (postTime descending)
        int insertIndex = 0;
        while (insertIndex < items.size() && items.get(insertIndex).postTime >= item.postTime) {
            insertIndex++;
        }
        items.add(insertIndex, item);

        // Cap at MAX_CAPACITY by trimming oldest from the tail
        while (items.size() > MAX_CAPACITY) {
            items.remove(items.size() - 1);
        }

        return true;
    }

    /**
     * Removes an item by key.
     *
     * @param key unique notification key.
     * @return true if an item was removed.
     */
    public synchronized boolean remove(String key) {
        if (key == null) {
            return false;
        }
        for (int i = 0; i < items.size(); i++) {
            if (key.equals(items.get(i).key)) {
                items.remove(i);
                return true;
            }
        }
        return false;
    }

    /**
     * Clears all cached items.
     */
    public synchronized void clear() {
        items.clear();
    }

    /**
     * Returns an unmodifiable snapshot of the current items, newest first.
     */
    public synchronized List<NotificationItem> snapshot() {
        return Collections.unmodifiableList(new ArrayList<>(items));
    }

    /**
     * Returns the current number of cached notifications.
     */
    public synchronized int size() {
        return items.size();
    }

    /**
     * Returns the item at index, or null if index is out of bounds.
     */
    public synchronized NotificationItem get(int index) {
        if (index < 0 || index >= items.size()) {
            return null;
        }
        return items.get(index);
    }
}
