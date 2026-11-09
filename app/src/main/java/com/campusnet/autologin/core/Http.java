package com.campusnet.autologin.core;

import com.campusnet.autologin.util.Str;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * 极简 HTTP 客户端。
 * <p>
 * 刻意不使用 OkHttp：校园网 portal 多为明文 HTTP + 自签证书 + 各种异常跳转，
 * 需要一个完全可控、不自动跟随重定向、能读原始 Location 的实现。
 *
 * English: Minimal HttpURLConnection wrapper. Manual redirect control, a tiny
 * cookie jar keyed by URL, and a charset fallback chain (declared charset ->
 * `<meta>` tag -> UTF-8 / GBK / ISO-8859-1 trial decoding). No third-party
 * network library, so behaviour stays predictable behind a captive portal.
 */
public final class Http {

    public static final String UA_ANDROID =
            "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";

    private static final Map<String, String> COOKIES = new LinkedHashMap<>();
    private static volatile boolean insecureTls = false;

    /**
     * 连接工厂：决定请求从哪个网络出去。
     * <p>
     * 默认走系统默认路由，但这在「Wi-Fi 未认证 + 移动数据开着」时是错的——
     * 系统会把请求交给蜂窝，于是探测结果变成"流量通不通"，与校园网认证无关。
     * 由 {@link Net} 注入一个绑定 Wi-Fi 网卡的实现来纠正。
     */
    public interface Connector {
        java.net.URLConnection open(URL url) throws java.io.IOException;
    }

    private static volatile Connector connector;

    /** 设置出口绑定；传 null 恢复为系统默认路由。 */
    public static void setConnector(Connector c) {
        connector = c;
    }

    private Http() {
    }

    /** 是否放开 https 证书校验（校园网自签证书场景）。默认关闭。 */
    public static void setInsecureTls(boolean v) {
        insecureTls = v;
    }

    public static void clearCookies() {
        synchronized (COOKIES) {
            COOKIES.clear();
        }
    }

    public static String cookieHeader(String url) {
        String host = hostOf(url);
        synchronized (COOKIES) {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, String> e : COOKIES.entrySet()) {
                if (e.getKey().startsWith(host + "|")) {
                    if (sb.length() > 0) sb.append("; ");
                    sb.append(e.getKey().substring(host.length() + 1)).append('=').append(e.getValue());
                }
            }
            return sb.toString();
        }
    }

    private static void storeCookies(String url, HttpURLConnection c) {
        String host = hostOf(url);
        try {
            Map<String, List<String>> h = c.getHeaderFields();
            for (Map.Entry<String, List<String>> e : h.entrySet()) {
                if (e.getKey() == null || !e.getKey().toLowerCase(Locale.US).equals("set-cookie")) continue;
                for (String raw : e.getValue()) {
                    if (raw == null) continue;
                    int eq = raw.indexOf('=');
                    if (eq <= 0) continue;
                    String name = raw.substring(0, eq).trim();
                    int semi = raw.indexOf(';', eq);
                    String value = semi < 0 ? raw.substring(eq + 1) : raw.substring(eq + 1, semi);
                    synchronized (COOKIES) {
                        COOKIES.put(host + "|" + name, value.trim());
                    }
                }
            }
        } catch (Throwable ignore) {
        }
    }

    private static String hostOf(String url) {
        try {
            return new URL(url).getHost();
        } catch (Throwable t) {
            return "?";
        }
    }

    public static final class Result {
        public int code;
        public String url = "";
        public String location = "";
        public String body = "";
        public String error = "";
        public final Map<String, String> headers = new HashMap<>();

        public boolean isRedirect() {
            return code == 301 || code == 302 || code == 303 || code == 307 || code == 308;
        }

        public boolean ok() {
            return code > 0 && error.isEmpty();
        }

        @Override
        public String toString() {
            return "HTTP " + code + (Str.empty(location) ? "" : " -> " + location)
                    + (Str.empty(error) ? "" : " err=" + error);
        }
    }

    public static Result get(String url, Map<String, String> headers, int timeoutMs) {
        return request("GET", url, null, null, headers, timeoutMs);
    }

    public static Result postForm(String url, Map<String, String> form,
                                  Map<String, String> headers, int timeoutMs) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : form.entrySet()) {
            if (sb.length() > 0) sb.append('&');
            sb.append(Str.urlEncode(e.getKey())).append('=').append(Str.urlEncode(e.getValue()));
        }
        return request("POST", url, sb.toString(), "application/x-www-form-urlencoded", headers, timeoutMs);
    }

    public static Result postRaw(String url, String body, String contentType,
                                 Map<String, String> headers, int timeoutMs) {
        return request("POST", url, body, contentType, headers, timeoutMs);
    }

    private static Result request(String method, String url, String body, String contentType,
                                  Map<String, String> headers, int timeoutMs) {
        Result r = new Result();
        r.url = url;
        HttpURLConnection c = null;
        try {
            URL u = new URL(url);
            Connector cn = connector;
            if (cn != null) {
                c = (HttpURLConnection) cn.open(u);
            } else {
                c = (HttpURLConnection) u.openConnection();
            }
            if (c instanceof HttpsURLConnection && insecureTls) {
                applyLooseTls((HttpsURLConnection) c);
            }
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(timeoutMs);
            c.setReadTimeout(timeoutMs);
            c.setRequestMethod(method);
            c.setRequestProperty("User-Agent", UA_ANDROID);
            c.setRequestProperty("Accept", "*/*");
            c.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9");
            c.setRequestProperty("Connection", "close");

            String cookie = cookieHeader(url);
            if (!cookie.isEmpty()) c.setRequestProperty("Cookie", cookie);

            if (headers != null) {
                for (Map.Entry<String, String> e : headers.entrySet()) {
                    if (e.getKey() != null && e.getValue() != null) {
                        c.setRequestProperty(e.getKey(), e.getValue());
                    }
                }
            }

            if (body != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type",
                        Str.nz(contentType, "application/x-www-form-urlencoded"));
                byte[] payload = Str.bytes(body, "UTF-8");
                c.setFixedLengthStreamingMode(payload.length);
                OutputStream os = c.getOutputStream();
                try {
                    os.write(payload);
                } finally {
                    os.close();
                }
            }

            c.connect();
            r.code = c.getResponseCode();
            r.location = Str.nz(c.getHeaderField("Location"));
            storeCookies(url, c);

            if (c.getHeaderFields() != null) {
                for (Map.Entry<String, List<String>> e : c.getHeaderFields().entrySet()) {
                    if (e.getKey() == null || e.getValue() == null || e.getValue().isEmpty()) continue;
                    r.headers.put(e.getKey(), e.getValue().get(0));
                }
            }

            // 3xx 通常没有 body，但仍尝试读取，某些 portal 会在 302 里塞提示
            InputStream is = null;
            try {
                is = r.code >= 400 ? c.getErrorStream() : c.getInputStream();
            } catch (Throwable t) {
                is = c.getErrorStream();
            }
            if (is != null) {
                byte[] raw = readAll(is, 512 * 1024);
                r.body = decode(raw, r.headers.get("Content-Type"));
            }
        } catch (Throwable t) {
            r.error = t.getClass().getSimpleName() + ": " + t.getMessage();
        } finally {
            if (c != null) {
                try {
                    c.disconnect();
                } catch (Throwable ignore) {
                }
            }
        }
        return r;
    }

    private static byte[] readAll(InputStream is, int limit) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        try {
            while ((n = is.read(buf)) > 0) {
                bos.write(buf, 0, n);
                if (bos.size() > limit) break;
            }
        } catch (Throwable ignore) {
        } finally {
            try {
                is.close();
            } catch (Throwable ignore) {
            }
        }
        return bos.toByteArray();
    }

    /**
     * 先按 header/meta 声明的字符集解码；解不出来再按 GBK 兜底。
     * 国内 portal 大量使用 GBK 且 header 不写 charset。
     */
    public static String decode(byte[] raw, String contentType) {
        if (raw == null || raw.length == 0) return "";
        String cs = null;
        if (contentType != null) {
            String lower = contentType.toLowerCase(Locale.US);
            int i = lower.indexOf("charset=");
            if (i >= 0) {
                cs = lower.substring(i + 8).split("[;\\s]")[0].replace("\"", "").trim();
            }
        }
        if (cs == null) {
            String head = new String(raw, 0, Math.min(raw.length, 2048), Charset.forName("ISO-8859-1"));
            String lower = head.toLowerCase(Locale.US);
            int i = lower.indexOf("charset=");
            if (i >= 0) {
                int end = i + 8;
                while (end < lower.length() && "abcdefghijklmnopqrstuvwxyz0123456789-_".indexOf(lower.charAt(end)) >= 0) {
                    end++;
                }
                if (end > i + 8) cs = lower.substring(i + 8, end);
            }
        }
        Charset[] order = {Charset.forName("UTF-8"), Charset.forName("GBK"), Charset.forName("ISO-8859-1")};
        if (cs != null) {
            try {
                Charset declared = Charset.forName(cs);
                List<Charset> list = new ArrayList<>();
                list.add(declared);
                for (Charset o : order) if (!o.equals(declared)) list.add(o);
                order = list.toArray(new Charset[0]);
            } catch (Throwable ignore) {
            }
        }
        for (Charset c : order) {
            String s = new String(raw, c);
            if (s.indexOf('\uFFFD') < 0) return s;
        }
        return new String(raw, Charset.forName("UTF-8"));
    }

    private static void applyLooseTls(HttpsURLConnection c) {
        try {
            TrustManager[] tm = new TrustManager[]{new X509TrustManager() {
                @Override
                public void checkClientTrusted(X509Certificate[] chain, String authType) {
                }

                @Override
                public void checkServerTrusted(X509Certificate[] chain, String authType) {
                }

                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            }};
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, tm, new java.security.SecureRandom());
            c.setSSLSocketFactory(ctx.getSocketFactory());
            c.setHostnameVerifier(new HostnameVerifier() {
                @Override
                public boolean verify(String hostname, SSLSession session) {
                    return true;
                }
            });
        } catch (Throwable ignore) {
        }
    }
}
