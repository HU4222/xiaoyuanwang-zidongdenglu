package com.campusnet.autologin.core;

/**
 * JSONP 外壳剥离。
 *
 * <p>锐捷 ePortal 的网页接口全是 JSONP：服务端返回
 * {@code dr1003({"result":"success",...});}，外面套一层「回调名(」和「)」。
 * 直接拿整个串去做 {@code "result":"success"} 匹配也能命中，但一旦要按字段取
 * {@code msg}、或者要把原文写进诊断报告，就需要干净的 JSON。
 *
 * <p>独立成类还有一个实际好处：它不依赖 Android，可以在桌面 JVM 上单测，
 * 不用为了验一行字符串处理去起模拟器。
 *
 * English: Strips the JSONP envelope. ePortal replies with `dr1003({...});` and
 * downstream code wants clean JSON. Splits on the first `(` and the last `)`
 * rather than a greedy regex, so payloads containing brackets survive.
 */
public final class Jsonp {

    private Jsonp() {
    }

    /** 剥掉外壳返回纯 JSON；不是 JSONP 或剥不出内容时原样返回。 */
    public static String unwrap(String body) {
        if (body == null || body.isEmpty()) return "";
        String s = body.trim();

        // 去掉可能存在的 BOM
        if (!s.isEmpty() && s.charAt(0) == '\uFEFF') s = s.substring(1).trim();

        int a = s.indexOf('(');
        int b = s.lastIndexOf(')');
        if (a > 0 && b > a) {
            String inner = s.substring(a + 1, b).trim();
            if (inner.startsWith("{") || inner.startsWith("[")) return inner;
        }
        if (s.startsWith("{") || s.startsWith("[")) return s;
        return s;
    }
}
