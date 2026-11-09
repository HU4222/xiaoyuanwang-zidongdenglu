package com.campusnet.autologin.util;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.util.Locale;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 字符串与摘要工具，无第三方依赖。
 *
 * English: String and digest helpers, no third-party dependencies.
 */
public final class Str {

    private Str() {
    }

    public static boolean empty(String s) {
        return s == null || s.trim().isEmpty();
    }

    public static String nz(String s) {
        return s == null ? "" : s;
    }

    public static String nz(String s, String def) {
        return empty(s) ? def : s;
    }

    public static String urlEncode(String s) {
        if (s == null) return "";
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return s;
        }
    }

    public static String md5(String s) {
        return hex(digest("MD5", bytes(s, "UTF-8"), null));
    }

    /** HMAC-MD5(data, key) 的十六进制小写结果。 */
    public static String hmacMd5(String data, String key) {
        return hex(digest("HmacMD5", bytes(data, "UTF-8"), bytes(key, "UTF-8")));
    }

    public static String sha1(String s) {
        return hex(digest("SHA-1", bytes(s, "UTF-8"), null));
    }

    private static byte[] digest(String algo, byte[] data, byte[] key) {
        try {
            if (key == null) {
                MessageDigest md = MessageDigest.getInstance(algo);
                return md.digest(data);
            }
            Mac mac = Mac.getInstance(algo);
            mac.init(new SecretKeySpec(key, algo));
            return mac.doFinal(data);
        } catch (Exception e) {
            return new byte[0];
        }
    }

    public static byte[] bytes(String s, String charset) {
        try {
            return s.getBytes(charset);
        } catch (UnsupportedEncodingException e) {
            return s.getBytes();
        }
    }

    public static String hex(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) {
            sb.append(String.format(Locale.US, "%02x", b));
        }
        return sb.toString();
    }

    /** 截断到 n 个字符，用于日志展示。 */
    public static String ellipsis(String s, int n) {
        if (s == null) return "";
        s = s.replaceAll("\\s+", " ").trim();
        return s.length() <= n ? s : s.substring(0, n) + "…";
    }

    /** 从 HTML 中粗略取 title，便于快速认清 portal 厂商。 */
    public static String htmlTitle(String html) {
        if (html == null) return "";
        int i = html.toLowerCase(Locale.US).indexOf("<title");
        if (i < 0) return "";
        int s = html.indexOf('>', i);
        int e = html.toLowerCase(Locale.US).indexOf("</title", s < 0 ? 0 : s);
        if (s < 0 || e < 0 || e <= s) return "";
        return html.substring(s + 1, e).trim();
    }
}
