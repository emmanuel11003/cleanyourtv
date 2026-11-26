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
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
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
 * Instant offline Spotlight Search overlay:
 * fuzzy-searches installed TV + sideloaded apps (including hidden apps),
 * HDMI / Tuner inputs, and built-in launcher & system actions with category filters,
 * recent-app prioritization, keyword matching, and matched-text highlighting.
 */
final class SpotlightDialog {
    static final int CAT_ALL = 0;
    static final int CAT_APPS = 1;
    static final int CAT_INPUTS = 2;
    static final int CAT_ACTIONS = 3;

    static final class Item {
        final int category;
        final String title;
        final String subtitle;
        final String keywords;
        final String packageName;
        final Drawable icon;
        final int iconRes;
        final long lastUsedMs;
        final Runnable action;

        Item(int category, String title, String subtitle, String keywords, String packageName,
             Drawable icon, int iconRes, long lastUsedMs, Runnable action) {
            this.category = category;
            this.title = title;
            this.subtitle = subtitle;
            this.keywords = keywords == null ? "" : keywords;
            this.packageName = packageName;
            this.icon = icon;
            this.iconRes = iconRes;
            this.lastUsedMs = lastUsedMs;
            this.action = action;
        }
    }

    private final MainActivity host;
    private final float density;
    private final List<Item> catalog = new ArrayList<>();
    private final LinearLayout panel;
    private final LinearLayout resultsBox;
    private final ScrollView scroll;
    private final EditText input;
    private final TextView clearBtn;
    private final TextView countBadge;
    private final TextView emptyView;
    private final TextView[] filterChips = new TextView[4];
    final PopupWindow popup;
    private int activeCategory = CAT_ALL;
    private boolean catalogReady;
    private Runnable pendingFilter;

    private SpotlightDialog(MainActivity host) {
        this.host = host;
        this.density = host.density;
        boolean smooth = host.animations && !host.reducedMotion;

        FrameLayout scrim = new FrameLayout(host);
        scrim.setBackgroundColor(0xB8000000);
        scrim.setClipChildren(false);
        scrim.setOnClickListener(v -> dismiss());

        panel = new LinearLayout(host);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundResource(R.drawable.glass_panel);
        panel.setPadding(dp(18), dp(14), dp(18), dp(14));
        panel.setClickable(true);
        panel.setClipChildren(true);
        panel.setClipToPadding(true);
        panel.setClipToOutline(true);

        // Search input bar with icon, EditText, clear button, and live match count
        LinearLayout searchBar = new LinearLayout(host);
        searchBar.setOrientation(LinearLayout.HORIZONTAL);
        searchBar.setGravity(Gravity.CENTER_VERTICAL);
        searchBar.setBackgroundResource(R.drawable.quick_settings_tile_bg);
        searchBar.setPadding(dp(14), dp(8), dp(12), dp(8));

        ImageView searchIcon = new ImageView(host);
        searchIcon.setImageResource(R.drawable.ic_search);
        searchIcon.setColorFilter(0xFFA8C7FA);
        LinearLayout.LayoutParams siLp = new LinearLayout.LayoutParams(dp(20), dp(20));
        siLp.setMarginEnd(dp(10));
        searchBar.addView(searchIcon, siLp);

        input = new EditText(host);
        input.setHint(R.string.spotlight_hint);
        input.setHintTextColor(0xFFA8B3C5);
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

        clearBtn = new TextView(host);
        clearBtn.setText("\u2715");
        clearBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        clearBtn.setTextColor(host.getColorStateList(R.color.quick_tile_text_color));
        clearBtn.setBackgroundResource(R.drawable.quick_settings_circle_bg);
        clearBtn.setGravity(Gravity.CENTER);
        clearBtn.setFocusable(true);
        clearBtn.setClickable(true);
        clearBtn.setVisibility(View.GONE);
        LinearLayout.LayoutParams clrLp = new LinearLayout.LayoutParams(dp(28), dp(28));
        clrLp.setMarginStart(dp(8));
        clearBtn.setOnClickListener(v -> {
            UiFeedback.playClick(v);
            input.setText("");
            input.requestFocus();
        });
        Motion.attachSmoothFocus(clearBtn, smooth, 1f);
        searchBar.addView(clearBtn, clrLp);

        countBadge = new TextView(host);
        countBadge.setTextColor(0xFFFFFFFF);
        countBadge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        countBadge.setPadding(dp(10), 0, dp(2), 0);
        searchBar.addView(countBadge, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        panel.addView(searchBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Category filter pills row: All | Apps | Inputs | Actions
        LinearLayout filterRow = new LinearLayout(host);
        filterRow.setOrientation(LinearLayout.HORIZONTAL);
        filterRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams frLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        frLp.topMargin = dp(10);
        String[] chipLabels = {"All", "Apps", "Inputs", "Actions"};
        for (int i = 0; i < filterChips.length; i++) {
            final int cat = i;
            TextView chip = new TextView(host);
            chip.setText(chipLabels[i]);
            chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
            chip.setTextColor(host.getColorStateList(R.color.quick_tile_text_color));
            chip.setBackgroundResource(R.drawable.quick_settings_tile_bg);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(dp(14), dp(5), dp(14), dp(5));
            chip.setFocusable(true);
            chip.setClickable(true);
            LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i < filterChips.length - 1) cLp.setMarginEnd(dp(8));
            chip.setOnClickListener(v -> {
                UiFeedback.playClick(v);
                if (activeCategory != cat) {
                    activeCategory = cat;
                    updateFilterChips();
                    renderMatches(input.getText() == null ? "" : input.getText().toString());
                }
            });
            chip.setOnKeyListener((v, keyCode, event) -> {
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                        input.requestFocus();
                        return true;
                    }
                    if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && focusFirstResult()) {
                        return true;
                    }
                } else if (event.getAction() == KeyEvent.ACTION_UP && keyCode == KeyEvent.KEYCODE_BACK) {
                    dismiss();
                    return true;
                }
                return false;
            });
            Motion.attachSmoothFocus(chip, smooth, 1f);
            filterChips[i] = chip;
            filterRow.addView(chip, cLp);
        }
        updateFilterChips();
        panel.addView(filterRow, frLp);

        emptyView = new TextView(host);
        emptyView.setText(R.string.spotlight_empty);
        emptyView.setTextColor(0xFFFFFFFF);
        emptyView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setPadding(0, dp(28), 0, dp(28));
        emptyView.setVisibility(View.GONE);
        panel.addView(emptyView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        scroll = new ScrollView(host);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setClipChildren(true);
        scroll.setClipToPadding(true);
        scroll.setClipToOutline(true);
        int rootH = host.root.getHeight();
        int scrollH = rootH > 0
                ? Math.min(dp(340), Math.max(dp(180), rootH - dp(220)))
                : dp(280);
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, scrollH);
        scrollLp.topMargin = dp(8);
        resultsBox = new LinearLayout(host);
        resultsBox.setOrientation(LinearLayout.VERTICAL);
        resultsBox.setClipChildren(true);
        resultsBox.setClipToPadding(true);
        resultsBox.setPadding(dp(4), dp(4), dp(4), dp(4));
        scroll.addView(resultsBox, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        scroll.setVisibility(View.GONE);
        panel.addView(scroll, scrollLp);

        int width = Math.min(dp(600), Math.max(dp(360), host.root.getWidth() - dp(96)));
        FrameLayout.LayoutParams panelLp = new FrameLayout.LayoutParams(
                width, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL | Gravity.TOP);
        panelLp.topMargin = dp(24);
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
                String q = s == null ? "" : s.toString();
                clearBtn.setVisibility(q.isEmpty() ? View.GONE : View.VISIBLE);
                scheduleFilter(q);
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
                hideIme();
                if (resultsBox.getChildCount() > 0) {
                    resultsBox.getChildAt(0).requestFocus();
                } else {
                    filterChips[activeCategory].requestFocus();
                }
                return true;
            } else if (event.getAction() == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                    && clearBtn.getVisibility() == View.VISIBLE
                    && input.getSelectionStart() == input.length()) {
                clearBtn.requestFocus();
                return true;
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
        if (host.animations && !host.reducedMotion) {
            dlg.panel.setAlpha(0f);
            dlg.panel.animate().alpha(1f).setDuration(160)
                    .setInterpolator(new DecelerateInterpolator(1.5f)).start();
        }
        dlg.loadCatalogAsync();
        dlg.input.requestFocus();
    }

    private void updateFilterChips() {
        for (int i = 0; i < filterChips.length; i++) {
            TextView chip = filterChips[i];
            if (chip == null) continue;
            boolean selected = (i == activeCategory);
            chip.setAlpha(selected ? 1f : 0.72f);
            chip.setSelected(selected);
        }
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
        LauncherExecutors.main().postDelayed(pendingFilter, 35L);
    }

    void dismiss() {
        cancelPendingFilter();
        hideIme();
        if (popup.isShowing()) popup.dismiss();
    }

    private boolean focusFirstResult() {
        if (resultsBox != null && resultsBox.getChildCount() > 0) {
            hideIme();
            resultsBox.getChildAt(0).requestFocus();
            return true;
        }
        return false;
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
                long usedMs = LastOpened.get(host, pkg);
                out.add(new Item(CAT_APPS, label, sub, baseLabel + " " + pkg, pkg, icon, 0, usedMs, () -> {
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
                    out.add(new Item(CAT_INPUTS, name, "TV Input", "hdmi av source input", null,
                            null, R.drawable.ic_input, 0L, () -> TvInputRouter.openInput(host, id)));
                }
            } catch (Throwable ignored) {
            }
        }

        // 3. Built-in Launcher & Quick Settings Actions
        out.add(new Item(CAT_ACTIONS, host.getString(R.string.quick_settings_title), "Quick Controls",
                "toggles panel sound display wifi bluetooth", null, null, R.drawable.ic_settings, 0L,
                () -> host.openOwnedActivity(new Intent(host, QuickSettingsActivity.class), null, -1)));
        out.add(new Item(CAT_ACTIONS, host.getString(R.string.launcher_settings), "Customize Launcher",
                "columns dock wallpaper clock theme preferences", null, null, R.drawable.ic_home_settings_pill, 0L,
                () -> host.openOwnedActivity(new Intent(host, LauncherSettingsActivity.class),
                        null, MainActivity.LAUNCHER_SETTINGS_REQUEST)));
        out.add(new Item(CAT_ACTIONS, host.getString(R.string.wallpaper_sync_title), "Wi-Fi Wallpapers & Backup",
                "upload photo browser qr sync", null, null, R.drawable.ic_sync, 0L,
                () -> host.openOwnedActivity(new Intent(host, WallpaperSyncActivity.class), null, -1)));
        out.add(new Item(CAT_ACTIONS, host.getString(R.string.tile_notifications), "Notification Center",
                "alerts messages unread", null, null, R.drawable.ic_notifications, 0L,
                () -> host.openOwnedActivity(new Intent(host, NotificationPanelActivity.class), null, -1)));
        out.add(new Item(CAT_ACTIONS, host.getString(R.string.tile_screensaver), "Calendar & Clock Screensaver",
                "ambient clock dream calendar", null, null, R.drawable.ic_screensaver, 0L,
                () -> host.startActivity(new Intent(host, ClockActivity.class))));
        out.add(new Item(CAT_ACTIONS, host.getString(R.string.tile_boost_ram), "Trim Background Apps & Cache",
                "memory ram boost clean free speed", null, null, R.drawable.ic_boost, 0L,
                () -> LauncherExecutors.io().execute(() -> {
                    MemoryBooster.BoostResult res = MemoryBooster.boost(host);
                    LauncherExecutors.main().post(() -> Toast.makeText(
                            host, MemoryBooster.formatResultMessage(host, res), Toast.LENGTH_LONG).show());
                })));
        out.add(new Item(CAT_ACTIONS, host.getString(R.string.ram_manager_title), "Live Memory Bar & Force Stop Apps",
                "ram memory cleaner manager force stop heavy apps cache", null, null, R.drawable.ic_boost, 0L,
                () -> RamManagerDialog.show(host, null)));
        out.add(new Item(CAT_ACTIONS, host.getString(R.string.reorder), "Rearrange Home Grid",
                "move sort order tiles", null, null, R.drawable.ic_swap_vert, 0L, host::toggleReorder));
        out.add(new Item(CAT_ACTIONS, host.getString(R.string.button_mapper), "Customize Remote Buttons",
                "remote key map button shortcut", null, null, R.drawable.ic_remote, 0L,
                () -> ButtonMapper.showButtonMapperMenu(host, null)));
        out.add(new Item(CAT_ACTIONS, host.getString(R.string.backup_export), "Save Layout & Settings JSON",
                "export backup restore json", null, null, R.drawable.ic_sync, 0L,
                () -> LauncherExecutors.io().execute(() -> {
                    SettingsBackup.Summary s = SettingsBackup.exportToDisk(host);
                    LauncherExecutors.main().post(() -> Toast.makeText(host,
                            s.ok ? host.getString(R.string.backup_saved, s.settingCount, s.orderCount)
                                    : host.getString(R.string.backup_failed, s.error),
                            Toast.LENGTH_LONG).show());
                })));
        out.add(new Item(CAT_ACTIONS, host.getString(R.string.tile_wifi), "Network Settings",
                "internet ethernet connection", null, null, R.drawable.ic_wifi, 0L,
                () -> host.open(SystemSettings.intent(host, Settings.ACTION_WIFI_SETTINGS))));
        out.add(new Item(CAT_ACTIONS, host.getString(R.string.tile_display), "Picture Settings",
                "brightness contrast hdr screen", null, null, R.drawable.ic_picture, 0L,
                () -> host.open(SystemSettings.displayIntent(host))));
        out.add(new Item(CAT_ACTIONS, host.getString(R.string.tile_sound), "Sound Settings",
                "audio volume speakers", null, null, R.drawable.ic_sound, 0L,
                () -> host.open(SystemSettings.intent(host, Settings.ACTION_SOUND_SETTINGS))));
        out.add(new Item(CAT_ACTIONS, host.getString(R.string.tile_bluetooth), "Remotes & Accessories",
                "pair controller headphones", null, null, R.drawable.ic_bluetooth, 0L,
                () -> host.open(SystemSettings.intent(host, Settings.ACTION_BLUETOOTH_SETTINGS))));
        out.add(new Item(CAT_ACTIONS, host.getString(R.string.tv_settings), "Android TV System Settings",
                "system preferences device", null, null, R.drawable.ic_settings, 0L,
                () -> host.open(SystemSettings.intent(host, Settings.ACTION_SETTINGS))));

        Collections.sort(out, (a, b) -> {
            int usedCmp = Long.compare(b.lastUsedMs, a.lastUsedMs);
            if (usedCmp != 0) return usedCmp;
            int catCmp = Integer.compare(a.category, b.category);
            if (catCmp != 0) return catCmp;
            return a.title.compareToIgnoreCase(b.title);
        });
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
        String q = rawQuery == null ? "" : rawQuery.trim();
        if (q.isEmpty()) {
            resultsBox.removeAllViews();
            countBadge.setText("");
            emptyView.setVisibility(View.GONE);
            scroll.setVisibility(View.GONE);
            return;
        }
        if (!catalogReady) return;
        List<Item> matched = new ArrayList<>();
        List<Integer> scores = new ArrayList<>();
        for (Item item : catalog) {
            if (activeCategory != CAT_ALL && item.category != activeCategory) continue;
            int titleScore = matchScore(item.title, q);
            int subScore = matchScore(item.subtitle, q) > 1 ? 1 : 0;
            int kwScore = matchScore(item.keywords, q) >= 2 ? 2 : 0;
            int score = Math.max(titleScore, Math.max(subScore, kwScore));
            if (score > 0) {
                matched.add(item);
                scores.add(score);
            }
        }
        // Sort by descending match score, then recent usage, then alphabetical
        List<Integer> idx = new ArrayList<>(matched.size());
        for (int i = 0; i < matched.size(); i++) idx.add(i);
        Collections.sort(idx, (a, b) -> {
            int cmp = Integer.compare(scores.get(b), scores.get(a));
            if (cmp != 0) return cmp;
            Item ia = matched.get(a);
            Item ib = matched.get(b);
            int usedCmp = Long.compare(ib.lastUsedMs, ia.lastUsedMs);
            if (usedCmp != 0) return usedCmp;
            return ia.title.compareToIgnoreCase(ib.title);
        });

        resultsBox.removeAllViews();
        int limit = Math.min(idx.size(), 24);
        countBadge.setText(String.valueOf(idx.size()));
        emptyView.setVisibility(limit == 0 ? View.VISIBLE : View.GONE);
        scroll.setVisibility(limit == 0 ? View.GONE : View.VISIBLE);
        boolean smooth = host.animations && !host.reducedMotion;
        for (int i = 0; i < limit; i++) {
            Item item = matched.get(idx.get(i));
            View row = buildRow(item, q, i == 0);
            Motion.attachSmoothFocus(row, smooth, 1.02f);
            resultsBox.addView(row);
        }
        scroll.scrollTo(0, 0);
    }

    private View buildRow(Item item, String query, boolean isFirst) {
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
        LinearLayout.LayoutParams iLp = new LinearLayout.LayoutParams(dp(26), dp(26));
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
        title.setText(highlightMatch(item.title, query));
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

        TextView hint = new TextView(host);
        hint.setText(item.category == CAT_APPS ? "Open" : item.category == CAT_INPUTS ? "Switch" : "Run");
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        hint.setTextColor(host.getColorStateList(R.color.quick_tile_sub_color));
        hint.setDuplicateParentStateEnabled(true);
        hint.setPadding(dp(10), 0, 0, 0);
        row.addView(hint);

        row.setOnClickListener(v -> {
            UiFeedback.playClick(v);
            dismiss();
            if (item.action != null) item.action.run();
        });
        row.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN && isFirst && keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                filterChips[activeCategory].requestFocus();
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

    private static CharSequence highlightMatch(String title, String query) {
        if (title == null || query == null || query.isEmpty()) return title;
        int idx = title.toLowerCase(Locale.ROOT).indexOf(query.toLowerCase(Locale.ROOT));
        if (idx < 0) return title;
        SpannableString span = new SpannableString(title);
        span.setSpan(new ForegroundColorSpan(0xFFA8C7FA), idx,
                Math.min(title.length(), idx + query.length()), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return span;
    }

    private int dp(int v) {
        return Math.round(v * density);
    }
}
