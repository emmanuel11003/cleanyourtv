package com.gathin.tvhome;

import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Package-private controller for Module Cards approval prompts and the Module Cards menu
 * in {@link MainActivity}.
 */
final class ModuleCardController {
    Dialog cardPrompt;
    boolean promptsPaused;

    void resetPaused() {
        promptsPaused = false;
    }

    boolean isPromptShowing() {
        return cardPrompt != null;
    }

    void dismissPrompt() {
        if (cardPrompt != null) cardPrompt.dismiss();
    }

    void showCardPrompt(MainActivity host) {
        if (!host.moduleCards || !host.tilesReady || promptsPaused || cardPrompt != null
                || !host.hasWindowFocus() || host.isFinishing() || host.reordering) return;
        if (host.cards.isEmpty() || !CardFeature.working()) return;
        CardFeature.run(() -> {
            showCardPromptNow(host);
            return null;
        }, null);
    }

    private void showCardPromptNow(MainActivity host) {
        final Cards.Card card = Cards.firstPending(host.cards);
        if (card == null) return;

        View panel = host.getLayoutInflater().inflate(R.layout.dialog_card_prompt, host.root, false);
        boolean rtl = host.getResources().getBoolean(R.bool.rtl_menu_bar);
        if (rtl) panel.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        ((TextView) panel.findViewById(R.id.title)).setText(host.getString(R.string.card_prompt_title, card.appLabel));
        ((TextView) panel.findViewById(R.id.body)).setText(card.state == Cards.State.RESIGNED
                ? host.getString(R.string.card_prompt_resigned, card.appLabel)
                : card.state == Cards.State.RELABELLED
                ? host.getString(R.string.card_prompt_relabelled, card.appLabel)
                : host.getString(R.string.card_prompt_body, card.appLabel));
        TextView names = panel.findViewById(R.id.names);
        if (Cards.fold(card.label).equals(Cards.fold(card.appLabel))) names.setVisibility(View.GONE);
        else names.setText(host.getString(R.string.card_prompt_names, card.label, card.appLabel));
        ((TextView) panel.findViewById(R.id.identity)).setText(
                host.getString(R.string.card_prompt_identity, card.pkg, Cards.shortDigest(card.digest)));
        TextView keep = panel.findViewById(R.id.btnKeep);
        TextView show = panel.findViewById(R.id.btnShow);
        if (rtl) {
            LinearLayout buttons = (LinearLayout) keep.getParent();
            buttons.removeAllViews();
            buttons.addView(show);
            buttons.addView(keep);
            keep.setNextFocusLeftId(R.id.btnShow);
            keep.setNextFocusRightId(R.id.btnKeep);
            show.setNextFocusLeftId(R.id.btnShow);
            show.setNextFocusRightId(R.id.btnKeep);
        }

        final Dialog dialog = new Dialog(host);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(panel);
        dialog.setCanceledOnTouchOutside(false);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout((int) (440 * host.density), ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setDimAmount(0.6f);
        }
        final boolean[] decided = {false};
        View.OnClickListener choose = v -> {
            if (decided[0]) return;
            decided[0] = true;
            cardPrompt = null;
            dialog.dismiss();
            decideCard(host, card, v.getId() == R.id.btnShow);
            host.restoreFocus();
        };
        keep.setOnClickListener(choose);
        show.setOnClickListener(choose);
        dialog.setOnCancelListener(d -> choose.onClick(keep));
        dialog.setOnDismissListener(d -> {
            if (cardPrompt == dialog) cardPrompt = null;
        });
        View.OnKeyListener keys = (v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) return true;
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT)
                return v.getId() == (rtl ? R.id.btnShow : R.id.btnKeep);
            return keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                    && v.getId() == (rtl ? R.id.btnKeep : R.id.btnShow);
        };
        keep.setOnKeyListener(keys);
        show.setOnKeyListener(keys);
        cardPrompt = dialog;
        dialog.show();
        keep.requestFocus();
    }

    private void decideCard(MainActivity host, Cards.Card card, boolean show) {
        boolean saved = CardFeature.run(() -> Cards.decide(Cards.storeFile(host), card, show), false);
        if (!saved) {
            Toast.makeText(host, R.string.card_not_saved, Toast.LENGTH_SHORT).show();
            promptsPaused = true;
        }
        host.reloadTiles();
    }

    void showModuleCards(MainActivity host) {
        if (host.cards.isEmpty() || !CardFeature.working()) {
            Toast.makeText(host, R.string.no_module_cards, Toast.LENGTH_SHORT).show();
            return;
        }
        CardFeature.run(() -> {
            showModuleCardsNow(host);
            return null;
        }, null);
    }

    private void showModuleCardsNow(MainActivity host) {
        View anchor = host.findViewById(R.id.btnSettings);
        LayoutInflater inflater = host.getLayoutInflater();
        View panel = inflater.inflate(R.layout.popup_cards, host.root, false);
        if (host.getResources().getBoolean(R.bool.rtl_menu_bar)) {
            panel.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        }
        LinearLayout rows = panel.findViewById(R.id.rows);
        final PopupWindow popup = HomeMenus.glassPopup(host, panel, (int) (400 * host.density));
        List<Cards.Card> listed = new ArrayList<>(host.cards);
        Collections.sort(listed, Cards::byLabel);
        for (Cards.Card card : listed) {
            View row = inflater.inflate(R.layout.item_card_row, rows, false);
            row.setTag(card.pkg);
            ((TextView) row.findViewById(R.id.title)).setText(card.label);
            if (host.getResources().getBoolean(R.bool.rtl_menu_bar)) {
                LinearLayout heading = (LinearLayout) row.findViewById(R.id.title).getParent();
                View status = row.findViewById(R.id.state);
                heading.removeView(status);
                heading.addView(status, 0);
            }
            TextView app = row.findViewById(R.id.app);
            if (Cards.fold(card.label).equals(Cards.fold(card.appLabel))) app.setVisibility(View.GONE);
            else app.setText(host.getString(R.string.card_app_name, card.appLabel));
            ((TextView) row.findViewById(R.id.pkg)).setText(card.pkg);
            ((TextView) row.findViewById(R.id.digest)).setText(Cards.longDigest(card.digest));
            final String pkg = card.pkg;
            row.setOnClickListener(v -> CardFeature.run(() -> {
                Cards.Card now = cardFor(host, pkg);
                if (now == null) return null;
                decideCard(host, now, now.state != Cards.State.SHOWN);
                updateCardStates(host, rows);
                return null;
            }, null));
            rows.addView(row);
        }
        updateCardStates(host, rows);
        panel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int[] at = new int[2];
        anchor.getLocationInWindow(at);
        int room = host.root.getHeight() - (at[1] + anchor.getHeight()) - (int) (34 * host.density);
        popup.setHeight(Math.min(panel.getMeasuredHeight(), Math.max(room, (int) (120 * host.density))));
        HomeMenus.dropIn(popup, panel, anchor,
                host.getResources().getBoolean(R.bool.rtl_menu_bar) ? Gravity.START : Gravity.END,
                host.density, host.animations, host.reducedMotion);
        rows.getChildAt(0).requestFocus();
    }

    private static Cards.Card cardFor(MainActivity host, String pkg) {
        for (Cards.Card c : host.cards) if (c.pkg.equals(pkg)) return c;
        return null;
    }

    private static void updateCardStates(MainActivity host, LinearLayout rows) {
        List<Cards.Card> drawn = Cards.shown(host.cards);
        for (int i = 0; i < rows.getChildCount(); i++) {
            View row = rows.getChildAt(i);
            Cards.Card card = row.getTag() instanceof String ? cardFor(host, (String) row.getTag()) : null;
            ((TextView) row.findViewById(R.id.state)).setText(
                    card != null ? stateText(host, card, drawn.contains(card)) : "");
        }
    }

    private static String stateText(MainActivity host, Cards.Card card, boolean drawn) {
        switch (card.state) {
            case SHOWN:
                return host.getString(drawn ? R.string.card_state_shown : R.string.card_state_over_limit);
            case HIDDEN:
                return host.getString(R.string.card_state_hidden);
            case RESIGNED:
                return host.getString(R.string.card_state_new_key);
            case RELABELLED:
                return host.getString(R.string.card_state_new_label);
            default:
                return host.getString(R.string.card_state_new);
        }
    }
}
