package com.campusnet.autologin.auth;

import com.campusnet.autologin.util.Str;

import java.io.ByteArrayOutputStream;

/**
 * 深澜（srun）portal 的加密实现。
 * <p>
 * 两段算法：
 * <ol>
 *   <li>{@code xencode} —— XXTEA（32 位块，delta 0x9E3779B9）加密后再用深澜自定义
 *       字母表做 base64。用于把 {@code {"username":..,"password":..}} 打包成 info。</li>
 *   <li>{@code chksum} —— 对串接后的校验串做 HMAC-MD5，密钥是服务端下发的 token。</li>
 * </ol>
 * 参数与主流 srun 客户端保持二进制级一致，这样无论是 srun3000 还是新版
 * srun_portal 都能对上。
 *
 * English: Srun crypto primitives — XXTEA with delta 0x9E3779B9, a reordered
 * Base64 alphabet, and HMAC-MD5 signing. Zero Android dependencies by design, so
 * it can be diffed against an independent Python reference on a desktop JVM.
 */
public final class SrunCrypto {

    private static final String STD_B64 =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    private static final String SRUN_B64 =
            "LVoJPiCN2R8G90yg+hmFHuacZ1OWMnrsSTXkYpUq/3dlbfKwv6xztjI7DeBE45QA";

    private SrunCrypto() {
    }

    /** 深澜专用 base64（字母表重排）。 */
    public static String base64(byte[] data) {
        String std = stdBase64(data);
        StringBuilder sb = new StringBuilder(std.length());
        for (int i = 0; i < std.length(); i++) {
            char ch = std.charAt(i);
            int idx = STD_B64.indexOf(ch);
            sb.append(idx < 0 ? ch : SRUN_B64.charAt(idx));
        }
        return sb.toString();
    }

    /**
     * 标准 Base64。刻意自己实现而不是用 android.util.Base64，
     * 这样整个加密模块不依赖 Android 运行时，可以在桌面 JVM 上做回归测试。
     */
    static String stdBase64(byte[] data) {
        if (data == null || data.length == 0) return "";
        StringBuilder sb = new StringBuilder((data.length + 2) / 3 * 4);
        int i = 0;
        while (i + 2 < data.length) {
            int n = ((data[i] & 0xff) << 16) | ((data[i + 1] & 0xff) << 8) | (data[i + 2] & 0xff);
            sb.append(STD_B64.charAt((n >>> 18) & 63))
                    .append(STD_B64.charAt((n >>> 12) & 63))
                    .append(STD_B64.charAt((n >>> 6) & 63))
                    .append(STD_B64.charAt(n & 63));
            i += 3;
        }
        int rem = data.length - i;
        if (rem == 1) {
            int n = (data[i] & 0xff) << 16;
            sb.append(STD_B64.charAt((n >>> 18) & 63))
                    .append(STD_B64.charAt((n >>> 12) & 63))
                    .append("==");
        } else if (rem == 2) {
            int n = ((data[i] & 0xff) << 16) | ((data[i + 1] & 0xff) << 8);
            sb.append(STD_B64.charAt((n >>> 18) & 63))
                    .append(STD_B64.charAt((n >>> 12) & 63))
                    .append(STD_B64.charAt((n >>> 6) & 63))
                    .append('=');
        }
        return sb.toString();
    }

    /** 仅供自测：srun base64 反解回标准字母表。 */
    static String srunBase64ToStd(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            int idx = SRUN_B64.indexOf(ch);
            sb.append(idx < 0 ? ch : STD_B64.charAt(idx));
        }
        return sb.toString();
    }

    public static byte[] xencode(String msg, String key) {
        if (Str.empty(msg)) return new byte[0];
        int[] pwd = sencode(Str.bytes(msg, "UTF-8"), true);
        int[] pwdk = sencode(Str.bytes(Str.nz(key), "UTF-8"), false);
        if (pwdk.length < 4) {
            int[] t = new int[4];
            System.arraycopy(pwdk, 0, t, 0, pwdk.length);
            pwdk = t;
        }

        int n = pwd.length - 1;
        int z = pwd[n];
        int y = pwd[0];
        int delta = 0x9E3779B9;
        int d = 0;
        int rounds = 6 + 52 / (n + 1);

        while (rounds-- > 0) {
            d += delta;
            int e = (d >>> 2) & 3;
            int p;
            for (p = 0; p < n; p++) {
                y = pwd[p + 1];
                int m = (z >>> 5 ^ y << 2) + ((y >>> 3 ^ z << 4) ^ (d ^ y)) + (pwdk[(p & 3) ^ e] ^ z);
                pwd[p] = pwd[p] + m;
                z = pwd[p];
            }
            y = pwd[0];
            int m = (z >>> 5 ^ y << 2) + ((y >>> 3 ^ z << 4) ^ (d ^ y)) + (pwdk[(p & 3) ^ e] ^ z);
            pwd[n] = pwd[n] + m;
            z = pwd[n];
        }
        return lencode(pwd, false);
    }

    /**
     * xencode 的逆运算，仅用于离线自测：验证 XXTEA 实现是可逆的。
     * 生产路径不调用。
     */
    static byte[] xdecode(byte[] cipher, String key) {
        if (cipher == null || cipher.length == 0) return new byte[0];
        int[] pwd = sencode(cipher, false);
        if (pwd.length < 2) return new byte[0];
        int[] pwdk = sencode(Str.bytes(Str.nz(key), "UTF-8"), false);
        if (pwdk.length < 4) {
            int[] t = new int[4];
            System.arraycopy(pwdk, 0, t, 0, pwdk.length);
            pwdk = t;
        }

        int n = pwd.length - 1;
        int delta = 0x9E3779B9;
        int rounds = 6 + 52 / (n + 1);
        int sum = rounds * delta;
        int y = pwd[0];

        for (int r = 0; r < rounds; r++) {
            int e = (sum >>> 2) & 3;
            for (int p = n; p > 0; p--) {
                int z = pwd[p - 1];
                int mx = (z >>> 5 ^ y << 2) + ((y >>> 3 ^ z << 4) ^ (sum ^ y))
                        + (pwdk[(p & 3) ^ e] ^ z);
                pwd[p] = pwd[p] - mx;
                y = pwd[p];
            }
            int z = pwd[n];
            int mx = (z >>> 5 ^ y << 2) + ((y >>> 3 ^ z << 4) ^ (sum ^ y))
                    + (pwdk[e] ^ z);
            pwd[0] = pwd[0] - mx;
            y = pwd[0];
            sum -= delta;
        }
        // 加密时最后一个 word 保存了原始字节长度
        return lencode(pwd, true);
    }

    /** 生成 srun 的 info 参数：{SRUN} + 自定义 base64(XXTEA(json))。 */    public static String buildInfo(String username, String password, String ip,
                                   String acid, String encVer) {
        String json = "{\"username\":\"" + esc(username) + "\",\"password\":\"" + esc(password)
                + "\",\"ip\":\"" + esc(ip) + "\",\"acid\":\"" + esc(acid)
                + "\",\"enc_ver\":\"" + esc(Str.nz(encVer, "srun_bx1")) + "\"}";
        return "{SRUN}" + base64(xencode(json, Str.nz(encVer, "srun_bx1")));
    }

    private static String esc(String s) {
        return Str.nz(s).replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /**
     * 校验串 = username + HMAC-MD5(password, token) + acid + ip + n + type + info
     * 再对整串做 HMAC-MD5，密钥仍是 token。
     */
    public static String checksum(String username, String passwordHmd5, String acid,
                                  String ip, String n, String type, String info, String token) {
        String raw = Str.nz(username) + Str.nz(passwordHmd5) + Str.nz(acid)
                + Str.nz(ip) + Str.nz(n) + Str.nz(type) + Str.nz(info);
        return Str.hmacMd5(raw, Str.nz(token));
    }

    public static String passwordHmd5(String password, String token) {
        return Str.hmacMd5(Str.nz(password), Str.nz(token));
    }

    // ------------------------------------------------------------------
    // XXTEA 块编解码
    // ------------------------------------------------------------------

    private static int[] sencode(byte[] data, boolean includeLen) {
        int len = data.length;
        int words = (len + 3) / 4;
        int[] out = new int[words + (includeLen ? 1 : 0)];
        for (int i = 0; i < len; i += 4) {
            int v = (byteAt(data, i) & 0xff)
                    | (byteAt(data, i + 1) & 0xff) << 8
                    | (byteAt(data, i + 2) & 0xff) << 16
                    | (byteAt(data, i + 3) & 0xff) << 24;
            out[i >> 2] = v;
        }
        if (includeLen) out[out.length - 1] = len;
        return out;
    }

    private static int byteAt(byte[] d, int i) {
        return i < d.length ? d[i] : 0;
    }

    private static byte[] lencode(int[] pwd, boolean includeLen) {
        int n = pwd.length;
        // includeLen 时最后一个 word 存的是原始字节长度，有效载荷只有 n-1 个 word
        int len = (includeLen ? (n - 1) : n) << 2;
        if (includeLen) {
            int m = pwd[n - 1];
            if (m < len - 3 || m > len) return new byte[0];
            len = m;
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream(len);
        for (int i = 0; i < n; i++) {
            int v = pwd[i];
            for (int j = 0; j < 4; j++) {
                if ((i << 2) + j < len) bos.write((v >>> (8 * j)) & 0xff);
            }
        }
        return bos.toByteArray();
    }
}
