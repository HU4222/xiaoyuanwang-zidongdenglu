package com.campusnet.autologin.core;

import com.campusnet.autologin.util.Str;

import java.net.URL;
import java.util.Locale;

/**
 * 凭据去处校验 —— 决定"这个地址到底该不该收下账号密码"。
 *
 * <p>为什么需要这个类：{@code generate_204} 这类地址是**连通性探测点**
 * （小米、Google、Apple、微软各自都有），它的作用只是回答"我这台机器有没有
 * 出网"。但当门户探测失败时，代码会把"探测用的那个 URL"当成"门户页"返回，
 * 于是一路传下去，最后变成拿用户的学号密码往 {@code connect.rom.miui.com}
 * POST——那是**第三方公司**的服务器，凭据直接出门了。
 *
 * <p>2026-11-06 15:30 的真机诊断就出现了这一条：探测报
 * {@code ConnectException}，落点却仍是 {@code connect.rom.miui.com/generate_204}，
 * 随后「通用表单」把账号密码提交到了那里。这件事必须从两头堵：探测失败时
 * 不返回门户地址（见 {@link PortalProbe#probe}），提交前再校验一次（见
 * {@code FormAuth}）。两头都做，是因为单一检查点总会被绕过——新增一个
 * 认证器、改一处回退逻辑，就漏了。
 *
 * <p>本类只做纯字符串判断，不碰 Android API，可以在桌面 JVM 上跑单测。
 *
 * English: Credential-destination check. Connectivity probes (`generate_204` and
 * friends) must never receive a username or password. Both ends are guarded: the
 * probe stops returning a portal URL on transport failure, and every submit path
 * re-checks its target. Two checks, because a single one gets bypassed the moment
 * a new authenticator or fallback is added.
 */
public final class Guard {

    /** 各家系统的连通性探测主机。这类主机永远不会是校园网门户。 */
    private static final String[] PROBE_HOSTS = {
            "connect.rom.miui.com",
            "connectivitycheck.gstatic.com",
            "clients3.google.com",
            "android.clients.google.com",
            "www.gstatic.com",
            "captive.apple.com",
            "captive.g.aaplimg.com",
            "connect.qualcomm.com",
            "connectivitycheck.platform.hicloud.com",
            "connectivitycheck.platform.dbankcloud.cn",
            "ncc.avast.com",
            "detectportal.firefox.com",
            "www.msftconnecttest.com",
            "www.msftncsi.com",
            "ipv6.msftconnecttest.com",
            "wifi.vivo.com.cn",
            "connectivitycheck.lan",
    };

    /** 探测点的路径特征。命中任一即可判定"不是门户"。 */
    private static final String[] PROBE_PATHS = {
            "generate_204", "gen_204", "hotspot-detect.html", "ncsi.txt",
            "connecttest.txt", "success.txt", "canonical.html", "/check_network_status.txt",
    };

    private Guard() {
    }

    /**
     * 这个地址是不是"连通性探测点"（而不是校园网门户）。
     * 地址为空也返回 true —— 没有地址就更不该往里塞凭据。
     */
    public static boolean isConnectivityCheck(String url) {
        if (Str.empty(url)) return true;
        String full = url.trim().toLowerCase(Locale.US);
        String host = hostOf(full);
        for (String h : PROBE_HOSTS) {
            if (host.equals(h) || host.endsWith("." + h)) return true;
        }
        for (String p : PROBE_PATHS) {
            if (full.contains(p)) return true;
        }
        return false;
    }

    /**
     * 提交账号密码前最后一道闸：允许 / 拒绝。
     *
     * @param target   准备提交到的地址
     * @param probeUrl 配置里的探测地址（用户可能改成自己学校的，所以不写死）
     * @return true 表示可以提交；false 表示必须中止
     */
    public static boolean maySubmitCredentials(String target, String probeUrl) {
        if (Str.empty(target)) return false;
        if (isConnectivityCheck(target)) return false;
        // 用户把探测地址改成了某个"看着像门户"的地址时，同主机同样不许提交
        if (!Str.empty(probeUrl) && sameHost(target, probeUrl)) return false;
        return true;
    }

    /** 拒绝时给人看的原因，写进日志与界面，避免下次还要翻代码。 */
    public static String rejectReason(String target) {
        return "拒绝向连通性探测地址提交账号密码（" + Str.ellipsis(Str.nz(target), 90)
                + "），这不是校园网门户，提交会把凭据发给第三方";
    }

    private static boolean sameHost(String a, String b) {
        String ha = hostOf(Str.nz(a).toLowerCase(Locale.US));
        String hb = hostOf(Str.nz(b).toLowerCase(Locale.US));
        return !ha.isEmpty() && ha.equals(hb);
    }

    /** 从 URL 里取主机名；解析失败返回空串（调用方按"不安全"处理）。 */
    public static String hostOf(String url) {
        if (Str.empty(url)) return "";
        try {
            String h = new URL(url).getHost();
            return h == null ? "" : h.toLowerCase(Locale.US);
        } catch (Throwable t) {
            return "";
        }
    }
}
