package com.campusnet.autologin.auth;

import com.campusnet.autologin.core.AppConfig;
import com.campusnet.autologin.core.Diag;
import com.campusnet.autologin.core.Guard;
import com.campusnet.autologin.core.Http;
import com.campusnet.autologin.core.Logx;
import com.campusnet.autologin.core.Net;
import com.campusnet.autologin.core.PageStore;
import com.campusnet.autologin.core.PortalProbe;
import com.campusnet.autologin.util.Str;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 锐捷 ePortal 认证（复刻浏览器行为，不猜参数）。
 * <p>
 * 1.0.1 在这里栽过一次：直接把探测到的地址当登录接口 POST，网关回
 * {@code HTTP 203 | Error code: 203 Bad request(2)}。这个 203 不是"密码错"，
 * 是网关**看不懂这次请求**——它要求的 {@code queryString} 里必须带
 * {@code wlanuserip / wlanacname / nasip …} 这一整串会话参数，而探测只拿到了
 * 一个 {@code wlanacip}。缺了它，网关认不出该把这发请求算在谁头上。
 * <p>
 * 所以这里不再硬拼参数，而是按浏览器的顺序走一遍：
 * <ol>
 *   <li>从探测起点开始跟随跳转，记下每一跳；</li>
 *   <li>若最后一跳的 query 不完整，就去页面 HTML / JS 里挖真正的门户地址
 *       （{@code a79.htm} 这类"认证前拦截页"是靠脚本再跳一次的），
 *       再不行就主动敲门户首页，让 AC 用 302 把参数补齐；</li>
 *   <li>拿到完整 queryString 再提交，路径大小写两种都试（锐捷自身
 *       版本间 {@code ePortal} / {@code eportal} 混用）；</li>
 *   <li>判定以「Wi-Fi 出口通不通」为权威，网关文案只用来解释原因。</li>
 * </ol>
 *
 * English: Ruijie ePortal login by replaying the browser flow instead of guessing
 * parameters. The first version POSTed straight to the probed URL and got HTTP
 * 203: the gateway could not tell whose request it was, because the session query
 * string was incomplete. Superseded in practice by EportalWebAuth.
 */
public final class EportalAuth implements Authenticator {

    /** 登录接口候选：锐捷不同版本大小写不一致，两个都试一遍。 */
    private static final String[] LOGIN_PATHS = {
            "/ePortal/InterFace.do?method=login",
            "/eportal/InterFace.do?method=login",
    };

    /** 主动敲门户首页，让 AC 302 补全会话参数。 */
    private static final String[] PORTAL_SEEDS = {
            "/eportal/index.jsp",
            "/ePortal/index.jsp",
            "/eportal/",
    };

    private static final int MAX_HOP = 6;

    /** 提交后等待认证生效的探测节奏（毫秒）。 */
    private static final int[] WAIT_PLAN = {900, 1500, 2200};

    private static final Pattern URL_P = Pattern.compile(
            "https?://[^\\s\"'<>()\\\\,;]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern LOC_P = Pattern.compile(
            "location(?:\\.href)?\\s*(?:=|:)\\s*['\"]([^'\"]{4,400})['\"]", Pattern.CASE_INSENSITIVE);
    private static final Pattern REPLACE_P = Pattern.compile(
            "location\\.replace\\s*\\(\\s*['\"]([^'\"]{4,400})['\"]", Pattern.CASE_INSENSITIVE);

    @Override
    public String name() {
        return "Ruijie(ePortal)";
    }

    @Override
    public AuthResult login(AppConfig cfg) {
        if (!cfg.hasCredentials()) return AuthResult.fail("账号或密码为空");

        Diag.reset("锐捷 ePortal 登录 "
                + new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(new Date()));
        Diag.kv("出口", Net.outletLabel());
        Diag.kv("本机 Wi-Fi IP", Str.nz(Net.wifiIp(), "(取不到)"));

        // ---------- 1. 打开门户，跟随跳转 ----------
        Trail t = openPortal(cfg);
        if (t.online) return AuthResult.ok("已通过校园网认证，无需登录");
        if (Str.empty(t.base)) {
            return AuthResult.fail("找不到锐捷网关", Str.nz(t.error, "门户地址为空"));
        }
        // 打开失败时 base 可能是探测地址本身（connect.rom.miui.com 这类）。
        // 往那里提交账号密码等于把凭据发给第三方，必须拦住。
        if (!Guard.maySubmitCredentials(t.base, cfg.probeUrl)) {
            String why = Guard.rejectReason(t.base);
            Logx.w(why);
            Diag.kv("拒绝提交", why);
            return AuthResult.fail("找不到锐捷网关，已拒绝提交账号密码", why);
        }

        // ---------- 2. 拿到完整的 queryString ----------
        String query = resolveQuery(t, cfg);
        Diag.section("queryString");
        Diag.kv("值", Str.ellipsis(query, 400));
        Diag.kv("来源", t.querySource);
        if (Str.empty(query)) {
            return AuthResult.fail("无法获得门户会话参数（queryString）",
                    "网关既没在跳转里给，也没在页面里给");
        }

        // ---------- 3. 逐候选提交 ----------
        List<String> tried = new ArrayList<>();
        AuthResult lastFail = null;

        for (String path : LOGIN_PATHS) {
            String api = t.base + path;
            String custom = Str.nz(cfg.authUrl);
            // 用户/AutoAuth 可能塞进一个错误地址（比如 a79.htm），只在它确实是
            // InterFace 接口时才采信，否则一律用本类自己推导的地址。
            if (!Str.empty(custom) && custom.toLowerCase(Locale.US).contains("interface.do")) {
                api = custom;
            }

            Map<String, String> form = new LinkedHashMap<>();
            form.put("userId", cfg.username);
            form.put("password", cfg.password);
            form.put("service", "");
            form.put("queryString", query);
            form.put("operatorPwd", "");
            form.put("operatorUserId", "");
            form.put("validcode", "");
            form.put("passwordEncrypt", "false");
            form.putAll(FormAuth.parseKv(cfg.extraParams));

            Map<String, String> hdr = new LinkedHashMap<>();
            hdr.put("Referer", Str.nz(t.finalUrl, t.base + "/"));
            hdr.put("Origin", t.base);
            hdr.put("Accept", "application/json, text/javascript, */*; q=0.01");
            hdr.put("X-Requested-With", "XMLHttpRequest");

            String body = encodeForm(form);
            Diag.section("提交 " + path);
            Diag.kv("地址", api);
            Diag.kv("Referer", hdr.get("Referer"));
            Diag.kv("字段数", String.valueOf(form.size()));

            Logx.i("ePortal 提交 -> " + api + "  (queryString " + query.length() + " 字符)");
            Http.Result r = Http.postRaw(api, body,
                    "application/x-www-form-urlencoded; charset=UTF-8", hdr, cfg.timeoutMs);
            Logx.i("ePortal 响应: " + r + " | " + Str.ellipsis(r.body, 240));

            Diag.kv("响应", r.toString());
            Diag.body("响应正文", r.body);

            if (!r.ok()) {
                tried.add(path + " 连接失败: " + r.error);
                lastFail = AuthResult.fail("提交失败：" + r.error, r.error);
                continue;
            }

            String lower = Str.nz(r.body).toLowerCase(Locale.US);

            // 网关明确说成功
            if (lower.contains("\"result\":\"success\"") || lower.contains("\"result\": \"success\"")
                    || lower.contains("\"result\":\"ok\"")) {
                if (waitOnline(cfg)) return AuthResult.ok("锐捷 ePortal 登录成功");
                return AuthResult.ok("锐捷返回成功（外网探测暂不可达，稍后会自动复验）");
            }

            // 网关明确说失败 —— 这是真正的业务失败（密码错、欠费等），
            // 换路径没意义，直接返回原文。
            if (lower.contains("\"result\":\"fail\"") || lower.contains("\"result\": \"fail\"")) {
                String msg = SrunAuth.jsonValue(r.body, "message");
                if (Str.empty(msg)) msg = SrunAuth.jsonValue(r.body, "msg");
                return AuthResult.fail("锐捷认证失败：" + Str.nz(msg, "未返回原因"),
                        Str.ellipsis(r.body, 200));
            }

            // 203 / "Error code:" —— 网关没看懂请求，换下一个候选
            if (r.code == 203 || lower.contains("error code")) {
                String why = "HTTP " + r.code + "（网关拒绝了这次请求：参数格式不被识别）";
                tried.add(path + " -> " + why);
                Logx.w(path + " " + why + "，换下一个候选");
                lastFail = AuthResult.fail("锐捷网关不接受请求参数", why);
                continue;
            }

            // 没给出可读结果：以出口连通性为准
            if (waitOnline(cfg)) return AuthResult.ok("锐捷 ePortal 登录成功（按连通性确认）");

            tried.add(path + " -> HTTP " + r.code);
            lastFail = AuthResult.fail("登录后仍无法访问外网", Str.ellipsis(r.body, 200));
        }

        // 保存门户原文，供导出诊断时定位
        String saved = PageStore.save("eportal", t.html);
        if (!saved.isEmpty()) {
            Diag.kv("门户页存档", saved);
            Logx.i("门户页已存档: " + saved);
        } else {
            Diag.kv("门户页存档", "写入失败");
        }

        Diag.section("候选结果");
        for (String s : tried) Diag.kv("", s);
        Logx.w("ePortal 全部候选均未成功：" + tried);

        return lastFail != null ? lastFail
                : AuthResult.fail("锐捷 ePortal 登录未能确认成功", tried.toString());
    }

    // ==================================================================
    // 打开门户
    // ==================================================================

    private static final class Trail {
        boolean online;
        String error = "";
        String finalUrl = "";
        String base = "";
        String html = "";
        String query = "";
        String querySource = "";
        final List<String> hops = new ArrayList<>();
    }

    private Trail openPortal(AppConfig cfg) {
        Trail t = new Trail();
        String probe = Str.nz(cfg.probeUrl, "http://connect.rom.miui.com/generate_204");

        Map<String, String> hdr = new LinkedHashMap<>();
        hdr.put("Cache-Control", "no-cache");
        hdr.put("Pragma", "no-cache");

        String url = probe;
        Http.Result last = null;
        for (int hop = 0; hop < MAX_HOP; hop++) {
            Http.Result r = Http.get(url, hdr, Math.min(cfg.timeoutMs, 8000));
            t.hops.add(Str.ellipsis(url, 120) + "  ->  " + r);
            last = r;
            if (!r.ok()) {
                t.error = r.error;
                break;
            }
            if (r.isRedirect() && !Str.empty(r.location)) {
                String next = PortalProbe.absolute(url, r.location);
                if (next.equalsIgnoreCase(url)) break;
                url = next;
                continue;
            }
            break;
        }

        Diag.section("跳转链");
        for (String h : t.hops) Diag.kv("", h);

        if (last == null) {
            t.error = "探测失败：无法建立连接";
            return t;
        }
        if (last.code == 204 || (last.code == 200 && Str.nz(last.body).trim().isEmpty())) {
            t.online = true;
            return t;
        }

        t.finalUrl = url;
        t.base = Str.nz(FormAuth.originOf(url), url);
        t.html = Str.nz(last.body);
        t.query = queryOf(url);
        if (hasWlanUser(t.query)) t.querySource = "跳转落点 URL";

        Logx.i("门户落点: " + url + "  query=" + Str.ellipsis(t.query, 120));
        return t;
    }

    // ==================================================================
    // 解析 queryString
    // ==================================================================

    private String resolveQuery(Trail t, AppConfig cfg) {
        if (hasWlanUser(t.query)) return decodeOnce(t.query);

        // (a) 页面里挖门户地址：a79.htm 这类拦截页靠 JS 再跳一次，
        //     真正带 wlanuserip 的地址只在脚本里出现。
        for (String cand : extractUrls(t.html, t.finalUrl)) {
            String q = fetchForQuery(cand, cfg);
            if (hasWlanUser(q)) {
                t.querySource = "页面脚本中的地址: " + Str.ellipsis(cand, 90);
                t.finalUrl = cand;
                return decodeOnce(q);
            }
        }

        // (b) 主动敲门户首页，让 AC 用 302 补参数
        for (String p : PORTAL_SEEDS) {
            String cand = t.base + p;
            String q = fetchForQuery(cand, cfg);
            if (hasWlanUser(q)) {
                t.querySource = "门户首页 302 补齐: " + cand;
                return decodeOnce(q);
            }
        }

        // (c) 用本机地址自己拼一份。成功率先天低，但比直接放弃好，
        //     而且假如能通，日志里会记下这条路是有效的。
        String ip = Net.wifiIp();
        // 页面自己给的 wlanacip 比我们按门户主机名猜的准（真机上就出现过
        // 门户在 10.0.0.1、AC 却是 10.0.0.2 的情况），优先用它。
        String acip = paramOf(t.query, "wlanacip");
        String gw = Str.empty(acip) ? hostOf(t.base) : acip;
        if (!Str.empty(ip)) {
            StringBuilder sb = new StringBuilder();
            sb.append("wlanuserip=").append(ip);
            sb.append("&wlanacip=").append(gw);
            sb.append("&nasip=").append(gw);
            sb.append("&t=wireless-v2");
            sb.append("&url=").append(Str.urlEncode("http://www.baidu.com"));
            t.querySource = "本机地址拼装（推测，可能不被网关接受）";
            Logx.w("拿不到门户会话参数，改用本机地址拼装: " + sb);
            return sb.toString();
        }

        if (!Str.empty(t.query)) {
            t.querySource = "仅有的跳转参数（可能不完整）";
            return decodeOnce(t.query);
        }
        return "";
    }

    /** GET 一次候选地址（跟随重定向），返回其最终 URL 的 query。 */
    private String fetchForQuery(String url, AppConfig cfg) {
        String cur = url;
        for (int i = 0; i < 4; i++) {
            Http.Result r = Http.get(cur, null, Math.min(cfg.timeoutMs, 6000));
            Diag.kv("探参数", Str.ellipsis(cur, 100) + " -> " + r);
            if (!r.ok()) return "";
            if (r.isRedirect() && !Str.empty(r.location)) {
                cur = PortalProbe.absolute(cur, r.location);
                String q = queryOf(cur);
                if (hasWlanUser(q)) return q;
                continue;
            }
            String q = queryOf(cur);
            if (hasWlanUser(q)) return q;
            // 页面里可能还有一层跳转
            for (String c2 : extractUrls(Str.nz(r.body), cur)) {
                String q2 = queryOf(c2);
                if (hasWlanUser(q2)) return q2;
            }
            return "";
        }
        return "";
    }

    /** 从 HTML 里收集所有可能是门户地址的 URL，含带 query 的相对形式。 */
    private static Set<String> extractUrls(String html, String base) {
        Set<String> out = new LinkedHashSet<>();
        if (Str.empty(html)) return out;

        Matcher m = URL_P.matcher(html);
        while (m.find()) {
            String u = m.group();
            if (u.endsWith(";") || u.endsWith(")")) continue;
            out.add(u.replace("&amp;", "&"));
        }
        Matcher m2 = LOC_P.matcher(html);
        while (m2.find()) out.add(m2.group(1).replace("&amp;", "&"));
        Matcher m3 = REPLACE_P.matcher(html);
        while (m3.find()) out.add(m3.group(1).replace("&amp;", "&"));

        // 只保留真正可能带会话参数 / 指向门户的地址，按"含 wlanuserip"优先排序
        List<String> ranked = new ArrayList<>();
        List<String> rest = new ArrayList<>();
        for (String u : out) {
            String abs = PortalProbe.absolute(base, u);
            if (hasWlanUser(abs)) ranked.add(abs);
            else if (abs.toLowerCase(Locale.US).contains("eportal")
                    || abs.toLowerCase(Locale.US).contains("a79")
                    || abs.toLowerCase(Locale.US).contains("index.jsp")) rest.add(abs);
        }
        LinkedHashSet<String> ordered = new LinkedHashSet<>(ranked);
        ordered.addAll(rest);
        return ordered;
    }

    private static String queryOf(String url) {
        if (Str.empty(url)) return "";
        int i = url.indexOf('?');
        if (i < 0) return "";
        String q = url.substring(i + 1);
        int h = q.indexOf('#');
        return h < 0 ? q : q.substring(0, h);
    }

    private static boolean hasWlanUser(String q) {
        if (Str.empty(q)) return false;
        String l = q.toLowerCase(Locale.US);
        return l.contains("wlanuserip") || l.contains("wlanusrip");
    }

    /** 从原始 query 里取一个参数的值（已解码一次）；没有就返回空串。 */
    private static String paramOf(String q, String key) {
        if (Str.empty(q) || Str.empty(key)) return "";
        for (String kv : q.split("&")) {
            int j = kv.indexOf('=');
            if (j <= 0) continue;
            if (!key.equalsIgnoreCase(kv.substring(0, j))) continue;
            String v = kv.substring(j + 1);
            if (v.indexOf('%') >= 0) {
                try {
                    v = java.net.URLDecoder.decode(v, "UTF-8");
                } catch (Throwable ignore) {
                }
            }
            return v;
        }
        return "";
    }

    /**
     * 把 URL 里的 query 还原成「未编码」形态。
     * <p>
     * 提交时 {@link #encodeForm} 会再整体编码一次，正好对应浏览器里
     * {@code encodeURIComponent(queryString)} 的行为。之前只替换了
     * {@code %3D/%26}，遇到 {@code %2E} 之类就还原不干净，网关也读不出来。
     */
    static String decodeOnce(String s) {
        if (Str.empty(s)) return s;
        try {
            return java.net.URLDecoder.decode(s, "UTF-8");
        } catch (Throwable t) {
            return s;
        }
    }

    private static String hostOf(String url) {
        try {
            return new java.net.URL(url).getHost();
        } catch (Throwable t) {
            return "";
        }
    }

    // ==================================================================
    // 提交与判定
    // ==================================================================

    /** 按 x-www-form-urlencoded 拼 body；值一律整体编码。 */
    private static String encodeForm(Map<String, String> form) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : form.entrySet()) {
            if (sb.length() > 0) sb.append('&');
            sb.append(Str.urlEncode(e.getKey())).append('=').append(Str.urlEncode(e.getValue()));
        }
        return sb.toString();
    }

    /** 提交后等网关放行：出口通了才算数。 */
    private boolean waitOnline(AppConfig cfg) {
        for (int i = 0; i < WAIT_PLAN.length; i++) {
            try {
                Thread.sleep(WAIT_PLAN[i]);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            if (PortalProbe.isOnline(cfg)) {
                Diag.kv("提交后探测", "第 " + (i + 1) + " 次：已通");
                return true;
            }
            Diag.kv("提交后探测", "第 " + (i + 1) + " 次：仍不通");
        }
        return false;
    }
}
