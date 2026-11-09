package com.campusnet.autologin.core;

import com.campusnet.autologin.util.Str;

import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 网络探测与门户站点自动识别。
 * <p>
 * 思路：未认证时运营商/网关会把任意 HTTP 请求劫持到 portal 登录页。
 * 因此「探测 → 拿到跳转链 → 抓 portal 页 → 解析表单」就能反推出该校
 * 实际使用的认证参数，不需要事先知道是深澜、锐捷还是 Dr.COM。
 * 识别出来的结果可以直接生成一份可用配置。
 *
 * English: Probes the network and auto-identifies the portal. An unauthenticated
 * gateway hijacks any HTTP request to the login page, so following the redirect
 * chain and parsing the resulting HTML recovers the vendor and the form fields —
 * no prior knowledge of srun / ePortal / Dr.COM required.
 */
public final class PortalProbe {

    private static final int MAX_HOP = 6;

    private PortalProbe() {
    }

    // ------------------------------------------------------------------
    // 数据结构
    // ------------------------------------------------------------------

    public static final class Field {
        public String name = "";
        public String value = "";
        public String type = "";
        public String placeholder = "";

        @Override
        public String toString() {
            return name + "(" + (Str.empty(type) ? "text" : type) + ")"
                    + (Str.empty(value) ? "" : "=" + Str.ellipsis(value, 24));
        }
    }

    public static final class Form {
        public String action = "";
        public String method = "post";
        public final List<Field> fields = new ArrayList<>();

        public String describe() {
            StringBuilder sb = new StringBuilder();
            sb.append(Str.empty(action) ? "(当前页)" : Str.ellipsis(action, 70)).append(" [").append(method).append("]\n");
            for (Field f : fields) sb.append("    ").append(f).append('\n');
            return sb.toString();
        }
    }

    public static final class ProbeResult {
        public boolean online;
        public int code;
        public String finalUrl = "";
        public String portalUrl = "";
        public String title = "";
        public String vendor = "unknown";
        public String vendorHint = "";
        public String suggestedProvider = "generic";
        public String suggestedAuthUrl = "";
        public String suggestedUserField = "";
        public String suggestedPassField = "";
        public final List<Form> forms = new ArrayList<>();
        /** 页面里的 iframe src；非空说明登录框在子页，直连填表无望。 */
        public final List<String> iframeClues = new ArrayList<>();
        public final List<String> trace = new ArrayList<>();
        public String snippet = "";
        public String error = "";
        /** 门户页完整 HTML（不参与 describe，只为落盘分析用） */
        public String html = "";

        public String describe() {
            StringBuilder sb = new StringBuilder();
            sb.append("状态: ").append(online ? "已通过校园网认证" : "未认证").append('\n');
            sb.append("出口: ").append(Net.outletLabel()).append('\n');
            sb.append("探测返回: HTTP ").append(code).append('\n');
            if (!error.isEmpty()) sb.append("错误: ").append(error).append('\n');
            if (!trace.isEmpty()) {
                sb.append("跳转链:\n");
                for (String t : trace) sb.append("    ").append(Str.ellipsis(t, 110)).append('\n');
            }
            if (!title.isEmpty()) sb.append("门户标题: ").append(title).append('\n');
            sb.append("识别厂商: ").append(vendor).append("  ").append(vendorHint).append('\n');
            if (!forms.isEmpty()) {
                sb.append("发现表单:\n");
                for (Form f : forms) sb.append(f.describe());
            } else if (!online) {
                sb.append("未发现表单（该 portal 可能由 JS 动态渲染，或需要先执行 802.1X）\n");
            }
            return sb.toString();
        }
    }

    // ------------------------------------------------------------------
    // 连通性判定
    // ------------------------------------------------------------------

    /**
     * 判断当前是否真的通过了校园网认证。
     * <p>
     * 关键点：探测请求被 {@link Net} 绑死在 Wi-Fi 网卡上。若没有 Wi-Fi，
     * 直接返回「无 Wi-Fi」，**不会**因为移动数据能上网就报已认证——
     * 这正是"开着流量时永远显示已登录"的根因所在。
     */
    public static Judge.State check(AppConfig cfg) {
        if (!Net.hasWifi()) {
            return Judge.noWifi(Net.hasCellular());
        }

        String probe = Str.nz(cfg.probeUrl, "http://connect.rom.miui.com/generate_204");
        Http.Result r = Http.get(probe, null, Math.min(cfg.timeoutMs, 6000));

        if (!r.ok()) {
            // 探测点本身不可达（可能是内网限制），换备用点再试一次
            Http.Result r2 = Http.get("http://www.gstatic.com/generate_204", null, Math.min(cfg.timeoutMs, 6000));
            if (r2.ok()) return Judge.evaluate(r2);
        }
        return Judge.evaluate(r);
    }

    /**
     * 是否已通过认证（能从 Wi-Fi 访问外网）。
     * <p>
     * 未连接 Wi-Fi 时一律返回 false —— 流量通不代表校园网通。
     */
    public static boolean isOnline(AppConfig cfg) {
        return check(cfg).online;
    }

    // ------------------------------------------------------------------
    // 完整探测
    // ------------------------------------------------------------------

    public static ProbeResult probe(AppConfig cfg) {
        ProbeResult out = new ProbeResult();

        // 没有 Wi-Fi 时不必探测：指定出口已强制为 Wi-Fi，此时请求必然失败，
        // 与其抛一堆连接错误，不如直接说清原因。
        if (!Net.hasWifi()) {
            out.online = false;
            out.error = Net.hasCellular()
                    ? "未连接 Wi-Fi（只有移动数据，流量不经过校园网关）"
                    : "未连接 Wi-Fi";
            Logx.w("探测跳过: " + out.error);
            return out;
        }

        String probe = Str.nz(cfg.probeUrl, "http://connect.rom.miui.com/generate_204");

        String url = probe;
        Http.Result last = null;
        Map<String, String> hdr = new LinkedHashMap<>();
        hdr.put("Cache-Control", "no-cache");

        for (int hop = 0; hop < MAX_HOP; hop++) {
            Http.Result r = Http.get(url, hdr, Math.min(cfg.timeoutMs, 8000));
            out.trace.add(url + "  ->  " + r);
            last = r;
            if (!r.ok()) {
                out.error = r.error;
                break;
            }
            if (r.isRedirect() && !Str.empty(r.location)) {
                String next = absolute(url, r.location);
                if (next.equalsIgnoreCase(url)) break;
                url = next;
                continue;
            }
            break;
        }

        if (last == null) {
            out.online = false;
            out.error = "探测失败：无法建立连接";
            return out;
        }

        out.code = last.code;
        out.finalUrl = url;

        if (last.code == 204 || (last.code == 200 && Str.nz(last.body).trim().isEmpty())) {
            out.online = true;
            out.vendorHint = "当前无需认证";
            return out;
        }

        out.online = false;

        // 请求本身没成功（连不上 / 超时），那就根本不存在"门户页"。
        // 以前这里不管成败都把落点当门户返回，下游「通用表单」于是把账号密码
        // 提交到了 connect.rom.miui.com 这类**第三方连通性探测点**上——
        // 2026-11-06 15:30 的真机诊断里就发生了这一条，属于凭据外泄。
        if (!last.ok()) {
            if (Str.empty(out.error)) out.error = "无法连接到探测地址";
            Logx.w("探测失败，未取得门户页: " + out.error);
            return out;
        }

        String html = Str.nz(last.body);

        // 没有跳转、落点仍是探测地址，页面里也没有任何门户特征 ——
        // 说明网关这次没有劫持请求，而不是"探测地址就是门户"。
        if (Guard.isConnectivityCheck(url) && !looksLikePortal(html)) {
            out.error = "网关没有把探测请求劫持到门户页（落点仍是连通性探测地址）";
            Logx.w("探测异常: " + out.error);
            return out;
        }

        // 最终落点就是 portal 页
        out.portalUrl = url;
        out.html = html;
        out.title = Str.htmlTitle(html);
        out.snippet = Str.ellipsis(html, 600);

        parseForms(html, url, out.forms);
        detectVendor(url, html, out);
        suggest(out);

        if (out.forms.isEmpty()) {
            String saved = PageStore.save("portal", html);
            if (!saved.isEmpty()) {
                Logx.i("门户页无表单，已存下原文供分析: " + saved);
            } else {
                Logx.w("门户页无表单，存档失败（导出诊断时可能缺少原文）");
            }
            // 锐捷的 a79.htm 这类"认证前拦截页"自己不收表单，真正带
            // wlanuserip 的地址靠页面脚本再跳一次。把页面里的地址线索
            // 直接点名到日志，省掉一轮"再给我一份日志"的来回。
            List<String> urls = urlClues(html, url);
            if (!urls.isEmpty()) {
                StringBuilder u = new StringBuilder("页面中的地址线索:");
                for (int i = 0; i < urls.size() && i < 8; i++) {
                    u.append("\n    ").append(Str.ellipsis(urls.get(i), 140));
                }
                Logx.i(u.toString());
            }

            // 无表单 + 有 iframe = 登录框在子页面里。这个判断能让后续流程
            // 直接去 WebView 那条路，不用再试一遍注定 203 的直连。
            List<String> iframes = iframeClues(html);
            if (!iframes.isEmpty()) {
                StringBuilder f = new StringBuilder("页面含 iframe，登录框可能在子页:");
                for (int i = 0; i < iframes.size() && i < 6; i++) {
                    f.append("\n    ").append(Str.ellipsis(iframes.get(i), 140));
                }
                out.iframeClues.addAll(iframes);
                Logx.i(f.toString());
            }
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < out.forms.size(); i++) {
            Form f = out.forms.get(i);
            sb.append("表单").append(i + 1).append(" action=").append(f.action)
                    .append(" 字段数=").append(f.fields.size()).append('\n');
        }
        Logx.i("探测完成: online=" + out.online + " vendor=" + out.vendor
                + " forms=" + out.forms.size()
                + " 落点=" + Str.ellipsis(out.portalUrl, 110)
                + (Str.empty(out.title) ? "" : " 标题=" + out.title)
                + "\n" + sb);
        return out;
    }

    /** 页面里出现的可疑地址（绝对 URL / location 赋值），按含会话参数优先。 */
    private static final Pattern U_P = Pattern.compile(
            "https?://[^\\s\"'<>()\\\\,;]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern L_P = Pattern.compile(
            "location(?:\\.href)?\\s*(?:=|:)\\s*['\"]([^'\"]{4,400})['\"]", Pattern.CASE_INSENSITIVE);

    static List<String> urlClues(String html, String baseUrl) {
        List<String> hot = new ArrayList<>();
        List<String> cold = new ArrayList<>();
        if (Str.empty(html)) return hot;
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        Matcher m = U_P.matcher(html);
        while (m.find()) seen.add(m.group().replace("&amp;", "&"));
        Matcher m2 = L_P.matcher(html);
        while (m2.find()) seen.add(PortalProbe.absolute(baseUrl, m2.group(1).replace("&amp;", "&")));
        for (String s : seen) {
            String l = s.toLowerCase(Locale.US);
            if (l.contains("wlanuserip") || l.contains("wlanusrip")) hot.add(s);
            else if (l.contains("eportal") || l.contains("a79") || l.contains("index.jsp")) cold.add(s);
        }
        hot.addAll(cold);
        return hot;
    }

    /**
     * 服务端返回的这段 HTML 像不像登录门户。
     * <p>
     * 只用来区分两种情况：网关把请求劫持到了门户页（正文里一定有表单、
     * 密码框或登录/认证字眼），还是网关根本没理这次探测（往往只是一小段
     * 平台自带的状态页）。后者绝不能当成"门户地址"往下传。
     */
    static boolean looksLikePortal(String html) {
        if (Str.empty(html)) return false;
        String h = html.toLowerCase(Locale.US);
        if (h.contains("<form")) return true;
        if (h.contains("type=\"password\"") || h.contains("type=password")) return true;
        String[] hints = {"登录", "登陆", "认证", "portal", "eportal", "srun",
                "drcom", "锐捷", "深澜", "城市热点", "上网"};
        for (String k : hints) {
            if (h.contains(k)) return true;
        }
        return false;
    }

    /** 页面里的 iframe/frame src（相对路径已补全）。 */
    private static final Pattern IFRAME_P = Pattern.compile(
            "<i?frame\\b([^>]*)>", Pattern.CASE_INSENSITIVE);

    static List<String> iframeClues(String html) {
        List<String> out = new ArrayList<>();
        if (Str.empty(html)) return out;
        Matcher m = IFRAME_P.matcher(html);
        while (m.find()) {
            Map<String, String> a = attrs(m.group(1));
            String src = a.get("src");
            if (Str.empty(src) || "about:blank".equalsIgnoreCase(src)) continue;
            if (!out.contains(src)) out.add(src);
        }
        return out;
    }

    // ------------------------------------------------------------------
    // 表单解析
    // ------------------------------------------------------------------
    private static final Pattern FORM_P = Pattern.compile(
            "<form([^>]*)>(.*?)</form>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern INPUT_P = Pattern.compile(
            "<input([^>]*)>", Pattern.CASE_INSENSITIVE);
    private static final Pattern ATTR_P = Pattern.compile(
            "([\\w:-]+)\\s*=\\s*(\"([^\"]*)\"|'([^']*)'|([^\\s>]+))", Pattern.CASE_INSENSITIVE);
    private static final Pattern OPTION_P = Pattern.compile(
            "<option([^>]*)>", Pattern.CASE_INSENSITIVE);

    private static Map<String, String> attrs(String raw) {
        Map<String, String> m = new LinkedHashMap<>();
        if (raw == null) return m;
        Matcher pm = ATTR_P.matcher(raw);
        while (pm.find()) {
            String k = pm.group(1).toLowerCase(Locale.US);
            String v = pm.group(3) != null ? pm.group(3)
                    : pm.group(4) != null ? pm.group(4) : Str.nz(pm.group(5));
            m.put(k, v);
        }
        return m;
    }

    /** 解析页面中的所有表单，供认证器复用。 */
    public static void parseForms(String html, String baseUrl, List<Form> out) {
        if (Str.empty(html)) return;
        Matcher fm = FORM_P.matcher(html);
        while (fm.find()) {
            Map<String, String> fa = attrs(fm.group(1));
            Form form = new Form();
            form.action = absolute(baseUrl, Str.nz(fa.get("action")));
            form.method = Str.nz(fa.get("method"), "post").toLowerCase(Locale.US);
            String inner = Str.nz(fm.group(2));

            Matcher im = INPUT_P.matcher(inner);
            while (im.find()) {
                Map<String, String> ia = attrs(im.group(1));
                String name = Str.nz(ia.get("name"));
                if (name.isEmpty()) continue;
                Field f = new Field();
                f.name = name;
                f.value = Str.nz(ia.get("value"));
                f.type = Str.nz(ia.get("type"), "text").toLowerCase(Locale.US);
                f.placeholder = Str.nz(ia.get("placeholder"));
                form.fields.add(f);
            }
            Matcher om = OPTION_P.matcher(inner);
            while (om.find()) {
                Map<String, String> oa = attrs(om.group(1));
                if (!oa.containsKey("name")) continue;
                Field f = new Field();
                f.name = Str.nz(oa.get("name"));
                f.value = Str.nz(oa.get("value"));
                f.type = "select";
                form.fields.add(f);
            }
            if (!form.fields.isEmpty()) out.add(form);
        }
    }

    // ------------------------------------------------------------------
    // 厂商识别 + 建议配置
    // ------------------------------------------------------------------

    private static void detectVendor(String url, String html, ProbeResult out) {
        String u = Str.nz(url).toLowerCase(Locale.US);
        String h = Str.nz(html).toLowerCase(Locale.US);
        String all = u + "\n" + h;

        if (all.contains("srun_portal") || all.contains("srun_bx1") || all.contains("深澜")
                || all.contains("/cgi-bin/srun")) {
            out.vendor = "srun";
            out.vendorHint = "深澜 srun";
        } else if (all.contains("eportal") || all.contains("interface.do")
                || all.contains("ruijie") || all.contains("锐捷")) {
            out.vendor = "eportal";
            out.vendorHint = "锐捷 ePortal";
        } else if (all.contains("drcom") || all.contains("dr.com") || all.contains("城市热点")
                || all.contains("drclient") || u.matches(".*:\\d*80/?(portal)?.*")) {
            out.vendor = "drcom";
            out.vendorHint = "Dr.COM（城市热点）";
        } else if (all.contains("portal") || all.contains("登录") || all.contains("认证")) {
            out.vendor = "portal";
            out.vendorHint = "通用 Portal";
        } else {
            out.vendor = "unknown";
            out.vendorHint = "未识别（可手动配置）";
        }
    }

    private static final String[] USER_HINTS = {
            "ddddd", "userid", "username", "user_name", "user", "account", "loginname",
            "login", "name", "zh", "num", "stuid", "sid"
    };
    private static final String[] PASS_HINTS = {
            "upass", "passwd", "password", "pwd", "pass", "userpwd"
    };

    private static void suggest(ProbeResult out) {
        // 优先挑含密码框的表单——那才是登录表单
        Form target = null;
        for (Form f : out.forms) {
            for (Field fd : f.fields) {
                if ("password".equals(fd.type)) {
                    target = f;
                    break;
                }
            }
            if (target != null) break;
        }
        if (target == null && !out.forms.isEmpty()) target = out.forms.get(0);

        if (target != null) {
            out.suggestedAuthUrl = target.action;

            for (Field fd : target.fields) {
                if ("password".equals(fd.type)) {
                    out.suggestedPassField = fd.name;
                    break;
                }
            }
            if (out.suggestedPassField.isEmpty()) {
                for (Field fd : target.fields) {
                    String n = fd.name.toLowerCase(Locale.US);
                    for (String p : PASS_HINTS) {
                        if (n.equals(p) || n.contains(p)) {
                            out.suggestedPassField = fd.name;
                            break;
                        }
                    }
                    if (!out.suggestedPassField.isEmpty()) break;
                }
            }
            // 用户名：排除密码框、排除空白 hidden
            for (Field fd : target.fields) {
                if (fd.name.equals(out.suggestedPassField)) continue;
                if ("hidden".equals(fd.type) || "submit".equals(fd.type) || "button".equals(fd.type)) continue;
                String n = fd.name.toLowerCase(Locale.US);
                for (String u : USER_HINTS) {
                    if (n.equals(u) || n.contains(u)) {
                        out.suggestedUserField = fd.name;
                        break;
                    }
                }
                if (!out.suggestedUserField.isEmpty()) break;
            }
            // 兜底：第一个可见文本框
            if (out.suggestedUserField.isEmpty()) {
                for (Field fd : target.fields) {
                    if ("text".equals(fd.type) || "tel".equals(fd.type) || "".equals(fd.type)) {
                        if (fd.name.equals(out.suggestedPassField)) continue;
                        out.suggestedUserField = fd.name;
                        break;
                    }
                }
            }
        }

        // provider 建议
        if ("srun".equals(out.vendor)) {
            out.suggestedProvider = "srun";
        } else if ("eportal".equals(out.vendor)) {
            out.suggestedProvider = "eportal";
        } else if ("drcom".equals(out.vendor)) {
            out.suggestedProvider = "drcom";
        } else {
            out.suggestedProvider = "generic";
        }
        // 深澜的关键参数常出现在 URL query 里
        if ("srun".equals(out.vendor)) {
            String ac = queryParam(out.portalUrl, "ac_id");
            if (!Str.empty(ac)) out.suggestedAuthUrl = Str.nz(out.suggestedAuthUrl, out.portalUrl);
        }
    }

    public static String queryParam(String url, String key) {
        if (Str.empty(url)) return "";
        try {
            URL u = new URL(url);
            String q = u.getQuery();
            if (q == null) return "";
            for (String kv : q.split("&")) {
                int i = kv.indexOf('=');
                if (i <= 0) continue;
                if (kv.substring(0, i).equalsIgnoreCase(key)) return kv.substring(i + 1);
            }
        } catch (Throwable ignore) {
        }
        return "";
    }

    /** 把 action / Location 解析成绝对地址。 */
    public static String absolute(String base, String ref) {
        if (Str.empty(ref)) return Str.nz(base);
        if (ref.startsWith("http://") || ref.startsWith("https://")) return ref;
        try {
            return new URL(new URL(base), ref).toString();
        } catch (Throwable t) {
            return ref;
        }
    }
}
