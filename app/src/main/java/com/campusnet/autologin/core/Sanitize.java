package com.campusnet.autologin.core;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 日志与诊断报告的脱敏出口。
 *
 * <p>存在的理由很直白：1.0.3 的「导出诊断」里出现了
 * {@code user_password=568923580h} 这样一字不改的密码——锐捷 ePortal 的登录
 * 接口把密码明文放在 URL 里，而抓请求的代码又把完整 URL 原样写进了日志。
 * 用户一旦把诊断文件发出去（发给我、发群、发客服），密码就等于公开了。
 *
 * <p>所以脱敏不放在「记录请求的那一处」，而是放在**所有文本的最终出口**：
 * {@link Logx#write} 与 {@link Diag#add}。这样哪怕以后新增了别的记录点、
 * 有人忘了打码，漏出去的也只会是 {@code ***}。
 *
 * English: The redaction exit. Passwords become `***` with the length dropped too
 * (length is itself a hint); accounts keep only the last four digits. Applied
 * centrally in Logx and Diag rather than at each call site, so a newly added log
 * line cannot leak by omission.
 */
public final class Sanitize {

    /** 密码类参数：整体打码，长度也不留（长度本身就是线索）。 */
    private static final Pattern SECRET_KV = Pattern.compile(
            "(?i)\\b(user_password|password|passwd|pwd|pass|user_pass|auth_pwd|secret|token)=([^&\\s\"'<>]*)");

    /** 账号类参数：保留后 4 位，够判断"是不是自己账号、有没有填错格式"，又不至于泄露。 */
    private static final Pattern ACCOUNT_KV = Pattern.compile(
            "(?i)\\b(user_account|userid|user_id|userId|username|user_name|account|login_name)=([^&\\s\"'<>]*)");

    /** JSON 里出现的同名字段："password":"xxx" */
    private static final Pattern SECRET_JSON = Pattern.compile(
            "(?i)\"(user_password|password|passwd|pwd|token)\"\\s*:\\s*\"([^\"]*)\"");

    private Sanitize() {
    }

    /** 对任意文本做脱敏；null 原样返回 null。 */
    public static String mask(String text) {
        if (text == null || text.isEmpty()) return text;

        String s = SECRET_KV.matcher(text).replaceAll("$1=***");
        s = SECRET_JSON.matcher(s).replaceAll("\"$1\":\"***\"");

        Matcher m = ACCOUNT_KV.matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(
                    m.group(1) + "=" + tail4(m.group(2))));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * 账号只留尾巴。URL 编码过的逗号前缀（{@code %2C1%2C20230000000}）先解码再取尾，
     * 否则取出来的四位是编码残留，反而误导。
     */
    private static String tail4(String v) {
        if (v == null || v.isEmpty()) return "(空)";
        String plain = v;
        if (v.indexOf('%') >= 0) {
            try {
                plain = java.net.URLDecoder.decode(v, "UTF-8");
            } catch (Throwable ignore) {
            }
        }
        if (plain.length() <= 4) return "***";
        return "***" + plain.substring(plain.length() - 4);
    }
}
