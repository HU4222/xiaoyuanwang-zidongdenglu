package com.campusnet.autologin.xposed;

import android.app.Activity;

import com.campusnet.autologin.core.AppConfig;
import com.campusnet.autologin.util.Str;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * LSPosed 模块入口。
 * <p>
 * 作用范围严格受配置约束：只有 {@code xposedTarget} 里列出的包才被 hook，
 * 其余应用完全不受影响。命中后 hook {@code Activity.onResume}，
 * 在登录页出现时自动填账号密码并点登录。
 * <p>
 * 为什么 hook 官方客户端而不是直接发请求：部分学校的手机端客户端带私有协议
 * （如 Dr.COM 的加密通道），逆向成本高于收益；直接驱动它的 UI 更省事也更稳。
 *
 * English: LSPosed entry point. Only packages listed in xposedTarget are hooked.
 * Hooking Activity.onResume catches every login screen regardless of the UI
 * framework the vendor client uses.
 */
public class XposedEntry implements IXposedHookLoadPackage {

    static final String MODULE_PKG = "com.campusnet.autologin";
    static final String PREF_NAME = "campusnet_prefs";

    /** 配置缓存，最多 3 秒刷新一次，避免每次 onResume 都读盘。 */
    private static volatile AppConfig cached;
    private static volatile long cachedAt = 0L;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) {
        try {
            if (lp == null || Str.empty(lp.packageName)) return;
            if (MODULE_PKG.equals(lp.packageName)) return;

            AppConfig cfg = config();
            if (cfg == null) return;
            if (!cfg.hasCredentials()) return;
            if (!matches(cfg.xposedTarget, lp.packageName)) return;

            XLog.i("命中目标应用: " + lp.packageName + " process=" + lp.processName);
            hookActivity(lp.classLoader, cfg);
        } catch (Throwable t) {
            XLog.e("handleLoadPackage 异常", t);
        }
    }

    private static void hookActivity(ClassLoader cl, final AppConfig cfg) {
        try {
            XposedHelpers.findAndHookMethod(
                    "android.app.Activity", cl, "onResume", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Activity act = (Activity) param.thisObject;
                                AppConfig fresh = config();
                                if (fresh == null || !fresh.hasCredentials()) return;
                                AutoFiller.run(act, fresh.username, fresh.password,
                                        fresh.xposedAutoFill, fresh.xposedAutoClick);
                            } catch (Throwable t) {
                                XLog.e("onResume hook 内部异常", t);
                            }
                        }
                    });
            XLog.i("已挂上 Activity.onResume hook");
        } catch (Throwable t) {
            XLog.e("挂 hook 失败", t);
        }
    }

    private static AppConfig config() {
        long now = System.currentTimeMillis();
        AppConfig c = cached;
        if (c != null && now - cachedAt < 3000) return c;
        try {
            XSharedPreferences sp = new XSharedPreferences(MODULE_PKG, PREF_NAME);
            sp.reload();
            String json = sp.getString("config_json", "");
            if (Str.empty(json)) return cached;
            c = AppConfig.fromJson(json);
            cached = c;
            cachedAt = now;
            return c;
        } catch (Throwable t) {
            XLog.e("读取模块配置失败", t);
            return cached;
        }
    }

    /** 支持逗号分隔多包名，以及结尾 * 前缀匹配。 */
    static boolean matches(String targets, String pkg) {
        if (Str.empty(targets) || Str.empty(pkg)) return false;
        for (String raw : targets.split("[,，;；\\s]+")) {
            String t = raw.trim();
            if (t.isEmpty()) continue;
            if (t.equals(pkg)) return true;
            if (t.endsWith("*") && pkg.startsWith(t.substring(0, t.length() - 1))) return true;
        }
        return false;
    }
}
