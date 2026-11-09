package com.campusnet.autologin.auth;

import com.campusnet.autologin.core.AppConfig;
import com.campusnet.autologin.core.Diag;
import com.campusnet.autologin.core.Http;
import com.campusnet.autologin.core.Judge;
import com.campusnet.autologin.core.LoginVerdict;
import com.campusnet.autologin.core.Logx;
import com.campusnet.autologin.core.Net;
import com.campusnet.autologin.core.PageStore;
import com.campusnet.autologin.core.PortalProbe;
import com.campusnet.autologin.util.Str;

import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从门户页脚本里反推认证接口，再提交。
 * <p>
 * 为什么需要这一类：锐捷新版门户（标题常是「上网登录页」）页面由 JS 渲染，
 * 服务端返回的 HTML 里<b>没有 &lt;form&gt;</b>。此时「通用表单」拿不到字段名，
 * 只能退化成分支——直接向门户首页 POST，网关返回的自然是错误页，
 * 于是界面报「登录失败」。这类门户必须按脚本里真实的接口地址和参数名提交。
 * <p>
 * 做法是<b>读页面，而不是假设厂商接口长什么样</b>：
 * <ol>
 *   <li>从 HTML/JS 里扫出候选接口路径（{@code InterFace.do}、{@code portal.do} 等）；</li>
 *   <li>从页面里识别实际用到的参数名（userId / password / queryString …）；</li>
 *   <li>把门户 URL 的 query 原样带上——网关靠 wlanuserip / wlanacip 这些认人；</li>
 *   <li>逐个候选提交，谁的响应像 JSON 接口就采信谁。</li>
 * </ol>
 * 这样即使同一厂商不同版本的路径不一样，也不需要改代码。
 *
 * English: Recovers the auth endpoint from the portal's own scripts and submits to
 * it. Newer ePortal pages render entirely in JavaScript and contain no `<form>`,
 * so field names and URLs have to be read out of the page rather than assumed.
 */
public final class PortalJsAuth implements Authenticator {

    /** 用户名参数名候选，按锐捷常见写法优先 */
    private static final String[] USER_KEYS = {
            "userId", "userid", "username", "userName", "user_name", "user",
            "account", "loginName", "uid", "DDDDD", "login"
    };
    /** 密码参数名候选 */
    private static final String[] PASS_KEYS = {
            "password", "passwd", "passWord", "pwd", "upass", "pass"
    };
    /** 网关认人用的附加参数，值从门户 URL 的 query 里取 */
    private static final String[] EXTRA_KEYS = {
            "service", "queryString", "operatorPwd", "operatorUserId", "validcode",
            "passwordEncrypt", "wlanuserip", "wlanacip", "nasip", "nasid", "mac",
            "userip", "t", "auth_type", "isGZip", "userIndex", "ssid", "apmac", "apip"
    };

    private static final Pattern QUOTED = Pattern.compile("[\"']([^\"'\\s<>]{2,180})[\"']");

    @Override
    public String name() {
        return "JS门户接口";
    }

    @Override
    public AuthResult login(AppConfig cfg) {
        if (!cfg.hasCredentials()) return AuthResult.fail("账号或密码为空");

        Diag.reset("JS 门户接口尝试");
        Diag.kv("出口", Net.outletLabel());

        String portalUrl = Str.nz(cfg.portalUrl);
        if (Str.empty(portalUrl)) {
            PortalProbe.ProbeResult pr = PortalProbe.probe(cfg);
            if (pr.online) return AuthResult.ok("已通过校园网认证，无需登录");
            portalUrl = pr.portalUrl;
            if (Str.empty(portalUrl)) return AuthResult.fail("找不到门户登录页", pr.error);
        }

        Http.Result page = Http.get(portalUrl, null, cfg.timeoutMs);
        if (!page.ok()) {
            return AuthResult.fail("打开门户页失败：" + page.error);
        }
        String html = Str.nz(page.body);
        Diag.kv("门户页", portalUrl + "  ->  " + page + "  " + html.length() + " 字符");
        if (html.isEmpty()) return AuthResult.fail("门户页为空，无法分析接口");

        // 页面有标准 <form> 时交给「通用表单」处理——它更准，也能带上 hidden 字段。
        // 这里提前退出，避免对普通门户多发无谓的请求。
        List<PortalProbe.Form> forms = new ArrayList<>();
        PortalProbe.parseForms(html, portalUrl, forms);
        for (PortalProbe.Form f : forms) {
            for (PortalProbe.Field fd : f.fields) {
                if ("password".equals(fd.type)) {
                    Diag.kv("跳过", "页面有标准登录表单，交给通用表单处理");
                    return AuthResult.fail("该门户有标准表单，不适用脚本接口方式");
                }
            }
        }

        String origin = Str.nz(FormAuth.originOf(portalUrl), portalUrl);
        String query = queryOf(portalUrl);

        List<String> apis = findApis(html, origin);
        String userKey = pickKey(html, USER_KEYS, cfg.userField);
        String passKey = pickKey(html, PASS_KEYS, cfg.passField);

        Diag.section("脚本分析");
        Diag.kv("origin", origin);
        Diag.kv("query", Str.ellipsis(query, 160));
        Diag.kv("候选接口", String.valueOf(apis.size()));
        for (String a : apis) Diag.kv("  -", a);
        Diag.kv("用户字段", userKey);
        Diag.kv("密码字段", passKey);

        if (userKey.isEmpty() || passKey.isEmpty()) {
            String saved = PageStore.save("portal", html);
            return AuthResult.fail("门户页里找不到账号/密码字段名（该页可能由 JS 在运行时生成）",
                    (saved.isEmpty() ? "" : "页面已保存: " + saved + "\n")
                            + "脚本里找到的接口: " + apis);
        }
        if (apis.isEmpty()) {
            String saved = PageStore.save("portal", html);
            return AuthResult.fail("门户页里找不到认证接口地址",
                    saved.isEmpty() ? "" : "页面已保存: " + saved);
        }

        // 两种 queryString 写法都试：原样编码的，和松散解码过的。
        // 不同版本的锐捷对它的处理不一致，B 方案只是兜底，不改变主流程。
        for (int variant = 0; variant < 2; variant++) {
            for (String api : apis) {
                Map<String, String> form = buildForm(html, query, variant, userKey, passKey, cfg);
                Map<String, String> hdr = new LinkedHashMap<>();
                hdr.put("Referer", portalUrl);
                hdr.put("Origin", origin);
                hdr.put("Accept", "application/json, text/javascript, */*; q=0.01");
                hdr.put("X-Requested-With", "XMLHttpRequest");

                Diag.section("尝试 " + (variant == 0 ? "A" : "B") + " -> " + api);
                for (Map.Entry<String, String> e : form.entrySet()) {
                    boolean secret = e.getKey().equals(passKey);
                    Diag.kv("  " + e.getKey(),
                            secret ? Diag.mask(e.getValue()) : Str.ellipsis(e.getValue(), 100));
                }

                Logx.i("JS门户提交(" + (variant == 0 ? "A" : "B") + ") -> " + api);
                Http.Result r = Http.postForm(api, form, hdr, cfg.timeoutMs);
                Logx.i("JS门户响应: " + r + " | " + Str.ellipsis(r.body, 200));
                Diag.kv("响应", r.toString());
                Diag.body("响应正文", r.body);

                if (!r.ok()) continue;
                if (!looksLikeApi(r)) {
                    Diag.kv("跳过", "响应不像接口（多半是网关把请求当普通网页处理了）");
                    continue;
                }
                return judge(cfg, r, portalUrl, html);
            }
        }

        String saved = PageStore.save("portal", html);
        return AuthResult.fail("所有候选接口都没返回接口结果",
                (saved.isEmpty() ? "" : "页面已保存: " + saved + "\n") + "候选: " + apis);
    }

    // ------------------------------------------------------------------

    /** 提交后等网关放行，再按「出口通不通」判定。与表单方式保持同一套标准。 */
    private AuthResult judge(AppConfig cfg, Http.Result r, String portalUrl, String html) {
        boolean online = false;
        Diag.section("提交后探测");
        int[] plan = {0, 1200, 2000};
        for (int i = 0; i < plan.length; i++) {
            if (plan[i] > 0) {
                try {
                    Thread.sleep(plan[i]);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            Judge.State st = PortalProbe.check(cfg);
            Diag.kv("第" + (i + 1) + "次", String.valueOf(st));
            if (st.online) {
                online = true;
                break;
            }
        }

        LoginVerdict.Verdict v = LoginVerdict.evaluate(r.body, online);
        Diag.kv("判定", v.toString());
        if (v.kind == LoginVerdict.SUCCESS) return AuthResult.ok(v.reason);

        String saved = PageStore.save("portal", html);
        String detail = v.evidence;
        if (!saved.isEmpty()) detail = detail + "\n页面已保存: " + saved;
        Diag.kv("页面", saved);
        return AuthResult.fail(v.reason, detail);
    }

    /** 响应像不像一个接口：JSON 结构，或极短纯文本。 */
    private static boolean looksLikeApi(Http.Result r) {
        String b = Str.nz(r.body).trim();
        if (b.isEmpty()) return false;
        if (b.startsWith("{") || b.startsWith("[")) return true;
        String lower = b.toLowerCase(Locale.US);
        if (lower.contains("\"result\"") || lower.contains("\"success\"")
                || lower.contains("\"message\"") || lower.contains("\"msg\"")) {
            return true;
        }
        // JSONP：callback({...})
        return b.length() < 400 && b.indexOf('(') > 0 && b.indexOf('{') > 0;
    }

    private static Map<String, String> buildForm(String html, String query, int variant,
                                                 String userKey, String passKey, AppConfig cfg) {
        Map<String, String> form = new LinkedHashMap<>();

        // 网关靠这些认人：门户 URL 的 query 里有什么就全带上
        Map<String, String> q = queryMap(query);
        for (String k : EXTRA_KEYS) {
            if (q.containsKey(k)) form.put(k, q.get(k));
        }
        if (!form.containsKey("queryString")) {
            form.put("queryString", variant == 0 ? query : looseDecode(query));
        }
        // 页面上出现过的其它参数，给个空值占位——不少接口会校验字段是否存在
        for (String k : EXTRA_KEYS) {
            if (form.containsKey(k)) continue;
            if (mentions(html, k)) form.put(k, defaultValue(k));
        }

        form.put(userKey, cfg.username);
        form.put(passKey, cfg.password);
        form.putAll(FormAuth.parseKv(cfg.extraParams));
        return form;
    }

    private static String defaultValue(String key) {
        if ("passwordEncrypt".equals(key)) return "false";
        return "";
    }

    /**
     * 扫出候选接口地址。
     * <p>
     * 只认同源地址：跨域的一律丢掉，避免把账号密码发到门户页里引用的
     * 第三方统计/广告域名上去。
     */
    static List<String> findApis(String html, String origin) {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = QUOTED.matcher(html);
        while (m.find()) {
            String s = m.group(1);
            if (!looksLikeApiPath(s)) continue;
            String full = toAbsolute(origin, s);
            if (full.isEmpty()) continue;
            out.add(full);
            if (out.size() >= 6) break;
        }
        // 兜底：常见路径。放在最后，前面对上了就不会用到
        for (String guess : new String[]{
                "/eportal/InterFace.do?method=login",
                "/InterFace.do?method=login",
                "/portal.do", "/login.do"}) {
            out.add(origin + guess);
            if (out.size() >= 6) break;
        }
        return new ArrayList<>(out);
    }

    private static boolean looksLikeApiPath(String s) {
        if (s.length() < 4 || s.length() > 180) return false;
        if (s.indexOf(' ') >= 0 || s.indexOf('<') >= 0) return false;
        String lower = s.toLowerCase(Locale.US);
        if (lower.startsWith("http")) return !lower.contains("google") && !lower.contains("baidu");
        boolean hasApiSuffix = lower.contains("interface.do") || lower.endsWith(".do")
                || lower.contains("portal.do") || lower.contains("login.do")
                || lower.contains("auth.do") || lower.contains("method=login");
        return hasApiSuffix;
    }

    private static String toAbsolute(String origin, String s) {
        if (s.toLowerCase(Locale.US).startsWith("http")) {
            try {
                URL u = new URL(s);
                URL base = new URL(origin);
                if (!u.getHost().equals(base.getHost())) return "";
                return s;
            } catch (Throwable t) {
                return "";
            }
        }
        if (!s.startsWith("/")) s = "/" + s;
        return origin + s;
    }

    private static String pickKey(String html, String[] keys, String configured) {
        if (!Str.empty(configured)) return configured;
        for (String k : keys) {
            if (mentions(html, k)) return k;
        }
        return "";
    }

    /** 页面里有没有出现过这个标识符（带引号或作为 key 出现，避免误命中散文）。 */
    private static boolean mentions(String html, String key) {
        return html.contains("\"" + key + "\"")
                || html.contains("'" + key + "'")
                || html.contains(key + "=")
                || html.contains(key + ":");
    }

    private static String queryOf(String url) {
        int i = url == null ? -1 : url.indexOf('?');
        return i < 0 ? "" : url.substring(i + 1);
    }

    static Map<String, String> queryMap(String query) {
        Map<String, String> m = new LinkedHashMap<>();
        if (Str.empty(query)) return m;
        for (String kv : query.split("&")) {
            int i = kv.indexOf('=');
            if (i <= 0) continue;
            m.put(kv.substring(0, i), kv.substring(i + 1));
        }
        return m;
    }

    /** 把 query 里已经编码过一次的分隔符还原，供不认双重编码的接口使用。 */
    static String looseDecode(String s) {
        if (Str.empty(s)) return "";
        return s.replace("%3D", "=").replace("%3d", "=")
                .replace("%26", "&").replace("%2526", "&")
                .replace("%2E", ".").replace("%2e", ".")
                .replace("%25", "%");
    }
}
