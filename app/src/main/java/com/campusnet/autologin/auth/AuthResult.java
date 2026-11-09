package com.campusnet.autologin.auth;

/**
 * 一次登录尝试的结果。
 *
 * English: Outcome of a single login attempt.
 */
public final class AuthResult {

    public boolean ok;
    public String message = "";
    public String detail = "";

    public static AuthResult ok(String msg) {
        AuthResult r = new AuthResult();
        r.ok = true;
        r.message = msg;
        return r;
    }

    /** 成功但有附加说明（比如"网关说成功但外网暂不可达"），detail 会一起进日志。 */
    public static AuthResult ok(String msg, String detail) {
        AuthResult r = new AuthResult();
        r.ok = true;
        r.message = msg;
        r.detail = detail == null ? "" : detail;
        return r;
    }

    public static AuthResult fail(String msg) {
        AuthResult r = new AuthResult();
        r.ok = false;
        r.message = msg;
        return r;
    }

    public static AuthResult fail(String msg, String detail) {
        AuthResult r = new AuthResult();
        r.ok = false;
        r.message = msg;
        r.detail = detail == null ? "" : detail;
        return r;
    }

    @Override
    public String toString() {
        return (ok ? "[成功] " : "[失败] ") + message + (detail.isEmpty() ? "" : " | " + detail);
    }
}
