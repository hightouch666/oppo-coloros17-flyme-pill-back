package com.laotian.pillback;

import android.util.Log;

import de.robv.android.xposed.XposedBridge;

/**
 * 统一日志出口：同时进 logcat（tag=PillBack）和 LSPosed 日志，方便 adb 抓。
 */
public final class L {

    public static final String TAG = "PillBack";

    private L() {}

    public static void i(String msg) {
        Log.i(TAG, msg);
        safeXposed("[PillBack] " + msg);
    }

    public static void w(String msg) {
        Log.w(TAG, msg);
        safeXposed("[PillBack] W " + msg);
    }

    public static void e(String msg, Throwable t) {
        Log.e(TAG, msg, t);
        safeXposed("[PillBack] E " + msg + " :: " + t);
    }

    private static void safeXposed(String s) {
        try {
            XposedBridge.log(s);
        } catch (Throwable ignored) {
            // App 进程里没有 Xposed，忽略
        }
    }
}
