package com.campusnet.autologin.core;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;

import com.campusnet.autologin.util.Str;

import java.io.IOException;
import java.net.NetworkInterface;
import java.net.URL;
import java.util.Collections;
import java.util.List;

/**
 * 网络出口判定。
 * <p>
 * 这里解决的问题很具体：手机同时开着移动数据时，Android 会把 socket 默认走的
 * 网络切到"评分更高"的那条。校园 Wi-Fi 因为被认证网关拦截，系统会判它
 * 「无互联网」，于是默认出口变成蜂窝。此时再去请求 generate_204 必然成功，
 * 于是「能不能上网」就等于「流量通不通」，跟校园网认证状态毫无关系——
 * 这就是「开着流量永远显示已登录」的成因。
 * <p>
 * 所以判定绝对不能看"能不能上网"，只能看"从 Wi-Fi 网卡出去能不能上网"。
 * 办法是把请求强制绑定到 Wi-Fi 的 {@link Network} 上，绕开默认路由。
 *
 * English: Network-egress decisions. Asks ConnectivityManager for the Wi-Fi
 * Network object and can bind the whole process to it, so probes cannot silently
 * fall back to cellular while the campus Wi-Fi is still unauthenticated.
 */
public final class Net {

    private static volatile Context appCtx;
    private static volatile Network wifiCache;
    private static ConnectivityManager.NetworkCallback callback;
    private static volatile boolean inited = false;
    /** 进程级出口是否已被 {@link #bindProcessToWifi(boolean)} 绑到 Wi-Fi */
    private static volatile boolean processBound = false;

    private Net() {
    }

    /** 已初始化的 Application Context，未初始化时为 null。 */
    public static Context app() {
        return appCtx;
    }

    /**
     * 幂等初始化：挂上 Wi-Fi 专用网络回调，并把 Http 的出口钉死在 Wi-Fi 上。
     * Application 与前台服务都会调，重复调用无副作用。
     */
    public static synchronized void init(Context ctx) {
        if (ctx != null) {
            Context app = ctx.getApplicationContext();
            appCtx = app != null ? app : ctx;
        }
        if (inited) return;
        inited = true;

        // 所有校园网请求强制走 Wi-Fi：拿不到 Wi-Fi 直接失败，
        // 而不是让流量兜底后拿到一个假的"成功"。
        Http.setConnector(new Http.Connector() {
            @Override
            public java.net.URLConnection open(URL url) throws IOException {
                Network n = wifi();
                if (n == null) {
                    throw new IOException("未连接 Wi-Fi");
                }
                return n.openConnection(url);
            }
        });

        registerCallback();
    }

    private static void registerCallback() {
        final ConnectivityManager cm = cm();
        if (cm == null) return;
        try {
            NetworkRequest req = new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .build();
            callback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    wifiCache = network;
                }

                @Override
                public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
                    if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                        wifiCache = network;
                    }
                }

                @Override
                public void onLost(Network network) {
                    if (wifiCache != null && wifiCache.equals(network)) {
                        wifiCache = null;
                    }
                }
            };
            cm.registerNetworkCallback(req, callback);
        } catch (Throwable t) {
            Logx.w("注册 Wi-Fi 网络回调失败，改用实时查询: " + t.getMessage());
        }
    }

    private static ConnectivityManager cm() {
        Context c = appCtx;
        if (c == null) return null;
        try {
            return (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 当前可用的 Wi-Fi 网络。返回 null 表示没有连接任何 Wi-Fi。
     * <p>
     * 注意这里**不要求** NET_CAPABILITY_VALIDATED：未认证的校园 Wi-Fi 恰恰
     * 是没通过系统联网校验的那条，把它排除掉就等于永远找不到校园网。
     */
    public static Network wifi() {
        Network cached = wifiCache;
        if (cached != null) {
            // 缓存可能是刚断开的旧句柄：拿它去 openConnection 会抛
            // "Network is unreachable"，进而被误读成"认证失败"。
            if (isAlive(cached)) return cached;
            wifiCache = null;
        }
        Network found = discover();
        if (found != null) wifiCache = found;
        return found;
    }

    private static boolean isAlive(Network n) {
        ConnectivityManager cm = cm();
        if (cm == null) return false;
        try {
            NetworkCapabilities c = cm.getNetworkCapabilities(n);
            return c != null && c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
        } catch (Throwable t) {
            return false;
        }
    }

    private static Network discover() {
        ConnectivityManager cm = cm();
        if (cm == null) return null;
        Network best = null;
        int bestScore = -1;
        try {
            for (Network n : cm.getAllNetworks()) {
                NetworkCapabilities c = cm.getNetworkCapabilities(n);
                if (c == null) continue;
                if (!c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue;
                // VPN 跑在 Wi-Fi 上时会出现叠加 transport，跳过，否则会把请求塞进隧道
                if (c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue;
                int score = 1;
                if (c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) score += 2;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                        && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                    score += 4;
                }
                if (score > bestScore) {
                    bestScore = score;
                    best = n;
                }
            }
        } catch (Throwable t) {
            Logx.w("枚举网络失败: " + t.getMessage());
        }
        return best;
    }

    /** 是否有 Wi-Fi 连接。校园网认证的前置条件。 */
    public static boolean hasWifi() {
        return wifi() != null;
    }

    /**
     * 系统默认出口（也就是没绑定网络时会走的那条）是不是 Wi-Fi。
     * 用来解释「为什么开着流量会误判」，仅作诊断展示。
     */
    public static boolean defaultIsWifi() {
        ConnectivityManager cm = cm();
        if (cm == null) return false;
        try {
            Network active = cm.getActiveNetwork();
            if (active == null) return false;
            NetworkCapabilities c = cm.getNetworkCapabilities(active);
            return c != null && c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 是否正在使用移动数据（用于把"流量兜底"这件事讲清楚）。 */
    public static boolean hasCellular() {
        ConnectivityManager cm = cm();
        if (cm == null) return false;
        try {
            for (Network n : cm.getAllNetworks()) {
                NetworkCapabilities c = cm.getNetworkCapabilities(n);
                if (c != null && c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) return true;
            }
        } catch (Throwable ignore) {
        }
        return false;
    }

    /**
     * 当前 Wi-Fi 的 IPv4 地址，取不到时返回空串。
     * <p>
     * 锐捷门户的 queryString 里 {@code wlanuserip} 必须是**这台设备**的地址，
     * 网关靠它把认证请求和会话对上。拿不到就只能靠页面自己给的参数。
     */
    public static String wifiIp() {
        Network n = wifi();
        ConnectivityManager cm = cm();
        if (n == null || cm == null) return "";
        try {
            android.net.LinkProperties lp = cm.getLinkProperties(n);
            if (lp == null) return "";
            for (android.net.LinkAddress la : lp.getLinkAddresses()) {
                java.net.InetAddress a = la.getAddress();
                if (a instanceof java.net.Inet4Address) {
                    String s = a.getHostAddress();
                    if (!Str.empty(s)) return s;
                }
            }
        } catch (Throwable t) {
            Logx.w("读取 Wi-Fi 地址失败: " + t.getMessage());
        }
        return "";
    }

    /**
     * 把整个进程的默认出口临时钉到 Wi-Fi 上（或恢复）。
     * <p>
     * {@link Http} 是靠 {@code network.openConnection()} 逐请求绑定的，但
     * WebView 不走这条路——它用系统默认路由。移动数据开着时系统会认为
     * 未认证的校园 Wi-Fi「没有互联网」，把默认出口切到蜂窝，于是 WebView
     * 打开 {@code 100.64.x.x} 这种校园内网地址直接 unreachable。
     * 这里用进程级绑定把这件事掰回来，用完必须恢复，否则会影响别的应用逻辑。
     *
     * @return 是否真的绑定/解绑成功
     */
    public static boolean bindProcessToWifi(boolean on) {
        ConnectivityManager cm = cm();
        if (cm == null) return false;
        try {
            if (on) {
                Network n = wifi();
                if (n == null) return false;
                boolean ok = cm.bindProcessToNetwork(n);
                processBound = ok;
                Logx.i("WebView 出口已绑定到 Wi-Fi " + (ok ? "成功" : "失败"));
                return ok;
            }
            if (!processBound) return true;
            processBound = false;
            boolean ok = cm.bindProcessToNetwork(null);
            Logx.i("WebView 出口绑定已恢复");
            return ok;
        } catch (Throwable t) {
            Logx.w("进程网络绑定失败: " + t.getMessage());
            return false;
        }
    }

    /** 当前 Wi-Fi 的 SSID（可能带引号）；拿不到时返回空串。 */
    public static String ssid() {
        Context ctx = appCtx;
        if (ctx == null) return "";
        try {
            WifiManager wm = (WifiManager) ctx.getSystemService(Context.WIFI_SERVICE);
            if (wm == null) return "";
            WifiInfo info = wm.getConnectionInfo();
            if (info == null) return "";
            String s = Str.nz(info.getSSID());
            if (s.contains("<unknown")) return "";
            return s.replace("\"", "").trim();
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * 给界面用的一句话出口描述。
     * 例：「Campus-WiFi · 移动数据同时开启」/「未连接 Wi-Fi」。
     */
    public static String outletLabel() {
        if (!hasWifi()) {
            return hasCellular() ? "未连接 Wi-Fi（当前只有移动数据）" : "未连接 Wi-Fi";
        }
        String s = ssid();
        String base = s.isEmpty() ? "Wi-Fi" : s;
        if (hasCellular()) return base + " · 移动数据同时开启";
        return base;
    }

    /**
     * 诊断：列出各网卡与 IPv4，排查"请求到底从哪出去的"。
     * 只读、无副作用，日志里输出。
     */
    public static String dump() {
        StringBuilder sb = new StringBuilder();
        sb.append("Wi-Fi=").append(hasWifi() ? "有" : "无")
                .append(" 默认出口=").append(defaultIsWifi() ? "Wi-Fi" : "非 Wi-Fi")
                .append(" 移动数据=").append(hasCellular() ? "有" : "无")
                .append(" SSID=").append(Str.empty(ssid()) ? "(未知)" : ssid());
        try {
            List<NetworkInterface> list = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface ni : list) {
                if (!ni.isUp()) continue;
                String name = ni.getName();
                if (!name.startsWith("wlan") && !name.startsWith("rmnet")
                        && !name.startsWith("ap") && !name.startsWith("tun")) {
                    continue;
                }
                sb.append("\n  ").append(name);
                List<java.net.InetAddress> addrs = Collections.list(ni.getInetAddresses());
                for (java.net.InetAddress a : addrs) {
                    if (a instanceof java.net.Inet4Address) sb.append(' ').append(a.getHostAddress());
                }
            }
        } catch (Throwable ignore) {
        }
        return sb.toString();
    }
}
