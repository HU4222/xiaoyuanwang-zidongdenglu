package com.campusnet.autologin.auth;

import com.campusnet.autologin.core.AppConfig;
import com.campusnet.autologin.core.Diag;
import com.campusnet.autologin.core.Guard;
import com.campusnet.autologin.core.Http;
import com.campusnet.autologin.core.Judge;
import com.campusnet.autologin.core.LoginVerdict;
import com.campusnet.autologin.core.Logx;
import com.campusnet.autologin.core.Net;
import com.campusnet.autologin.core.PortalProbe;
import com.campusnet.autologin.util.Str;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 通用 Web Portal 表单认证。
 * <p>
 * 不预设任何学校的参数名，而是：抓登录页 → 找出含密码框的表单 →
 * 以页面全部 hidden 字段为基线 → 自动定位用户名/密码控件 → 提交。
 * 「自动定位」覆盖了国内几大厂商的历史命名（Dr.COM 的 userid/passwd、
 * 锐捷老门户的 DDDDD/upass、各类定制的 username/password/account）。
 * <p>
 * <b>判定顺序是关键</b>：提交后先轮询"Wi-Fi 出口通了没有"，通了就是成功；
 * 没通才去读服务端文案，而且只用来解释原因。反过来先搜关键词会误伤——
 * 响应正文里 {@code onerror=}、{@code showError(} 这类写法随处可见，
 * 曾经把「已登录成功」的响应判成失败并把命中的 {@code error} 当原因显示出去。
 *
 * English: Generic HTML form login. Finds the form holding a password field,
 * treats every hidden input as the baseline, then locates the username/password
 * controls by name heuristics. Success is decided by polling Wi-Fi egress, never
 * by grepping the response body.
 */
public final class FormAuth implements Authenticator {

    /** 提交后等待认证生效的探测间隔（毫秒）。网关放行常常有一两秒延迟。 */
    private static final int[] WAIT_PLAN = {0, 1200, 2000};

    private final String forcedProvider;

    public FormAuth() {
        this(null);
    }

    public FormAuth(String provider) {
        this.forcedProvider = provider;
    }

    @Override
    public String name() {
        return forcedProvider == null ? "PortalForm" : "PortalForm/" + forcedProvider;
    }

    @Override
    public AuthResult login(AppConfig cfg) {
        if (!cfg.hasCredentials()) {
            return AuthResult.fail("账号或密码为空，无法登录");
        }
        // Wi-Fi 掉了就没必要再往下走：此时任何请求都发不出去，
        // 继续跑只会在日志里刷一串没用的失败，还会污染诊断结论。
        if (!Net.hasWifi()) {
            return AuthResult.fail("未连接 Wi-Fi，已中止本次登录");
        }

        Diag.reset("登录尝试 " + new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(new Date()));
        Diag.kv("方式", name());
        Diag.kv("出口", Net.outletLabel());
        Diag.kv("账号", cfg.username);
        Diag.kv("密码", Diag.mask(cfg.password));

        String portalUrl = Str.nz(cfg.portalUrl);
        String html = null;

        // 1) 拿到登录页
        if (!Str.empty(portalUrl)) {
            Http.Result pr = Http.get(portalUrl, null, cfg.timeoutMs);
            Diag.kv("打开配置的门户页", portalUrl + "  ->  " + pr);
            if (pr.ok() && pr.code < 400) {
                html = pr.body;
            } else {
                Logx.w("打开门户页失败: " + pr + "，将改用探测结果");
                portalUrl = "";
            }
        }
        if (Str.empty(portalUrl)) {
            PortalProbe.ProbeResult probe = PortalProbe.probe(cfg);
            Diag.kv("探测门户", "vendor=" + probe.vendor + " portal=" + probe.portalUrl
                    + " forms=" + probe.forms.size()
                    + (Str.empty(probe.error) ? "" : " err=" + probe.error));
            if (probe.online) return AuthResult.ok("已通过校园网认证，无需登录");
            if (Str.empty(probe.portalUrl)) {
                return AuthResult.fail("找不到门户登录页", probe.error);
            }
            portalUrl = probe.portalUrl;
            // probe 里的 snippet 是截断过的，解析表单必须用完整页面
            Http.Result full = Http.get(portalUrl, null, cfg.timeoutMs);
            html = (full.ok() && full.code < 400 && !Str.empty(full.body))
                    ? full.body : probe.snippet;
        }

        // 2) 解析表单
        List<PortalProbe.Form> forms = new ArrayList<>();
        PortalProbe.parseForms(html, portalUrl, forms);
        PortalProbe.Form form = pickLoginForm(forms);

        Map<String, String> payload = new LinkedHashMap<>();
        String action;
        String method = "POST";

        if (form != null) {
            action = Str.nz(cfg.authUrl, form.action);
            method = "GET".equalsIgnoreCase(form.method) ? "GET" : "POST";
            for (PortalProbe.Field f : form.fields) {
                if ("submit".equals(f.type) || "button".equals(f.type) || "reset".equals(f.type)
                        || "file".equals(f.type) || "image".equals(f.type)) {
                    continue;
                }
                payload.put(f.name, f.value);
            }
            Diag.section("表单");
            Diag.kv("action", Str.empty(form.action) ? "(当前页)" : form.action);
            Diag.kv("method", form.method);
            for (PortalProbe.Field f : form.fields) {
                Diag.kv("  " + f.name + " [" + f.type + "]",
                        "hidden".equals(f.type) ? Str.ellipsis(f.value, 80) : "");
            }
        } else {
            // 没有表单：多为 JS 提交，退化为直接对 portal 地址 POST
            action = Str.nz(cfg.authUrl, portalUrl);
            Diag.section("表单");
            Diag.kv("结果", "页面里没有 <form>，退化为直接 POST 门户地址（很可能是失败主因）");
        }

        // 3) 定位用户名/密码字段
        String userField = Str.nz(cfg.userField, detectUserField(form, cfg));
        String passField = Str.nz(cfg.passField, detectPassField(form));
        Diag.kv("用户字段", Str.empty(userField) ? "(未识别)" : userField);
        Diag.kv("密码字段", Str.empty(passField) ? "(未识别)" : passField);
        if (Str.empty(userField) || Str.empty(passField)) {
            return AuthResult.fail("无法识别账号/密码字段，请在高级设置里手动指定",
                    "form=" + (form == null ? "none" : form.describe()));
        }
        payload.put(userField, cfg.username);
        payload.put(passField, cfg.password);

        // 4) 追加固定参数
        payload.putAll(parseKv(cfg.extraParams));

        // 5) 自定义模板优先
        String body = null;
        String targetUrl = action;
        if (!Str.empty(cfg.formTemplate)) {
            body = renderTemplate(cfg.formTemplate, cfg, payload);
        }

        Map<String, String> hdr = new LinkedHashMap<>();
        hdr.put("Referer", portalUrl);
        hdr.put("Origin", originOf(portalUrl));

        // 提交前最后一道闸：绝不对连通性探测地址发账号密码。
        // 2026-11-06 15:30 的真机事故就走这条路：探测连不上 → 门户地址退化成
        // connect.rom.miui.com/generate_204 → 这里把学号密码 POST 到了小米的服务器。
        // 探测失败的原因归探测失败，不能演变成"换个地方把凭据交出去"。
        if (!Guard.maySubmitCredentials(targetUrl, cfg.probeUrl)) {
            String why = Guard.rejectReason(targetUrl);
            Logx.w(why);
            Diag.section("拒绝提交");
            Diag.kv("目标", Str.nz(targetUrl));
            Diag.kv("原因", why);
            return AuthResult.fail("没有找到真正的门户登录地址，已拒绝提交账号密码", why);
        }
        if (!Net.hasWifi()) {
            Logx.w("提交前 Wi-Fi 已断开，中止");
            return AuthResult.fail("Wi-Fi 在登录过程中断开，已中止");
        }

        Diag.section("提交");
        Diag.kv("地址", targetUrl);
        Diag.kv("方法", body != null ? "POST(raw)" : method);
        Diag.kv("Referer", portalUrl);
        for (Map.Entry<String, String> e : payload.entrySet()) {
            boolean secret = e.getKey().equals(passField);
            Diag.kv("  " + e.getKey(), secret ? Diag.mask(e.getValue()) : Str.ellipsis(e.getValue(), 120));
        }
        Logx.i("提交登录 -> " + targetUrl + "  用户字段=" + userField + "  密码字段=" + passField);

        Http.Result r;
        if (body != null) {
            r = Http.postRaw(targetUrl, body, "application/x-www-form-urlencoded", hdr, cfg.timeoutMs);
        } else if ("GET".equals(method)) {
            r = Http.get(targetUrl + (targetUrl.contains("?") ? "&" : "?") + encode(payload), hdr, cfg.timeoutMs);
        } else {
            r = Http.postForm(targetUrl, payload, hdr, cfg.timeoutMs);
        }
        Logx.i("登录响应: " + r + " | " + Str.ellipsis(r.body, 180));

        Diag.section("响应");
        Diag.kv("状态", r.toString());
        for (Map.Entry<String, String> e : r.headers.entrySet()) {
            String k = e.getKey().toLowerCase(Locale.US);
            if (k.equals("set-cookie") || k.equals("content-type") || k.equals("location")
                    || k.equals("server") || k.equals("www-authenticate")) {
                Diag.kv("header " + e.getKey(), Str.ellipsis(e.getValue(), 160));
            }
        }
        Diag.body("响应正文", r.body);

        if (!r.ok()) {
            // 完整留证在 Diag 里（可从「运行日志 → 导出诊断」取），
            // 这里只回一句话，避免把整段诊断塞进主界面的详情行
            return AuthResult.fail("提交失败：" + r.error, Str.ellipsis(r.toString(), 200));
        }

        // 6) 判定：先看 Wi-Fi 出口通不通，通了就是成功，页面写什么都不管
        boolean online = false;
        Diag.section("提交后探测");
        for (int i = 0; i < WAIT_PLAN.length; i++) {
            if (WAIT_PLAN[i] > 0) sleep(WAIT_PLAN[i]);
            Judge.State st = PortalProbe.check(cfg);
            Diag.kv("第" + (i + 1) + "次", String.valueOf(st));
            if (st.online) {
                online = true;
                break;
            }
            if (i == WAIT_PLAN.length - 1 && !Str.empty(st.reason)) {
                Diag.kv("最后原因", st.reason);
            }
        }

        LoginVerdict.Verdict v = LoginVerdict.evaluate(r.body, online);
        Diag.section("判定");
        Diag.kv("结果", v.kind == LoginVerdict.SUCCESS ? "成功"
                : v.kind == LoginVerdict.FAIL ? "失败" : "未确定");
        Diag.kv("原因", v.reason);
        if (!Str.empty(v.evidence)) Diag.kv("依据", v.evidence);
        Logx.i("判定: " + v + " | " + v.evidence);

        if (v.kind == LoginVerdict.SUCCESS) {
            return AuthResult.ok(v.reason);
        }
        // 失败也把判定依据带上，用户截一张图就能定位
        return AuthResult.fail(v.reason, v.evidence);
    }

    // ------------------------------------------------------------------

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private PortalProbe.Form pickLoginForm(List<PortalProbe.Form> forms) {
        if (forms.isEmpty()) return null;
        for (PortalProbe.Form f : forms) {
            for (PortalProbe.Field fd : f.fields) {
                if ("password".equals(fd.type)) return f;
            }
        }
        return forms.get(0);
    }

    private String detectUserField(PortalProbe.Form form, AppConfig cfg) {
        if (form == null) return "username";
        String pass = detectPassField(form);
        for (PortalProbe.Field f : form.fields) {
            if (f.name.equals(pass)) continue;
            if ("hidden".equals(f.type) || "password".equals(f.type)) continue;
            String n = f.name.toLowerCase(Locale.US);
            String p = Str.nz(f.placeholder).toLowerCase(Locale.US);
            if (n.contains("user") || n.contains("name") || n.contains("account")
                    || n.contains("ddddd") || n.contains("zh") || n.contains("num")
                    || p.contains("账号") || p.contains("用户") || p.contains("学号")) {
                return f.name;
            }
        }
        for (PortalProbe.Field f : form.fields) {
            if (f.name.equals(pass)) continue;
            if ("text".equals(f.type) || "tel".equals(f.type) || "number".equals(f.type)
                    || "email".equals(f.type) || f.type.isEmpty()) {
                return f.name;
            }
        }
        return "";
    }

    private String detectPassField(PortalProbe.Form form) {
        if (form == null) return "password";
        for (PortalProbe.Field f : form.fields) {
            if ("password".equals(f.type)) return f.name;
        }
        for (PortalProbe.Field f : form.fields) {
            String n = f.name.toLowerCase(Locale.US);
            if (n.equals("upass") || n.contains("pass") || n.contains("pwd")) return f.name;
        }
        return "";
    }

    static Map<String, String> parseKv(String s) {
        Map<String, String> m = new LinkedHashMap<>();
        if (Str.empty(s)) return m;
        for (String kv : s.split("&")) {
            int i = kv.indexOf('=');
            if (i <= 0) continue;
            m.put(kv.substring(0, i).trim(), kv.substring(i + 1));
        }
        return m;
    }

    private static String encode(Map<String, String> m) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : m.entrySet()) {
            if (sb.length() > 0) sb.append('&');
            sb.append(Str.urlEncode(e.getKey())).append('=').append(Str.urlEncode(e.getValue()));
        }
        return sb.toString();
    }

    static String renderTemplate(String tpl, AppConfig cfg, Map<String, String> payload) {
        String s = tpl;
        s = s.replace("${user}", Str.urlEncode(cfg.username));
        s = s.replace("${pass}", Str.urlEncode(cfg.password));
        s = s.replace("${userRaw}", cfg.username);
        s = s.replace("${passRaw}", cfg.password);
        s = s.replace("${md5pass}", Str.md5(cfg.password));
        s = s.replace("${ts}", String.valueOf(System.currentTimeMillis() / 1000));
        s = s.replace("${rand}", String.valueOf((int) (Math.random() * 900000) + 100000));
        // ${name} 用表单里已有的值替换
        for (Map.Entry<String, String> e : payload.entrySet()) {
            s = s.replace("${" + e.getKey() + "}", Str.urlEncode(e.getValue()));
        }
        return s;
    }

    static String originOf(String url) {
        try {
            java.net.URL u = new java.net.URL(url);
            int port = u.getPort();
            return u.getProtocol() + "://" + u.getHost() + (port > 0 ? ":" + port : "");
        } catch (Throwable t) {
            return "";
        }
    }
}
