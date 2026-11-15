package com.gathin.tvhome;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Recent Apps / Multitasking Switcher for CleanYourTV.
 *
 * Provides a lightweight horizontal card carousel navigable by a TV remote.
 * Supports:
 *   1. Resume/switch to an active application task (or cold-start if task was reclaimed by OS).
 *   2. Dismiss an app from launcher history (honestly labelled; does not stop background processes).
 *   3. Direct access to Android App Info for authentic Force Stop operations.
 *   4. Clean D-pad navigation, smooth hardware-accelerated scale animations, and deterministic focus.
 *   5. Native Android Recents invocation when the accessibility service supports GLOBAL_ACTION_RECENTS.
 */
public final class RecentAppsActivity extends Activity {
    private static final String TAG = "TvHome.RecentApps";
    private static final float FOCUS_SCALE = 1.07f;
    private static final int ANIM_DURATION_MS = 140;

    private RecyclerView recentsRecycler;
    private RecentsAdapter adapter;
    private View contentLayout;
    private View emptyLayout;
    private TextView btnActionOpen;
    private TextView btnActionDismiss;
    private TextView btnActionForceStop;
    private TextView btnActionAppInfo;
    private ExecutorService loaderExecutor;

    private int focusedPosition = 0;
    private WindowMorph morph;
    private boolean animateTransitions;
    private boolean launchingApp;
    private boolean closingWithMorph;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(LauncherLocale.wrap(base));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        overridePendingTransition(0, 0);
        Config cfg = Config.load(this);
        animateTransitions = cfg.animations && !cfg.reducedMotion;
        setContentView(R.layout.activity_recent_apps);
        ClockFormat.apply(getWindow().getDecorView(), cfg.clockFormat);

        View root = findViewById(R.id.recentRoot);
        View header = findViewById(R.id.headerLayout);
        contentLayout = findViewById(R.id.contentLayout);
        emptyLayout = findViewById(R.id.emptyLayout);
        morph = new WindowMorph(root, null, header, contentLayout, emptyLayout);
        morph.setTargetCornerRadius(0f);
        morph.attachSource(getIntent());
        morph.playEnter(animateTransitions, null);
        recentsRecycler = findViewById(R.id.recentsRecycler);
        btnActionOpen = findViewById(R.id.btnActionOpen);
        btnActionDismiss = findViewById(R.id.btnActionDismiss);
        btnActionForceStop = findViewById(R.id.btnActionForceStop);
        btnActionAppInfo = findViewById(R.id.btnActionAppInfo);

        TextView btnEmptyHome = findViewById(R.id.btnEmptyHome);
        if (btnEmptyHome != null) {
            btnEmptyHome.setOnClickListener(v -> finishWithCloseTransition());
        }

        LinearLayoutManager layoutManager = new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false);
        recentsRecycler.setLayoutManager(layoutManager);
        adapter = new RecentsAdapter();
        recentsRecycler.setAdapter(adapter);

        setupActionButtons();
        LastOpened.recordClosed(this);
        loadRecentApps();
    }

    @Override
    protected void onResume() {
        super.onResume();
        launchingApp = false;
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        launchingApp = false;
        closingWithMorph = false;
        setIntent(intent);
        if (morph != null) {
            morph.cancelAndReset();
            morph.attachSource(intent);
            morph.playEnter(animateTransitions, null);
        }
        loadRecentApps();
    }

    private void applyOpenTransition() {
        if (animateTransitions) {
            overridePendingTransition(R.anim.app_open_enter, R.anim.app_open_exit);
        } else {
            overridePendingTransition(0, 0);
        }
    }

    private void applyCloseTransition() {
        if (animateTransitions) {
            overridePendingTransition(R.anim.app_close_enter, R.anim.app_close_exit);
        } else {
            overridePendingTransition(0, 0);
        }
    }

    private void finishWithCloseTransition() {
        if (closingWithMorph || isFinishing()) return;
        closingWithMorph = true;
        if (morph != null) {
            morph.playExit(animateTransitions && !launchingApp, () -> {
                if (!isFinishing()) finish();
                overridePendingTransition(0, 0);
            });
        } else {
            if (!isFinishing()) finish();
            applyCloseTransition();
        }
    }

    private void setupActionButtons() {
        btnActionOpen.setOnClickListener(v -> {
            RecentApps.Item item = adapter.getItem(focusedPosition);
            if (item != null) performOpen(item);
        });

        btnActionDismiss.setOnClickListener(v -> {
            RecentApps.Item item = adapter.getItem(focusedPosition);
            if (item != null) performDismiss(item, focusedPosition);
        });

        // Only display Force Stop when the operation can actually be performed.
        // If authorization is missing (e.g. unprivileged Android 14), offer App Info instead.
        boolean hasForceStopPrivilege = RecentApps.canForceStop(this);
        if (btnActionForceStop != null) {
            btnActionForceStop.setVisibility(hasForceStopPrivilege ? View.VISIBLE : View.GONE);
            if (hasForceStopPrivilege) {
                btnActionForceStop.setOnClickListener(v -> {
                    RecentApps.Item item = adapter.getItem(focusedPosition);
                    if (item != null) performForceStop(item, focusedPosition);
                });
            }
        }

        btnActionAppInfo.setOnClickListener(v -> {
            RecentApps.Item item = adapter.getItem(focusedPosition);
            if (item != null) performAppInfo(item);
        });

        // D-pad UP from action buttons returns focus to the currently selected card
        View.OnKeyListener actionUpListener = (v, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                focusCardAt(focusedPosition);
                return true;
            }
            return false;
        };

        btnActionOpen.setOnKeyListener(actionUpListener);
        btnActionDismiss.setOnKeyListener(actionUpListener);
        if (btnActionForceStop != null) {
            btnActionForceStop.setOnKeyListener(actionUpListener);
        }
        btnActionAppInfo.setOnKeyListener(actionUpListener);
    }

    private void focusCardAt(int pos) {
        if (adapter == null || adapter.getItemCount() == 0) return;
        int target = Math.max(0, Math.min(pos, adapter.getItemCount() - 1));
        focusedPosition = target;
        recentsRecycler.scrollToPosition(target);
        recentsRecycler.post(() -> {
            RecyclerView.ViewHolder vh = recentsRecycler.findViewHolderForAdapterPosition(target);
            if (vh != null) {
                vh.itemView.requestFocus();
            }
        });
    }

    private void loadRecentApps() {
        if (loaderExecutor == null || loaderExecutor.isShutdown()) {
            loaderExecutor = Executors.newSingleThreadExecutor();
        }

        loaderExecutor.execute(() -> {
            List<RecentApps.Item> items = RecentApps.loadRecents(this);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                adapter.setItems(items);
                if (items.isEmpty()) {
                    showEmptyState();
                } else {
                    contentLayout.setVisibility(View.VISIBLE);
                    emptyLayout.setVisibility(View.GONE);
                    focusCardAt(0);
                }
            });
        });
    }

    private Bundle launchOptions() {
        if (!animateTransitions) {
            return ActivityOptions.makeCustomAnimation(this, 0, 0).toBundle();
        }
        if (recentsRecycler != null) {
            RecyclerView.ViewHolder vh = recentsRecycler.findViewHolderForAdapterPosition(focusedPosition);
            if (vh != null && vh.itemView.getWidth() > 0 && vh.itemView.getHeight() > 0) {
                return ActivityOptions.makeScaleUpAnimation(
                        vh.itemView, 0, 0, vh.itemView.getWidth(), vh.itemView.getHeight()).toBundle();
            }
        }
        return ActivityOptions.makeCustomAnimation(this, R.anim.app_open_enter, R.anim.app_open_exit).toBundle();
    }

    private void performOpen(RecentApps.Item item) {
        if (item == null) return;
        Intent intent = RecentApps.buildResumeIntent(this, item.pkg, item.componentName);
        if (intent == null) {
            Toast.makeText(this, getString(R.string.cant_open, item.label), Toast.LENGTH_SHORT).show();
            return;
        }
        launchingApp = true;
        try {
            startActivity(intent, launchOptions());
            ComponentName cn = (item.componentName != null && !item.componentName.isEmpty())
                    ? ComponentName.unflattenFromString(item.componentName) : null;
            RecentApps.record(this, item.pkg, cn, item.label);
            LastOpened.record(this, item.pkg);
            finish();
        } catch (Exception e) {
            launchingApp = false;
            Log.w(TAG, "Cannot resume app " + item.pkg + ": " + e);
            // Fallback attempt with standard launch intent
            try {
                Intent fallback = getPackageManager().getLaunchIntentForPackage(item.pkg);
                if (fallback != null) {
                    fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    launchingApp = true;
                    startActivity(fallback, launchOptions());
                    RecentApps.record(this, item.pkg, null, item.label);
                    LastOpened.record(this, item.pkg);
                    finish();
                    return;
                }
            } catch (Exception ignored) {
                launchingApp = false;
            }
            Toast.makeText(this, getString(R.string.cant_open, item.label), Toast.LENGTH_SHORT).show();
        }
    }

    private void performDismiss(RecentApps.Item item, int position) {
        if (item == null) return;
        RecentApps.dismiss(this, item.pkg);
        adapter.removeItem(position);
        Toast.makeText(this, getString(R.string.recents_dismissed_toast, item.label), Toast.LENGTH_SHORT).show();

        if (adapter.getItemCount() == 0) {
            showEmptyState();
        } else {
            int newPos = Math.min(position, adapter.getItemCount() - 1);
            focusedPosition = newPos;
            recentsRecycler.scrollToPosition(newPos);
            recentsRecycler.post(() -> {
                RecyclerView.ViewHolder vh = recentsRecycler.findViewHolderForAdapterPosition(newPos);
                if (vh != null) {
                    vh.itemView.requestFocus();
                } else {
                    recentsRecycler.postDelayed(() -> {
                        RecyclerView.ViewHolder vh2 = recentsRecycler.findViewHolderForAdapterPosition(newPos);
                        if (vh2 != null) {
                            vh2.itemView.requestFocus();
                        } else if (btnActionDismiss != null && btnActionDismiss.isShown()) {
                            btnActionDismiss.requestFocus();
                        }
                    }, 50);
                }
            });
        }
    }

    private void performForceStop(RecentApps.Item item, int position) {
        if (item == null) return;
        if (RecentApps.canForceStop(this)) {
            boolean stopped = RecentApps.forceStop(this, item.pkg);
            if (stopped) {
                RecentApps.dismiss(this, item.pkg);
                adapter.removeItem(position);
                Toast.makeText(this, item.label + " force stopped", Toast.LENGTH_SHORT).show();
                if (adapter.getItemCount() == 0) {
                    showEmptyState();
                } else {
                    focusCardAt(Math.min(position, adapter.getItemCount() - 1));
                }
                return;
            }
        }
        // Fallback: explain privilege requirement and route to App Info
        Toast.makeText(this, R.string.recents_force_stop_requires_auth, Toast.LENGTH_LONG).show();
        performAppInfo(item);
    }

    private void performAppInfo(RecentApps.Item item) {
        if (item == null) return;
        try {
            Intent info = SystemSettings.appInfo(this, item.pkg);
            if (info == null) {
                info = RecentApps.buildAppInfoIntent(item.pkg);
            }
            if (info != null) {
                info.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                launchingApp = true;
                if (animateTransitions) {
                    ActivityOptions opts = ActivityOptions.makeCustomAnimation(this, R.anim.app_open_enter, R.anim.app_open_exit);
                    startActivity(info, opts.toBundle());
                } else {
                    startActivity(info);
                }
            } else {
                Toast.makeText(this, getString(R.string.cant_open, item.label), Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            launchingApp = false;
            Log.w(TAG, "Cannot open App Info for " + item.pkg + ": " + e);
            Toast.makeText(this, getString(R.string.cant_open, item.label), Toast.LENGTH_SHORT).show();
        }
    }

    private void showEmptyState() {
        contentLayout.setVisibility(View.GONE);
        emptyLayout.setVisibility(View.VISIBLE);
        View btnEmptyHome = findViewById(R.id.btnEmptyHome);
        if (btnEmptyHome != null) {
            btnEmptyHome.requestFocus();
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (closingWithMorph) return true;
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            finishWithCloseTransition();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void onBackPressed() {
        finishWithCloseTransition();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (!closingWithMorph) {
            if (morph != null) morph.cancelAndReset();
            if (isFinishing() && !launchingApp) {
                applyCloseTransition();
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (morph != null) morph.cancelAndReset();
        if (loaderExecutor != null) {
            loaderExecutor.shutdownNow();
            loaderExecutor = null;
        }
        if (recentsRecycler != null) {
            recentsRecycler.setAdapter(null);
        }
    }

    // --- Recents Adapter ---

    private final class RecentsAdapter extends RecyclerView.Adapter<RecentsViewHolder> {
        private final List<RecentApps.Item> list = new ArrayList<>();

        void setItems(List<RecentApps.Item> items) {
            list.clear();
            if (items != null) list.addAll(items);
            notifyDataSetChanged();
        }

        void removeItem(int position) {
            if (position >= 0 && position < list.size()) {
                list.remove(position);
                notifyItemRemoved(position);
                notifyItemRangeChanged(position, list.size() - position);
            }
        }

        void clear() {
            list.clear();
            notifyDataSetChanged();
        }

        RecentApps.Item getItem(int position) {
            if (position >= 0 && position < list.size()) {
                return list.get(position);
            }
            return null;
        }

        @NonNull
        @Override
        public RecentsViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_recent_card, parent, false);
            return new RecentsViewHolder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull RecentsViewHolder holder, int position) {
            RecentApps.Item item = list.get(position);
            holder.bind(item, position);
        }

        @Override
        public int getItemCount() {
            return list.size();
        }
    }

    // --- Recents ViewHolder ---

    private final class RecentsViewHolder extends RecyclerView.ViewHolder {
        private final ImageView imgBanner;
        private final ImageView imgIcon;
        private final TextView txtAppName;
        private final TextView txtAppTime;

        RecentsViewHolder(@NonNull View itemView) {
            super(itemView);
            imgBanner = itemView.findViewById(R.id.imgBanner);
            imgIcon = itemView.findViewById(R.id.imgIcon);
            txtAppName = itemView.findViewById(R.id.txtAppName);
            txtAppTime = itemView.findViewById(R.id.txtAppTime);

            itemView.setOnFocusChangeListener((v, hasFocus) -> {
                v.animate().cancel();
                float targetScale = hasFocus ? FOCUS_SCALE : 1.0f;
                if (hasFocus) {
                    int pos = getBindingAdapterPosition();
                    if (pos != RecyclerView.NO_POSITION) {
                        focusedPosition = pos;
                        recentsRecycler.smoothScrollToPosition(pos);
                    }
                }
                if (!animateTransitions) {
                    v.setScaleX(targetScale);
                    v.setScaleY(targetScale);
                } else {
                    v.animate()
                            .scaleX(targetScale)
                            .scaleY(targetScale)
                            .setDuration(ANIM_DURATION_MS)
                            .setInterpolator(new DecelerateInterpolator())
                            .start();
                }
            });

            // D-pad DOWN from card moves focus directly to the Action Bar
            itemView.setOnKeyListener((v, keyCode, event) -> {
                if (event.getAction() == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                    if (btnActionOpen != null && btnActionOpen.isShown()) {
                        btnActionOpen.requestFocus();
                        return true;
                    }
                }
                return false;
            });

            itemView.setOnClickListener(v -> {
                int pos = getBindingAdapterPosition();
                if (pos != RecyclerView.NO_POSITION) {
                    RecentApps.Item item = adapter.getItem(pos);
                    if (item != null) performOpen(item);
                }
            });
        }

        void bind(RecentApps.Item item, int position) {
            itemView.animate().cancel();
            float scale = itemView.hasFocus() ? FOCUS_SCALE : 1.0f;
            itemView.setScaleX(scale);
            itemView.setScaleY(scale);

            txtAppName.setText(item.label);
            txtAppTime.setText(item.relativeTime != null && !item.relativeTime.isEmpty() ? item.relativeTime : "");

            if (item.banner != null) {
                imgBanner.setImageDrawable(item.banner);
                imgBanner.setVisibility(View.VISIBLE);
                imgIcon.setImageDrawable(null);
                imgIcon.setVisibility(View.GONE);
            } else if (item.icon != null) {
                imgBanner.setImageDrawable(null);
                imgBanner.setVisibility(View.GONE);
                imgIcon.setImageDrawable(item.icon);
                imgIcon.setVisibility(View.VISIBLE);
            } else {
                imgBanner.setImageDrawable(null);
                imgBanner.setVisibility(View.GONE);
                imgIcon.setImageDrawable(null);
                imgIcon.setVisibility(View.GONE);
            }
        }
    }
}
