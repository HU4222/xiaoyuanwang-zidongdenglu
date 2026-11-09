package com.campusnet.autologin.auth;

import com.campusnet.autologin.core.AppConfig;

/**
 * 认证器统一接口。实现必须无状态、可并发重复调用。
 *
 * English: Common authenticator interface. Implementations must be stateless and
 * safe to call repeatedly.
 */
public interface Authenticator {

    /** 展示名，用于日志。 */
    String name();

    /** 执行一次完整认证。不得抛异常，失败请返回 AuthResult。 */
    AuthResult login(AppConfig cfg);
}
