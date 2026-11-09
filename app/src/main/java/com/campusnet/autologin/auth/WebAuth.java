package com.campusnet.autologin.auth;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.webkit.ValueCallback;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.campusnet.autologin.core.AppConfig;
import com.campusnet.autologin.core.Diag;
import com.campusnet.autologin.core.Http;
import com.campusnet.autologin.core.Logx;
import com.campusnet.autologin.core.Net;
import com.campusnet.autologin.core.PageStore;
import com.campusnet.autologin.core.PortalProbe;
import com.campusnet.autologin.util.Str;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 用隐藏 WebView 真实登录一遍。
 * <p>
 * 为什么需要它：锐捷 ePortal 的门户页是纯 JS 渲染的，登录参数（queryString、
 * 可能的加密串）都由页面脚本算出来。HTTP 直连只能靠猜，猜错就是
 * {@code 203 Bad request(2)}。而 WebView 里跑的是**学校自己的那套 JS**，
 * 参数天然正确，我们只负责把账号密码填进去、按一下登录。
 * <p>
 * 这是兜底，不是首选：它比直连慢（要加载页面、等 JS），但在"直连怎么都
 * 不通"的情况下，它和真人手动登录的成功率基本一致。
 * <p>
 * 三个必须处理的细节：
 * <ul>
 *   <li>WebView 走系统默认路由，移动数据开着时会被切到蜂窝，校园内网地址
 *       直接 unreachable —— 所以全程把进程出口钉在 Wi-Fi 上，用完恢复。</li>
 *   <li>WebView 只能在主线程创建，而认证器跑在后台线程 —— 用 Handler 切换，
 *       结果通过轮询回传。</li>
 *   <li><b>不能用 {@code WebView.postDelayed} 派发注入</b>。没 attach 到窗口的
 *       View，post 出去的 Runnable 只会躺在 {@code ViewRootImpl} 的静态
 *       RunQueue 里，等 attach 时才执行；隐藏 WebView 永远不会 attach，
 *       于是脚本一次都跑不到，日志里连"填表结果"都没有。必须改用
 *       {@code Handler.postDelayed}。</li>
 * </ul>
 *
 * English: Logs in through a hidden WebView so the portal's own JavaScript builds
 * the parameters. Slower than a direct request, but as reliable as a human login.
 * Injection must be driven by a Handler, never `View.postDelayed`: a WebView that
 * is never attached parks the runnable in a static queue and it silently never runs.
 */
public final class WebAuth implements Authenticator {

    private static final int OVERALL_MS = 70_000;
    private static final int POLL_MS = 2000;
    private static final long FIRST_INJECT_DELAY = 1200;
    private static final long INJECT_EVERY_MS = 4000;
    private static final int INJECT_MAX = 9;
    private static final int REQ_LOG_MAX = 25;

    private static final Object JS_LOCK = new Object();
    private static String sInjectJs;
    private static String sScanJs;

    @Override
    public String name() {
        return "WebView 自动填表";
    }

    @Override
    public AuthResult login(AppConfig cfg) {
        Context ctx = Net.app();
        if (ctx == null) return AuthResult.fail("应用上下文未就绪");
        if (!cfg.hasCredentials()) return AuthResult.fail("账号或密码为空");

        Diag.reset("WebView 登录 "
                + new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(new Date()));
        Diag.kv("出口", Net.outletLabel());

        // ---- 起始地址：优先探测到的门户页，它就是拦截页，页面自己会跳 ----
        String start = Str.nz(cfg.portalUrl);
        if (Str.empty(start)) {
            PortalProbe.ProbeResult pr = PortalProbe.probe(cfg);
            if (pr.online) return AuthResult.ok("已通过校园网认证，无需登录");
            start = Str.nz(pr.portalUrl,
                    Str.nz(cfg.probeUrl, "http://connect.rom.miui.com/generate_204"));
        }
        Diag.kv("起始地址", start);
        Logx.i("WebView 打开 " + start);

        // ---- WebView 专属：把进程出口钉到 Wi-Fi，否则会被蜂窝抢走 ----
        boolean bound = Net.bindProcessToWifi(true);

        State st = new State();
        Handler main = new Handler(Looper.getMainLooper());

        try {
            if (!createWebView(ctx, main, start, cfg, st)) {
                return AuthResult.fail("无法创建 WebView（系统 WebView 组件可能被禁用）");
            }

            long deadline = System.currentTimeMillis() + OVERALL_MS;
            long nextInjectAt = System.currentTimeMillis() + FIRST_INJECT_DELAY;
            int polls = 0;
            int injects = 0;

            while (System.currentTimeMillis() < deadline) {
                if (st.destroyed.get()) break;
                sleep(POLL_MS);
                polls++;

                // 1) 权威信号：Wi-Fi 出口能不能出外网
                if (PortalProbe.isOnline(cfg)) {
                    Diag.kv("结果", "第 " + polls + " 次轮询：Wi-Fi 出口已通");
                    Logx.i("WebView 登录成功（出口已通）");
                    return AuthResult.ok("已通过校园网认证（网页登录方式）");
                }

                // 2) 该填表了就填一次。页面每次跳转都会把时间提前，所以新页面
                //    能立刻被填上，而不是等满 4 秒。
                if (injects < INJECT_MAX && System.currentTimeMillis() >= nextInjectAt) {
                    injects++;
                    nextInjectAt = System.currentTimeMillis() + INJECT_EVERY_MS;
                    inject(main, st, cfg);
                }

                // 3) 页面状态：把 URL 和可见文本读回来，既是取证也是判断依据
                readPage(main, st);

                String url = Str.nz(st.url);
                String text = Str.nz(st.text);
                if (!text.isEmpty()) {
                    Diag.kv("页面[" + polls + "]", Str.ellipsis(url, 100) + " | "
                            + Str.ellipsis(text.replaceAll("\\s+", " "), 200));
                }

                String fail = hitFailPhrase(text);
                if (fail != null && st.submitted.get() > 0) {
                    Logx.w("WebView 页面提示失败: " + fail);
                    Diag.kv("页面失败提示", fail);
                    readHtmlBlocking(main, st, 3000);
                    String saved = PageStore.save("weblogin-fail", Str.nz(st.html));
                    if (!saved.isEmpty()) Diag.kv("页面存档", saved);
                    return AuthResult.fail(fail, Str.ellipsis(text, 300));
                }
            }

            // 超时：把最后一次看到的页面留下来，这比"超时"两个字有用得多
            readHtmlBlocking(main, st, 4000);
            String saved = PageStore.save("weblogin", Str.nz(st.html));
            Diag.kv("页面存档", Str.empty(saved) ? "写入失败" : saved);
            Diag.kv("最后 URL", st.url);
            Diag.body("最后页面文本", st.text);
            Logx.w("WebView 登录超时，最后 URL=" + st.url);

            String extra = "最后停在 " + Str.ellipsis(st.url, 120);
            if (st.submitted.get() == 0) {
                extra += "；" + Str.nz(st.frameSummary, "页面上没找到可填的密码框");
            } else {
                if (!Str.empty(st.popup)) extra += "；页面提示：" + Str.ellipsis(st.popup, 80);
                if (!Str.empty(st.lastNet)) extra += "；页面请求：" + Str.ellipsis(st.lastNet, 120);
                if (Str.empty(st.popup) && Str.empty(st.lastNet)) {
                    extra += "；已点登录但页面既没提示也没发请求，可能是脚本没加载完就被点了";
                }
            }
            return AuthResult.fail("网页登录未能在 70 秒内完成", extra);
        } finally {
            destroy(main, st);
            if (bound) Net.bindProcessToWifi(false);
        }
    }

    // ==================================================================
    // WebView 生命周期
    // ==================================================================

    private static final class State {
        volatile WebView wv;
        volatile boolean created;
        final AtomicBoolean destroyed = new AtomicBoolean(false);
        volatile String url = "";
        volatile String text = "";
        volatile String html = "";
        volatile String frameSummary = "";
        /** 页面自己弹出来的提示文字（layer/toast/alert），失败时最有价值的一条 */
        volatile String popup = "";
        /** 页面最近发出的几个请求（地址 + 状态），用来判断"点了到底有没有发请求" */
        volatile String lastNet = "";
        final AtomicInteger submitted = new AtomicInteger();
        final AtomicInteger frameLogs = new AtomicInteger();
        final AtomicInteger reqLogs = new AtomicInteger();
    }

    private static boolean createWebView(final Context ctx, Handler main,
                                         final String start, final AppConfig cfg,
                                         final State st) {
        final CountDownLatch ready = new CountDownLatch(1);
        main.post(new Runnable() {
            @Override
            public void run() {
                try {
                    WebView wv = new WebView(ctx);
                    WebSettings s = wv.getSettings();
                    s.setJavaScriptEnabled(true);
                    s.setDomStorageEnabled(true);
                    s.setDatabaseEnabled(true);
                    s.setJavaScriptCanOpenWindowsAutomatically(true);
                    s.setUseWideViewPort(true);
                    s.setLoadWithOverviewMode(true);
                    s.setCacheMode(WebSettings.LOAD_NO_CACHE);
                    s.setUserAgentString(Http.UA_ANDROID);
                    s.setSupportMultipleWindows(false);
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
                    }
                    wv.setWebViewClient(new WebViewClient() {
                        @Override
                        public void onPageFinished(WebView view, String url) {
                            st.url = Str.nz(url);
                            Logx.i("WebView 页面加载完成: " + url);
                            // 新页面到了就立刻填一次，别等轮询周期
                            injectLater(view, cfg, st, FIRST_INJECT_DELAY);
                        }

                        /**
                         * 抓到真实提交地址的唯一办法。门户页点了登录之后到底往哪 POST、
                         * 带哪些 query，网络层看得一清二楚，比自己猜一百次都准。
                         */
                        @Override
                        public WebResourceResponse shouldInterceptRequest(WebView view,
                                                                          WebResourceRequest req) {
                            try {
                                String u = req.getUrl() == null ? "" : req.getUrl().toString();
                                String m = Str.nz(req.getMethod(), "GET");
                                String low = u.toLowerCase(Locale.US);
                                boolean interesting = "POST".equalsIgnoreCase(m)
                                        || low.contains("interface")
                                        || low.contains("eportal")
                                        || low.contains("login")
                                        || low.contains("a79");
                                if (interesting && st.reqLogs.incrementAndGet() <= REQ_LOG_MAX) {
                                    Logx.i("WebView 请求 " + m + " " + Str.ellipsis(u, 400));
                                    Diag.kv("请求 " + st.reqLogs.get(), m + " "
                                            + Str.ellipsis(u, 300));
                                }
                            } catch (Throwable ignore) {
                            }
                            return null;
                        }

                        @Override
                        public void onReceivedError(WebView view, WebResourceRequest req,
                                                    WebResourceError err) {
                            if (req != null && req.isForMainFrame()) {
                                Logx.w("WebView 主文档加载失败: " + err.getDescription()
                                        + " @ " + req.getUrl());
                            }
                        }
                    });
                    st.wv = wv;
                    st.created = true;
                    wv.loadUrl(start);
                } catch (Throwable t) {
                    Logx.e("创建 WebView 失败", t);
                } finally {
                    ready.countDown();
                }
            }
        });
        try {
            return ready.await(15, TimeUnit.SECONDS) && st.created;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static void destroy(Handler main, final State st) {
        if (st.destroyed.getAndSet(true)) return;
        main.post(new Runnable() {
            @Override
            public void run() {
                WebView wv = st.wv;
                st.wv = null;
                if (wv == null) return;
                try {
                    wv.stopLoading();
                    wv.loadUrl("about:blank");
                    wv.destroy();
                } catch (Throwable ignore) {
                }
            }
        });
    }

    // ==================================================================
    // 注入
    // ==================================================================

    /**
     * onPageFinished 里延迟填表。**必须走 Handler，不能用 WebView.postDelayed**，
     * 隐藏 WebView 没 attach 到窗口，View.post 的 Runnable 会一直排队不执行。
     */
    private static void injectLater(final WebView wv, final AppConfig cfg,
                                    final State st, long delayMs) {
        final Handler main = new Handler(Looper.getMainLooper());
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (st.destroyed.get() || st.wv != wv) return;
                inject(main, st, cfg);
            }
        }, delayMs);
    }

    private static void inject(Handler main, final State st, AppConfig cfg) {
        final String js = buildInjectJs(cfg);
        if (js == null) return;
        main.post(new Runnable() {
            @Override
            public void run() {
                final WebView wv = st.wv;
                if (wv == null) return;
                try {
                    wv.evaluateJavascript(js, new ValueCallback<String>() {
                        @Override
                        public void onReceiveValue(String v) {
                            onInjectResult(st, v);
                        }
                    });
                } catch (Throwable t) {
                    Logx.w("注入脚本失败: " + t.getMessage());
                }
            }
        });
    }

    private static void onInjectResult(State st, String raw) {
        String inner = unwrap(raw);
        if (Str.empty(inner)) return;
        String status = "";
        String frames = "";
        String popup = "";
        String netLog = "";
        try {
            JSONObject o = new JSONObject(inner);
            status = o.optString("s", "");
            frames = describeFrames(o.optJSONArray("f"));
            popup = o.optString("m", "");
            netLog = describeNet(o.optJSONArray("n"));
        } catch (Throwable t) {
            status = inner;
        }

        StringBuilder line = new StringBuilder("WebView 填表结果: ").append(status);
        if (!popup.isEmpty()) line.append("  页面提示=「").append(Str.ellipsis(popup, 120)).append("」");
        if (!netLog.isEmpty()) line.append("  请求=").append(Str.ellipsis(netLog, 200));
        if (!frames.isEmpty()) line.append(" | ").append(frames);
        Logx.i(line.toString());

        if (st.frameLogs.incrementAndGet() <= 4) {
            Diag.kv("填表", status);
            if (!popup.isEmpty()) Diag.kv("页面提示", popup);
            if (!netLog.isEmpty()) Diag.kv("页面请求", netLog);
            if (!frames.isEmpty()) Diag.kv("帧结构", frames);
        }
        if (!frames.isEmpty()) st.frameSummary = frames;
        if (!popup.isEmpty()) st.popup = popup;
        if (!netLog.isEmpty()) st.lastNet = netLog;

        if (status.startsWith("CLICK") || status.startsWith("FORM")
                || status.startsWith("ENTER") || status.startsWith("DONE")) {
            st.submitted.incrementAndGet();
        }
    }

    /**
     * 页面自己发出的请求摘要。
     * <p>
     * 这一行是"点了按钮却没反应"的唯一解释途径：真机上出现过连点三次、
     * 日志里一个请求都没有的情况，那时只能猜。有了它就能区分
     * 「页面把点击拦下了（有提示文字、无请求）」和
     * 「请求发了但失败了（有请求、状态 0 或报错）」。
     */
    private static String describeNet(JSONArray arr) {
        if (arr == null || arr.length() == 0) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < arr.length(); i++) {
            String s = arr.optString(i, "");
            if (Str.empty(s)) continue;
            if (sb.length() > 0) sb.append(" ／ ");
            sb.append(s);
        }
        return sb.toString();
    }

    /** 把 JS 回的 frame 数组压成一行人话：top n=3 pw=1 / 0 n=2 pw=1 … */
    private static String describeFrames(JSONArray arr) {
        if (arr == null || arr.length() == 0) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < arr.length() && i < 8; i++) {
            JSONObject f = arr.optJSONObject(i);
            if (f == null) continue;
            if (sb.length() > 0) sb.append(" ／ ");
            String p = f.optString("p", "?");
            int n = f.optInt("n", -1);
            int pw = f.optInt("pw", 0);
            if (n < 0) {
                sb.append(p).append(" 取不到内容（").append(Str.ellipsis(f.optString("u"), 80)).append("）");
            } else {
                sb.append(p).append(" input=").append(n).append(" 密码框=").append(pw);
                String t = f.optString("t", "");
                if (!Str.empty(t)) sb.append(" 「").append(Str.ellipsis(t, 40)).append("」");
            }
        }
        return sb.toString();
    }

    // ==================================================================
    // 脚本资源
    // ==================================================================

    private static String buildInjectJs(AppConfig cfg) {
        String tpl = asset("inject.js");
        if (tpl == null) {
            Logx.w("assets/inject.js 读取失败");
            return null;
        }
        String user = JSONObject.quote(Str.nz(cfg.username));
        String pass = JSONObject.quote(Str.nz(cfg.password));
        return tpl.replace("__USER__", user).replace("__PASS__", pass);
    }

    private static String asset(String name) {
        synchronized (JS_LOCK) {
            if ("inject.js".equals(name) && sInjectJs != null) return sInjectJs;
            if ("scan.js".equals(name) && sScanJs != null) return sScanJs;

            Context ctx = Net.app();
            if (ctx == null) return null;
            InputStream in = null;
            try {
                in = ctx.getAssets().open(name);
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                String s = new String(bos.toByteArray(), "UTF-8");
                if ("inject.js".equals(name)) sInjectJs = s;
                else sScanJs = s;
                return s;
            } catch (Throwable t) {
                Logx.w("读取 assets/" + name + " 失败: " + t.getMessage());
                return null;
            } finally {
                if (in != null) {
                    try {
                        in.close();
                    } catch (Throwable ignore) {
                    }
                }
            }
        }
    }

    /** evaluateJavascript 回传的是「JSON 字符串的 JSON 字符串」，这里剥掉外层引号。 */
    private static String unwrap(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty() || "null".equals(s)) return null;
        if (s.length() >= 2 && s.charAt(0) == '"' && s.charAt(s.length() - 1) == '"') {
            try {
                return new JSONObject("{\"x\":" + s + "}").getString("x");
            } catch (Throwable t) {
                return null;
            }
        }
        return s;
    }

    /** 把当前页面的 URL 与可见文本读回来（主线程执行，结果异步落到 State）。 */
    private static void readPage(Handler main, final State st) {
        main.post(new Runnable() {
            @Override
            public void run() {
                final WebView wv = st.wv;
                if (wv == null) return;
                try {
                    wv.evaluateJavascript(
                            "(function(){var t='';try{t=(document.body&&document.body.innerText)||'';}"
                                    + "catch(e){}"
                                    + "return JSON.stringify({u:document.location.href,"
                                    + "t:t.slice(0,800)});})()",
                            new ValueCallback<String>() {
                                @Override
                                public void onReceiveValue(String v) {
                                    apply(st, v, false);
                                }
                            });
                } catch (Throwable ignore) {
                }
            }
        });
    }

    /** 只在收尾时读一次整页 HTML——每次轮询都过桥 200KB 太浪费。 */
    private static String readHtmlBlocking(Handler main, final State st, long waitMs) {
        final String js = asset("scan.js");
        if (js == null) return "";
        final CountDownLatch latch = new CountDownLatch(1);
        final String[] out = {""};
        main.post(new Runnable() {
            @Override
            public void run() {
                WebView wv = st.wv;
                if (wv == null) {
                    latch.countDown();
                    return;
                }
                try {
                    wv.evaluateJavascript(js, new ValueCallback<String>() {
                        @Override
                        public void onReceiveValue(String v) {
                            apply(st, v, true);
                            out[0] = st.html;
                            latch.countDown();
                        }
                    });
                } catch (Throwable t) {
                    latch.countDown();
                }
            }
        });
        try {
            latch.await(waitMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return out[0];
    }

    private static void apply(State st, String raw, boolean withHtml) {
        String inner = unwrap(raw);
        if (inner == null) return;
        try {
            JSONObject o = new JSONObject(inner);
            st.url = o.optString("u", st.url);
            st.text = o.optString("t", st.text);
            if (withHtml) st.html = o.optString("h", st.html);
        } catch (Throwable ignore) {
        }
    }

    // ==================================================================

    private static final String[] FAIL_PHRASES = {
            "密码错误", "密码不正确", "密码有误",
            "账号或密码", "帐号或密码", "用户名或密码",
            "认证失败", "登录失败", "认证不成功", "登录不成功",
            "用户不存在", "账号不存在", "账号已停用", "账户已停用",
            "欠费", "已停机", "已被锁定", "在线数已满", "已达最大用户数"
    };

    private static String hitFailPhrase(String text) {
        if (Str.empty(text)) return null;
        for (String p : FAIL_PHRASES) {
            if (text.contains(p)) return "认证未通过：" + p;
        }
        return null;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
