package com.campusnet.autologin.core;

/**
 * 登录结果判定的桌面自测（不依赖 Android）。
 * <p>
 * 锁死两条这次真机上踩到的回归点：
 * <ol>
 *   <li>网关回的 {@code Error code: 203 Bad request(2)} 不能被解释成
 *       "服务端返回失败文案：error"——那不是原因，是噪音；</li>
 *   <li>只要 Wi-Fi 出口通了，正文里出现 {@code error} 一律不算数——
 *       否则登录成功也会被判成失败。</li>
 * </ol>
 *
 * 运行：
 *   javac -encoding UTF-8 -d /tmp/lvt \
 *     app/src/main/java/com/campusnet/autologin/util/Str.java \
 *     app/src/main/java/com/campusnet/autologin/core/LoginVerdict.java \
 *     selftest/com/campusnet/autologin/core/LoginVerdictSelfTest.java
 *   java -cp /tmp/lvt com.campusnet.autologin.core.LoginVerdictSelfTest
 */
public final class LoginVerdictSelfTest {

    private static int pass = 0;
    private static int fail = 0;

    private LoginVerdictSelfTest() {
    }

    public static void main(String[] args) {
        // ---- 真机原始响应：锐捷 AC 的 203 ----
        LoginVerdict.Verdict v = LoginVerdict.evaluate(
                "<html><body> Error code: 203 Bad request(2) </body></html>", false);
        eq("203 判失败", LoginVerdict.FAIL, v.kind);
        ok("203 原因含 HTTP 码", v.reason.contains("203"));
        ok("203 原因可读（不是裸 error）", v.reason.contains("网关") && !v.reason.contains("：error"));

        // ---- 出口已通时，正文里的 error 一律不算数 ----
        v = LoginVerdict.evaluate("<html>onerror=showError('x')</html>", true);
        eq("出口通 + 正文含 error", LoginVerdict.SUCCESS, v.kind);

        // ---- 服务端给出明确的失败原因 ----
        v = LoginVerdict.evaluate(
                "{\"result\":\"fail\",\"message\":\"密码错误，请重新输入\"}", false);
        eq("JSON 失败", LoginVerdict.FAIL, v.kind);
        ok("JSON 失败取到原文", v.reason.contains("密码错误"));

        // ---- 服务端说成功，但出口还没通 ----
        v = LoginVerdict.evaluate(
                "{\"result\":\"success\",\"message\":\"登录成功\"}", false);
        eq("JSON 成功但出口未通", LoginVerdict.SUCCESS, v.kind);

        // ---- 已在线 ----
        v = LoginVerdict.evaluate("{\"message\":\"该账号已在线\"}", false);
        ok("已在线不判失败", v.kind != LoginVerdict.FAIL);

        // ---- 空响应 ----
        v = LoginVerdict.evaluate("", false);
        eq("空响应未确定", LoginVerdict.UNKNOWN, v.kind);

        // ---- HTML 页面里真的写了密码错误 ----
        v = LoginVerdict.evaluate("<div class='tip'>密码错误</div>", false);
        eq("页面失败短语", LoginVerdict.FAIL, v.kind);

        // ---- 页面里的 errorPage / onerror 不应命中 ----
        v = LoginVerdict.evaluate("<script>function onerror(){}</script>", false);
        eq("裸 error 不命中", LoginVerdict.UNKNOWN, v.kind);

        System.out.println();
        System.out.println("结果: " + pass + " 通过 / " + fail + " 失败");
        if (fail > 0) System.exit(1);
    }

    private static void eq(String name, int want, int got) {
        if (want == got) {
            pass++;
            System.out.println("  PASS  " + name);
        } else {
            fail++;
            System.out.println("  FAIL  " + name + "  期望=" + want + " 实际=" + got);
        }
    }

    private static void ok(String name, boolean cond) {
        if (cond) {
            pass++;
            System.out.println("  PASS  " + name);
        } else {
            fail++;
            System.out.println("  FAIL  " + name);
        }
    }
}
