package com.campusnet.autologin.core;

/**
 * {@link Guard} 的桌面自测。
 *
 * <p>守的是"账号密码到底能不能往这个地址送"。用例全部来自真实事故：
 * 2026-11-06 15:30 的真机诊断里，探测连接失败后门户地址退化成了
 * {@code http://connect.rom.miui.com/generate_204}，通用表单模块把学号密码
 * POST 到了小米的服务器上。这种错必须被断言钉住，不能靠"记得别这么写"。
 *
 * <p>运行：
 * <pre>
 * javac -encoding UTF-8 -d /tmp/g core/Guard.java core/Str.java selftest/.../GuardSelfTest.java
 * java -cp /tmp/g com.campusnet.autologin.core.GuardSelfTest
 * </pre>
 */
public final class GuardSelfTest {

    private static int pass = 0;
    private static int fail = 0;

    public static void main(String[] args) {
        // ---------- 1. 必须认出这些是连通性探测点 ----------
        probe("小米探测点", "http://connect.rom.miui.com/generate_204");
        probe("Google 探测点", "http://www.gstatic.com/generate_204");
        probe("Google clients3", "http://clients3.google.com/generate_204");
        probe("Apple 探测点", "http://captive.apple.com/hotspot-detect.html");
        probe("微软探测点", "http://www.msftconnecttest.com/connecttest.txt");
        probe("高通探测点", "http://connect.qualcomm.com/generate_204");
        probe("大小写混写", "HTTP://CONNECT.ROM.MIUI.COM/generate_204");
        probe("无协议前缀", "connect.rom.miui.com/generate_204");
        probe("空串", "");

        // ---------- 2. 这些是正常门户，不能误杀 ----------
        portal("锐捷 a79 拦截页", "http://10.0.0.1/a79.htm?wlanacip=100%2e64%2e13%2e18");
        portal("锐捷网页接口", "http://10.0.0.1:801/eportal/portal/login?callback=dr1003");
        portal("锐捷老接口", "http://10.0.0.1/ePortal/InterFace.do?method=login");
        portal("深澜", "http://10.10.10.10/cgi-bin/srun_portal");
        portal("域名门户", "http://portal.gdufe.edu.cn/login");

        // ---------- 3. 提交校验（target + probeUrl 组合）----------
        String probeUrl = "http://connect.rom.miui.com/generate_204";

        allow("门户 10.0.0.1 + 默认探测点", "http://10.0.0.1:801/eportal/portal/login", probeUrl);
        allow("门户域名 + 空探测点", "http://portal.gdufe.edu.cn/login", "");
        allow("门户 IP + 自定义探测点", "http://10.0.0.1/a79.htm", "http://1.1.1.1/generate_204");

        reject("事故原样重现：提交到生成 204 的地址",
                "http://connect.rom.miui.com/generate_204", probeUrl);
        reject("把探测主机当门户主机", "http://connect.rom.miui.com", probeUrl);
        reject("目标为空", "", probeUrl);
        reject("探测点自身（即使 probeUrl 为空）",
                "http://www.gstatic.com/generate_204", "");

        // ---------- 4. 拒绝原因必须说清楚，别只说"失败"----------
        String why = Guard.rejectReason("http://connect.rom.miui.com/generate_204");
        expect("拒绝原因含目标地址", why.contains("connect.rom.miui.com"));
        expect("拒绝原因说明会外发凭据", why.contains("凭据"));

        // ---------- 5. 主机解析 ----------
        expect("hostOf 正常", "10.0.0.1".equals(Guard.hostOf("http://10.0.0.1:801/x")));
        expect("hostOf 畸形地址返回空", "".equals(Guard.hostOf("不是网址")));

        System.out.println();
        System.out.println("通过 " + pass + " / " + (pass + fail)
                + (fail == 0 ? "  全部通过" : "  失败 " + fail));
        if (fail != 0) System.exit(1);
    }

    private static void probe(String name, String url) {
        expect("探测点应识别: " + name, Guard.isConnectivityCheck(url));
        expect("探测点不得当成凭据去处: " + name, !Guard.maySubmitCredentials(url, ""));
    }

    private static void portal(String name, String url) {
        expect("门户不得被误判: " + name, !Guard.isConnectivityCheck(url));
    }

    private static void allow(String name, String target, String probeUrl) {
        expect("应允许提交: " + name, Guard.maySubmitCredentials(target, probeUrl));
    }

    private static void reject(String name, String target, String probeUrl) {
        expect("应拒绝提交: " + name, !Guard.maySubmitCredentials(target, probeUrl));
    }

    private static void expect(String name, boolean ok) {
        if (ok) {
            pass++;
            System.out.println("  ok   " + name);
        } else {
            fail++;
            System.out.println("  FAIL " + name);
        }
    }
}
