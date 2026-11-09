package com.campusnet.autologin.core;

import com.campusnet.autologin.util.Str;

/**
 * 锐捷 ePortal 网页版登录接口的 query 构造。
 *
 * <p>单独抽出来是为了能被桌面 JVM 逐字节验证——把 2026-11-06 15:10 真机抓包里
 * 门户自己发出的那串 query 当作"标准答案"，断言这里拼出来的东西与它**完全一致**
 * （连参数顺序和 `%2C` 这种编码细节都要对上）。
 *
 * <p>为什么值得这么做：网关对这个接口的容忍度很低，参数顺序或编码差一点就回
 * {@code HTTP 203 Bad request}，而 203 不会告诉你是哪个字段错了。之前
 * 1.0.1–1.0.3 就是在"猜参数"上耗掉的。能离线比对的部分不该留到真机上试。
 *
 * <p>注意 {@code v} 是前端生成的一次性数字，服务端不校验具体值，测试时把抓包里
 * 的 {@code 10420} 传进来即可做到全串相等。
 *
 * English: Builds the query string for Ruijie ePortal's web login endpoint.
 * Isolated so it can be compared byte-for-byte against a real captured request on
 * a desktop JVM: parameter order and encoding details both matter, and the gateway
 * answers HTTP 203 without saying which field is wrong.
 */
public final class EportalQuery {

    private EportalQuery() {
    }

    /**
     * 按门户页的真实发送顺序拼 query（不含前导 `?`）。
     *
     * @param username  学号/账号，不带前缀（内部补 `,1,`）
     * @param password  密码明文（服务端就要明文，这是锐捷的设计）
     * @param userIp    本机 Wi-Fi 出口 IP，形如 {@code 10.0.0.123}
     * @param acIp      AC 地址，来自门户页的 {@code wlanacip} 参数
     * @param jsVersion 门户页的 jsVersion，如 {@code 4.1.3}
     * @param callback  JSONP 回调名，抓包里是 {@code dr1003}
     * @param v         一次性数字，抓包里是 {@code 10420}
     */
    public static String build(String username, String password, String userIp,
                               String acIp, String jsVersion, String callback, String v) {
        StringBuilder sb = new StringBuilder();
        kv(sb, "callback", callback);
        kv(sb, "login_method", "1");
        kv(sb, "user_account", ",1," + Str.nz(username));
        kv(sb, "user_password", Str.nz(password));
        kv(sb, "wlan_user_ip", Str.nz(userIp));
        kv(sb, "wlan_user_ipv6", "");
        kv(sb, "wlan_user_mac", "000000000000");
        kv(sb, "wlan_ac_ip", Str.nz(acIp));
        kv(sb, "wlan_ac_name", "");
        kv(sb, "jsVersion", Str.nz(jsVersion, "4.1.3"));
        kv(sb, "terminal_type", "2");
        kv(sb, "lang", "zh-cn");
        kv(sb, "v", Str.nz(v));
        // 门户页自己的 URL 里 lang 出现了两次（zh-cn 与 zh），照抄，别"优化"
        kv(sb, "lang", "zh");
        return sb.toString();
    }

    /** 生成一个与前端同形态的一次性数字（5 位）。 */
    public static String randomV() {
        return String.valueOf(10000 + (System.currentTimeMillis() % 90000));
    }

    private static void kv(StringBuilder sb, String k, String v) {
        if (sb.length() > 0) sb.append('&');
        sb.append(Str.urlEncode(k)).append('=').append(Str.urlEncode(Str.nz(v)));
    }
}
