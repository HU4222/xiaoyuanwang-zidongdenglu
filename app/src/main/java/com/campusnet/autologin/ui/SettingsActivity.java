package com.campusnet.autologin.ui;

import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.campusnet.autologin.BuildConfig;
import com.campusnet.autologin.R;
import com.campusnet.autologin.core.AppConfig;
import com.campusnet.autologin.core.AppRank;
import com.campusnet.autologin.core.AppScanner;
import com.campusnet.autologin.core.Http;
import com.campusnet.autologin.core.Logx;
import com.campusnet.autologin.engine.WatchdogService;
import com.campusnet.autologin.root.RootShell;
import com.campusnet.autologin.util.Str;
import com.google.android.material.materialswitch.MaterialSwitch;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SettingsActivity extends AppCompatActivity {

    private AppConfig cfg;
    private final ExecutorService exec = Executors.newSingleThreadExecutor();

    private TextView tvProvider;
    private TextView tvInterval;
    private EditText etProbeUrl, etPortalUrl, etAuthUrl;
    private EditText etUserField, etPassField, etFormTpl, etExtra, etSuccess, etFail;
    private EditText etAcId, etSrunType;
    private EditText etBackoff, etRetryMax, etTimeout;
    private EditText etSsid;
    private EditText etRootPre, etRootPost;
    private EditText etXposedTarget;
    private MaterialSwitch swOnlyWifi, swRoot, swRootWatchdog, swXposedFill, swXposedClick, swBoot, swNotify;

    private String[] providerLabels;
    private String[] providerValues;
    private String[] intervalLabels;
    private String[] intervalValues;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        cfg = AppConfig.load(this);

        providerLabels = getResources().getStringArray(R.array.provider_labels);
        providerValues = getResources().getStringArray(R.array.provider_values);
        intervalLabels = getResources().getStringArray(R.array.interval_labels);
        intervalValues = getResources().getStringArray(R.array.interval_values);

        bind();
        fill();
        wire();
        Anim.viewIn(findViewById(R.id.settingsContent));
    }

    private void bind() {
        tvProvider = findViewById(R.id.tvProvider);
        tvInterval = findViewById(R.id.tvInterval);
        etProbeUrl = findViewById(R.id.etProbeUrl);
        etPortalUrl = findViewById(R.id.etPortalUrl);
        etAuthUrl = findViewById(R.id.etAuthUrl);
        etUserField = findViewById(R.id.etUserField);
        etPassField = findViewById(R.id.etPassField);
        etFormTpl = findViewById(R.id.etFormTpl);
        etExtra = findViewById(R.id.etExtra);
        etSuccess = findViewById(R.id.etSuccess);
        etFail = findViewById(R.id.etFail);
        etAcId = findViewById(R.id.etAcId);
        etSrunType = findViewById(R.id.etSrunType);
        etBackoff = findViewById(R.id.etBackoff);
        etRetryMax = findViewById(R.id.etRetryMax);
        etTimeout = findViewById(R.id.etTimeout);
        etSsid = findViewById(R.id.etSsid);
        etRootPre = findViewById(R.id.etRootPre);
        etRootPost = findViewById(R.id.etRootPost);
        etXposedTarget = findViewById(R.id.etXposedTarget);

        swOnlyWifi = findViewById(R.id.swOnlyWifi);
        swRoot = findViewById(R.id.swRoot);
        swRootWatchdog = findViewById(R.id.swRootWatchdog);
        swXposedFill = findViewById(R.id.swXposedFill);
        swXposedClick = findViewById(R.id.swXposedClick);
        swBoot = findViewById(R.id.swBoot);
        swNotify = findViewById(R.id.swNotify);
    }

    private void fill() {
        tvProvider.setText(labelOfProvider(cfg.provider));
        tvInterval.setText(labelOfInterval(cfg.intervalSec));

        etProbeUrl.setText(cfg.probeUrl);
        etPortalUrl.setText(cfg.portalUrl);
        etAuthUrl.setText(cfg.authUrl);
        etUserField.setText(cfg.userField);
        etPassField.setText(cfg.passField);
        etFormTpl.setText(cfg.formTemplate);
        etExtra.setText(cfg.extraParams);
        etSuccess.setText(cfg.successKeys);
        etFail.setText(cfg.failKeys);
        etAcId.setText(cfg.srunAcId);
        etSrunType.setText(cfg.srunType);
        etBackoff.setText(String.valueOf(cfg.retryBackoffSec));
        etRetryMax.setText(String.valueOf(cfg.retryMax));
        etTimeout.setText(String.valueOf(cfg.timeoutMs));
        etSsid.setText(cfg.wifiSsidFilter);
        etRootPre.setText(cfg.rootPreCmd);
        etRootPost.setText(cfg.rootPostCmd);
        etXposedTarget.setText(cfg.xposedTarget);

        swOnlyWifi.setChecked(cfg.onlyWifi);
        swRoot.setChecked(cfg.useRoot);
        swRootWatchdog.setChecked(cfg.rootWatchdog);
        swXposedFill.setChecked(cfg.xposedAutoFill);
        swXposedClick.setChecked(cfg.xposedAutoClick);
        swBoot.setChecked(cfg.autoStartOnBoot);
        swNotify.setChecked(cfg.notify);
    }

    private String labelOfProvider(String v) {
        for (int i = 0; i < providerValues.length; i++) {
            if (providerValues[i].equals(v)) return providerLabels[i];
        }
        return providerLabels[0];
    }

    private String labelOfInterval(int sec) {
        for (int i = 0; i < intervalValues.length; i++) {
            if (intervalValues[i].equals(String.valueOf(sec))) return intervalLabels[i];
        }
        return intervalLabels[2];
    }

    private void wire() {
        tvProvider.setOnClickListener(v -> {
            int checked = 0;
            for (int i = 0; i < providerValues.length; i++) {
                if (providerValues[i].equals(cfg.provider)) checked = i;
            }
            new AlertDialog.Builder(this)
                    .setTitle("认证方式")
                    .setSingleChoiceItems(providerLabels, checked, (d, which) -> {
                        cfg.provider = providerValues[which];
                        tvProvider.setText(providerLabels[which]);
                        d.dismiss();
                    })
                    .show();
        });

        tvInterval.setOnClickListener(v -> {
            int checked = 2;
            for (int i = 0; i < intervalValues.length; i++) {
                if (intervalValues[i].equals(String.valueOf(cfg.intervalSec))) checked = i;
            }
            new AlertDialog.Builder(this)
                    .setTitle("心跳间隔")
                    .setSingleChoiceItems(intervalLabels, checked, (d, which) -> {
                        cfg.intervalSec = parseInt(intervalValues[which], 60);
                        tvInterval.setText(intervalLabels[which]);
                        d.dismiss();
                    })
                    .show();
        });

        findViewById(R.id.btnBack).setOnClickListener(v -> {
            save(true);
            finish();
        });
        findViewById(R.id.btnSave).setOnClickListener(v -> save(false));
        findViewById(R.id.btnExport).setOnClickListener(v -> exportConfig());
        findViewById(R.id.btnImport).setOnClickListener(v -> importConfig());
        findViewById(R.id.btnRootBoost).setOnClickListener(v -> doRootBoost());
        findViewById(R.id.rowXposedPick).setOnClickListener(v -> pickRecommendedApp());
    }

    // ------------------------------------------------------------------
    // 推荐应用：扫描本机 → 打分排序 → 勾选 → 写回目标包名
    // ------------------------------------------------------------------

    private void pickRecommendedApp() {
        View row = findViewById(R.id.rowXposedPick);
        row.setEnabled(false);
        exec.execute(() -> {
            final List<AppRank.Cand> all = AppScanner.scan(this);
            runOnUiThread(() -> {
                row.setEnabled(true);
                openPickDialog(all);
            });
        });
    }

    private void openPickDialog(List<AppRank.Cand> all) {
        if (all == null || all.isEmpty()) {
            Toast.makeText(this, "没扫到已安装的应用，请在 LSPosed 里手动勾选", Toast.LENGTH_LONG).show();
            return;
        }
        List<AppRank.Cand> rec = new ArrayList<>();
        for (AppRank.Cand c : all) {
            if (AppRank.recommendable(c.score)) rec.add(c);
        }
        if (rec.isEmpty()) {
            // 一个都没对上关键字，直接给全量列表，别让用户空手而归
            showChooseDialog(all, all, "全部应用", false);
            return;
        }
        showChooseDialog(rec, all, "推荐应用", true);
    }

    /**
     * @param list         当前列表要展示的候选
     * @param all          全量候选，供「全部应用」切换
     * @param withAllButton 是否显示「全部应用」按钮
     */
    private void showChooseDialog(final List<AppRank.Cand> list, final List<AppRank.Cand> all,
                                  String title, final boolean withAllButton) {
        final String[] labels = new String[list.size()];
        final boolean[] checked = new boolean[list.size()];
        Set<String> existing = currentTargets();
        for (int i = 0; i < list.size(); i++) {
            labels[i] = list.get(i).display();
            checked[i] = existing.contains(list.get(i).pkg);
        }

        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle(title + "（" + list.size() + " 个）")
                .setMultiChoiceItems(labels, checked, (d, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton("填入", (d, w) -> {
                    StringBuilder sb = new StringBuilder();
                    int n = 0;
                    for (int i = 0; i < list.size(); i++) {
                        if (!checked[i]) continue;
                        if (sb.length() > 0) sb.append(',');
                        sb.append(list.get(i).pkg);
                        n++;
                    }
                    etXposedTarget.setText(sb.toString());
                    Toast.makeText(this,
                            n == 0 ? "没有勾选任何应用"
                                    : ("已填入 " + n + " 个包名，去 LSPosed 作用域勾选后生效"),
                            Toast.LENGTH_LONG).show();
                })
                .setNegativeButton("取消", null);

        if (withAllButton) {
            b.setNeutralButton("全部应用", (d, w) -> showChooseDialog(all, all, "全部应用", false));
        }
        b.show();
    }

    /** 解析输入框里现有的包名（逗号 / 分号 / 空格分隔，去掉通配符后缀）。 */
    private Set<String> currentTargets() {
        Set<String> out = new HashSet<>();
        String s = etXposedTarget.getText().toString();
        for (String raw : s.split("[,，;；\\s]+")) {
            String t = raw.trim();
            if (t.isEmpty()) continue;
            if (t.endsWith("*")) t = t.substring(0, t.length() - 1);
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    private void doRootBoost() {
        new AlertDialog.Builder(this)
                .setTitle("解除后台限制")
                .setMessage("将执行以下操作（需要 root，会弹出授权）：\n\n"
                        + "· 加入电池优化白名单\n"
                        + "· 允许后台运行 / 唤醒锁 / 前台服务\n"
                        + "· 待机分组设为 active\n\n"
                        + "这些是让守护长期存活最有效的手段。")
                .setPositiveButton("执行", (d, w) -> {
                    Toast.makeText(this, "正在执行…", Toast.LENGTH_SHORT).show();
                    exec.execute(() -> {
                        boolean rooted = RootShell.isRooted();
                        String msg;
                        if (!rooted) {
                            msg = "未获得 root 权限";
                        } else {
                            RootShell.Result r = RootShell.enableBoost(getPackageName());
                            msg = r.ok() ? "已解除系统后台限制" : ("执行失败：" + r);
                        }
                        final String m = msg;
                        Logx.i("root boost 结果: " + m);
                        runOnUiThread(() -> Toast.makeText(this, m, Toast.LENGTH_LONG).show());
                    });
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void save(boolean finishAfter) {
        cfg.probeUrl = text(etProbeUrl, cfg.probeUrl);
        cfg.portalUrl = text(etPortalUrl, "");
        cfg.authUrl = text(etAuthUrl, "");
        cfg.userField = text(etUserField, "");
        cfg.passField = text(etPassField, "");
        cfg.formTemplate = text(etFormTpl, "");
        cfg.extraParams = text(etExtra, "");
        cfg.successKeys = text(etSuccess, "");
        cfg.failKeys = text(etFail, "");
        cfg.srunAcId = text(etAcId, "1");
        cfg.srunType = text(etSrunType, "1");
        cfg.retryBackoffSec = Math.max(10, parseInt(etBackoff.getText().toString(), 30));
        cfg.retryMax = Math.max(1, parseInt(etRetryMax.getText().toString(), 3));
        cfg.timeoutMs = Math.max(3000, parseInt(etTimeout.getText().toString(), 8000));
        cfg.wifiSsidFilter = text(etSsid, "");
        cfg.rootPreCmd = text(etRootPre, "");
        cfg.rootPostCmd = text(etRootPost, "");
        cfg.xposedTarget = text(etXposedTarget, "");

        cfg.onlyWifi = swOnlyWifi.isChecked();
        cfg.useRoot = swRoot.isChecked();
        cfg.rootWatchdog = swRootWatchdog.isChecked();
        cfg.xposedAutoFill = swXposedFill.isChecked();
        cfg.xposedAutoClick = swXposedClick.isChecked();
        cfg.autoStartOnBoot = swBoot.isChecked();
        cfg.notify = swNotify.isChecked();

        // 判断是否本该启用：保持与守护服务实际状态一致
        cfg.enabled = WatchdogService.isRunning();

        cfg.save(this);
        Http.setInsecureTls(cfg.insecureTls);

        if (cfg.useRoot && cfg.rootWatchdog) {
            exec.execute(() -> {
                if (RootShell.isRooted()) {
                    RootShell.startWatchdog(getPackageName(), Math.max(60, cfg.intervalSec * 2));
                }
            });
        } else {
            exec.execute(RootShell::stopWatchdog);
        }

        Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show();
        if (finishAfter) {
            setResult(RESULT_OK);
        }
    }

    private String text(EditText et, String def) {
        String s = et.getText().toString().trim();
        return s.isEmpty() ? def : s;
    }

    private static int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Throwable t) {
            return def;
        }
    }

    // ------------------------------------------------------------------
    // 配置导入导出：用对话框贴 JSON，避免存储权限和文件选择器的麻烦
    // ------------------------------------------------------------------

    private void exportConfig() {
        final String json = cfg.toJson().toString();
        EditText et = new EditText(this);
        et.setText(json);
        et.setTextSize(10f);
        et.setTypeface(android.graphics.Typeface.MONOSPACE);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        et.setSelection(0);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        LinearLayout wrap = new LinearLayout(this);
        wrap.setPadding(pad, pad / 2, pad, 0);
        wrap.addView(et, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        new AlertDialog.Builder(this)
                .setTitle("导出配置（可全选复制）")
                .setView(wrap)
                .setPositiveButton("关闭", null)
                .show();
    }

    private void importConfig() {
        EditText et = new EditText(this);
        et.setHint("粘贴配置 JSON");
        et.setTextSize(10f);
        et.setTypeface(android.graphics.Typeface.MONOSPACE);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        LinearLayout wrap = new LinearLayout(this);
        wrap.setPadding(pad, pad / 2, pad, 0);
        wrap.addView(et, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        new AlertDialog.Builder(this)
                .setTitle("导入配置")
                .setView(wrap)
                .setPositiveButton("导入", (d, w) -> {
                    String s = et.getText().toString().trim();
                    if (Str.empty(s)) return;
                    AppConfig imported = AppConfig.fromJson(s);
                    imported.enabled = cfg.enabled;
                    cfg = imported;
                    cfg.save(this);
                    fill();
                    Toast.makeText(this, "已导入", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    @Override
    protected void onDestroy() {
        exec.shutdownNow();
        super.onDestroy();
    }
}
