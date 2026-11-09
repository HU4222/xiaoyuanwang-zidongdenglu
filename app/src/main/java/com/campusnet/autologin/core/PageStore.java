package com.campusnet.autologin.core;

import android.content.Context;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 把门户页原始 HTML 落盘，供离线分析。
 * <p>
 * 存在的理由：锐捷新版门户是完全由 JS 渲染的，服务端返回的 HTML 里没有
 * {@code <form>}，所以"字段名"和"接口地址"都藏在脚本里。光看界面上的
 * 探测摘要根本判断不了该往哪个地址提交什么参数——必须看原页面。
 * <p>
 * 落在 {@code Android/data/<包名>/files/diag/} 下，这个路径不需要 root、
 * 也不需要把应用编成 debuggable，插上 USB 用 {@code adb pull} 就能取走。
 *
 * English: Dumps portal HTML to `Android/data/<pkg>/files/diag/`, reachable with
 * `adb pull` without root or a debuggable build. Needed because the ePortal login
 * page ships no `<form>`; its field names live inside the page scripts.
 */
public final class PageStore {

    private static final int MAX = 512 * 1024;

    private PageStore() {
    }

    /** 保存页面，返回落盘路径；保存失败返回空串。 */
    public static String save(String tag, String content) {
        if (content == null || content.isEmpty()) return "";
        Context ctx = Net.app();
        if (ctx == null) {
            Logx.w("门户页存档失败: 应用上下文为空");
            return "";
        }
        // 优先外部私有目录（adb pull 方便），拿不到就退到内部存储——
        // 1.0.1 只试了前者，遇到外置存储不可用就静默失败，日志里连个痕迹都没有。
        File dir = null;
        try {
            File base = ctx.getExternalFilesDir(null);
            if (base != null) {
                File d = new File(base, "diag");
                if (d.exists() || d.mkdirs()) dir = d;
            }
        } catch (Throwable t) {
            Logx.w("外部存档目录不可用: " + t.getMessage());
        }
        if (dir == null) {
            File d = new File(ctx.getFilesDir(), "diag");
            if (d.exists() || d.mkdirs()) dir = d;
        }
        if (dir == null) {
            Logx.w("门户页存档失败: 没有可写目录");
            return "";
        }

        try {
            String stamp = new SimpleDateFormat("MMdd-HHmmss", Locale.US).format(new Date());
            File f = new File(dir, tag + "-" + stamp + ".html");
            FileWriter w = new FileWriter(f);
            try {
                w.write(content.length() > MAX ? content.substring(0, MAX) : content);
            } finally {
                w.close();
            }
            // 只留最近 8 份，避免长期守护把存储占满
            prune(dir);
            return f.getAbsolutePath();
        } catch (Throwable t) {
            Logx.w("门户页存档失败: " + t);
            return "";
        }
    }

    private static void prune(File dir) {
        try {
            File[] all = dir.listFiles();
            if (all == null || all.length <= 8) return;
            java.util.Arrays.sort(all, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
            for (int i = 0; i < all.length - 8; i++) {
                //noinspection ResultOfMethodCallIgnored
                all[i].delete();
            }
        } catch (Throwable ignore) {
        }
    }
}
