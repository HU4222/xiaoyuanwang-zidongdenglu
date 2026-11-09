package com.campusnet.autologin.ui;

import android.Manifest;
import android.animation.ValueAnimator;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.campusnet.autologin.BuildConfig;
import com.campusnet.autologin.R;
import com.campusnet.autologin.auth.AuthResult;
import com.campusnet.autologin.core.AppConfig;
import com.campusnet.autologin.core.Logx;
import com.campusnet.autologin.core.Net;
import com.campusnet.autologin.core.PortalProbe;
import com.campusnet.autologin.engine.LoginEngine;
import com.campusnet.autologin.engine.WatchdogService;
import com.campusnet.autologin.util.Str;
import com.google.android.material.materialswitch.MaterialSwitch;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity implements WatchdogService.Listener {

    private static final int REQ_NOTI = 101;

    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private AppConfig cfg;

    private EditText etUser;
    private EditText etPass;
    private TextView tvState;
    private TextView tvDetail;
    private TextView tvLast;
    private TextView tvMode;
    private TextView tvKvProvider;
    private TextView tvKvInterval;
    private TextView tvFooter;
    private TextView tvWatchdogHint;
    private View dotStatus;
    private View dotPing;
    private MaterialSwitch swWatchdog;
    private com.google.android.material.button.MaterialButton btnProbe;
    /** 主按钮是 AppCompatButton（自定义渐变背景），类型必须跟着改，否则运行时 ClassCastException */
    private Button btnLogin;
    private ValueAnimator pingAnim;

    private boolean suppressSwitch = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Logx.init(this);
        cfg = AppConfig.load(this);

        bindViews();
        fillFromConfig();
        wireActions();
        refreshState();
        Anim.viewIn(findViewById(R.id.heroBlock));
        maybeAskNotifications();
    }

    private void bindViews() {
        etUser = findViewById(R.id.etUser);
        etPass = findViewById(R.id.etPass);
        tvState = findViewById(R.id.tvState);
        tvDetail = findViewById(R.id.tvDetail);
        tvLast = findViewById(R.id.tvLast);
        tvMode = findViewById(R.id.tvMode);
        tvKvProvider = findViewById(R.id.tvKvProvider);
        tvKvInterval = findViewById(R.id.tvKvInterval);
        tvFooter = findViewById(R.id.tvFooter);
        tvWatchdogHint = findViewById(R.id.tvWatchdogHint);
        dotStatus = findViewById(R.id.dotStatus);
        dotPing = findViewById(R.id.dotPing);
        swWatchdog = findViewById(R.id.swWatchdog);
        btnProbe = findViewById(R.id.btnProbe);
        btnLogin = findViewById(R.id.btnLogin);
    }

    private void fillFromConfig() {
        etUser.setText(cfg.username);
        etPass.setText(cfg.password);
        suppressSwitch = true;
        swWatchdog.setChecked(cfg.enabled && WatchdogService.isRunning());
        suppressSwitch = false;
    }

    private void wireActions() {
        btnProbe.setOnClickListener(v -> doProbe());
        btnLogin.setOnClickListener(v -> doLogin());

        swWatchdog.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (suppressSwitch) return;
            persist();
            cfg.enabled = isChecked;
            cfg.save(this);
            if (isChecked) {
                maybeAskNotifications();
                WatchdogService.start(this);
                new AlertDialog.Builder(this)
                        .setTitle("让守护活下来")
                        .setMessage("系统会不定期冻结后台应用。建议做两件事：\n\n"
                                + "1. 在系统设置里允许本应用「自启动」和「后台运行」\n"
                                + "2. 把本应用从电池优化白名单外移出（点下方按钮直接跳转）\n\n"
                                + "有 root 的话，高级设置里有个一键脚本能把这些一次性配好。")
                        .setPositiveButton("去设置", (d, w) -> openBatterySettings())
                        .setNegativeButton("稍后", null)
                        .show();
            } else {
                WatchdogService.stop(this);
            }
            refreshState();
        });

        findViewById(R.id.btnSettings).setOnClickListener(v -> {
            persist();
            startActivity(new Intent(this, SettingsActivity.class));
        });
        findViewById(R.id.btnLog).setOnClickListener(v -> {
            persist();
            startActivity(new Intent(this, LogActivity.class));
        });
    }

    // ------------------------------------------------------------------

    @Override
    protected void onStart() {
        super.onStart();
        WatchdogService.addListener(this);
        refreshState();
    }

    @Override
    protected void onStop() {
        WatchdogService.removeListener(this);
        // 循环动画必须在这里收掉，否则退到后台还在空转
        Anim.stop(pingAnim);
        pingAnim = null;
        super.onStop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        cfg = AppConfig.load(this);
        if (!etUser.getText().toString().equals(cfg.username)) {
            etUser.setText(cfg.username);
        }
        refreshState();
    }

    @Override
    protected void onPause() {
        persist();
        super.onPause();
    }

    private void persist() {
        String u = etUser.getText().toString().trim();
        String p = etPass.getText().toString();
        boolean changed = !u.equals(cfg.username) || !p.equals(cfg.password);
        cfg.username = u;
        cfg.password = p;
        if (changed) {
            cfg.save(this);
            Logx.i("已保存账号信息");
        }
    }

    // ------------------------------------------------------------------
    // 状态展示
    // ------------------------------------------------------------------

    private void refreshState() {
        boolean running = WatchdogService.isRunning();
        Boolean online = WatchdogService.stateOnline();
        boolean hasWifi = Net.hasWifi();

        if (!hasWifi) {
            // 没有 Wi-Fi 时，"能不能上网"完全由移动数据决定，与校园网认证无关。
            // 这里必须盖掉服务上报的 online，否则就会出现流量一通就显示已登录。
            tvState.setText(running ? "未连 Wi-Fi" : "未启动");
            tvDetail.setText(Net.hasCellular()
                    ? "当前只有移动数据，流量不经过校园网关；连上校园 Wi-Fi 后自动开始"
                    : "连上校园 Wi-Fi 后自动开始认证");
            setHealth(R.drawable.bg_dot_warn, 0,
                    "无 Wi-Fi", R.drawable.bg_pill_warn, R.color.warn);
        } else if (running) {
            String text = WatchdogService.stateText();
            tvState.setText(Str.empty(text) ? "守护中" : text);
            tvDetail.setText("每 " + cfg.intervalSec + " 秒检查一次，掉线自动重新认证 · "
                    + Net.outletLabel());
            if (Boolean.TRUE.equals(online)) {
                setHealth(R.drawable.bg_dot_ok, R.drawable.bg_ping_ok,
                        "已认证", R.drawable.bg_pill_ok, R.color.ok);
            } else if (Boolean.FALSE.equals(online)) {
                setHealth(R.drawable.bg_dot_bad, R.drawable.bg_ping_bad,
                        "离线", R.drawable.bg_pill_danger, R.color.danger);
            } else {
                setHealth(R.drawable.bg_dot_idle, 0,
                        "检测中", R.drawable.bg_pill_soft, R.color.text2);
            }
        } else {
            tvState.setText("未启动");
            tvDetail.setText(Str.empty(cfg.lastResult)
                    ? "填写账号后点「检测网络」，确认能识别到你的认证系统"
                    : "上次结果：" + cfg.lastResult);
            setHealth(R.drawable.bg_dot_idle, 0,
                    "已停止", R.drawable.bg_pill_soft, R.color.text2);
        }

        tvKvProvider.setText(providerLabel(cfg.provider));
        tvKvInterval.setText(cfg.intervalSec + " 秒");

        String last = cfg.lastLoginAt;
        if (!Str.empty(last) && last.length() > 16) last = last.substring(0, 16);
        tvLast.setText(Str.empty(last) ? "—" : last);

        tvWatchdogHint.setText(cfg.onlyWifi
                ? "认证请求固定走 Wi-Fi，移动数据不参与；通知栏常驻一条状态，可随时关掉"
                : "已关闭 Wi-Fi 白名单，但认证请求仍只走 Wi-Fi（流量无法完成校园网认证）");

        String mode = BuildConfig.VERSION_NAME;
        tvFooter.setText("v" + mode + " · 免 root 可用 · 支持 LSPosed 增强");

        suppressSwitch = true;
        swWatchdog.setChecked(cfg.enabled && running);
        suppressSwitch = false;
    }

    /**
     * 状态点、脉冲外圈、状态徽章三处必须一起变。
     * 分开写最容易出现「点是绿的、徽章还写着离线」这种自相矛盾的界面。
     */
    private void setHealth(int dotRes, int pingRes, String label, int pillRes, int colorRes) {
        dotStatus.setBackgroundResource(dotRes);

        tvMode.setText(label);
        tvMode.setBackgroundResource(pillRes);
        tvMode.setTextColor(ContextCompat.getColor(this, colorRes));

        if (pingRes == 0) {
            Anim.stop(pingAnim);
            pingAnim = null;
            dotPing.setVisibility(View.GONE);
        } else {
            dotPing.setBackgroundResource(pingRes);
            if (pingAnim == null || !pingAnim.isRunning()) {
                pingAnim = Anim.ping(dotPing);
            }
        }
    }

    private String providerLabel(String p) {
        if (p == null) return "自动";
        switch (p) {
            case "srun":
                return "深澜 srun";
            case "eportal":
                return "锐捷 ePortal";
            case "drcom":
                return "Dr.COM";
            case "generic":
                return "通用表单";
            default:
                return "自动识别";
        }
    }

    @Override
    public void onState(String text, Boolean online) {
        ui.post(this::refreshState);
    }

    // ------------------------------------------------------------------
    // 探测
    // ------------------------------------------------------------------

    private void doProbe() {
        persist();
        if (!Net.hasWifi()) {
            toast(Net.hasCellular()
                    ? "请先连上校园 Wi-Fi：移动数据能上网，但不经过校园网关"
                    : "请先连上校园 Wi-Fi 再检测");
            refreshState();
            return;
        }
        setBusy(true);
        tvDetail.setText("正在探测当前网络…");
        final AppConfig snapshot = AppConfig.fromJson(cfg.toJson().toString());

        exec.execute(() -> {
            PortalProbe.ProbeResult r;
            try {
                r = LoginEngine.probe(MainActivity.this, snapshot);
            } catch (Throwable t) {
                Logx.e("探测失败", t);
                r = null;
            }
            final PortalProbe.ProbeResult result = r;
            ui.post(() -> {
                setBusy(false);
                if (result == null) {
                    toast("探测失败，请检查网络");
                    refreshState();
                    return;
                }
                showProbeResult(result);
            });
        });
    }

    private void showProbeResult(PortalProbe.ProbeResult r) {
        String body = r.describe();
        if (r.online) {
            body += "\n结论：从 Wi-Fi 出口已能正常上网，当前无需登录。";
        } else if (!Str.empty(r.suggestedUserField) && !Str.empty(r.suggestedPassField)) {
            body += "\n结论：已识别出登录表单，点「应用建议」自动填好字段名。";
        } else {
            body += "\n结论：没能自动认出表单，到高级设置里手动填写字段名，"
                    + "或把认证方式改成「通用表单」。";
        }

        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle(r.online ? "网络正常" : "探测结果")
                .setMessage(body)
                .setNegativeButton("关闭", null);

        if (!r.online && (!Str.empty(r.suggestedAuthUrl) || !Str.empty(r.suggestedUserField))) {
            b.setPositiveButton("应用建议", (d, w) -> applySuggestion(r));
        }
        AlertDialog d = b.create();
        d.show();
        if (d.getWindow() != null) {
            TextView tv = d.findViewById(android.R.id.message);
            if (tv != null) {
                tv.setTextSize(11f);
                tv.setTypeface(android.graphics.Typeface.MONOSPACE);
                tv.setTextIsSelectable(true);
            }
        }
        if (!r.online) refreshState();
    }

    private void applySuggestion(PortalProbe.ProbeResult r) {
        if (!Str.empty(r.portalUrl)) cfg.portalUrl = r.portalUrl;
        if (!Str.empty(r.suggestedAuthUrl)) cfg.authUrl = r.suggestedAuthUrl;
        if (!Str.empty(r.suggestedUserField)) cfg.userField = r.suggestedUserField;
        if (!Str.empty(r.suggestedPassField)) cfg.passField = r.suggestedPassField;
        if (!Str.empty(r.suggestedProvider)) cfg.provider = r.suggestedProvider;
        cfg.save(this);
        Logx.i("已应用探测建议: provider=" + cfg.provider + " authUrl=" + cfg.authUrl
                + " user=" + cfg.userField + " pass=" + cfg.passField);
        toast("已保存建议配置");
        refreshState();
    }

    // ------------------------------------------------------------------
    // 登录
    // ------------------------------------------------------------------

    private void doLogin() {
        persist();
        if (!cfg.hasCredentials()) {
            toast("请先填写账号和密码");
            return;
        }
        if (!Net.hasWifi()) {
            toast(Net.hasCellular()
                    ? "未连接校园 Wi-Fi。移动数据能上网，但无法完成校园网认证"
                    : "未连接 Wi-Fi，请先连上校园无线网络");
            refreshState();
            return;
        }
        setBusy(true);
        tvDetail.setText("正在登录…");
        final AppConfig snapshot = AppConfig.fromJson(cfg.toJson().toString());

        exec.execute(() -> {
            AuthResult r;
            try {
                r = LoginEngine.loginNow(MainActivity.this, snapshot, true);
            } catch (Throwable t) {
                Logx.e("登录失败", t);
                r = AuthResult.fail("异常：" + t.getMessage());
            }
            final AuthResult result = r;
            ui.post(() -> {
                setBusy(false);
                cfg = AppConfig.load(MainActivity.this);
                tvDetail.setText(result.ok ? result.message : "登录失败：" + result.message);
                if (result.ok) {
                    toast(result.message);
                } else {
                    showFailure(result);
                }
                refreshState();
            });
        });
    }

    /**
     * 登录失败的提示。
     * <p>
     * 刻意把「判定依据」也放出来：失败原因在真机上往往拿不到日志，
     * 而截图是最方便的沟通方式。文案自解释，一张截图就能定位到
     * 是字段名不对、密码错误、还是门户根本没给表单。
     */
    private void showFailure(AuthResult r) {
        StringBuilder sb = new StringBuilder(r.message == null ? "" : r.message);
        if (!Str.empty(r.detail)) {
            sb.append("\n\n依据：").append(Str.ellipsis(r.detail, 300));
        }
        sb.append("\n\n本次的完整请求与响应已记录。");
        new AlertDialog.Builder(this)
                .setTitle("登录没有成功")
                .setMessage(sb)
                .setPositiveButton("查看诊断", (d, w) ->
                        startActivity(new Intent(this, LogActivity.class)))
                .setNegativeButton("关闭", null)
                .show();
    }

    private void setBusy(boolean busy) {
        btnProbe.setEnabled(!busy);
        btnLogin.setEnabled(!busy);
        btnProbe.setAlpha(busy ? 0.5f : 1f);
        btnLogin.setAlpha(busy ? 0.5f : 1f);
    }

    // ------------------------------------------------------------------
    // 权限与系统设置
    // ------------------------------------------------------------------

    private void maybeAskNotifications() {
        if (Build.VERSION.SDK_INT < 33) return;
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) {
            return;
        }
        ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTI);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_NOTI) {
            boolean granted = grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            if (!granted) {
                toast("没有通知权限，守护状态将不可见（功能仍会运行）");
            }
        }
    }

    private void openBatterySettings() {
        try {
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Throwable t) {
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Throwable t2) {
                toast("无法打开电池优化设置，请手动到系统设置里操作");
            }
        }
    }

    private void toast(String s) {
        if (!TextUtils.isEmpty(s)) Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onDestroy() {
        exec.shutdownNow();
        super.onDestroy();
    }
}
