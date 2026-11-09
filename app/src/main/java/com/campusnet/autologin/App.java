package com.campusnet.autologin;

import android.app.Application;

import com.campusnet.autologin.core.AppConfig;
import com.campusnet.autologin.core.Http;
import com.campusnet.autologin.core.Logx;
import com.campusnet.autologin.core.Net;

/**
 * 应用入口：初始化日志、配置、HTTP 与网络绑定。
 * <p>
 * 顺序有讲究——日志最先（后面每一步都要写日志），网络绑定必须早于任何探测，
 * 否则第一次探测就可能从移动数据出去，把「流量通」误判成「已认证」。
 *
 * English: Application entry point. The order matters: logging first (everything
 * after it writes a line), and the Wi-Fi binding before the first probe, or that
 * probe may leave through cellular and be read as "already authenticated".
 */
public class App extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        Logx.init(this);
        AppConfig cfg = AppConfig.load(this);
        Http.setInsecureTls(cfg.insecureTls);
        // 把 HTTP 出口绑定到 Wi-Fi，必须在任何探测之前完成
        Net.init(this);
        Logx.i("应用启动 v" + BuildConfig.VERSION_NAME);
    }
}
