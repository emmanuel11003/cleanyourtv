package com.gathin.tvhome;

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Shared single-thread background executor and main-thread handler for launcher I/O
 * (Config loading, wallpaper decoding, and file checks) to avoid thread proliferation
 * on 2 GB RAM / Cortex-A55 TVs.
 */
final class LauncherExecutors {
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "TvHome-IO");
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private LauncherExecutors() {
    }

    static ExecutorService io() {
        return IO;
    }

    static Handler main() {
        return MAIN;
    }
}
