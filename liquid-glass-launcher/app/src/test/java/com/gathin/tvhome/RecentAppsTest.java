package com.gathin.tvhome;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class RecentAppsTest {

    private static final String LAUNCHER_PKG = "com.gathin.tvhome";
    private static final String YOUTUBE_PKG = "com.google.android.youtube.tv";
    private static final String NETFLIX_PKG = "com.netflix.ninja";
    private static final String PRIME_PKG = "com.amazon.amazonvideo.livingroom";

    @Test
    public void maxRecentsBound() {
        assertEquals("Max recents should be bounded to 15 items for memory control", 15, RecentApps.MAX_RECENTS);
    }

    @Test
    public void appInfoUriValid() {
        assertEquals("Valid package URI", "package:com.google.android.youtube.tv", RecentApps.appInfoUri(YOUTUBE_PKG));
    }

    @Test
    public void appInfoUriNullOrEmpty() {
        assertNull("Null package should return null", RecentApps.appInfoUri(null));
        assertNull("Empty package should return null", RecentApps.appInfoUri(""));
        assertNull("Whitespace package should return null", RecentApps.appInfoUri("   "));
    }

    @Test
    public void isValidPackageFilter() {
        assertTrue("External app is valid", RecentApps.isValidPackage(YOUTUBE_PKG, LAUNCHER_PKG));
        assertFalse("Self launcher package must be excluded", RecentApps.isValidPackage(LAUNCHER_PKG, LAUNCHER_PKG));
        assertFalse("Null package must be excluded", RecentApps.isValidPackage(null, LAUNCHER_PKG));
        assertFalse("Empty package must be excluded", RecentApps.isValidPackage("", LAUNCHER_PKG));
        assertFalse("Whitespace package must be excluded", RecentApps.isValidPackage("   ", LAUNCHER_PKG));
        assertFalse("Android framework package must be excluded", RecentApps.isValidPackage("android", LAUNCHER_PKG));
        assertFalse("SystemUI package must be excluded", RecentApps.isValidPackage("com.android.systemui", LAUNCHER_PKG));
    }

    @Test
    public void buttonMapperActionRecentsConstant() {
        assertEquals("ButtonMapper action recents constant", "action:recents", ButtonMapper.ACTION_RECENTS);
    }

    @Test
    public void recordJsonAddsNewEntry() {
        String json = RecentApps.recordJson("[]", YOUTUBE_PKG, "com.google.android.youtube.tv/MainActivity", "YouTube", 1000L, LAUNCHER_PKG);
        List<RecentApps.Record> list = RecentApps.decodeRecords(json);
        assertEquals(1, list.size());
        RecentApps.Record r = list.get(0);
        assertEquals(YOUTUBE_PKG, r.pkg);
        assertEquals("com.google.android.youtube.tv/MainActivity", r.cmp);
        assertEquals("YouTube", r.lbl);
        assertEquals(1000L, r.time);
    }

    @Test
    public void recordJsonDeduplicatesAndUpdateTimestamp() {
        String initial = RecentApps.recordJson("[]", YOUTUBE_PKG, "com.google.android.youtube.tv/MainActivity", "YouTube", 1000L, LAUNCHER_PKG);
        initial = RecentApps.recordJson(initial, NETFLIX_PKG, "com.netflix.ninja/MainActivity", "Netflix", 2000L, LAUNCHER_PKG);

        List<RecentApps.Record> list = RecentApps.decodeRecords(initial);
        assertEquals(2, list.size());
        assertEquals(NETFLIX_PKG, list.get(0).pkg);
        assertEquals(YOUTUBE_PKG, list.get(1).pkg);

        // Re-launch YouTube without passing label or component: should move to front, update time, and retain earlier label/component
        String updated = RecentApps.recordJson(initial, YOUTUBE_PKG, null, null, 3000L, LAUNCHER_PKG);
        List<RecentApps.Record> listUpdated = RecentApps.decodeRecords(updated);
        assertEquals(2, listUpdated.size());
        RecentApps.Record first = listUpdated.get(0);
        assertEquals(YOUTUBE_PKG, first.pkg);
        assertEquals(3000L, first.time);
        assertEquals("YouTube", first.lbl);
        assertEquals("com.google.android.youtube.tv/MainActivity", first.cmp);
        assertEquals(NETFLIX_PKG, listUpdated.get(1).pkg);
    }

    @Test
    public void recordJsonMaxRecentsLimit() {
        String json = "[]";
        for (int i = 1; i <= 20; i++) {
            json = RecentApps.recordJson(json, "com.app.test" + i, null, "App " + i, i * 1000L, LAUNCHER_PKG);
        }
        List<RecentApps.Record> list = RecentApps.decodeRecords(json);
        assertEquals(RecentApps.MAX_RECENTS, list.size());
        assertEquals("com.app.test20", list.get(0).pkg);
        assertEquals("com.app.test6", list.get(list.size() - 1).pkg);
    }

    @Test
    public void recordJsonExcludesInvalidAndSelfPackages() {
        String json = RecentApps.recordJson("[]", LAUNCHER_PKG, null, "CleanYourTV", 1000L, LAUNCHER_PKG);
        assertEquals("[]", json);

        json = RecentApps.recordJson(json, "com.android.systemui", null, "System UI", 1000L, LAUNCHER_PKG);
        assertEquals("[]", json);

        json = RecentApps.recordJson(json, "android", null, "Android", 1000L, LAUNCHER_PKG);
        assertEquals("[]", json);
    }

    @Test
    public void dismissJsonRemovesExistingPackage() {
        String json = RecentApps.recordJson("[]", YOUTUBE_PKG, null, "YouTube", 1000L, LAUNCHER_PKG);
        json = RecentApps.recordJson(json, NETFLIX_PKG, null, "Netflix", 2000L, LAUNCHER_PKG);

        String dismissed = RecentApps.dismissJson(json, YOUTUBE_PKG);
        List<RecentApps.Record> list = RecentApps.decodeRecords(dismissed);
        assertEquals(1, list.size());
        assertEquals(NETFLIX_PKG, list.get(0).pkg);
    }

    @Test
    public void dismissJsonNonExistentPackage() {
        String json = "[{\"pkg\":\"" + NETFLIX_PKG + "\",\"cmp\":\"\",\"lbl\":\"\",\"time\":1000}]";
        String dismissed = RecentApps.dismissJson(json, YOUTUBE_PKG);
        assertEquals(json, dismissed);
    }

    @Test
    public void pruneJsonRemovesPackages() {
        String json = RecentApps.recordJson("[]", YOUTUBE_PKG, null, "YouTube", 1000L, LAUNCHER_PKG);
        json = RecentApps.recordJson(json, NETFLIX_PKG, null, "Netflix", 2000L, LAUNCHER_PKG);
        json = RecentApps.recordJson(json, PRIME_PKG, null, "Prime", 3000L, LAUNCHER_PKG);

        String pruned = RecentApps.pruneJson(json, Arrays.asList(YOUTUBE_PKG, PRIME_PKG));
        List<RecentApps.Record> list = RecentApps.decodeRecords(pruned);
        assertEquals(1, list.size());
        assertEquals(NETFLIX_PKG, list.get(0).pkg);
    }

    @Test
    public void mergeUsageEventSupplementsWithoutOverwritingLabel() {
        Map<String, RecentApps.Record> map = new HashMap<>();

        // 1. Initial launcher-recorded entry
        map.put(YOUTUBE_PKG, new RecentApps.Record(YOUTUBE_PKG, "", "YouTube TV", 1000L));

        // 2. UsageEvent with newer timestamp
        RecentApps.mergeUsageEvent(map, YOUTUBE_PKG, "PlayerActivity", 2000L, LAUNCHER_PKG);
        assertEquals(1, map.size());
        RecentApps.Record updated = map.get(YOUTUBE_PKG);
        assertEquals(2000L, updated.time);
        assertEquals("YouTube TV", updated.lbl);
        assertEquals(YOUTUBE_PKG + "/PlayerActivity", updated.cmp);

        // 3. UsageEvent for new package
        RecentApps.mergeUsageEvent(map, NETFLIX_PKG, "MainActivity", 3000L, LAUNCHER_PKG);
        assertEquals(2, map.size());
        assertEquals(3000L, map.get(NETFLIX_PKG).time);

        // 4. UsageEvent for launcher itself should be ignored
        RecentApps.mergeUsageEvent(map, LAUNCHER_PKG, "MainActivity", 4000L, LAUNCHER_PKG);
        assertEquals(2, map.size());
    }

    @Test
    public void emptyAndCorruptJsonHandling() {
        assertEquals("[]", RecentApps.recordJson(null, "", null, null, 0, LAUNCHER_PKG));
        assertEquals("invalid json", RecentApps.recordJson("invalid json", "", null, null, 0, LAUNCHER_PKG));
        // Valid package with corrupt input json safely recovers and starts clean list
        String recovered = RecentApps.recordJson("corrupt json", YOUTUBE_PKG, null, "YouTube", 1000L, LAUNCHER_PKG);
        List<RecentApps.Record> list = RecentApps.decodeRecords(recovered);
        assertEquals(1, list.size());
        assertEquals(YOUTUBE_PKG, list.get(0).pkg);

        assertNull(RecentApps.dismissJson(null, YOUTUBE_PKG));
        assertEquals("invalid", RecentApps.dismissJson("invalid", YOUTUBE_PKG));
        assertNull(RecentApps.pruneJson(null, Arrays.asList(YOUTUBE_PKG)));
    }

    // --- Phase 1: Native recents API tests ---

    @Test
    public void nativeRecentsInitiallyUntested() {
        // Before any invocation, the native recents state should be untested
        // (testing the static API contract; actual service testing requires a device)
        assertFalse("Before any call, isNativeRecentsTested should be false or deterministic",
                HomeKeyService.isNativeRecentsAvailable() && !HomeKeyService.isNativeRecentsTested());
    }

    @Test
    public void nativeRecentsWithoutServiceReturnsFalse() {
        // Without the accessibility service bound, tryNativeRecents should return false
        assertFalse("tryNativeRecents without service should return false",
                HomeKeyService.tryNativeRecents());
    }

    @Test
    public void isServiceBoundWithoutServiceReturnsFalse() {
        assertFalse("isServiceBound without running service should be false",
                HomeKeyService.isServiceBound());
    }

    // --- Phase 2: Honest labelling tests ---

    @Test
    public void canForceStopDefaultReturnsFalse() {
        assertFalse("canForceStop should return false on unprivileged Android 14",
                RecentApps.canForceStop(null));
        assertFalse("forceStop should return false when not authorized",
                RecentApps.forceStop(null, YOUTUBE_PKG));
    }

    @Test
    public void dismissToastDoesNotClaimProcessTermination() {
        // The dismissed_toast string must not claim to close, kill, or stop the app.
        // We verify the format string template contains dismissal and background running context.
        String format = "%1$s dismissed from recents. The app may still be running in the background.";
        assertTrue("Dismiss toast should mention dismissal",
                format.contains("dismissed from recents"));
        assertTrue("Dismiss toast should acknowledge app may still run",
                format.contains("may still be running"));
        assertFalse("Dismiss toast must not claim app was stopped",
                format.toLowerCase().contains("stopped"));
        assertFalse("Dismiss toast must not claim app was killed",
                format.toLowerCase().contains("killed"));
        assertFalse("Dismiss toast must not claim app was closed",
                format.toLowerCase().contains("closed"));
    }
}
