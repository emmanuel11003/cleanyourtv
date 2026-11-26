package com.gathin.tvhome;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Instant offline Spotlight Search overlay (Feature #1):
 * fuzzy-searches installed TV + sideloaded apps (including hidden apps with badge),
 * HDMI / Tuner inputs, and built-in launcher & quick settings actions with zero network access.
 */
final class SpotlightDialog {
    static final class Item {
        final String title;
        final String subtitle;
        final String packageName;
        final Drawable icon;
        final int iconRes;
        final Runnable action;

        Item(String title, String subtitle, String packageName, Drawable icon, int iconRes, Runnable action) {
            this.title = title;
            this.subtitle = subtitle;
            this.packageName = packageName;
            this.icon = icon;
            this.iconRes = iconRes;
            this.action = action;
        }
    }

    private final MainActivity host;
    private final float density;
    private final List<Item> catalog = new ArrayList<>();
    private final LinearLayout resultsBox;
    private final EditText input;
    private final TextView emptyView;
    final PopupWindow popup;
    private boolean catalogReady;
    private Runnable pendingFilter;

    private SpotlightDialog(MainActivity host) {
        this.host = host;
        this.density = host.density;

        FrameLayout scrim = new FrameLayout(host);
        scrim.setBackgroundColor(0xB8000000);
        scrim.setClipChildren(false);
        scrim.setOnClickListener(v -> dismiss());

        LinearLayout panel = new LinearLayout(host);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundResource(R.drawable.glass_panel);
        panel.setPadding(dp(18), dp(14), dp(18), dp(14));
        panel.setClickable(true);
        panel.setClipChildren(true);
        panel.setClipToPadding(true);
        panel.setClipToOutline(true);

        // Search input row matching existing dark glass surface
        LinearLayout searchBar = new LinearLayout(host);
        searchBar.setOrientation(LinearLayout.HORIZONTAL);
        searchBar.setGravity(Gravity.CENTER_VERTICAL);
        searchBar.setBackgroundResource(R.drawable.quick_settings_tile_bg);
        searchBar.setPadding(dp(14), dp(8), dp(14), dp(8));

        ImageView searchIcon = new ImageView(host);
        searchIcon.setImageResource(R.drawable.ic_search);
        searchIcon.setColorFilter(0xFFA8C7FA);
        LinearLayout.LayoutParams siLp = new LinearLayout.LayoutParams(dp(20), dp(20));
        siLp.setMarginEnd(dp(10));
        searchBar.addView(searchIcon, siLp);

        input = new EditText(host);
        input.setHint(R.string.spotlight_hint);
        input.setHintTextColor(0x8CFFFFFF);
        input.setTextColor(0xFFFFFFFF);
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f);
        input.setBackground(null);
        input.setSingleLine(true);
        input.setImeOptions(EditorInfo.IME_ACTION_SEARCH | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        input.setFocusable(true);
        input.setFocusableInTouchMode(true);
        try {
            input.setTypeface(UiFont.get(host, R.string.font_regular));
        } catch (Exception ignored) {
        }
        searchBar.addView(input, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        panel.addView(searchBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        emptyView = new TextView(host);
        emptyView.setText(R.string.spotlight_empty);
        emptyView.setTextColor(0xFFFFFFFF);
        emptyView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setPadding(0, dp(24), 0, dp(24));
        emptyView.setVisibility(View.GONE);
        panel.addView(emptyView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ScrollView scroll = new ScrollView(host);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setClipChildren(true);
        scroll.setClipToPadding(true);
        scroll.setClipToOutline(true);
        int rootH = host.root.getHeight();
        int scrollH = rootH > 0
                ? Math.min(dp(192), Math.max(dp(136), rootH - dp(328)))
                : dp(186);
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, scrollH);
        scrollLp.topMargin = dp(10);
        resultsBox = new LinearLayout(host);
        resultsBox.setOrientation(LinearLayout.VERTICAL);
        resultsBox.setClipChildren(true);
        resultsBox.setClipToPadding(true);
        resultsBox.setPadding(dp(8), dp(6), dp(8), dp(6));
        scroll.addView(resultsBox, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        panel.addView(scroll, scrollLp);

        int width = Math.min(dp(560), Math.max(dp(340), host.root.getWidth() - dp(96)));
        FrameLayout.LayoutParams panelLp = new FrameLayout.LayoutParams(
                width, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL | Gravity.TOP);
        panelLp.topMargin = dp(18);
        scrim.addView(panel, panelLp);

        popup = new PopupWindow(scrim, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popup.setOutsideTouchable(true);
        popup.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);
        popup.setAnimationStyle(0);
        popup.setOnDismissListener(() -> {
            cancelPendingFilter();
            hideIme();
            if (host.openPopup == popup) host.openPopup = null;
        });

        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                scheduleFilter(s == null ? "" : s.toString());
            }
        });
        input.setOnEditorActionListener((v, actionId, event) -> {
            if (resultsBox.getChildCount() > 0) {
                hideIme();
                resultsBox.getChildAt(0).performClick();
                return true;
            }
            return false;
        });
        input.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                if (resultsBox.getChildCount() > 0) {
                    hideIme();
                    resultsBox.getChildAt(0).requestFocus();
                    return true;
                }
            } else if (event.getAction() == KeyEvent.ACTION_UP && keyCode == KeyEvent.KEYCODE_BACK) {
                dismiss();
                return true;
            }
            return false;
        });
    }

    static void show(MainActivity host) {
        if (host == null || host.isFinishing() || host.isDestroyed()
                || host.root == null || !host.root.isAttachedToWindow()) return;
        if (host.openPopup != null) {
            host.openPopup.dismiss();
        }
        SpotlightDialog dlg = new SpotlightDialog(host);
        host.openPopup = dlg.popup;
        dlg.popup.showAtLocation(host.root, Gravity.NO_GRAVITY, 0, 0);
        dlg.loadCatalogAsync();
        dlg.input.requestFocus();
    }

    private void cancelPendingFilter() {
        if (pendingFilter != null) {
            LauncherExecutors.main().removeCallbacks(pendingFilter);
            pendingFilter = null;
        }
    }

    private void scheduleFilter(String query) {
        cancelPendingFilter();
        pendingFilter = () -> {
            pendingFilter = null;
            if (popup.isShowing() && !host.isFinishing() && !host.isDestroyed()) {
                renderMatches(query);
            }
        };
        LauncherExecutors.main().postDelayed(pendingFilter, 40L);
    }

    void dismiss() {
        cancelPendingFilter();
        hideIme();
        if (popup.isShowing()) popup.dismiss();
    }

    private void hideIme() {
        try {
            InputMethodManager imm = (InputMethodManager) host.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null && input.getWindowToken() != null) {
                imm.hideSoftInputFromWindow(input.getWindowToken(), 0);
            }
        } catch (Throwable ignored) {
        }
    }

    private void loadCatalogAsync() {
        LauncherExecutors.io().execute(() -> {
            List<Item> built = buildCatalog();
            LauncherExecutors.main().post(() -> {
                if (!popup.isShowing() || host.isFinishing() || host.isDestroyed()) return;
                catalog.clear();
                catalog.addAll(built);
                catalogReady = true;
                renderMatches(input.getText() == null ? "" : input.getText().toString());
            });
        });
    }

    private List<Item> buildCatalog() {
        List<Item> out = new ArrayList<>();
        PackageManager pm = host.getPackageManager();
        Set<String> userHidden = AppCustomizations.loadHidden(host);
        Map<String, String> customLabels = AppCustomizations.loadLabels(host);
        String selfPkg = host.getPackageName();

        // 1. Installed TV + sideloaded apps
        Set<String> seen = new HashSet<>();
        Intent[] queries = {
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER),
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        };
        for (int q = 0; q < queries.length; q++) {
            boolean leanback = (q == 0);
            for (ResolveInfo ri : pm.queryIntentActivities(queries[q], 0)) {
                if (ri.activityInfo == null || !ri.activityInfo.exported) continue;
                String pkg = ri.activityInfo.packageName;
                if (pkg.equals(selfPkg) || !seen.add(pkg)) continue;
                String baseLabel = String.valueOf(ri.loadLabel(pm)).trim();
                String custom = customLabels.get(pkg);
                String label = (custom != null && !custom.isEmpty()) ? custom : baseLabel;
                if (label.isEmpty()) label = pkg;
                boolean hidden = userHidden.contains(pkg);
                String sub = hidden ? "Hidden App \u00B7 OK to open" : (leanback ? "TV App" : "App");
                Drawable icon = null;
                try {
                    icon = ri.loadIcon(pm);
                } catch (Throwable ignored) {
                }
                final String targetPkg = pkg;
                final boolean isLeanback = leanback;
                out.add(new Item(label, sub, pkg, icon, 0, () -> {
                    Intent launch = isLeanback ? pm.getLeanbackLaunchIntentForPackage(targetPkg) : null;
                    if (launch == null) launch = pm.getLaunchIntentForPackage(targetPkg);
                    if (launch != null) {
                        host.open(launch);
                    } else {
                        Toast.makeText(host, R.string.not_available, Toast.LENGTH_SHORT).show();
                    }
                }));
            }
        }

        // 2. HDMI / TV Inputs
        TvInputManager tim = (TvInputManager) host.getSystemService(Context.TV_INPUT_SERVICE);
        if (tim != null) {
            try {
                for (TvInputInfo info : tim.getTvInputList()) {
                    if (info == null || !info.isPassthroughInput() || info.isHidden(host)) continue;
                    CharSequence customName = info.loadCustomLabel(host);
                    CharSequence rawName = customName != null ? customName : info.loadLabel(host);
                    String name = rawName != null ? rawName.toString().trim() : info.getId();
                    if (name.isEmpty()) continue;
                    final String id = info.getId();
                    out.add(new Item(name, "TV Input", null, null, R.drawable.ic_input,
                            () -> TvInputRouter.openInput(host, id)));
                }
            } catch (Throwable ignored) {
            }
        }

        // 3. Built-in Launcher & Quick Settings Actions
        out.add(new Item(host.getString(R.string.quick_settings_title), "Quick Controls", null, null,
                R.drawable.ic_settings, () -> host.openOwnedActivity(
                new Intent(host, QuickSettingsActivity.class), null, -1)));
        out.add(new Item(host.getString(R.string.launcher_settings), "Customize Launcher", null, null,
                R.drawable.ic_home_settings_pill, () -> host.openOwnedActivity(
                new Intent(host, LauncherSettingsActivity.class), null, MainActivity.LAUNCHER_SETTINGS_REQUEST)));
        out.add(new Item(host.getString(R.string.wallpaper_sync_title), "Wi-Fi Wallpapers & Backup", null, null,
                R.drawable.ic_sync, () -> host.openOwnedActivity(
                new Intent(host, WallpaperSyncActivity.class), null, -1)));
        out.add(new Item(host.getString(R.string.tile_notifications), "Notification Center", null, null,
                R.drawable.ic_notifications, () -> host.openOwnedActivity(
                new Intent(host, NotificationPanelActivity.class), null, -1)));
        out.add(new Item(host.getString(R.string.tile_screensaver), "Calendar & Clock Screensaver", null, null,
                R.drawable.ic_screensaver, () -> host.startActivity(new Intent(host, ClockActivity.class))));
        out.add(new Item(host.getString(R.string.tile_boost_ram), "Trim Background Apps", null, null,
                R.drawable.ic_boost, () -> LauncherExecutors.io().execute(() -> {
            MemoryBooster.BoostResult res = MemoryBooster.boost(host);
            LauncherExecutors.main().post(() -> {
                String msg = res.freedMb > 0
                        ? host.getString(R.string.boost_ram_freed, res.freedMb, res.after.freeMb)
                        : host.getString(R.string.boost_ram_clean, res.after.freeMb);
                Toast.makeText(host, msg, Toast.LENGTH_SHORT).show();
            });
        })));
        out.add(new Item(host.getString(R.string.reorder), "Rearrange Home Grid", null, null,
                R.drawable.ic_swap_vert, host::toggleReorder));
        out.add(new Item(host.getString(R.string.backup_export), "Save Layout & Settings JSON", null, null,
                R.drawable.ic_sync, () -> LauncherExecutors.io().execute(() -> {
            SettingsBackup.Summary s = SettingsBackup.exportToDisk(host);
            LauncherExecutors.main().post(() -> Toast.makeText(host,
                    s.ok ? host.getString(R.string.backup_saved, s.settingCount, s.orderCount)
                            : host.getString(R.string.backup_failed, s.error),
                    Toast.LENGTH_LONG).show());
        })));
        out.add(new Item(host.getString(R.string.tile_wifi), "Network Settings", null, null,
                R.drawable.ic_wifi, () -> host.open(SystemSettings.intent(host, Settings.ACTION_WIFI_SETTINGS))));
        out.add(new Item(host.getString(R.string.tile_display), "Picture Settings", null, null,
                R.drawable.ic_picture, () -> host.open(SystemSettings.displayIntent(host))));
        out.add(new Item(host.getString(R.string.tv_settings), "Android TV System Settings", null, null,
                R.drawable.ic_settings, () -> host.open(SystemSettings.intent(host, Settings.ACTION_SETTINGS))));

        Collections.sort(out, (a, b) -> a.title.compareToIgnoreCase(b.title));
        return out;
    }

    /** Fuzzy score: 4 = exact prefix, 3 = word-start, 2 = substring, 1 = subsequence, 0 = no match. */
    static int matchScore(String text, String query) {
        if (query == null || query.isEmpty()) return 1;
        if (text == null || text.isEmpty()) return 0;
        String t = text.toLowerCase(Locale.ROOT);
        String q = query.toLowerCase(Locale.ROOT).trim();
        if (q.isEmpty()) return 1;
        if (t.startsWith(q)) return 4;
        int idx = t.indexOf(q);
        if (idx > 0) {
            char prev = t.charAt(idx - 1);
            if (prev == ' ' || prev == '-' || prev == '_' || prev == '.') return 3;
            return 2;
        }
        // Subsequence fuzzy match (e.g., "yt" -> "YouTube", "ws" -> "Wallpaper Sync")
        int qi = 0;
        for (int ti = 0; ti < t.length() && qi < q.length(); ti++) {
            if (t.charAt(ti) == q.charAt(qi)) qi++;
        }
        return qi == q.length() ? 1 : 0;
    }

    private void renderMatches(String rawQuery) {
        if (!catalogReady) return;
        String q = rawQuery == null ? "" : rawQuery.trim();
        List<Item> matched = new ArrayList<>();
        List<Integer> scores = new ArrayList<>();
        for (Item item : catalog) {
            int score = Math.max(matchScore(item.title, q), matchScore(item.subtitle, q) > 1 ? 1 : 0);
            if (score > 0) {
                matched.add(item);
                scores.add(score);
            }
        }
        // Sort by descending match score, then alphabetical
        List<Integer> idx = new ArrayList<>(matched.size());
        for (int i = 0; i < matched.size(); i++) idx.add(i);
        Collections.sort(idx, (a, b) -> {
            int cmp = Integer.compare(scores.get(b), scores.get(a));
            if (cmp != 0) return cmp;
            return matched.get(a).title.compareToIgnoreCase(matched.get(b).title);
        });

        resultsBox.removeAllViews();
        int limit = Math.min(idx.size(), 18);
        emptyView.setVisibility(limit == 0 ? View.VISIBLE : View.GONE);
        boolean smooth = host.animations && !host.reducedMotion;
        for (int i = 0; i < limit; i++) {
            Item item = matched.get(idx.get(i));
            View row = buildRow(item, i == 0);
            Motion.attachSmoothFocus(row, smooth, 1.02f);
            resultsBox.addView(row);
        }
    }

    private View buildRow(Item item, boolean isFirst) {
        LinearLayout row = new LinearLayout(host);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(6);
        row.setLayoutParams(lp);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(R.drawable.quick_settings_tile_bg);
        row.setPadding(dp(12), dp(9), dp(14), dp(9));
        row.setFocusable(true);
        row.setClickable(true);

        ImageView iconView = new ImageView(host);
        LinearLayout.LayoutParams iLp = new LinearLayout.LayoutParams(dp(24), dp(24));
        iLp.setMarginEnd(dp(12));
        iconView.setLayoutParams(iLp);
        iconView.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        if (item.icon != null) {
            iconView.setImageDrawable(item.icon);
        } else if (item.iconRes != 0) {
            iconView.setImageResource(item.iconRes);
            iconView.setImageTintList(host.getColorStateList(R.color.quick_tile_icon_color));
            iconView.setDuplicateParentStateEnabled(true);
        }
        row.addView(iconView);

        LinearLayout col = new LinearLayout(host);
        col.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        col.setOrientation(LinearLayout.VERTICAL);
        col.setDuplicateParentStateEnabled(true);

        TextView title = new TextView(host);
        title.setText(item.title);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        title.setTextColor(host.getColorStateList(R.color.quick_tile_text_color));
        title.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setDuplicateParentStateEnabled(true);
        col.addView(title);

        if (item.subtitle != null && !item.subtitle.isEmpty()) {
            TextView sub = new TextView(host);
            sub.setText(item.subtitle);
            sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
            sub.setTextColor(host.getColorStateList(R.color.quick_tile_sub_color));
            sub.setSingleLine(true);
            sub.setEllipsize(TextUtils.TruncateAt.END);
            sub.setDuplicateParentStateEnabled(true);
            col.addView(sub);
        }
        row.addView(col);

        row.setOnClickListener(v -> {
            UiFeedback.playClick(v);
            dismiss();
            if (item.action != null) item.action.run();
        });
        row.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN && isFirst && keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                input.requestFocus();
                return true;
            }
            if (event.getAction() == KeyEvent.ACTION_UP && keyCode == KeyEvent.KEYCODE_BACK) {
                dismiss();
                return true;
            }
            return false;
        });
        return row;
    }

    private int dp(int v) {
        return Math.round(v * density);
    }
}
