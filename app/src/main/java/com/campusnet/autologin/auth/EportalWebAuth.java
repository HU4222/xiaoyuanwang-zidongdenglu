package com.campusnet.autologin.auth;

import com.campusnet.autologin.core.AppConfig;
import com.campusnet.autologin.core.Diag;
import com.campusnet.autologin.core.EportalQuery;
import com.campusnet.autologin.core.Guard;
import com.campusnet.autologin.core.Http;
import com.campusnet.autologin.core.Jsonp;
import com.campusnet.autologin.core.Logx;
import com.campusnet.autologin.core.Net;
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
 * 锐捷 ePortal **网页版登录接口**直连。
 *
 * <p>这个类来自一次真机抓包。之前的 {@link EportalAuth} 一直在猜
 * {@code /ePortal/InterFace.do?method=login}，被网关回
 * {@code HTTP 203 Bad request(2)}；而学校门户页真正发出去的请求是完全另一条路：
 *
 * <pre>
 * GET http://10.0.0.1:801/eportal/portal/login
 *     ?callback=dr1003
 *     &amp;login_method=1
 *     &amp;user_account=%2C1%2C20230000000
 *     &amp;user_password=***
 *     &amp;wlan_user_ip=10.0.0.123
 *     &amp;wlan_user_ipv6=
 *     &amp;wlan_user_mac=000000000000
 *     &amp;wlan_ac_ip=10.0.0.2
 *     &amp;wlan_ac_name=
 *     &amp;jsVersion=4.1.3
 *     &amp;terminal_type=2
 *     &amp;lang=zh-cn
 *     &amp;v=10420
 * </pre>
 *
 * <p>三个关键差异，也是 1.0.1–1.0.3 一直 203 的原因：
 * <ul>
 *   <li><b>端口 801</b>，不是 80；</li>
 *   <li><b>路径 {@code /eportal/portal/login}</b>，不是 {@code InterFace.do}；</li>
 *   <li><b>GET + JSONP</b>，参数平铺在 query 里，没有 {@code queryString} 这一层。</li>
 * </ul>
 *
 * <p>{@code user_account} 的值是 {@code ,1,账号}——那个 {@code 1} 是业务类型前缀，
 * 不能省。所有值都取本机实时数据（Wi-Fi 出口 IP、页面给的 AC 地址），不写死。
 *
 * English: Direct call to ePortal's web login endpoint, reproduced from one real
 * captured request (all addresses and accounts below are placeholders). Port 801,
 * path `/eportal/portal/login`, GET with flat JSONP parameters — not the widely
 * documented `InterFace.do`.
 */
public final class EportalWebAuth implements Authenticator {

    /** 网页版登录接口路径；大小写在锐捷各版本间混用，都试。 */
    private static final String[] LOGIN_PATHS = {
            "/eportal/portal/login",
            "/ePortal/portal/login",
    };

    /** 端口优先取页面里出现过的，取不到时按经验值兜底。 */
    private static final String[] FALLBACK_PORTS = {"801", ""};

    private static final String DEFAULT_JS_VERSION = "4.1.3";

    private static final int[] WAIT_PLAN = {700, 1200, 2000};

    private static final Pattern JS_VERSION_P = Pattern.compile(
            "jsVersion[\"']?\\s*[=:]\\s*[\"']?([0-9]+\\.[0-9.]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern PORT_P = Pattern.compile(
            "https?://[0-9.]+:(\\d{2,5})/eportal", Pattern.CASE_INSENSITIVE);

    @Override
    public String name() {
        return "Ruijie(网页接口)";
    }

    @Override
    public AuthResult login(AppConfig cfg) {
        if (!cfg.hasCredentials()) return AuthResult.fail("账号或密码为空");

        Diag.reset("锐捷网页接口登录 "
                + new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(new Date()));
        Diag.kv("出口", Net.outletLabel());

        // ---------- 1. 打开门户，把页面里能读到的会话信息挖出来 ----------
        Page page = openPortal(cfg);
        if (page.online) return AuthResult.ok("已通过校园网认证，无需登录");
        if (Str.empty(page.host)) return AuthResult.fail("找不到锐捷门户主机", page.error);
        // 门户没打开时 host 可能还是探测地址（connect.rom.miui.com 之类）。
        // 往那儿拼 login 接口等于把账号密码送去第三方，必须拦。
        if (!Guard.maySubmitCredentials("http://" + page.host, cfg.probeUrl)) {
            String why = Guard.rejectReason(page.finalUrl);
            Logx.w(why);
            Diag.kv("拒绝提交", why);
            return AuthResult.fail("没有找到锐捷门户主机，已拒绝提交账号密码", why);
        }

        String userIp = Net.wifiIp();
        if (Str.empty(userIp)) {
            return AuthResult.fail("取不到本机 Wi-Fi IP", "未连接 Wi-Fi 或未分配到地址");
        }
        // 门户给的 wlanacip 是 AC 地址，和门户主机不一定是同一台
        // （真机上是门户 10.0.0.1 / AC 10.0.0.2），必须用它给的值。
        String acIp = Str.nz(paramOf(page.query, "wlanacip"), page.host);
        String jsVersion = Str.nz(page.jsVersion, DEFAULT_JS_VERSION);

        Diag.section("会话参数");
        Diag.kv("门户主机", page.host);
        Diag.kv("本机 Wi-Fi IP", userIp);
        Diag.kv("AC 地址（wlanacip）", acIp);
        Diag.kv("jsVersion", jsVersion);
        Diag.kv("候选端口", String.valueOf(page.ports));

        // ---------- 2. 逐端口 × 逐路径提交 ----------
        List<String> tried = new ArrayList<>();
        AuthResult lastFail = null;

        for (String port : page.ports) {
            String origin = "http://" + page.host + (Str.empty(port) ? "" : ":" + port);
            for (String path : LOGIN_PATHS) {
                // query 的构造放在 EportalQuery 里，那里有一份"和真机抓包逐字节比对"
                // 的单测守着：参数顺序、%2C 编码、重复的 lang 都必须和浏览器一致。
                String query = EportalQuery.build(cfg.username, cfg.password, userIp,
                        acIp, jsVersion, "dr1003", EportalQuery.randomV());
                String url = origin + path + "?" + query;

                // 日志里绝不放明文密码：Logx/Diag 有统一脱敏兜底，这里再显式说明一次
                Logx.i("锐捷网页接口 GET -> " + origin + path
                        + "  (账号=" + tail4(cfg.username) + " 本机IP=" + userIp + " AC=" + acIp + ")");

                Http.Result r = Http.get(url, headers(page.finalUrl), cfg.timeoutMs);
                String brief = Str.ellipsis(unwrapJsonp(r.body), 200);
                Logx.i("锐捷网页接口响应: " + r + " | " + brief);

                Diag.section("提交 " + path + (Str.empty(port) ? "" : (":" + port)));
                Diag.kv("地址", origin + path);
                Diag.kv("响应", r.toString());
                Diag.body("响应正文", unwrapJsonp(r.body));

                if (!r.ok()) {
                    tried.add(origin + path + " 连接失败: " + r.error);
                    lastFail = AuthResult.fail("提交失败：" + r.error, r.error);
                    continue;
                }

                String json = unwrapJsonp(r.body);
                String result = Str.nz(SrunAuth.jsonValue(json, "result")).toLowerCase(Locale.US);
                String msg = SrunAuth.jsonValue(json, "msg");
                if (Str.empty(msg)) msg = SrunAuth.jsonValue(json, "message");

                // 网关明确说成功
                if ("success".equals(result) || "ok".equals(result)) {
                    if (waitOnline(cfg)) return AuthResult.ok("锐捷 ePortal 登录成功");
                    return AuthResult.ok("锐捷返回成功（外网探测暂不可达，稍后会自动复验）", brief);
                }

                // 已经在线：对守护场景来说这就是成功
                if (containsOnlineHint(msg) || containsOnlineHint(json)) {
                    return AuthResult.ok("网关提示已在线，视为登录成功", brief);
                }

                // 明确的业务失败（密码错、欠费、账号停用）——换路径没意义
                if ("fail".equals(result)) {
                    return AuthResult.fail("锐捷认证失败：" + Str.nz(msg, "未返回原因"), brief);
                }

                // 没给出可读结果，交给连通性判定
                if (waitOnline(cfg)) return AuthResult.ok("锐捷 ePortal 登录成功（按连通性确认）");

                tried.add(origin + path + " -> HTTP " + r.code + " " + brief);
                lastFail = AuthResult.fail("登录后仍无法访问外网", brief);
            }
        }

        Diag.section("候选结果");
        for (String s : tried) Diag.kv("", s);
        Logx.w("锐捷网页接口全部候选未成功：" + tried);

        return lastFail != null ? lastFail : AuthResult.fail("锐捷网页接口登录未能确认成功", tried.toString());
    }

    // ==================================================================
    // 门户页与会话信息
    // ==================================================================

    private static final class Page {
        boolean online;
        String error = "";
        String host = "";
        String query = "";
        String finalUrl = "";
        String html = "";
        String jsVersion = "";
        final List<String> ports = new ArrayList<>();
    }

    private Page openPortal(AppConfig cfg) {
        Page p = new Page();
        String probe = Str.nz(cfg.probeUrl, "http://connect.rom.miui.com/generate_204");

        String url = probe;
        Http.Result last = null;
        for (int hop = 0; hop < 6; hop++) {
            Http.Result r = Http.get(url, null, Math.min(cfg.timeoutMs, 8000));
            last = r;
            if (!r.ok()) {
                p.error = r.error;
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

        if (last == null) {
            p.error = "探测失败：无法建立连接";
            return p;
        }
        if (last.code == 204 || (last.code == 200 && Str.nz(last.body).trim().isEmpty())) {
            p.online = true;
            return p;
        }

        p.finalUrl = url;
        p.host = hostOf(url);
        p.html = Str.nz(last.body);
        p.query = queryOf(url);

        // jsVersion 决定网关怎么解析其余参数，能读就读，读不到用默认值
        Matcher jv = JS_VERSION_P.matcher(p.html);
        if (jv.find()) p.jsVersion = jv.group(1);

        // 端口：页面里出现过的优先，然后回落到经验值
        Set<String> ports = new LinkedHashSet<>();
        Matcher pt = PORT_P.matcher(p.html);
        while (pt.find()) ports.add(pt.group(1));
        for (String f : FALLBACK_PORTS) ports.add(f);
        p.ports.addAll(ports);

        Logx.i("锐捷门户: " + url + "  主机=" + p.host
                + "  jsVersion=" + Str.nz(p.jsVersion, DEFAULT_JS_VERSION));
        return p;
    }

    private static Map<String, String> headers(String referer) {
        Map<String, String> h = new LinkedHashMap<>();
        if (!Str.empty(referer)) h.put("Referer", referer);
        h.put("Accept", "*/*");
        // 不带 X-Requested-With：这是 JSONP（<script> 标签）发起的请求，
        // 浏览器不会带这个头，带了反而和页面行为不一致。
        return h;
    }

    /** 剥掉 JSONP 外壳 {@code dr1003({...});}，返回纯 JSON 文本。 */
    static String unwrapJsonp(String body) {
        return Jsonp.unwrap(body);
    }

    private static boolean containsOnlineHint(String s) {
        if (Str.empty(s)) return false;
        String l = s.toLowerCase(Locale.US);
        return l.contains("已在线") || l.contains("已经在线") || l.contains("已登录")
                || l.contains("already online") || l.contains("already login");
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

    // ==================================================================
    // 小工具
    // ==================================================================

    private static String hostOf(String url) {
        try {
            return new java.net.URL(url).getHost();
        } catch (Throwable t) {
            return "";
        }
    }

    private static String queryOf(String url) {
        if (Str.empty(url)) return "";
        int i = url.indexOf('?');
        if (i < 0) return "";
        String q = url.substring(i + 1);
        int h = q.indexOf('#');
        return h < 0 ? q : q.substring(0, h);
    }

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

    private static String tail4(String s) {
        if (Str.empty(s)) return "(空)";
        return s.length() <= 4 ? "***" : "***" + s.substring(s.length() - 4);
    }
}
