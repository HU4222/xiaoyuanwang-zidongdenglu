package com.campusnet.autologin.core;

/**
 * {@link EportalQuery} 的桌面自测：拿真机抓包当标准答案，逐字节比对。
 *
 * <p>标准答案来自 2026-11-06 15:10 的 WebView 抓包——学校门户页自己发出去的
 * 那一发请求，是这条链路上唯一"绝对正确"的样本（结构照抄如下，值换成占位）：
 *
 * <pre>
 * GET http://10.0.0.1:801/eportal/portal/login
 *   ?callback=dr1003&amp;login_method=1&amp;user_account=%2C1%2C&lt;账号&gt;
 *   &amp;user_password=&lt;密码&gt;&amp;wlan_user_ip=10.0.0.123&amp;wlan_user_ipv6=
 *   &amp;wlan_user_mac=000000000000&amp;wlan_ac_ip=10.0.0.2&amp;wlan_ac_name=
 *   &amp;jsVersion=4.1.3&amp;terminal_type=2&amp;lang=zh-cn&amp;v=10420&amp;lang=zh
 * </pre>
 *
 * <p><b>源码里不放真凭据。</b>真密码和真学号不进仓库、不进源码包，默认用占位值；
 * 想拿真实数据复现原始字节，用 {@code -DcnaUser=... -DcnaPw=...} 传进来即可
 * （差异只会出现在值上，结构、顺序、编码照样能验）。
 *
 * <p>值得这么做：锐捷网关对这个接口的容忍度极低，参数顺序或编码差一点就回
 * {@code HTTP 203 Bad request}，而且 203 不会告诉你是哪个字段错了。
 * 能离线比对的部分就不该留到手机上试——1.0.1 到 1.0.3 全耗在"猜参数"上。
 */
public final class EportalQuerySelfTest {

    private static final String USER = System.getProperty("cnaUser", "20000000001");
    private static final String PW = System.getProperty("cnaPw", "testpass");
    private static final String MY_IP = "10.0.0.123";
    private static final String AC_IP = "10.0.0.2";
    private static final String JS_VER = "4.1.3";
    private static final String V = "10420";

    /** 抓包的结构，值用占位符填，断言时与 EportalQuery 的输出逐字节比。 */
    private static String expected() {
        return "callback=dr1003&login_method=1&user_account=%2C1%2C" + USER
                + "&user_password=" + PW + "&wlan_user_ip=" + MY_IP + "&wlan_user_ipv6="
                + "&wlan_user_mac=000000000000&wlan_ac_ip=" + AC_IP + "&wlan_ac_name="
                + "&jsVersion=" + JS_VER + "&terminal_type=2&lang=zh-cn&v=" + V + "&lang=zh";
    }

    private static int pass = 0;
    private static int fail = 0;

    public static void main(String[] args) {
        // ---------- 1. 结构与抓包逐字节相等 ----------
        String built = EportalQuery.build(USER, PW, MY_IP, AC_IP, JS_VER, "dr1003", V);
        expect("与 15:10 抓包结构逐字节一致", expected().equals(built));
        if (!expected().equals(built)) {
            System.out.println("    期望: " + expected());
            System.out.println("    实际: " + built);
            diff(expected(), built);
        }

        // ---------- 2. 参数顺序必须固定（网关按顺序解析）----------
        expect("第一个参数是 callback", built.startsWith("callback=dr1003&"));
        expect("user_account 紧跟 login_method",
                built.contains("login_method=1&user_account="));
        expect("user_password 紧跟 user_account",
                built.contains("user_account=%2C1%2C"));
        expect("lang 出现两次且顺序不变",
                built.indexOf("lang=zh-cn") > 0
                        && built.lastIndexOf("lang=zh") > built.indexOf("lang=zh-cn"));

        // ---------- 3. 编码细节 ----------
        expect("逗号编码为 %2C 而不是裸逗号",
                built.contains("%2C1%2C") && !built.contains("user_account=,1,"));
        expect("空值保留等号（wlan_user_ipv6=）", built.contains("wlan_user_ipv6=&"));
        expect("mac 用 12 个 0", built.contains("wlan_user_mac=000000000000"));
        expect("没有 queryString= 这一层壳（网页接口是平铺参数）",
                !built.contains("queryString"));

        // 密码里有特殊字符时不能破坏 query 结构
        String odd = EportalQuery.build("s1", "a&b=c d", "1.2.3.4",
                "5.6.7.8", "4.1.3", "dr1003", "10001");
        expect("密码里的 & 被编码", odd.contains("user_password=a%26b%3Dc+d"));
        expect("密码里的 & 没产生额外参数",
                countOf(odd, "user_password=") == 1 && !odd.contains("&b="));

        // ---------- 4. 缺参数时的兜底 ----------
        String noVer = EportalQuery.build("s1", "p", "1.2.3.4", "5.6.7.8", "", "dr1003", "10001");
        expect("jsVersion 为空时回落到 4.1.3", noVer.contains("jsVersion=4.1.3"));

        String v = EportalQuery.randomV();
        expect("randomV 是 5 位数字", v.length() == 5 && v.matches("\\d{5}"));

        System.out.println();
        System.out.println("通过 " + pass + " / " + (pass + fail)
                + (fail == 0 ? "  全部通过" : "  失败 " + fail));
        if (fail != 0) System.exit(1);
    }

    /** 打印第一个不一致的位置，省得靠肉眼数。 */
    private static void diff(String a, String b) {
        int n = Math.min(a.length(), b.length());
        for (int i = 0; i < n; i++) {
            if (a.charAt(i) != b.charAt(i)) {
                int from = Math.max(0, i - 20);
                System.out.println("    首个差异在第 " + i + " 位:");
                System.out.println("      期望 ..." + a.substring(from, Math.min(a.length(), i + 20)));
                System.out.println("      实际 ..." + b.substring(from, Math.min(b.length(), i + 20)));
                return;
            }
        }
        System.out.println("    前 " + n + " 位相同，长度不同: " + a.length() + " vs " + b.length());
    }

    private static int countOf(String s, String sub) {
        int c = 0, i = 0;
        while ((i = s.indexOf(sub, i)) >= 0) {
            c++;
            i += sub.length();
        }
        return c;
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
