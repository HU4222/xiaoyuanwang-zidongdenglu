package com.campusnet.autologin.core;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link AppRank} 的桌面自测。
 *
 * <p>守的是"推荐应用该推谁"。这条规则出错有两个方向的代价：
 * 漏推 → 用户在几百个包名里找不到，功能等于没有；
 * 错推 → 用户照着勾了无关应用，账号密码被填进别的 App 的输入框。
 * 所以用例里既有必须高分的校园网客户端，也有必须零分的日常应用。
 *
 * <p>运行：
 * <pre>
 * javac -encoding UTF-8 -d /tmp/ar -sourcepath app/src/main/java \
 *       selftest/com/campusnet/autologin/core/AppRankSelfTest.java
 * java -cp /tmp/ar com.campusnet.autologin.core.AppRankSelfTest
 * </pre>
 */
public final class AppRankSelfTest {

    private static int pass = 0;
    private static int fail = 0;

    public static void main(String[] args) {
        // ---------- 1. 典型的校园网客户端，必须进推荐 ----------
        strong("锐捷 Wi-Fi 助手", "com.ruijie.wifiassistant", "锐捷Wi-Fi助手");
        strong("锐捷纯包名", "com.ruijie.eas", "Ruijie");
        strong("深澜客户端", "com.srun.srunclient", "深澜客户端");
        strong("Dr.COM 哆点", "com.drcom.notif", "哆点");
        strong("校园网直白命名", "com.campus.net", "校园网");
        strong("城市热点 ePortal", "com.citywifi.eportal", "认证助手");
        strong("天翼校园", "cn.telecom.campus", "天翼校园");
        strong("净网先锋 NetKeeper", "com.netkeeper.client", "NetKeeper");
        strong("大小写混写", "COM.RUIJIE.CLIENT", "Ruijie Client");
        strong("中文应用名", "com.unknown.sdk", "校园网认证");

        // ---------- 2. 日常应用必须 0 分，且不进推荐 ----------
        zero("微信", "com.tencent.mm", "微信");
        zero("网易云音乐", "com.netease.cloudmusic", "网易云音乐");
        zero("淘宝", "com.taobao.taobao", "淘宝");
        zero("米家", "com.xiaomi.smarthome", "米家");
        zero("高德地图", "com.autonavi.minimap", "高德地图");
        zero("支付宝", "com.eg.android.AlipayGphone", "支付宝");

        // ---------- 3. 通用词只给低分，够不到推荐线 ----------
        weak("WiFi 分析仪", "com.example.wifianalyzer", "WiFi 分析仪");
        weak("网络收音机", "com.example.radio", "网络收音机");
        weak("WLAN 工具", "com.example.wlantool", "WLAN 工具");

        // ---------- 4. 空值不能炸 ----------
        eq("空包名空名称", 0, AppRank.score("", ""));
        eq("null 包名", 0, AppRank.score(null, null));
        eq("null 名称", 4, AppRank.score("com.ruijie.x", null));

        // ---------- 5. 推荐线上下一分 ----------
        eq("差一分不入推荐", false, AppRank.recommendable(3));
        eq("刚好达标", true, AppRank.recommendable(4));

        // ---------- 6. display 格式 ----------
        eq("显示格式", "微信（com.tencent.mm）",
                new AppRank.Cand("com.tencent.mm", "微信", 0).display());
        eq("缺名称时退回包名", "com.a.b",
                new AppRank.Cand("com.a.b", "", 0).display());

        // ---------- 7. 排序：高分在前，同分按名称升序 ----------
        List<AppRank.Cand> list = new ArrayList<>();
        list.add(new AppRank.Cand("com.aaa.wifi", "A 应用", 1));
        list.add(new AppRank.Cand("com.ruijie.a", "锐捷助手", 8));
        list.add(new AppRank.Cand("com.bbb.wifi", "B 应用", 1));
        list.add(new AppRank.Cand("com.srun.b", "深澜", 8));
        AppRank.sort(list);
        eq("排序第一", "深澜", list.get(0).label);   // 同为 8 分，"深澜" < "锐捷助手"
        eq("排序第二", "锐捷助手", list.get(1).label);
        eq("排序第三", "A 应用", list.get(2).label);
        eq("排序第四", "B 应用", list.get(3).label);

        System.out.println();
        System.out.println("通过 " + pass + " / 失败 " + fail);
        if (fail > 0) System.exit(1);
    }

    private static void strong(String name, String pkg, String label) {
        int s = AppRank.score(pkg, label);
        record(name + "（score=" + s + "）", AppRank.recommendable(s));
    }

    private static void zero(String name, String pkg, String label) {
        int s = AppRank.score(pkg, label);
        record(name + "（score=" + s + "，应为 0）", s == 0);
    }

    private static void weak(String name, String pkg, String label) {
        int s = AppRank.score(pkg, label);
        record(name + "（score=" + s + "，应 >0 且不进推荐）",
                s > 0 && !AppRank.recommendable(s));
    }

    private static void eq(String name, Object expect, Object actual) {
        boolean ok = expect == null ? actual == null : expect.equals(actual);
        record(name + " 期望=" + expect + " 实得=" + actual, ok);
    }

    private static void record(String desc, boolean ok) {
        if (ok) {
            pass++;
            System.out.println("  OK   " + desc);
        } else {
            fail++;
            System.out.println("  FAIL " + desc);
        }
    }
}
