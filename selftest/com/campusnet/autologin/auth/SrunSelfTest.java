package com.campusnet.autologin.auth;

import com.campusnet.autologin.util.Str;

/**
 * 深澜加密的离线自测。不需要 Android 设备，直接在桌面 JVM 上跑：
 *
 * <pre>
 * javac -encoding UTF-8 -d /tmp/st \
 *   app/src/main/java/com/campusnet/autologin/util/Str.java \
 *   app/src/main/java/com/campusnet/autologin/auth/SrunCrypto.java \
 *   selftest/com/campusnet/autologin/auth/SrunSelfTest.java
 * java -cp /tmp/st com.campusnet.autologin.auth.SrunSelfTest
 * </pre>
 *
 * 输出中的 XXTEAHEX / INFO 行会与 selftest/srun_ref.py 的结果逐字节比对。
 */
public class SrunSelfTest {

    private static int pass = 0;
    private static int fail = 0;

    public static void main(String[] args) throws Exception {
        // ---- 1. Base64 标准向量 ----
        eq("b64-empty", SrunCrypto.stdBase64(new byte[0]), "");
        eq("b64-hello", SrunCrypto.stdBase64(Str.bytes("hello", "UTF-8")), "aGVsbG8=");
        eq("b64-a", SrunCrypto.stdBase64(Str.bytes("a", "UTF-8")), "YQ==");
        eq("b64-ab", SrunCrypto.stdBase64(Str.bytes("ab", "UTF-8")), "YWI=");
        eq("b64-abc", SrunCrypto.stdBase64(Str.bytes("abc", "UTF-8")), "YWJj");
        eq("b64-man", SrunCrypto.stdBase64(Str.bytes("Man", "UTF-8")), "TWFu");

        // ---- 2. 摘要标准向量 ----
        eq("md5-abc", Str.md5("abc"), "900150983cd24fb0d6963f7d28e17f72");
        eq("sha1-abc", Str.sha1("abc"), "a9993e364706816aba3e25717850c26c9cd0d89d");
        // RFC 2202 test case 2
        eq("hmacmd5-rfc2202", Str.hmacMd5("what do ya want for nothing?", "Jefe"),
                "750c783e6ab0b503eaa86e310a5db738");

        // ---- 3. XXTEA 可逆性 ----
        String[] msgs = {
                "a",
                "abcd",
                "hello world",
                "{\"username\":\"2023110101\",\"password\":\"test123\","
                        + "\"ip\":\"10.0.0.2\",\"acid\":\"1\",\"enc_ver\":\"srun_bx1\"}"
        };
        for (String m : msgs) {
            byte[] enc = SrunCrypto.xencode(m, "srun_bx1");
            byte[] dec = SrunCrypto.xdecode(enc, "srun_bx1");
            eq("roundtrip[" + m.length() + "]", new String(dec, "UTF-8"), m);
            System.out.println("XXTEAHEX\t" + m + "\t" + Str.hex(enc));
        }

        // ---- 4. srun base64 字母表可逆 ----
        byte[] raw = Str.bytes("srun-test-payload", "UTF-8");
        String srun = SrunCrypto.base64(raw);
        eq("srunb64-reversible", SrunCrypto.srunBase64ToStd(srun), SrunCrypto.stdBase64(raw));

        // ---- 5. info 结构 ----
        String info = SrunCrypto.buildInfo("u1", "p1", "10.0.0.2", "1", "srun_bx1");
        eq("info-prefix", info.startsWith("{SRUN}") ? "{SRUN}" : info, "{SRUN}");
        ok("info-length", info.length() > 20, "len=" + info.length());
        System.out.println("INFO\t" + info);

        System.out.println();
        System.out.println("RESULT pass=" + pass + " fail=" + fail);
        if (fail > 0) System.exit(1);
    }

    private static void eq(String name, String actual, String expected) {
        if (expected == null ? actual == null : expected.equals(actual)) {
            pass++;
            System.out.println("PASS\t" + name);
        } else {
            fail++;
            System.out.println("FAIL\t" + name + "\n  expected: " + expected + "\n  actual  : " + actual);
        }
    }

    private static void ok(String name, boolean cond, String detail) {
        if (cond) {
            pass++;
            System.out.println("PASS\t" + name + "  " + detail);
        } else {
            fail++;
            System.out.println("FAIL\t" + name + "  " + detail);
        }
    }
}
