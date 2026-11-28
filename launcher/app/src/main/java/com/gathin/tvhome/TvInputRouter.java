package com.gathin.tvhome;

import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.Uri;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Package-private TV input, source panel, and boot/wake app routing for {@link MainActivity}.
 */
final class TvInputRouter {
    private TvInputRouter() {
    }

    static void updateInputsButton(MainActivity host) {
        if (!host.showInputsPill) {
            host.btnInputs.setVisibility(View.GONE);
            return;
        }
        boolean any = liveTvIntent(host) != null || !host.sourcePanelService.isEmpty()
                || (host.coocaaSources && !CoocaaSources.list(host).isEmpty());
        TvInputManager tim = (TvInputManager) host.getSystemService(Context.TV_INPUT_SERVICE);
        if (!any && tim != null) {
            for (TvInputInfo info : tim.getTvInputList()) {
                if (info.isPassthroughInput() && !info.isHidden(host)) {
                    any = true;
                    break;
                }
            }
        }
        host.btnInputs.setVisibility(any ? View.VISIBLE : View.GONE);
    }

    static boolean openLiveTvAfterBoot(MainActivity host) {
        if (Config.STARTUP_HOME.equals(host.startupDestination)) return false;
        String app = host.openOnStartChoice ? host.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE)
                .getString(OpenOnStart.KEY, "") : "";
        boolean wantLiveTv = Config.STARTUP_LIVE_TV.equals(host.startupDestination)
                || (host.startupDestination.isEmpty() && host.bootToLiveTv);
        boolean wantApp = Config.STARTUP_APP.equals(host.startupDestination)
                || (host.startupDestination.isEmpty() && !host.bootToLiveTv);
        if (!wantLiveTv && (!wantApp || app.isEmpty())) return false;
        int boot = OpenOnStart.boot(host);
        SharedPreferences prefs = host.getSharedPreferences(OpenOnStart.BOOT_PREFS, Context.MODE_PRIVATE);
        String target = wantLiveTv ? host.liveTvPkg : app;
        if (!OpenOnStart.due(boot, prefs.getInt(OpenOnStart.BOOT_KEY, -1), target, host.getPackageName())) return false;
        prefs.edit().putInt(OpenOnStart.BOOT_KEY, boot).apply();
        Intent i = wantLiveTv ? liveTvIntent(host) : OpenOnStart.intent(host.getPackageManager(), app);
        if (i == null) return false;
        return openWhenReady(host, i);
    }

    static void openStartAppOnWake(MainActivity host) {
        String app = host.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE)
                .getString(OpenOnStart.KEY, "");
        Intent i = !app.isEmpty() && !app.equals(host.getPackageName())
                ? OpenOnStart.intent(host.getPackageManager(), app)
                : (host.bootToLiveTv || Config.STARTUP_LIVE_TV.equals(host.startupDestination) ? liveTvIntent(host) : null);
        if (i != null) openWhenReady(host, i);
    }

    private static boolean openWhenReady(MainActivity host, Intent i) {
        if (host.openOnStartWaitMs > 0 && !OpenOnStart.online(host)) {
            host.startPending = i;
            host.startDeadline = SystemClock.elapsedRealtime() + host.openOnStartWaitMs;
            host.handler.postDelayed(host.openWhenOnline, host.openOnStartWaitMs);
            if (OpenOnStart.online(host)) host.handler.post(host.openWhenOnline);
            return false;
        }
        host.open(i);
        return true;
    }

    static Intent liveTvIntent(MainActivity host) {
        if (host.liveTvPkg.isEmpty()) return null;
        Intent i = host.getPackageManager().getLeanbackLaunchIntentForPackage(host.liveTvPkg);
        if (i != null || host.liveTvAction.isEmpty()) return i;
        Intent byAction = new Intent(host.liveTvAction).setPackage(host.liveTvPkg);
        return host.getPackageManager().resolveActivity(byAction, 0) != null ? byAction : null;
    }

    static void openSourcePanel(MainActivity host) {
        Intent i = new Intent(host.sourcePanelAction);
        ComponentName c = ComponentName.unflattenFromString(host.sourcePanelService);
        if (c != null) i.setComponent(c);
        int eq = host.sourcePanelExtra.indexOf('=');
        if (eq > 0) {
            try {
                i.putExtra(host.sourcePanelExtra.substring(0, eq).trim(),
                        Integer.parseInt(host.sourcePanelExtra.substring(eq + 1).trim()));
            } catch (NumberFormatException ignored) {
            }
        }
        try {
            if (host.getPackageManager().resolveActivity(i, 0) != null) {
                host.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            }
            host.startService(i);
        } catch (Exception e) {
            Toast.makeText(host, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }

    static Integer mappedSource(MainActivity host, String inputId) {
        return InputSources.mapped(host.inputSources, inputId);
    }

    static void openInput(MainActivity host, String inputId) {
        String sourceId = inputId;
        TvInputManager tim = (TvInputManager) host.getSystemService(Context.TV_INPUT_SERVICE);
        if (tim != null) {
            try {
                TvInputInfo info = tim.getTvInputInfo(inputId);
                if (info != null && info.getParentId() != null) sourceId = info.getParentId();
            } catch (RuntimeException ignored) {
            }
        }
        Integer source = mappedSource(host, sourceId);
        if (source != null && source >= 0 && !host.inputAction.isEmpty()) {
            try {
                host.startActivity(inputActionIntent(host).putExtra("input", source.intValue())
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            } catch (ActivityNotFoundException | SecurityException ignored) {
            }
        } else if (source == null && InputSources.isComponent(host.inputAction)) {
            try {
                host.startActivity(inputActionIntent(host).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            } catch (ActivityNotFoundException | SecurityException ignored) {
            }
        }
        Uri uri = TvContract.buildChannelUriForPassthroughInput(inputId);
        Intent view = (host.inputUriScheme.isEmpty()
                ? new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "vnd.android.cursor.item/channel")
                : new Intent(Intent.ACTION_VIEW, uri.buildUpon().scheme(host.inputUriScheme).build()))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (!host.liveTvPkg.isEmpty()) {
            try {
                host.startActivity(new Intent(view).setPackage(host.liveTvPkg));
                return;
            } catch (ActivityNotFoundException | SecurityException ignored) {
            }
        }
        try {
            host.startActivity(view);
            return;
        } catch (ActivityNotFoundException | SecurityException e) {
            if (host.inputAction.isEmpty()) {
                Toast.makeText(host, R.string.not_available, Toast.LENGTH_SHORT).show();
                return;
            }
        }
        host.open(inputActionIntent(host).setDataAndType(uri, "vnd.android.cursor.item/channel"));
    }

    private static Intent inputActionIntent(MainActivity host) {
        ComponentName component = InputSources.isComponent(host.inputAction)
                ? ComponentName.unflattenFromString(host.inputAction) : null;
        return component != null ? new Intent().setComponent(component) : new Intent(host.inputAction);
    }

    static void showInputs(MainActivity host) {
        List<CoocaaSources.Source> coocaa = host.coocaaSources ? CoocaaSources.list(host) : Collections.emptyList();
        if (coocaa.isEmpty() && !host.sourcePanelService.isEmpty() && liveTvIntent(host) == null) {
            openSourcePanel(host);
            return;
        }
        boolean underGear = host.topMenu;
        View anchor = host.findViewById(underGear ? R.id.btnSettings : R.id.btnInputs);
        LayoutInflater inflater = host.getLayoutInflater();
        View panel = inflater.inflate(R.layout.popup_inputs, host.root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);

        int width = Math.max(anchor.getWidth(), (int) (150 * host.density));
        final PopupWindow popup = HomeMenus.glassPopup(host, panel, width);

        TvInputManager tim = (TvInputManager) host.getSystemService(Context.TV_INPUT_SERVICE);
        boolean connectedOnly = host.inputsConnectedOnly;
        List<View> folded = new ArrayList<>();
        boolean sawNonHdmi = false;
        boolean rootChannel = TvContract.Channels.CONTENT_URI.toString().equals(host.liveTvInputId);
        String tunerId = null;
        boolean tunerIsHw = false;
        boolean tunerIsAnalog = false;
        if (tim != null) {
            List<TvInputInfo> inputs = new ArrayList<>(tim.getTvInputList());
            Collections.sort(inputs, (a, b) -> {
                boolean ha = a.getType() == TvInputInfo.TYPE_HDMI, hb = b.getType() == TvInputInfo.TYPE_HDMI;
                if (ha != hb) return ha ? -1 : 1;
                return String.valueOf(a.loadLabel(host)).compareToIgnoreCase(String.valueOf(b.loadLabel(host)));
            });
            Set<String> inputIds = new HashSet<>();
            for (TvInputInfo info : inputs) inputIds.add(info.getId());
            Map<String, List<TvInputInfo>> cecDevices = new HashMap<>();
            for (TvInputInfo info : inputs) {
                String parent = info.getParentId();
                if (parent == null || !inputIds.contains(parent)) continue;
                if (!info.isPassthroughInput() || info.isHidden(host)) continue;
                List<TvInputInfo> onPort = cecDevices.get(parent);
                if (onPort == null) cecDevices.put(parent, onPort = new ArrayList<>());
                onPort.add(info);
            }
            int hdmiPorts = 0, hdmiConnected = 0;
            for (TvInputInfo info : inputs) {
                if (!info.isPassthroughInput() || info.getType() != TvInputInfo.TYPE_HDMI) continue;
                if (info.getParentId() != null && inputIds.contains(info.getParentId())) continue;
                hdmiPorts++;
                if (tim.getInputState(info.getId()) == TvInputManager.INPUT_STATE_CONNECTED) hdmiConnected++;
            }
            boolean hdmiStateBogus = host.inputsCecStateOnly || (hdmiPorts > 1 && hdmiConnected == hdmiPorts);
            for (TvInputInfo info : inputs) {
                if (info.getType() == TvInputInfo.TYPE_TUNER
                        && (info.getServiceInfo().applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0
                        && (host.liveTvInputId.isEmpty() || rootChannel || host.liveTvInputId.equals(info.getId()))) {
                    boolean hw = info.getId().matches(".*/HW\\d+$");
                    boolean analog = info.getId().toLowerCase(Locale.ROOT).contains("analog");
                    boolean better = tunerId == null
                            || (hw && !tunerIsHw)
                            || (hw == tunerIsHw && tunerIsAnalog && !analog)
                            || (hw == tunerIsHw && analog == tunerIsAnalog && info.getId().compareTo(tunerId) < 0);
                    if (better) {
                        tunerId = info.getId();
                        tunerIsHw = hw;
                        tunerIsAnalog = analog;
                    }
                }
                if (!info.isPassthroughInput() || info.isHidden(host)) continue;
                if (Integer.valueOf(-1).equals(mappedSource(host, info.getId()))) continue;
                if (info.getParentId() != null && inputIds.contains(info.getParentId())) continue;
                CharSequence l = info.loadLabel(host);
                String label = l == null ? info.getId() : l.toString();
                boolean hdmi = info.getType() == TvInputInfo.TYPE_HDMI;
                if (hdmi && !label.toUpperCase(Locale.ROOT).contains("HDMI")) label = "HDMI " + label;
                CharSequence custom = InputNames.tidy(info.loadCustomLabel(host));
                boolean named = custom != null && custom.length() > 0 && !custom.toString().equals(label);
                if (!hdmi && rows.getChildCount() > 0 && !sawNonHdmi) {
                    View line = HomeMenus.addSeparator(host, rows, host.density);
                    if (connectedOnly) fold(line, folded);
                }
                if (!hdmi) sawNonHdmi = true;
                List<TvInputInfo> devices = cecDevices.get(info.getId());
                if (devices != null) {
                    for (TvInputInfo device : devices) {
                        CharSequence name = InputNames.tidy(device.loadCustomLabel(host));
                        if (name == null || name.length() == 0) name = named ? custom : device.loadLabel(host);
                        String deviceLabel = name == null || name.length() == 0 ? label : name + " \u00B7 " + label;
                        final String deviceId = device.getId();
                        int state = hdmiStateBogus && !host.inputsCecStateOnly ? -1 : tim.getInputState(deviceId);
                        View row = HomeMenus.addInputRow(inflater, rows, popup, R.drawable.ic_input, deviceLabel,
                                state, () -> openInput(host, deviceId));
                        if (connectedOnly && InputFold.folds(true, state)) fold(row, folded);
                    }
                    continue;
                }
                if (named) label = custom + " \u00B7 " + label;
                final String id = info.getId();
                int state = !hdmi || hdmiStateBogus ? -1 : tim.getInputState(id);
                View row = HomeMenus.addInputRow(inflater, rows, popup, hdmi ? R.drawable.ic_input : R.drawable.ic_av,
                        label, state, () -> openInput(host, id));
                if (connectedOnly && InputFold.folds(hdmi, state)) fold(row, folded);
            }
        }
        if (!folded.isEmpty()) {
            View more = inflater.inflate(R.layout.item_input, rows, false);
            ((ImageView) more.findViewById(R.id.icon)).setImageResource(R.drawable.ic_input);
            ((TextView) more.findViewById(R.id.title)).setText(R.string.more_inputs);
            more.findViewById(R.id.status).setVisibility(View.GONE);
            more.findViewById(R.id.dot).setVisibility(View.GONE);
            more.setOnClickListener(v -> {
                for (View f : folded) f.setVisibility(View.VISIBLE);
                rows.removeView(more);
                for (View f : folded) {
                    if (f.isFocusable()) { f.requestFocus(); break; }
                }
            });
            rows.addView(more);
        }
        if (rows.getChildCount() == 0) {
            for (CoocaaSources.Source s : coocaa) {
                int icon = "HDMI".equals(s.name) ? R.drawable.ic_input
                        : "VOD".equals(s.name) ? R.drawable.ic_livetv : R.drawable.ic_av;
                HomeMenus.addInputRow(inflater, rows, popup, icon, s.label, -1, () -> {
                    if (!CoocaaSources.open(host, s)) {
                        Toast.makeText(host, R.string.not_available, Toast.LENGTH_SHORT).show();
                    }
                });
            }
        }
        if (rows.getChildCount() == 0 && !host.sourcePanelService.isEmpty()) {
            HomeMenus.addInputRow(inflater, rows, popup, R.drawable.ic_input,
                    host.getString(R.string.inputs), -1, () -> openSourcePanel(host));
        }
        Intent liveTv = liveTvIntent(host);
        if (liveTv != null && tunerId != null) {
            boolean configured = !host.liveTvInputId.isEmpty() && !rootChannel;
            Intent tuned = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(configured ? TvContract.buildChannelsUriForInput(tunerId)
                                    : TvContract.buildChannelUriForPassthroughInput(tunerId),
                            configured ? TvContract.Channels.CONTENT_TYPE : TvContract.Channels.CONTENT_ITEM_TYPE)
                    .setPackage(host.liveTvPkg);
            ComponentName handler = tuned.resolveActivity(host.getPackageManager());
            if (handler != null) {
                liveTv = rootChannel
                        ? new Intent(Intent.ACTION_VIEW).setData(TvContract.Channels.CONTENT_URI).setComponent(handler)
                        : tuned;
            }
        }
        if (liveTv != null) {
            if (rows.getChildCount() > 0) HomeMenus.addSeparator(host, rows, host.density);
            final Intent liveTvIntent = liveTv;
            HomeMenus.addInputRow(inflater, rows, popup, R.drawable.ic_livetv,
                    host.getString(R.string.live_tv), -1, () -> host.open(liveTvIntent));
        }
        if (rows.getChildCount() == 0) {
            Toast.makeText(host, R.string.no_inputs, Toast.LENGTH_SHORT).show();
            return;
        }

        HomeMenus.addSeparator(host, rows, host.density);
        View settingsAnchor = underGear ? host.findViewById(R.id.btnSettings) : anchor;
        HomeMenus.addInputRow(inflater, rows, popup, R.drawable.ic_home_settings_pill,
                host.getString(R.string.launcher_settings), -1,
                () -> host.openOwnedActivity(new Intent(host, LauncherSettingsActivity.class),
                        settingsAnchor, MainActivity.LAUNCHER_SETTINGS_REQUEST));

        panel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        popup.setWidth(Math.max(width, Math.min(panel.getMeasuredWidth(), (int) (360 * host.density))));

        int alignment = host.getResources().getBoolean(R.bool.rtl_menu_bar) != underGear ? Gravity.END : Gravity.START;
        HomeMenus.dropIn(popup, panel, anchor, alignment, host.density, host.animations, host.reducedMotion);
        for (int i = 0; i < rows.getChildCount(); i++) {
            View child = rows.getChildAt(i);
            if (child.getVisibility() == View.VISIBLE && child.isFocusable()) { child.requestFocus(); break; }
        }
    }

    private static void fold(View view, List<View> folded) {
        view.setVisibility(View.GONE);
        folded.add(view);
    }
}
