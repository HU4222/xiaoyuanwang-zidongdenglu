package com.campusnet.autologin.auth;

import com.campusnet.autologin.core.AppConfig;
import com.campusnet.autologin.core.Logx;
import com.campusnet.autologin.core.PortalProbe;
import com.campusnet.autologin.util.Str;

import java.util.Locale;

/**
 * 认证器工厂 + 自动模式。
 * <p>
 * 自动模式先探测门户站点识别厂商，再按「最可能 → 最通用」的顺序尝试。
 * 这样即使用户不知道自家学校是哪套系统，也能一次点通。
 *
 * English: Authenticator factory and the auto mode — probe the portal, identify
 * the vendor, then try authenticators from most-likely to most-generic.
 */
public final class AuthFactory {

    private AuthFactory() {
    }

    public static Authenticator create(AppConfig cfg) {
        String p = Str.nz(cfg.provider, "auto").toLowerCase(Locale.US);
        switch (p) {
            case "srun":
                return new SrunAuth();
            case "eportal":
                // 手动选「锐捷」时也走抓包验证过的网页接口，而不是老的 InterFace.do
                return new EportalWebAuth();
            case "web":
                return new WebAuth();
            case "drcom":
                return new FormAuth("drcom");
            case "generic":
                return new FormAuth("generic");
            default:
                return new AutoAuth();
        }
    }

    /** 依次尝试多个候选认证器，第一个成功的胜出。 */
    public static final class AutoAuth implements Authenticator {

        @Override
        public String name() {
            return "Auto";
        }

        @Override
        public AuthResult login(AppConfig cfg) {
            PortalProbe.ProbeResult probe = PortalProbe.probe(cfg);
            if (probe.online) return AuthResult.ok("已通过校园网认证，无需登录");
            // 没有 Wi-Fi 时探测会直接失败，此时再往下试认证器毫无意义，
            // 只会用流量去戳校园内网 IP，一律失败还刷一堆日志。
            if (!probe.error.isEmpty() && !com.campusnet.autologin.core.Net.hasWifi()) {
                return AuthResult.fail(probe.error);
            }

            String vendor = Str.nz(probe.vendor, "unknown");
            Logx.i("自动模式：识别为 " + vendor + "（" + probe.vendorHint + "）");

            // 探测结果回填，后续请求不用再猜
            AppConfig effective = AppConfig.fromJson(cfg.toJson().toString());
            if (Str.empty(effective.portalUrl) && !Str.empty(probe.portalUrl)) {
                effective.portalUrl = probe.portalUrl;
            }
            if (Str.empty(effective.authUrl) && !Str.empty(probe.suggestedAuthUrl)) {
                effective.authUrl = probe.suggestedAuthUrl;
            }
            if (Str.empty(effective.userField)) effective.userField = probe.suggestedUserField;
            if (Str.empty(effective.passField)) effective.passField = probe.suggestedPassField;

            // 门户页里一个表单都没有，说明登录框在 iframe 子页里、或者整页由
            // 脚本渲染。这两种情况直连无论怎么拼参数都拿不到会话，只会在
            // 网关上刷失败记录，所以直接把 WebView 提到最前面。
            boolean noForm = probe.forms.isEmpty();
            if (noForm) {
                Logx.i("门户页无表单（登录框在子页或由脚本渲染）→ 先走网页自动填表");
            }

            Authenticator[] chain;
            switch (vendor) {
                case "srun":
                    chain = noForm
                            ? new Authenticator[]{new WebAuth(), new SrunAuth(), new FormAuth("srun")}
                            : new Authenticator[]{new SrunAuth(), new FormAuth("srun"), new WebAuth()};
                    break;
                case "eportal":
                    // 顺序按「快 → 慢」排，但第一条必须是真机验证过的那条：
                    // EportalWebAuth 是抓包得到的网页接口（端口 801 的
                    // /eportal/portal/login，GET + JSONP），一两个请求就出结果；
                    // 旧的 InterFace.do 排在后面兜其他锐捷版本；WebView 最后。
                    chain = new Authenticator[]{
                            new EportalWebAuth(), new EportalAuth(),
                            new FormAuth("eportal"), new WebAuth()};
                    break;
                case "drcom":
                    chain = noForm
                            ? new Authenticator[]{new WebAuth(), new FormAuth("drcom")}
                            : new Authenticator[]{new FormAuth("drcom"), new WebAuth()};
                    break;
                default:
                    chain = noForm
                            ? new Authenticator[]{new WebAuth(), new FormAuth("generic")}
                            : new Authenticator[]{new FormAuth("generic"), new WebAuth()};
                    break;
            }

            AuthResult last = AuthResult.fail("没有可用的认证方式");
            for (Authenticator a : chain) {
                Logx.i("尝试 " + a.name() + " …");
                try {
                    AuthResult r = a.login(effective);
                    if (r.ok) return r;
                    last = r;
                    Logx.w(a.name() + " 未成功：" + r.message);
                } catch (Throwable t) {
                    Logx.e("认证器 " + a.name() + " 抛异常", t);
                    last = AuthResult.fail(a.name() + " 异常：" + t.getMessage());
                }
            }
            return last;
        }
    }
}
