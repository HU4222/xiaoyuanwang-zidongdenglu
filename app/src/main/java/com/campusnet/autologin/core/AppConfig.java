package com.campusnet.autologin.core;

import android.content.Context;
import android.content.SharedPreferences;

import com.campusnet.autologin.util.Str;

import org.json.JSONObject;

/**
 * 全部可调参数集中在这里，序列化成一个 JSON 字符串存 SharedPreferences，
 * 这样导入/导出配置、Root 钩子读取、Xposed 模块跨进程读取都用同一份结构。
 *
 * English: Every tunable in one class, serialised to a single JSON string in
 * SharedPreferences so import/export, root hooks and the Xposed module all read
 * the same structure.
 */
public final class AppConfig {

    public static final String PREF_NAME = "campusnet_prefs";
    private static final String KEY_JSON = "config_json";

    // ---- 总开关 ----
    /** 守护是否启用（与 WatchdogService 的实际运行状态保持一致） */
    public boolean enabled = false;

    // ---- 账号 ----
    public String username = "";
    public String password = "";

    // ---- 协议 ----
    /** auto | srun | eportal | drcom | generic */
    public String provider = "auto";

    // ---- 探测与提交 ----
    /** 连通性探测地址：返回 204 表示已联网（未认证时会被劫持成 302/200 门户页） */
    public String probeUrl = "http://connect.rom.miui.com/generate_204";
    /** 门户页地址，留空则靠探测得到 */
    public String portalUrl = "";
    /** 登录提交地址，留空则从门户页 <form action> 解析 */
    public String authUrl = "";
    /** 自定义表单模板，如 user=${user}&pass=${pass}；留空则自动识别字段 */
    public String formTemplate = "";
    /** 手动指定用户名字段名（留空自动识别） */
    public String userField = "";
    /** 手动指定密码字段名（留空自动识别） */
    public String passField = "";
    /** 额外固定参数，形如 a=1&b=2，会并入表单 */
    public String extraParams = "";
    /** 判定成功的关键字，逗号分隔；留空用内置集合 */
    public String successKeys = "";
    /** 判定失败的关键字，逗号分隔 */
    public String failKeys = "";

    // ---- 深澜 srun 专有 ----
    public String srunAcId = "1";
    public String srunType = "1";
    /** srun_bx1（默认算法）| plain */
    public String srunEnc = "srun_bx1";

    // ---- 网络与节奏 ----
    public int timeoutMs = 8000;
    /** 心跳间隔，秒。校园网空闲 2 分钟掉线，这里默认 60 秒 */
    public int intervalSec = 60;
    public int retryBackoffSec = 30;
    public int retryMax = 3;
    public boolean onlyWifi = true;
    /** 只在这些 WiFi 上工作，逗号分隔的 SSID 片段；留空不限制 */
    public String wifiSsidFilter = "";
    public boolean insecureTls = false;

    // ---- 权限增强 ----
    public boolean useRoot = false;
    public String rootPreCmd = "";
    public String rootPostCmd = "";
    public boolean rootWatchdog = false;

    // ---- 通知与自启 ----
    public boolean notify = true;
    public boolean autoStartOnBoot = true;

    // ---- Xposed ----
    public String xposedTarget = "";
    public boolean xposedAutoFill = true;
    public boolean xposedAutoClick = true;

    // ---- 最近状态（只读展示） ----
    public String lastLoginAt = "";
    public String lastResult = "";

    public boolean hasCredentials() {
        return !Str.empty(username) && !Str.empty(password);
    }

    // ------------------------------------------------------------------
    // 序列化
    // ------------------------------------------------------------------

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("enabled", enabled);
            o.put("username", username);
            o.put("password", password);
            o.put("provider", provider);
            o.put("probeUrl", probeUrl);
            o.put("portalUrl", portalUrl);
            o.put("authUrl", authUrl);
            o.put("formTemplate", formTemplate);
            o.put("userField", userField);
            o.put("passField", passField);
            o.put("extraParams", extraParams);
            o.put("successKeys", successKeys);
            o.put("failKeys", failKeys);
            o.put("srunAcId", srunAcId);
            o.put("srunType", srunType);
            o.put("srunEnc", srunEnc);
            o.put("timeoutMs", timeoutMs);
            o.put("intervalSec", intervalSec);
            o.put("retryBackoffSec", retryBackoffSec);
            o.put("retryMax", retryMax);
            o.put("onlyWifi", onlyWifi);
            o.put("wifiSsidFilter", wifiSsidFilter);
            o.put("insecureTls", insecureTls);
            o.put("useRoot", useRoot);
            o.put("rootPreCmd", rootPreCmd);
            o.put("rootPostCmd", rootPostCmd);
            o.put("rootWatchdog", rootWatchdog);
            o.put("notify", notify);
            o.put("autoStartOnBoot", autoStartOnBoot);
            o.put("xposedTarget", xposedTarget);
            o.put("xposedAutoFill", xposedAutoFill);
            o.put("xposedAutoClick", xposedAutoClick);
            o.put("lastLoginAt", lastLoginAt);
            o.put("lastResult", lastResult);
        } catch (Throwable ignore) {
        }
        return o;
    }

    public static AppConfig fromJson(String json) {
        AppConfig c = new AppConfig();
        if (Str.empty(json)) return c;
        try {
            JSONObject o = new JSONObject(json);
            c.enabled = o.optBoolean("enabled", c.enabled);
            c.username = o.optString("username", c.username);
            c.password = o.optString("password", c.password);
            c.provider = o.optString("provider", c.provider);
            c.probeUrl = o.optString("probeUrl", c.probeUrl);
            c.portalUrl = o.optString("portalUrl", c.portalUrl);
            c.authUrl = o.optString("authUrl", c.authUrl);
            c.formTemplate = o.optString("formTemplate", c.formTemplate);
            c.userField = o.optString("userField", c.userField);
            c.passField = o.optString("passField", c.passField);
            c.extraParams = o.optString("extraParams", c.extraParams);
            c.successKeys = o.optString("successKeys", c.successKeys);
            c.failKeys = o.optString("failKeys", c.failKeys);
            c.srunAcId = o.optString("srunAcId", c.srunAcId);
            c.srunType = o.optString("srunType", c.srunType);
            c.srunEnc = o.optString("srunEnc", c.srunEnc);
            c.timeoutMs = o.optInt("timeoutMs", c.timeoutMs);
            c.intervalSec = Math.max(15, o.optInt("intervalSec", c.intervalSec));
            c.retryBackoffSec = o.optInt("retryBackoffSec", c.retryBackoffSec);
            c.retryMax = o.optInt("retryMax", c.retryMax);
            c.onlyWifi = o.optBoolean("onlyWifi", c.onlyWifi);
            c.wifiSsidFilter = o.optString("wifiSsidFilter", c.wifiSsidFilter);
            c.insecureTls = o.optBoolean("insecureTls", c.insecureTls);
            c.useRoot = o.optBoolean("useRoot", c.useRoot);
            c.rootPreCmd = o.optString("rootPreCmd", c.rootPreCmd);
            c.rootPostCmd = o.optString("rootPostCmd", c.rootPostCmd);
            c.rootWatchdog = o.optBoolean("rootWatchdog", c.rootWatchdog);
            c.notify = o.optBoolean("notify", c.notify);
            c.autoStartOnBoot = o.optBoolean("autoStartOnBoot", c.autoStartOnBoot);
            c.xposedTarget = o.optString("xposedTarget", c.xposedTarget);
            c.xposedAutoFill = o.optBoolean("xposedAutoFill", c.xposedAutoFill);
            c.xposedAutoClick = o.optBoolean("xposedAutoClick", c.xposedAutoClick);
            c.lastLoginAt = o.optString("lastLoginAt", c.lastLoginAt);
            c.lastResult = o.optString("lastResult", c.lastResult);
        } catch (Throwable ignore) {
        }
        return c;
    }

    public void save(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        p.edit().putString(KEY_JSON, toJson().toString()).apply();
    }

    public static AppConfig load(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        return fromJson(p.getString(KEY_JSON, ""));
    }

    /** 供 Xposed 模块跨进程读取（不需要 Context）。 */
    public static String rawJson(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        return p.getString(KEY_JSON, "");
    }
}
