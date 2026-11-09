package com.campusnet.autologin.engine;

import android.content.Context;

import com.campusnet.autologin.auth.AuthFactory;
import com.campusnet.autologin.auth.AuthResult;
import com.campusnet.autologin.auth.Authenticator;
import com.campusnet.autologin.core.AppConfig;
import com.campusnet.autologin.core.Http;
import com.campusnet.autologin.core.Logx;
import com.campusnet.autologin.core.Judge;
import com.campusnet.autologin.core.Net;
import com.campusnet.autologin.core.PortalProbe;
import com.campusnet.autologin.root.RootShell;
import com.campusnet.autologin.util.Str;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 登录调度中枢。
 * <p>
 * 职责：串行化所有登录请求（避免心跳、手动点击、网络回调三方并发撞车）、
 * 复用同一份 Cookie 会话、执行 Root 前后置钩子、回写状态。
 *
 * English: Central login scheduler. Serialises concurrent requests (heartbeat,
 * manual tap, network callback), reuses one cookie session, runs the optional root
 * hooks before and after, and writes the resulting state back.
 */
public final class LoginEngine {

    private static final AtomicBoolean BUSY = new AtomicBoolean(false);
    private static volatile long lastAttemptAt = 0L;
    private static volatile String lastMessage = "";

    private LoginEngine() {
    }

    public static boolean isBusy() {
        return BUSY.get();
    }

    public static long lastAttemptAt() {
        return lastAttemptAt;
    }

    public static String lastMessage() {
        return lastMessage;
    }

    /**
     * 执行一次登录。
     *
     * @param force true 表示跳过「是否已在线」的预检，直接提交
     */
    public static AuthResult loginNow(Context ctx, AppConfig cfg, boolean force) {
        if (!BUSY.compareAndSet(false, true)) {
            Logx.i("已有登录流程在执行，本次跳过");
            return AuthResult.fail("已有登录任务在执行");
        }
        try {
            lastAttemptAt = System.currentTimeMillis();
            Http.setInsecureTls(cfg.insecureTls);
            Net.init(ctx);

            if (!cfg.hasCredentials()) {
                lastMessage = "账号或密码未填写";
                return AuthResult.fail(lastMessage);
            }

            // 硬门禁：校园网认证只发生在 Wi-Fi 上。没有 Wi-Fi 时提交请求
            // 只会走流量打到校园内网 IP，必然失败，还可能触发账号异常记录。
            if (!Net.hasWifi()) {
                lastMessage = "未连接 Wi-Fi";
                Logx.w("登录跳过: " + Net.dump());
                return AuthResult.fail(Net.hasCellular()
                        ? "未连接校园 Wi-Fi。移动数据能上网，但不经过校园网关，无法完成认证"
                        : "未连接 Wi-Fi，请先连上校园无线网络");
            }

            if (!force && PortalProbe.isOnline(cfg)) {
                lastMessage = "已在线";
                return AuthResult.ok("已在线，无需登录");
            }

            // Root 前置钩子（如切换 MAC、拉起 pppd）
            if (cfg.useRoot && !Str.empty(cfg.rootPreCmd)) {
                RootShell.Result rr = RootShell.exec(cfg.rootPreCmd);
                Logx.i("root 前置钩子: " + rr);
            }

            Authenticator auth = AuthFactory.create(cfg);
            Logx.i("开始登录，方式=" + auth.name() + " 用户=" + cfg.username);
            AuthResult result;
            try {
                result = auth.login(cfg);
            } catch (Throwable t) {
                Logx.e("登录异常", t);
                result = AuthResult.fail("异常：" + t.getMessage());
            }

            // Root 后置钩子
            if (cfg.useRoot && !Str.empty(cfg.rootPostCmd)) {
                RootShell.Result rr = RootShell.exec(cfg.rootPostCmd);
                Logx.i("root 后置钩子: " + rr);
            }

            String ts = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(new Date());
            // 只留一句话给界面显示；detail 里可能含整段响应，塞进配置会让首页详情行爆炸
            cfg.lastResult = result.message;
            if (result.ok) cfg.lastLoginAt = ts;
            cfg.save(ctx);

            lastMessage = result.message;
            Logx.i("登录结果: " + result);
            return result;
        } finally {
            BUSY.set(false);
        }
    }

    /** 仅检测，不登录。用于界面上的「检测网络」。 */
    public static PortalProbe.ProbeResult probe(Context ctx, AppConfig cfg) {
        Http.setInsecureTls(cfg.insecureTls);
        Net.init(ctx);
        return PortalProbe.probe(cfg);
    }

    /** 仅判断认证状态；未连接 Wi-Fi 时恒为 false（流量通不算认证通过）。 */
    public static boolean isOnline(AppConfig cfg) {
        Http.setInsecureTls(cfg.insecureTls);
        return PortalProbe.isOnline(cfg);
    }

    /** 带原因的认证状态，界面用它区分「没 Wi-Fi」和「掉线」。 */
    public static Judge.State check(Context ctx, AppConfig cfg) {
        Http.setInsecureTls(cfg.insecureTls);
        Net.init(ctx);
        return PortalProbe.check(cfg);
    }
}
