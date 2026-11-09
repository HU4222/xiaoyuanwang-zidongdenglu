package com.campusnet.autologin.core;

import com.campusnet.autologin.util.Str;

import java.util.ArrayList;
import java.util.List;

/**
 * 最近一次登录尝试的完整留证。
 * <p>
 * 存在的理由很实际：出问题时用户往往拿不到 logcat，也说不清"到底哪一步不对"，
 * 而真正需要看的是**那一发请求和那一发响应**——提交到哪个地址、带了哪些字段、
 * 服务端回了什么。把这些原样留在内存里，用户截图或导出一次就够了。
 * <p>
 * 只保留最近一次，避免守护模式下无限增长。
 *
 * English: Keeps the last login attempt verbatim — target URL, submitted fields
 * and raw response — because users rarely have logcat when something breaks.
 */
public final class Diag {

    private static final List<String> LINES = new ArrayList<>();
    private static final int CAP = 600;
    private static final int BODY_MAX = 3000;

    private Diag() {
    }

    public static synchronized void reset(String title) {
        LINES.clear();
        if (!Str.empty(title)) LINES.add("== " + title + " ==");
    }

    public static synchronized void add(String line) {
        if (line == null) return;
        // 与 Logx 共用同一个脱敏出口：导出诊断是要发给别人的，
        // 里面绝不能出现明文密码。
        LINES.add(Sanitize.mask(line));
        while (LINES.size() > CAP) LINES.remove(0);
    }

    public static synchronized void kv(String key, String value) {
        add("  " + key + ": " + (value == null ? "" : value));
    }

    public static synchronized void section(String title) {
        add("-- " + title + " --");
    }

    public static synchronized void body(String title, String body) {
        add("  [" + title + "]");
        if (Str.empty(body)) {
            add("    (空)");
            return;
        }
        String s = body.replaceAll("\\r", "");
        if (s.length() > BODY_MAX) s = s.substring(0, BODY_MAX) + "\n    …(共 " + body.length() + " 字符，已截断)";
        for (String line : s.split("\n")) add("    " + line);
    }

    /** 密码等敏感值按长度打码，只留长度信息以便判断"是不是空串/编码问题"。 */
    public static String mask(String secret) {
        if (secret == null) return "";
        int n = secret.length();
        if (n == 0) return "(空)";
        return "******(" + n + "位)";
    }

    public static synchronized boolean isEmpty() {
        return LINES.isEmpty();
    }

    public static synchronized String dump() {
        StringBuilder sb = new StringBuilder();
        for (String l : LINES) sb.append(l).append('\n');
        return sb.toString();
    }
}
