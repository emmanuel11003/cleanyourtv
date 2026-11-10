package com.companion.tvmirror.input;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import com.companion.tvmirror.data.AppSettings;

public class TouchpadController implements View.OnTouchListener {

    public interface CommandSender {
        void send(String jsonCommand);
    }

    private final Context context;
    private final CommandSender sender;
    private final AppSettings settings;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Vibrator vibrator;

    private float startX;
    private float startY;
    private long startTime;
    private boolean isLongPressed = false;
    private boolean isMultiTouch = false;

    private float swipeThreshold;
    private final int touchSlop;
    private final int longPressTimeout;

    private final Runnable longPressRunnable = new Runnable() {
        @Override
        public void run() {
            isLongPressed = true;
            triggerHaptic();
            if (AppSettings.ACTION_MENU.equals(settings.getLongPressAction())) {
                sender.send(RemoteCommand.createKey(RemoteCommand.KEYCODE_MENU));
            } else {
                sender.send(RemoteCommand.createKey(RemoteCommand.KEYCODE_BACK));
            }
        }
    };

    public TouchpadController(Context context, CommandSender sender, AppSettings settings) {
        this.context = context;
        this.sender = sender;
        this.settings = settings;
        this.vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
        this.touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        this.longPressTimeout = ViewConfiguration.getLongPressTimeout();
        updateSensitivity();
    }

    public void updateSensitivity() {
        String sens = settings.getSensitivity();
        if (AppSettings.SENS_LOW.equals(sens)) {
            swipeThreshold = touchSlop * 3.5f;
        } else if (AppSettings.SENS_HIGH.equals(sens)) {
            swipeThreshold = touchSlop * 1.5f;
        } else {
            swipeThreshold = touchSlop * 2.2f;
        }
    }

    @Override
    public boolean onTouch(View v, MotionEvent event) {
        int action = event.getActionMasked();

        switch (action) {
            case MotionEvent.ACTION_DOWN:
                startX = event.getX();
                startY = event.getY();
                startTime = System.currentTimeMillis();
                isLongPressed = false;
                isMultiTouch = false;
                handler.postDelayed(longPressRunnable, longPressTimeout);
                return true;

            case MotionEvent.ACTION_POINTER_DOWN:
                if (event.getPointerCount() >= 2) {
                    isMultiTouch = true;
                    handler.removeCallbacks(longPressRunnable);
                }
                return true;

            case MotionEvent.ACTION_MOVE:
                float dxMove = Math.abs(event.getX() - startX);
                float dyMove = Math.abs(event.getY() - startY);
                if (dxMove > touchSlop || dyMove > touchSlop) {
                    handler.removeCallbacks(longPressRunnable);
                }
                return true;

            case MotionEvent.ACTION_UP:
                handler.removeCallbacks(longPressRunnable);
                if (isLongPressed) {
                    return true;
                }

                if (isMultiTouch) {
                    // Two-finger tap = Home
                    triggerHaptic();
                    sender.send(RemoteCommand.createKey(RemoteCommand.KEYCODE_HOME));
                    return true;
                }

                float endX = event.getX();
                float endY = event.getY();
                float dx = endX - startX;
                float dy = endY - startY;
                long duration = System.currentTimeMillis() - startTime;

                if (Math.abs(dx) < swipeThreshold && Math.abs(dy) < swipeThreshold && duration < 400) {
                    // Tap = OK / ENTER
                    triggerHaptic();
                    sender.send(RemoteCommand.createTap());
                } else if (Math.abs(dx) >= swipeThreshold || Math.abs(dy) >= swipeThreshold) {
                    // Swipe
                    triggerHaptic();
                    if (Math.abs(dx) > Math.abs(dy)) {
                        if (dx > 0) {
                            sender.send(RemoteCommand.createSwipe("right"));
                        } else {
                            sender.send(RemoteCommand.createSwipe("left"));
                        }
                    } else {
                        if (dy > 0) {
                            sender.send(RemoteCommand.createSwipe("down"));
                        } else {
                            sender.send(RemoteCommand.createSwipe("up"));
                        }
                    }
                }
                return true;

            case MotionEvent.ACTION_CANCEL:
                handler.removeCallbacks(longPressRunnable);
                return true;
        }

        return false;
    }

    private void triggerHaptic() {
        if (!settings.isHapticEnabled() || vibrator == null) {
            return;
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(20, VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                vibrator.vibrate(20);
            }
        } catch (Exception ignored) {
        }
    }
}
