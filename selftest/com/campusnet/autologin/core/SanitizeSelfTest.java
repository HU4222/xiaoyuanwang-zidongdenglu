package com.campusnet.autologin.core;

/**
 * 脱敏与 JSONP 的桌面 JVM 自测。
 *
 * <p>重点不是"函数能用"，而是**把 1.0.3 真机诊断报告里那条泄露了密码的原始日志行
 * 原样喂进来**，断言输出里再也不含明文。这条用例一旦回退，用户下次导出诊断
 * 就会再一次把密码发出去，而这类事故是不可挽回的。
 *
 * <p>运行方式（不需要 Android SDK / 模拟器）：
 * <pre>
 * javac -encoding UTF-8 -d /tmp/t \
 *   app/src/main/java/com/campusnet/autologin/core/Sanitize.java \
 *   app/src/main/java/com/campusnet/autologin/core/Jsonp.java \
 *   selftest/com/campusnet/autologin/core/SanitizeSelfTest.java
 * java -cp /tmp/t com.campusnet.autologin.core.SanitizeSelfTest
 * </pre>
 */
public final class SanitizeSelfTest {

    private static int pass = 0;
    private static int fail = 0;

    public static void main(String[] args) {
        // ---------- 一、真实事故回归：1.0.3 诊断报告里的原始行 ----------
        // 这一行来自 2026-11-06 15:10 的真机抓包，密码是明文，且已经随诊断文件外发。
        String leaked = "WebView 请求 GET http://10.0.0.1:801/eportal/portal/login"
                + "?callback=dr1003&login_method=1&user_account=%2C1%2C20230000000"
                + "&user_password=568923580h&wlan_user_ip=10.0.0.123"
                + "&wlan_ac_ip=10.0.0.2&jsVersion=4.1.3&terminal_type=2&lang=zh-cn";
        String safe = Sanitize.mask(leaked);
        notContains("真实事故行：密码不进日志", safe, "568923580h");
        contains("真实事故行：密码位被替换", safe, "user_password=***");
        notContains("真实事故行：完整账号不进日志", safe, "20230000000");
        contains("真实事故行：账号只留尾 4 位", safe, "user_account=***2121");
        contains("真实事故行：非敏感参数保留", safe, "wlan_user_ip=10.0.0.123");
        contains("真实事故行：路径保留", safe, "/eportal/portal/login");

        // ---------- 二、各种写法都要盖住 ----------
        eq("form 形式 password", "password=***",
                Sanitize.mask("password=abc123"));
        eq("form 形式 passwd", "passwd=***",
                Sanitize.mask("passwd=abc123"));
        eq("form 形式 pwd", "pwd=***",
                Sanitize.mask("pwd=abc123"));
        eq("大小写混合", "User_Password=***",
                Sanitize.mask("User_Password=abc123"));
        eq("JSON 形式", "{\"password\":\"***\"}",
                Sanitize.mask("{\"password\":\"abc123\"}"));
        eq("token 也要打码", "token=***",
                Sanitize.mask("token=abcdef"));
        eq("空密码不炸", "user_password=***",
                Sanitize.mask("user_password="));

        // ---------- 三、不能误伤正常文本 ----------
        eq("普通句子不动", "密码错误：请重新输入",
                Sanitize.mask("密码错误：请重新输入"));
        eq("英文提示不动", "Login failed, please retry",
                Sanitize.mask("Login failed, please retry"));
        eq("域名里的 pass 不动", "http://example.com/pass/page",
                Sanitize.mask("http://example.com/pass/page"));
        eq("null 原样", null, Sanitize.mask(null));
        eq("空串原样", "", Sanitize.mask(""));

        // ---------- 四、JSONP 剥壳 ----------
        eq("标准 JSONP", "{\"result\":\"success\"}",
                Jsonp.unwrap("dr1003({\"result\":\"success\"});"));
        eq("带尾分号空格", "{\"result\":\"fail\",\"msg\":\"密码错误\"}",
                Jsonp.unwrap("  dr1003( {\"result\":\"fail\",\"msg\":\"密码错误\"} ) ;  "));
        eq("已是纯 JSON", "{\"result\":\"success\"}",
                Jsonp.unwrap("{\"result\":\"success\"}"));
        eq("BOM 也去掉", "{\"a\":1}",
                Jsonp.unwrap("\uFEFF{\"a\":1}"));
        eq("非 JSONP 原样", "Error code: 203 Bad request(2)",
                Jsonp.unwrap("Error code: 203 Bad request(2)"));
        eq("空输入", "", Jsonp.unwrap(null));

        // ---------- 五、组合验证：剥壳后再脱敏 ----------
        String jsonpWithPwd = "dr1003({\"result\":\"fail\",\"user_password\":\"568923580h\"});";
        String afterJsonp = Sanitize.mask(Jsonp.unwrap(jsonpWithPwd));
        notContains("JSONP 内的密码也盖住", afterJsonp, "568923580h");

        System.out.println();
        System.out.println("通过 " + pass + " / 共 " + (pass + fail));
        if (fail > 0) {
            System.out.println("有 " + fail + " 项失败");
            System.exit(1);
        }
        System.out.println("Sanitize / Jsonp 全部通过");
    }

    // ------------------------------------------------------------------

    private static void ok(String name) {
        pass++;
        System.out.println("  PASS  " + name);
    }

    private static void bad(String name, String expect, String actual) {
        fail++;
        System.out.println("  FAIL  " + name);
        System.out.println("        期望: " + expect);
        System.out.println("        实际: " + actual);
    }

    private static void eq(String name, String expect, String actual) {
        if (expect == null ? actual == null : expect.equals(actual)) ok(name);
        else bad(name, expect, actual);
    }

    private static void contains(String name, String haystack, String needle) {
        if (haystack != null && haystack.contains(needle)) ok(name);
        else bad(name, "包含「" + needle + "」", haystack);
    }

    private static void notContains(String name, String haystack, String needle) {
        if (haystack == null || !haystack.contains(needle)) ok(name);
        else bad(name, "不含「" + needle + "」", haystack);
    }
}
