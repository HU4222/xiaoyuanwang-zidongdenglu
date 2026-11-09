package com.campusnet.autologin.core;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 「推荐应用」扫描：列出本机装了哪些应用，交给 {@link AppRank} 打分排序。
 * <p>
 * 为什么需要它：LSPosed 里勾选作用域时，用户面对的是几百个包名，
 * 而「哪个是学校那个客户端」这件事应用自己就能查（PackageManager 里
 * 有应用名），没理由让用户去猜。扫出来点一下就直接填进目标包名。
 * <p>
 * 只列有启动图标的应用（通过 LAUNCHER intent 查询），既不需要
 * QUERY_ALL_PACKAGES 这种敏感权限，也天然排除了几百个系统组件。
 *
 * English: Enumerates installed launchable apps and ranks them via AppRank, so the
 * user picks an LSPosed scope from a short list instead of scrolling through
 * hundreds of package names.
 */
public final class AppScanner {

    /** LSPosed 官方管理器包名；旧版 EdXposed / 原版 Xposed 作为兜底。 */
    private static final String[] LSPOSED_PKGS = {
            "org.lsposed.manager",
            "org.meowcat.edxposed.manager",
            "de.robv.android.xposed.installer"
    };

    private AppScanner() {
    }

    /** 扫描并按推荐度排序（含 0 分应用，供「显示全部」用）。 */
    public static List<AppRank.Cand> scan(Context ctx) {
        List<AppRank.Cand> out = new ArrayList<>();
        if (ctx == null) return out;
        try {
            PackageManager pm = ctx.getPackageManager();
            Intent main = new Intent(Intent.ACTION_MAIN);
            main.addCategory(Intent.CATEGORY_LAUNCHER);

            List<ResolveInfo> ris = pm.queryIntentActivities(main, 0);
            if (ris == null) return out;

            String self = ctx.getPackageName();
            Set<String> seen = new HashSet<>();
            for (ResolveInfo ri : ris) {
                if (ri == null || ri.activityInfo == null) continue;
                String pkg = ri.activityInfo.packageName;
                if (pkg == null || pkg.isEmpty()) continue;
                if (pkg.equals(self)) continue;          // 别把自己推荐给自己
                if (!seen.add(pkg)) continue;            // 一个应用多个入口只留一条

                String label;
                try {
                    CharSequence cs = ri.loadLabel(pm);
                    label = cs == null ? pkg : cs.toString().trim();
                } catch (Throwable t) {
                    label = pkg;
                }
                out.add(new AppRank.Cand(pkg, label, AppRank.score(pkg, label)));
            }
        } catch (Throwable t) {
            Logx.w("扫描已安装应用失败: " + t.getMessage());
        }
        AppRank.sort(out);
        return out;
    }

    /** 设备上是否装了 LSPosed 管理器。 */
    public static boolean hasLsposed(Context ctx) {
        return lsposedPkg(ctx) != null;
    }

    private static String lsposedPkg(Context ctx) {
        if (ctx == null) return null;
        try {
            PackageManager pm = ctx.getPackageManager();
            for (String p : LSPOSED_PKGS) {
                try {
                    pm.getPackageInfo(p, 0);
                    return p;
                } catch (Throwable ignore) {
                    // 没装，试下一个
                }
            }
        } catch (Throwable ignore) {
        }
        return null;
    }

    /**
     * 直接打开 LSPosed 管理器。
     *
     * @return true 表示已成功发起跳转
     */
    public static boolean openLsposed(Context ctx) {
        String pkg = lsposedPkg(ctx);
        if (pkg == null) return false;
        try {
            Intent i = ctx.getPackageManager().getLaunchIntentForPackage(pkg);
            if (i == null) return false;
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
            return true;
        } catch (Throwable t) {
            Logx.w("打开 LSPosed 失败: " + t.getMessage());
            return false;
        }
    }
}
