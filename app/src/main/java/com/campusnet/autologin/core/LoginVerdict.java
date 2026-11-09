package com.campusnet.autologin.core;

import com.campusnet.autologin.util.Str;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 登录结果的最终判定。
 * <p>
 * 判定顺序是本类的全部要点，不能随意调换：
 * <ol>
 *   <li><b>先看 Wi-Fi 出口是否通</b>。这是唯一权威信号——认证的目的就是让 Wi-Fi
 *       能出外网，通了就是成功，页面写什么都无关。</li>
 *   <li>不通，才去读服务端文案，而且只用来<b>解释原因</b>，不作为唯一判据。</li>
 * </ol>
 * 反过来做（先搜关键词）就会出现这次的问题：响应里只要出现 {@code error}
 * 这个子串——{@code onerror=}、{@code function showError(}、{@code errorPage}
 * 这类随处可见的写法——就被判成失败，即使实际已经登录成功。
 * <p>
 * 所以失败文案只认<b>明确的中文错误短语</b>，不认裸的 error / fail / invalid。
 * <p>
 * 与 {@link Judge} 一样做成零 Android 依赖的纯函数：这个判定错一次，
 * 用户看到的就是"明明能用却报失败"，而真机上很难稳定复现，
 * 只能在桌面 JVM 上拿构造好的响应穷举。
 *
 * English: Final verdict on a login attempt. Wi-Fi egress is the authoritative
 * signal; server text only explains a failure. Keyword matching is restricted to
 * explicit Chinese error phrases, because a bare `error` also matches `onerror=`,
 * `showError(` and `errorPage` — which once reported success as failure.
 */
public final class LoginVerdict {

    public static final int SUCCESS = 0;
    public static final int FAIL = 1;
    public static final int UNKNOWN = 2;

    public static final class Verdict {
        public int kind = UNKNOWN;
        /** 给人看的一句话原因 */
        public String reason = "";
        /** 命中的原文片段，用于截图定位 */
        public String evidence = "";
        /** Wi-Fi 出口当时是否已通 */
        public boolean online;
        /** 原因是从关键词猜的，还是服务端直接说的 */
        public boolean fromKeyword;

        public boolean ok() {
            return kind == SUCCESS;
        }

        @Override
        public String toString() {
            return "kind=" + kind + " online=" + online + " reason=" + reason;
        }
    }

    /**
     * 明确表示失败的文案。
     * <p>
     * 刻意不含 {@code error} / {@code fail} / {@code invalid} / {@code 错误}：
     * 这些在 HTML、JS、CSS 类名里遍地都是，拿来当判据必然误伤。
     */
    private static final String[] FAIL_PHRASES = {
            "密码错误", "密码不正确", "密码有误", "密码无效",
            "账号或密码", "帐号或密码", "用户名或密码", "账号密码",
            "认证失败", "登录失败", "认证不成功", "登录不成功",
            "认证被拒绝", "拒绝认证", "不允许上网", "禁止上网",
            "用户不存在", "账号不存在", "帐号不存在", "用户信息有误",
            "欠费", "已停机", "账号已停用", "账户已停用", "账号已注销",
            "已被锁定", "账号被锁", "账户被锁",
            "不在线用户", "已达最大用户数", "超过最大在线数", "在线数已满",
            "invalid password", "wrong password", "authentication failed",
            "login failed", "user not found", "account locked", "access denied"
    };

    private static final String[] OK_PHRASES = {
            "登录成功", "认证成功", "成功登录", "已通过认证", "认证通过",
            "已登录", "logout", "成功"
    };

    /** 服务端说"你已经在线了"。这不一定是失败，取决于出口通不通。 */
    private static final String[] ALREADY_PHRASES = {
            "已在线", "已经在线", "在线中", "重复登录", "already online", "already logged in"
    };

    /** 常见承载服务端文案的 JSON 键，按可信度排序 */
    private static final String[] MSG_KEYS = {
            "msg", "message", "errmsg", "err_msg", "errmsgstr", "error",
            "reason", "info", "retmsg", "ret_msg", "tips", "tip", "result", "res", "desc"
    };

    private static final Pattern JSON_STR = Pattern.compile(
            "\"([A-Za-z_][A-Za-z0-9_]*)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.){0,300})\"");

    /** {@code Error code: 203 Bad request(2)} 这类网关错误页 */
    private static final Pattern HTTP_ERR_P = Pattern.compile(
            "error\\s*code\\s*[:：]?\\s*(\\d{3})", Pattern.CASE_INSENSITIVE);

    private static final int SCAN_LIMIT = 64 * 1024;

    private LoginVerdict() {
    }

    /**
     * @param body         服务端响应正文，可为 null
     * @param onlineOnWifi 提交后从 Wi-Fi 出口探测外网是否已通
     */
    public static Verdict evaluate(String body, boolean onlineOnWifi) {
        Verdict v = new Verdict();
        v.online = onlineOnWifi;

        // 1) 权威信号
        if (onlineOnWifi) {
            v.kind = SUCCESS;
            String srv = serverMessage(body);
            v.reason = "已通过校园网认证（Wi-Fi 出口可达外网）";
            if (!srv.isEmpty()) v.evidence = Str.ellipsis(srv, 120);
            return v;
        }

        String text = scanText(body);

        // 1.5) HTTP 层错误页：锐捷 AC 会回 <html><body> Error code: 203 Bad request(2)。
        //      这种页面没有中文、也不是 JSON，早先会一路滑到「未确定」，
        //      再被上层拼成一句看不懂的「服务端返回失败文案：error」。
        //      203 的真实含义是「网关看不懂这次请求」，跟密码对不对无关。
        Matcher em = HTTP_ERR_P.matcher(text);
        if (em.find()) {
            v.kind = FAIL;
            v.fromKeyword = true;
            v.reason = "网关拒绝了登录请求（HTTP " + em.group(1) + "），提交地址或参数不被接受";
            v.evidence = Str.ellipsis(squeeze(text), 160);
            return v;
        }

        // 2) 服务端自己说了什么，优先采信它
        String srv = serverMessage(text);
        if (!srv.isEmpty()) {
            String lower = srv.toLowerCase(Locale.US);
            if (hits(lower, FAIL_PHRASES) || hits(srv, FAIL_PHRASES)) {
                v.kind = FAIL;
                v.reason = srv;
                v.evidence = Str.ellipsis(srv, 160);
                return v;
            }
            if (hits(lower, ALREADY_PHRASES) || hits(srv, ALREADY_PHRASES)) {
                v.kind = UNKNOWN;
                v.reason = "服务端提示账号已在线，但 Wi-Fi 出口仍不通";
                v.evidence = Str.ellipsis(srv, 160);
                return v;
            }
            if (hits(lower, OK_PHRASES) || hits(srv, OK_PHRASES)) {
                v.kind = SUCCESS;
                v.reason = "服务端提示成功，外网可能还没生效";
                v.evidence = Str.ellipsis(srv, 160);
                return v;
            }
        }

        // 3) 页面里明确的失败短语
        String hitFail = firstHit(text, FAIL_PHRASES);
        if (hitFail != null) {
            v.kind = FAIL;
            v.fromKeyword = true;
            v.reason = "服务端返回：" + hitFail;
            v.evidence = context(text, hitFail);
            return v;
        }

        // 4) 页面里明确的成功短语，但出口不通 → 不要判死，如实说明
        String hitOk = firstHit(text, OK_PHRASES);
        if (hitOk != null) {
            v.kind = SUCCESS;
            v.fromKeyword = true;
            v.reason = "服务端提示成功，外网可能还没生效";
            v.evidence = context(text, hitOk);
            return v;
        }

        // 5) 什么都没认出来
        v.kind = UNKNOWN;
        v.reason = "已提交，但 Wi-Fi 出口仍不通，服务端也没给出可读原因";
        if (!srv.isEmpty()) {
            v.reason = srv;
            v.evidence = Str.ellipsis(srv, 160);
        } else {
            v.evidence = excerpt(text, 120);
        }
        return v;
    }

    // ------------------------------------------------------------------

    /** 只截前 64KB 并压掉空白，够用且避免大页面拖慢判定。 */
    static String scanText(String body) {
        if (body == null) return "";
        String s = body.length() > SCAN_LIMIT ? body.substring(0, SCAN_LIMIT) : body;
        return s;
    }

    /**
     * 从 JSON 风格的响应里取服务端文案。
     * <p>
     * 不用 org.json：它在桌面 JVM 上不可用，而这段逻辑必须能在桌面跑测试。
     * 只用正则匹配 {@code "key":"value"} 已经够覆盖各类 portal 的返回格式。
     */
    static String serverMessage(String body) {
        String text = scanText(body);
        if (text.isEmpty() || text.indexOf('{') < 0) return "";

        String best = "";
        int bestRank = Integer.MAX_VALUE;

        Matcher m = JSON_STR.matcher(text);
        while (m.find()) {
            String key = m.group(1).toLowerCase(Locale.US);
            String val = unescape(m.group(2)).trim();
            if (val.isEmpty() || !looksHuman(val)) continue;

            int rank = indexOf(MSG_KEYS, key);
            if (rank < 0) {
                // 键名不认识，但值含中文的话也可能就是提示语，排在最后
                if (!hasCjk(val)) continue;
                rank = MSG_KEYS.length;
            }
            if (rank < bestRank) {
                bestRank = rank;
                best = val;
            }
        }
        return best;
    }

    private static boolean looksHuman(String v) {
        if (v.length() < 2 || v.length() > 200) return false;
        String lower = v.toLowerCase(Locale.US);
        if (lower.equals("ok") || lower.equals("success") || lower.equals("null")
                || lower.equals("true") || lower.equals("false")) {
            // ok / success 也是有效信息，但交给短语命中处理，不当成"文案"
            return false;
        }
        return hasCjk(v) || v.indexOf(' ') > 0;
    }

    private static boolean hasCjk(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0x4E00 && c <= 0x9FFF) return true;
        }
        return false;
    }

    private static String unescape(String s) {
        if (s.indexOf('\\') < 0) return s;
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\' || i + 1 >= s.length()) {
                sb.append(c);
                continue;
            }
            char n = s.charAt(++i);
            switch (n) {
                case 'n':
                case 'r':
                case 't':
                    sb.append(' ');
                    break;
                case 'u':
                    if (i + 4 < s.length()) {
                        try {
                            sb.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
                            i += 4;
                        } catch (Throwable ignore) {
                            sb.append(n);
                        }
                    }
                    break;
                default:
                    sb.append(n);
            }
        }
        return sb.toString();
    }

    private static int indexOf(String[] arr, String key) {
        for (int i = 0; i < arr.length; i++) {
            if (arr[i].equals(key)) return i;
        }
        return -1;
    }

    private static boolean hits(String haystack, String[] needles) {
        return firstHit(haystack, needles) != null;
    }

    private static String firstHit(String haystack, String[] needles) {
        String lower = haystack.toLowerCase(Locale.US);
        for (String n : needles) {
            if (n == null || n.isEmpty()) continue;
            if (lower.contains(n.toLowerCase(Locale.US))) return n;
        }
        return null;
    }

    /** 命中位置前后的原文，压掉换行，便于一眼看懂为什么判成失败。 */
    static String context(String text, String needle) {
        String lower = text.toLowerCase(Locale.US);
        int i = lower.indexOf(needle.toLowerCase(Locale.US));
        if (i < 0) return "";
        int from = Math.max(0, i - 60);
        int to = Math.min(text.length(), i + needle.length() + 90);
        return squeeze(text.substring(from, to));
    }

    static String excerpt(String text, int max) {
        return Str.ellipsis(squeeze(text), max);
    }

    private static String squeeze(String s) {
        if (s == null) return "";
        return s.replaceAll("\\s+", " ").trim();
    }
}
