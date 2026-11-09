package com.campusnet.autologin.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.method.ScrollingMovementMethod;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import com.campusnet.autologin.BuildConfig;
import com.campusnet.autologin.R;
import com.campusnet.autologin.core.Diag;
import com.campusnet.autologin.core.Logx;
import com.google.android.material.button.MaterialButton;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class LogActivity extends AppCompatActivity implements Logx.Sink {

    private TextView tvLog;
    private MaterialButton btnAutoScroll;
    private boolean autoScroll = true;
    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_log);
        Logx.init(this);

        tvLog = findViewById(R.id.tvLog);
        btnAutoScroll = findViewById(R.id.btnAutoScroll);
        tvLog.setMovementMethod(new ScrollingMovementMethod());

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnClear).setOnClickListener(v -> {
            Logx.clear();
            tvLog.setText("");
        });
        findViewById(R.id.btnCopy).setOnClickListener(v -> {
            String all = Logx.dump();
            if (all.isEmpty()) {
                toast("没有日志");
                return;
            }
            copyToClipboard(all);
            toast("已复制 " + all.length() + " 字符");
        });
        findViewById(R.id.btnExport).setOnClickListener(v -> exportDiag());
        // 手动兜底：自动填表认不出页面结构时，至少还能自己登进去
        findViewById(R.id.btnPortal).setOnClickListener(v -> {
            try {
                startActivity(new Intent(this, PortalActivity.class));
            } catch (Throwable t) {
                toast("打不开门户页：" + t.getMessage());
            }
        });
        btnAutoScroll.setOnClickListener(v -> {
            autoScroll = !autoScroll;
            btnAutoScroll.setText("自动滚动：" + (autoScroll ? "开" : "关"));
        });

        render();
        Anim.viewIn(tvLog);
    }

    // ------------------------------------------------------------------
    // 导出诊断
    // ------------------------------------------------------------------

    /**
     * 把「最近一次登录的请求/响应全文 + 运行日志」写成文本文件并调系统分享。
     * <p>
     * 做这个是因为真出问题时用户通常拿不到 logcat，而定位问题必须看到
     * 那一发请求打到了哪个地址、服务端回了什么。导出成文件后可以直接发出去。
     */
    private void exportDiag() {
        String report = buildReport();
        try {
            File dir = new File(getCacheDir(), "diag");
            if (!dir.exists()) {
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
            }
            String name = "校园网诊断-"
                    + new SimpleDateFormat("MMdd-HHmmss", Locale.US).format(new Date()) + ".txt";
            File f = new File(dir, name);
            FileWriter w = new FileWriter(f);
            try {
                w.write(report);
            } finally {
                w.close();
            }

            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", f);
            Intent i = new Intent(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .putExtra(Intent.EXTRA_SUBJECT, "校园网自动登录 · 诊断报告")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(i, "导出诊断报告"));
        } catch (Throwable t) {
            // 分享不可用时退回复制到剪贴板，至少还能粘出来
            Logx.w("导出诊断失败: " + t.getMessage());
            copyToClipboard(report);
            toast("分享不可用，已复制诊断报告到剪贴板（" + report.length() + " 字符）");
        }
    }

    private void copyToClipboard(String s) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("campusnet-diag", s));
    }

    private String buildReport() {
        StringBuilder sb = new StringBuilder();
        sb.append("校园网自动登录 · 诊断报告\n");
        sb.append("版本: ").append(BuildConfig.VERSION_NAME)
                .append(" (versionCode ").append(BuildConfig.VERSION_CODE).append(")\n");
        sb.append("时间: ")
                .append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()))
                .append('\n');
        sb.append("设备: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append(" / Android ").append(Build.VERSION.RELEASE)
                .append(" / SDK ").append(Build.VERSION.SDK_INT).append('\n');
        sb.append('\n');

        sb.append("===== 最近一次登录尝试 =====\n");
        sb.append(Diag.isEmpty() ? "(本次启动后还没有登录过)\n" : Diag.dump());
        sb.append('\n');

        sb.append("===== 运行日志 =====\n");
        String log = Logx.dump();
        sb.append(log.isEmpty() ? "(空)\n" : log);
        return sb.toString();
    }

    // ------------------------------------------------------------------

    private void render() {
        String all = Logx.dump();
        tvLog.setText(all.isEmpty() ? "暂无日志。开启守护或点「立即登录」后再回来看看。" : all);
        if (autoScroll) scrollToBottom();
    }

    private void scrollToBottom() {
        ui.post(() -> {
            try {
                int h = tvLog.getLayout() == null ? 0 : tvLog.getLayout().getHeight()
                        - tvLog.getHeight() + tvLog.getPaddingTop() + tvLog.getPaddingBottom();
                tvLog.scrollTo(0, Math.max(0, h));
            } catch (Throwable ignore) {
            }
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        Logx.addSink(this);
        render();
    }

    @Override
    protected void onStop() {
        Logx.removeSink(this);
        super.onStop();
    }

    @Override
    public void onLog(String line) {
        ui.post(() -> {
            String all = Logx.dump();
            tvLog.setText(all);
            if (autoScroll) scrollToBottom();
        });
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
