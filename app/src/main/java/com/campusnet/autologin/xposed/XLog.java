package com.campusnet.autologin.xposed;

import android.util.Log;

/**
 * Xposed 侧的日志出口，统一加前缀方便 logcat 过滤。
 *
 * English: Log sink for the Xposed side, prefixed for easy logcat filtering.
 */
final class XLog {

    private static final String TAG = "CampusNet-Xposed";

    private XLog() {
    }

    static void i(String msg) {
        try {
            de.robv.android.xposed.XposedBridge.log("[CampusNet] " + msg);
        } catch (Throwable ignore) {
        }
        try {
            Log.i(TAG, msg);
        } catch (Throwable ignore) {
        }
    }

    static void e(String msg, Throwable t) {
        try {
            de.robv.android.xposed.XposedBridge.log("[CampusNet] " + msg
                    + (t == null ? "" : " | " + t));
        } catch (Throwable ignore) {
        }
        try {
            Log.e(TAG, msg, t);
        } catch (Throwable ignore) {
        }
    }
}
