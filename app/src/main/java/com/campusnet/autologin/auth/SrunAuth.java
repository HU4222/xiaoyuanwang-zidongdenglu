package com.campusnet.autologin.auth;

import com.campusnet.autologin.core.AppConfig;
import com.campusnet.autologin.core.Http;
import com.campusnet.autologin.core.Logx;
import com.campusnet.autologin.core.PortalProbe;
import com.campusnet.autologin.util.Str;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 深澜（srun / srun3000）portal 认证。
 * <p>
 * 流程：get_challenge 拿 token → 用 token 派生 password 摘要与 chksum →
 * login 提交。参数与官方 Web 端一致。
 *
 * English: Srun (srun3000) portal login — fetch a challenge, derive the password
 * digest and the checksum from it, then submit. Matches the official web client.
 */
public final class SrunAuth implements Authenticator {

    @Override
    public String name() {
        return "Srun(深澜)";
    }

    @Override
    public AuthResult login(AppConfig cfg) {
        if (!cfg.hasCredentials()) return AuthResult.fail("账号或密码为空");

        String base = resolveBase(cfg);
        if (Str.empty(base)) {
            return AuthResult.fail("无法确定深澜认证服务器地址，请先做一次「检测网络」");
        }
        String ip = FormAuth.parseKv(cfg.extraParams).get("ip");
        if (Str.empty(ip)) ip = localIp();

        String acid = Str.nz(cfg.srunAcId, "1");
        String acidFromUrl = queryFromPortal(cfg, base, "ac_id");
        if (!Str.empty(acidFromUrl)) acid = acidFromUrl;

        String n = "200";
        String type = Str.nz(cfg.srunType, "1");
        String enc = Str.nz(cfg.srunEnc, "srun_bx1");

        Map<String, String> hdr = new LinkedHashMap<>();
        hdr.put("Referer", Str.nz(cfg.portalUrl, base + "/"));

        // ---- 1. challenge ----
        String token = null;
        String[] challengePaths = {
                "/cgi-bin/srun_portal", "/srun_portal", "/cgi-bin/srun_portal_pc"
        };
        for (String p : challengePaths) {
            String url = base + p + "?callback=x&action=get_challenge&user_name="
                    + Str.urlEncode(cfg.username) + "&ip=" + Str.urlEncode(ip);
            Http.Result r = Http.get(url, hdr, cfg.timeoutMs);
            Logx.i("srun get_challenge(" + p + "): " + r + " | " + Str.ellipsis(r.body, 160));
            token = jsonValue(r.body, "challenge");
            if (!Str.empty(token)) break;
        }
        if (Str.empty(token)) {
            return AuthResult.fail("未获取到 challenge，可能不是深澜认证或需要 https",
                    "ip=" + ip + " base=" + base);
        }

        // ---- 2. 计算签名 ----
        String hmd5 = SrunCrypto.passwordHmd5(cfg.password, token);
        String info = SrunCrypto.buildInfo(cfg.username, cfg.password, ip, acid, enc);
        String chksum = SrunCrypto.checksum(cfg.username, hmd5, acid, ip, n, type, info, token);
        Logx.i("srun 签名完成: acid=" + acid + " ip=" + ip + " hmd5=" + Str.ellipsis(hmd5, 8) + "…");

        // ---- 3. login ----
        StringBuilder q = new StringBuilder();
        q.append("callback=x&action=login")
                .append("&username=").append(Str.urlEncode(cfg.username))
                .append("&password=").append(Str.urlEncode(hmd5))
                .append("&ac_id=").append(Str.urlEncode(acid))
                .append("&ip=").append(Str.urlEncode(ip))
                .append("&chksum=").append(Str.urlEncode(chksum))
                .append("&info=").append(Str.urlEncode(info))
                .append("&n=").append(n)
                .append("&type=").append(type)
                .append("&os=Android&name=Android&double_stack=0");

        Http.Result lr = null;
        for (String p : challengePaths) {
            String url = base + p + "?" + q;
            lr = Http.get(url, hdr, cfg.timeoutMs);
            Logx.i("srun login(" + p + "): " + lr + " | " + Str.ellipsis(lr.body, 220));
            if (lr.ok() && !Str.empty(lr.body) && lr.body.contains("error")) break;
        }
        if (lr == null || !lr.ok()) {
            return AuthResult.fail("登录请求失败：" + (lr == null ? "无响应" : lr.error));
        }

        String err = jsonValue(lr.body, "error");
        String errMsg = jsonValue(lr.body, "error_msg");
        if (!Str.empty(err) && !"ok".equalsIgnoreCase(err)) {
            String human = translate(err, errMsg);
            return AuthResult.fail(human, Str.ellipsis(lr.body, 200));
        }

        if (PortalProbe.isOnline(cfg)) {
            return AuthResult.ok("深澜登录成功");
        }
        // 某些部署登录成功但外网探测点被限制，仍以服务端 ok 为准
        if ("ok".equalsIgnoreCase(err)) {
            return AuthResult.ok("深澜返回成功（外网探测暂不可达）");
        }
        return AuthResult.fail("登录后仍无法访问外网", Str.ellipsis(lr.body, 200));
    }

    // ------------------------------------------------------------------

    private static String translate(String err, String msg) {
        String e = Str.nz(err).toLowerCase();
        if (e.contains("password_error") || e.contains("e2553")) return "密码错误";
        if (e.contains("user_not_found") || e.contains("e2531")) return "账号不存在";
        if (e.contains("usernum_limit")) return "该账号在线设备数已达上限";
        if (e.contains("ip_already_online")) return "本机 IP 已在线，无需重复登录";
        if (e.contains("insufficient_balance") || e.contains("e2620")) return "余额不足";
        if (e.contains("account_locked")) return "账号被锁定";
        if (e.contains("user_disabled")) return "账号已停用";
        if (e.contains("challenge_timeout")) return "challenge 已过期，重试即可";
        return "认证失败：" + Str.nz(err) + (Str.empty(msg) ? "" : " / " + msg);
    }

    private static String resolveBase(AppConfig cfg) {
        String p = Str.nz(cfg.portalUrl);
        if (!Str.empty(p)) {
            String o = FormAuth.originOf(p);
            if (!Str.empty(o)) return o;
        }
        if (!Str.empty(cfg.authUrl)) {
            String o = FormAuth.originOf(cfg.authUrl);
            if (!Str.empty(o)) return o;
        }
        PortalProbe.ProbeResult pr = PortalProbe.probe(cfg);
        if (!Str.empty(pr.portalUrl)) return FormAuth.originOf(pr.portalUrl);
        return "";
    }

    private static String queryFromPortal(AppConfig cfg, String base, String key) {
        String v = PortalProbe.queryParam(cfg.portalUrl, key);
        if (!Str.empty(v)) return v;
        return "";
    }

    /** 从 JSON / JSONP 里取字符串字段，不引第三方库。 */
    static String jsonValue(String body, String key) {
        if (Str.empty(body)) return "";
        String k = "\"" + key + "\"";
        int i = body.indexOf(k);
        if (i < 0) return "";
        int c = body.indexOf(':', i + k.length());
        if (c < 0) return "";
        int p = c + 1;
        while (p < body.length() && Character.isWhitespace(body.charAt(p))) p++;
        if (p >= body.length()) return "";
        char q = body.charAt(p);
        if (q == '"') {
            StringBuilder sb = new StringBuilder();
            for (int j = p + 1; j < body.length(); j++) {
                char ch = body.charAt(j);
                if (ch == '\\' && j + 1 < body.length()) {
                    sb.append(body.charAt(++j));
                } else if (ch == '"') {
                    break;
                } else {
                    sb.append(ch);
                }
            }
            return sb.toString();
        }
        int e = p;
        while (e < body.length() && ",}] \n\r\t".indexOf(body.charAt(e)) < 0) e++;
        return body.substring(p, e);
    }

    /** 取本机 WiFi 网卡的 IPv4 地址，srun 的 ip 参数必须是这个。 */
    public static String localIp() {
        List<String> fallback = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (ni == null || !ni.isUp() || ni.isLoopback()) continue;
                String n = ni.getName() == null ? "" : ni.getName();
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    InetAddress a = ia.getAddress();
                    if (!(a instanceof Inet4Address)) continue;
                    if (a.isLoopbackAddress() || a.isLinkLocalAddress()) continue;
                    String ip = a.getHostAddress();
                    if (n.startsWith("wlan") || n.startsWith("ap") || n.startsWith("swlan")) return ip;
                    fallback.add(ip);
                }
            }
        } catch (Throwable t) {
            Logx.w("读取本机 IP 失败: " + t.getMessage());
        }
        return fallback.isEmpty() ? "0.0.0.0" : fallback.get(0);
    }
}
