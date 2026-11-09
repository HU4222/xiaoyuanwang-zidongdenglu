package com.campusnet.autologin.ui;

import android.annotation.SuppressLint;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.campusnet.autologin.core.AppConfig;
import com.campusnet.autologin.core.Http;
import com.campusnet.autologin.core.Logx;
import com.campusnet.autologin.core.Net;
import com.campusnet.autologin.core.PortalProbe;
import com.campusnet.autologin.util.Str;

/**
 * 手动登录门户页。
 * <p>
 * 存在的理由很简单：自动填表万一认不出学校的页面结构，用户不能就此断网。
 * 这里把门户原样打开，人自己输账号密码点登录，一定能通。
 * <p>
 * 用的是可见 WebView，但仍然要把进程出口钉在 Wi-Fi 上——移动数据开着时
 * 系统默认路由会指向蜂窝，校园内网地址（100.64.x.x）从那里根本不通。
 *
 * English: Manual portal page as a last resort — when auto-fill cannot recognise
 * the page, the user can still log in by hand. The process stays bound to the
 * Wi-Fi network, otherwise the campus internal address is unreachable.
 */
public class PortalActivity extends AppCompatActivity {

    private WebView web;
    private boolean bound;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        AppConfig cfg = AppConfig.load(this);
        String start = Str.nz(cfg.portalUrl);
        if (Str.empty(start)) {
            PortalProbe.ProbeResult pr = PortalProbe.probe(cfg);
            start = Str.nz(pr.portalUrl, Str.nz(cfg.probeUrl, "http://connect.rom.miui.com/generate_204"));
        }

        bound = Net.bindProcessToWifi(true);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.WHITE);

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setUserAgentString(Http.UA_ANDROID);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        }
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                return false;
            }
        });

        root.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 顶部一条提示条，说明这是手动兜底页，也告诉用户怎么回来
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(0xF21F2437);
        bar.setPadding(dp(14), dp(10), dp(14), dp(10));

        TextView hint = new TextView(this);
        hint.setText("手动登录 · 输入账号密码后点页面上的登录按钮，成功后按返回");
        hint.setTextColor(Color.WHITE);
        hint.setTextSize(12);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        bar.addView(hint, lp);

        TextView close = new TextView(this);
        close.setText("关闭");
        close.setTextColor(0xFFB18CFF);
        close.setTextSize(13);
        close.setPadding(dp(12), dp(6), 0, dp(6));
        close.setOnClickListener(v -> finish());
        bar.addView(close);

        // 提示条浮在页面上方，WebView 全屏但顶部留出 44dp 不被盖住
        FrameLayout.LayoutParams barLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        barLp.gravity = Gravity.TOP;
        root.addView(bar, barLp);

        setContentView(root);

        Logx.i("打开门户页（手动）: " + start);
        web.loadUrl(start);
    }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) {
            web.goBack();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            try {
                web.stopLoading();
                web.loadUrl("about:blank");
                web.destroy();
            } catch (Throwable ignore) {
            }
            web = null;
        }
        if (bound) {
            Net.bindProcessToWifi(false);
            bound = false;
        }
        super.onDestroy();
    }
}
