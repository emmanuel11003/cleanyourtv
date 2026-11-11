package io.github.emmanuel11003.tvnotifications;

import org.junit.Before;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Plain JUnit 4 unit tests for NotificationCache.
 * Runs on JVM without Android dependencies or Robolectric.
 */
public class NotificationCacheTest {

    private NotificationCache cache;

    @Before
    public void setUp() {
        cache = new NotificationCache();
    }

    @Test
    public void testCapAt20() {
        for (int i = 1; i <= 25; i++) {
            NotificationItem item = new NotificationItem(
                    "key_" + i,
                    "com.example.app",
                    "Title " + i,
                    "Body " + i,
                    1000L * i,
                    null
            );
            cache.addOrUpdate(item);
        }

        assertEquals(20, cache.size());
        // Newest should be key_25 (postTime 25000)
        assertEquals("key_25", cache.get(0).key);
        // Oldest retained should be key_6 (postTime 6000)
        assertEquals("key_6", cache.get(19).key);
        // Items 1 through 5 must have been evicted
        for (int i = 1; i <= 5; i++) {
            assertFalse(cache.remove("key_" + i));
        }
    }

    @Test
    public void testNewestFirstOrdering() {
        long[] times = {100L, 500L, 200L, 900L, 400L};
        for (int i = 0; i < times.length; i++) {
            cache.addOrUpdate(new NotificationItem(
                    "key_" + i,
                    "com.example.app",
                    "Title",
                    "Text",
                    times[i],
                    null
            ));
        }

        List<NotificationItem> list = cache.snapshot();
        assertEquals(5, list.size());
        assertEquals(900L, list.get(0).postTime);
        assertEquals(500L, list.get(1).postTime);
        assertEquals(400L, list.get(2).postTime);
        assertEquals(200L, list.get(3).postTime);
        assertEquals(100L, list.get(4).postTime);
    }

    @Test
    public void testOngoingAndSummaryFiltering() {
        // Standard notification (no special flags)
        assertTrue(NotificationCache.isEligible(0));
        assertTrue(NotificationCache.isEligible(0x00000010)); // e.g. FLAG_AUTO_CANCEL

        // Ongoing events (media players, foreground services) must be rejected
        assertFalse(NotificationCache.isEligible(NotificationCache.FLAG_ONGOING_EVENT));
        assertFalse(NotificationCache.isEligible(NotificationCache.FLAG_ONGOING_EVENT | 0x10));

        // Group summaries must be rejected
        assertFalse(NotificationCache.isEligible(NotificationCache.FLAG_GROUP_SUMMARY));
        assertFalse(NotificationCache.isEligible(NotificationCache.FLAG_GROUP_SUMMARY | 0x10));

        // Both ongoing and group summary
        assertFalse(NotificationCache.isEligible(NotificationCache.FLAG_ONGOING_EVENT | NotificationCache.FLAG_GROUP_SUMMARY));
    }

    @Test
    public void testRemovalByKey() {
        cache.addOrUpdate(new NotificationItem("k1", "pkg1", "T1", "B1", 100L, null));
        cache.addOrUpdate(new NotificationItem("k2", "pkg2", "T2", "B2", 200L, null));
        assertEquals(2, cache.size());

        assertTrue(cache.remove("k1"));
        assertEquals(1, cache.size());
        assertEquals("k2", cache.get(0).key);

        assertFalse(cache.remove("nonexistent"));
        assertFalse(cache.remove(null));
        assertEquals(1, cache.size());
    }

    @Test
    public void testReplacementOnRepost() {
        cache.addOrUpdate(new NotificationItem("k1", "pkg1", "Old Title", "Old Text", 100L, null));
        cache.addOrUpdate(new NotificationItem("k2", "pkg2", "Title 2", "Text 2", 200L, null));
        assertEquals(2, cache.size());
        assertEquals("k2", cache.get(0).key);

        // Repost k1 with newer timestamp 500L
        cache.addOrUpdate(new NotificationItem("k1", "pkg1", "New Title", "New Text", 500L, null));
        assertEquals(2, cache.size());
        // k1 should now be at the head because its timestamp is 500L > 200L
        assertEquals("k1", cache.get(0).key);
        assertEquals("New Title", cache.get(0).title);
    }

    @Test
    public void testClear() {
        cache.addOrUpdate(new NotificationItem("k1", "pkg1", "T", "B", 100L, null));
        cache.addOrUpdate(new NotificationItem("k2", "pkg2", "T", "B", 200L, null));
        assertEquals(2, cache.size());

        cache.clear();
        assertEquals(0, cache.size());
        assertNull(cache.get(0));
    }

    @Test
    public void testNullHandling() {
        assertFalse(cache.addOrUpdate(null));
        assertFalse(cache.addOrUpdate(new NotificationItem(null, "pkg", "T", "B", 100L, null)));
        assertEquals(0, cache.size());
    }
}
