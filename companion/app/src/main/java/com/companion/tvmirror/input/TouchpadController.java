package com.companion.tvmirror.input;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import com.companion.tvmirror.data.AppSettings;

public class TouchpadController implements View.OnTouchListener, View.OnAttachStateChangeListener {

    public interface CommandSender {
        void send(String jsonCommand);
    }

    private final CommandSender sender;
    private final AppSettings settings;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Vibrator vibrator;

    private int activePointerId = MotionEvent.INVALID_POINTER_ID;
    private int secondPointerId = MotionEvent.INVALID_POINTER_ID;
    private float startX;
    private float startY;
    private float secondStartX;
    private float secondStartY;
    private long startTime;
    private boolean isLongPressed = false;
    private boolean isMultiTouch = false;
    private boolean twoFingerMoved = false;

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
    public void onViewAttachedToWindow(View v) {
    }

    @Override
    public void onViewDetachedFromWindow(View v) {
        handler.removeCallbacks(longPressRunnable);
        resetGestureState();
    }

    private void resetGestureState() {
        activePointerId = MotionEvent.INVALID_POINTER_ID;
        secondPointerId = MotionEvent.INVALID_POINTER_ID;
        isLongPressed = false;
        isMultiTouch = false;
        twoFingerMoved = false;
    }

    @Override
    public boolean onTouch(View v, MotionEvent event) {
        int action = event.getActionMasked();

        switch (action) {
            case MotionEvent.ACTION_DOWN:
                handler.removeCallbacks(longPressRunnable);
                activePointerId = event.getPointerId(0);
                secondPointerId = MotionEvent.INVALID_POINTER_ID;
                startX = event.getX(0);
                startY = event.getY(0);
                startTime = SystemClock.uptimeMillis();
                isLongPressed = false;
                isMultiTouch = false;
                twoFingerMoved = false;
                handler.postDelayed(longPressRunnable, longPressTimeout);
                return true;

            case MotionEvent.ACTION_POINTER_DOWN:
                handler.removeCallbacks(longPressRunnable);
                if (event.getPointerCount() == 2 && !isMultiTouch && !isLongPressed) {
                    isMultiTouch = true;
                    int pIdx = event.getActionIndex();
                    secondPointerId = event.getPointerId(pIdx);
                    secondStartX = event.getX(pIdx);
                    secondStartY = event.getY(pIdx);
                    twoFingerMoved = false;
                } else if (event.getPointerCount() > 2) {
                    isMultiTouch = true;
                    twoFingerMoved = true;
                }
                return true;

            case MotionEvent.ACTION_MOVE: {
                int activeIdx = event.findPointerIndex(activePointerId);
                if (activeIdx >= 0) {
                    float dxMove = Math.abs(event.getX(activeIdx) - startX);
                    float dyMove = Math.abs(event.getY(activeIdx) - startY);
                    if (dxMove > touchSlop || dyMove > touchSlop) {
                        handler.removeCallbacks(longPressRunnable);
                        if (isMultiTouch) {
                            twoFingerMoved = true;
                        }
                    }
                }
                if (isMultiTouch && secondPointerId != MotionEvent.INVALID_POINTER_ID) {
                    int secIdx = event.findPointerIndex(secondPointerId);
                    if (secIdx >= 0) {
                        float dxSec = Math.abs(event.getX(secIdx) - secondStartX);
                        float dySec = Math.abs(event.getY(secIdx) - secondStartY);
                        if (dxSec > touchSlop || dySec > touchSlop) {
                            twoFingerMoved = true;
                        }
                    }
                }
                return true;
            }

            case MotionEvent.ACTION_POINTER_UP: {
                int actionIdx = event.getActionIndex();
                int pointerId = event.getPointerId(actionIdx);
                if (isMultiTouch) {
                    if (pointerId == activePointerId) {
                        if (Math.abs(event.getX(actionIdx) - startX) > touchSlop
                                || Math.abs(event.getY(actionIdx) - startY) > touchSlop) {
                            twoFingerMoved = true;
                        }
                        int newIdx = (actionIdx == 0) ? 1 : 0;
                        if (newIdx < event.getPointerCount()) {
                            activePointerId = event.getPointerId(newIdx);
                            startX = (pointerId == secondPointerId) ? startX : secondStartX;
                            startY = (pointerId == secondPointerId) ? startY : secondStartY;
                        }
                    } else if (pointerId == secondPointerId) {
                        if (Math.abs(event.getX(actionIdx) - secondStartX) > touchSlop
                                || Math.abs(event.getY(actionIdx) - secondStartY) > touchSlop) {
                            twoFingerMoved = true;
                        }
                        secondPointerId = MotionEvent.INVALID_POINTER_ID;
                    }
                } else if (pointerId == activePointerId) {
                    int newIdx = (actionIdx == 0) ? 1 : 0;
                    if (newIdx < event.getPointerCount()) {
                        activePointerId = event.getPointerId(newIdx);
                        startX = event.getX(newIdx);
                        startY = event.getY(newIdx);
                    }
                }
                return true;
            }

            case MotionEvent.ACTION_UP:
                handler.removeCallbacks(longPressRunnable);
                if (isLongPressed) {
                    resetGestureState();
                    return true;
                }

                long duration = SystemClock.uptimeMillis() - startTime;
                int activeIdx = event.findPointerIndex(activePointerId);
                float endX = (activeIdx >= 0) ? event.getX(activeIdx) : event.getX();
                float endY = (activeIdx >= 0) ? event.getY(activeIdx) : event.getY();
                float dx = endX - startX;
                float dy = endY - startY;

                if (isMultiTouch) {
                    if (Math.abs(dx) > touchSlop || Math.abs(dy) > touchSlop) {
                        twoFingerMoved = true;
                    }
                    if (!twoFingerMoved && duration < 400) {
                        // Stationary short two-finger tap = Home
                        triggerHaptic();
                        sender.send(RemoteCommand.createKey(RemoteCommand.KEYCODE_HOME));
                    }
                    resetGestureState();
                    return true;
                }

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
                resetGestureState();
                return true;

            case MotionEvent.ACTION_CANCEL:
                handler.removeCallbacks(longPressRunnable);
                resetGestureState();
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
