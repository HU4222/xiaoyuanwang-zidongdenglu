package com.campusnet.autologin.engine;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.campusnet.autologin.core.AppConfig;
import com.campusnet.autologin.core.Logx;

/**
 * 开机 / 应用更新 / 看门狗唤醒的统一入口。
 * <p>
 * Android 12+ 对「从后台启动前台服务」有限制，这里全部包在 try/catch 里，
 * 失败也不崩，下一次闹钟或网络回调会再试。
 *
 * English: Single entry point for boot, package update and watchdog wake-ups.
 * Every startForegroundService call is wrapped in try/catch: Android 12+ forbids
 * starting a foreground service from the background, and the next alarm or
 * network callback retries.
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? "" : String.valueOf(intent.getAction());
        Logx.init(context);
        Logx.i("收到广播: " + action);

        boolean shouldStart;
        switch (action) {
            case Intent.ACTION_BOOT_COMPLETED:
            case Intent.ACTION_MY_PACKAGE_REPLACED:
            case Intent.ACTION_USER_PRESENT:
            case WatchdogService.ACTION_RESTART:
                shouldStart = true;
                break;
            case "android.net.conn.CONNECTIVITY_CHANGE":
                // 只在守护已启用时响应，避免无谓唤醒
                shouldStart = AppConfig.load(context).enabled;
                break;
            default:
                shouldStart = AppConfig.load(context).enabled;
                break;
        }

        if (!shouldStart) return;

        AppConfig cfg = AppConfig.load(context);
        if (!cfg.autoStartOnBoot && WatchdogService.ACTION_RESTART.equals(action)) {
            // 重启闹钟不受「开机自启」开关限制，否则会陷入永久停止
            shouldStart = true;
        }
        if (!shouldStart) return;

        WatchdogService.start(context);
    }
}
