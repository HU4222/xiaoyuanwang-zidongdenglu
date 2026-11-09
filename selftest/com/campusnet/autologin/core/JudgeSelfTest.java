package com.campusnet.autologin.core;

/**
 * 认证状态判定的桌面自测（不依赖 Android）。
 * <p>
 * 重点锁死这次修复的回归点：同一份「探测返回 204」的响应，如果请求是从
 * 移动数据出去的，它证明的是流量能上网，绝不能推出"已登录校园网"。
 * 修复前 isOnline() 只看响应不看出口，于是开关流量就恒为已登录。
 *
 * 运行：
 *   javac -encoding UTF-8 -d /tmp/jt \
 *     app/src/main/java/com/campusnet/autologin/util/Str.java \
 *     app/src/main/java/com/campusnet/autologin/core/Http.java \
 *     app/src/main/java/com/campusnet/autologin/core/Judge.java \
 *     selftest/com/campusnet/autologin/core/JudgeSelfTest.java
 *   java -cp /tmp/jt com.campusnet.autologin.core.JudgeSelfTest
 */
public final class JudgeSelfTest {

    private static int pass = 0;
    private static int fail = 0;

    private JudgeSelfTest() {
    }

    /** 构造一份探测响应。 */
    private static Http.Result resp(int code, String body) {
        Http.Result r = new Http.Result();
        r.code = code;
        r.body = body == null ? "" : body;
        r.url = "http://connect.rom.miui.com/generate_204";
        return r;
    }

    /** 构造一份"请求根本没发出去"的响应，如绑定网络不可达。 */
    private static Http.Result failed(String err) {
        Http.Result r = new Http.Result();
        r.code = 0;
        r.error = err;
        return r;
    }

    private static void check(String name, boolean cond) {
        if (cond) {
            pass++;
            System.out.println("  PASS  " + name);
        } else {
            fail++;
            System.out.println("  FAIL  " + name);
        }
    }

    public static void main(String[] args) {
        System.out.println("== 无 Wi-Fi（只有移动数据）==");
        Judge.State noWifi = Judge.noWifi(true);
        check("hasWifi=false", !noWifi.hasWifi);
        check("即使流量能上网也判未认证", !noWifi.online);
        check("原因里点明移动数据不顶用", noWifi.reason.contains("移动数据"));

        Judge.State noNet = Judge.noWifi(false);
        check("彻底没网时也判未认证", !noNet.online);
        check("彻底没网时不提移动数据", !noNet.reason.contains("移动数据"));

        System.out.println("== 有 Wi-Fi：正常响应 ==");
        check("204 空包 -> 已认证", Judge.evaluate(resp(204, "")).online);
        check("205 空包 -> 已认证", Judge.evaluate(resp(205, "")).online);
        check("200 空包 -> 已认证", Judge.evaluate(resp(200, "")).online);
        check("200 极短纯文本 -> 已认证", Judge.evaluate(resp(200, "ok")).online);

        System.out.println("== 有 Wi-Fi：被网关拦截 ==");
        check("302 跳门户 -> 未认证", !Judge.evaluate(resp(302, "")).online);
        check("301 跳门户 -> 未认证", !Judge.evaluate(resp(301, "")).online);
        check("200 返回登录页 HTML -> 未认证",
                !Judge.evaluate(resp(200, "<html><body>请先登录</body></html>")).online);
        check("拦截时有给出原因", !Judge.evaluate(resp(302, "")).reason.isEmpty());

        System.out.println("== 有 Wi-Fi：异常响应 ==");
        check("绑定网络不可达 -> 未认证", !Judge.evaluate(failed("Network is unreachable")).online);
        check("超时 -> 未认证", !Judge.evaluate(failed("SocketTimeoutException")).online);
        check("空响应对象 -> 未认证", !Judge.evaluate(null).online);
        check("404 -> 未认证", !Judge.evaluate(resp(404, "")).online);

        System.out.println("== 回归：同一份 204，出口不同结论必须相反 ==");
        Http.Result same204 = resp(204, "");
        boolean fromTraffic = Judge.noWifi(true).online;   // 修复前这里会得到 true
        boolean fromWifi = Judge.evaluate(same204).online;
        check("流量出口的 204 不算认证通过", !fromTraffic);
        check("Wi-Fi 出口的 204 才算认证通过", fromWifi);
        check("两者结论相反（这正是原 bug 的分界线）", fromTraffic != fromWifi);

        System.out.println("== 回归：有 Wi-Fi 但请求失败，不能回退成流量判定 ==");
        check("Wi-Fi 在但连不通 -> 未认证，不得报在线",
                !Judge.evaluate(failed("未连接 Wi-Fi")).online);

        System.out.println();
        System.out.println("RESULT: " + pass + " passed, " + fail + " failed, total " + (pass + fail));
        if (fail > 0) System.exit(1);
    }
}
