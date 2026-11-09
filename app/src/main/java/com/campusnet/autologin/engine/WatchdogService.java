package com.campusnet.autologin.engine;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.SystemClock;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.campusnet.autologin.BuildConfig;
import com.campusnet.autologin.R;
import com.campusnet.autologin.auth.AuthResult;
import com.campusnet.autologin.core.AppConfig;
import com.campusnet.autologin.core.Logx;
import com.campusnet.autologin.core.Judge;
import com.campusnet.autologin.core.Net;
import com.campusnet.autologin.core.PortalProbe;
import com.campusnet.autologin.ui.MainActivity;
import com.campusnet.autologin.util.Str;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 守护服务：前台常驻 + 定时心跳 + 网络变化即时触发。
 * <p>
 * 节奏：每 intervalSec 秒探测一次外网；离线才登录，登录失败按
 * retryBackoffSec 指数退避，超过 retryMax 后回到常规间隔，避免把
 * 认证服务器打出风控。
 *
 * English: Foreground watchdog — one persistent low-importance notification, a
 * periodic probe, and immediate re-checks on network changes. Probes every
 * intervalSec seconds, logs in only when offline, and waits between failed
 * attempts.
 */
public class WatchdogService extends Service implements Logx.Sink {

    public static final String ACTION_START = "com.campusnet.autologin.action.START";
    public static final String ACTION_STOP = "com.campusnet.autologin.action.STOP";
    public static final String ACTION_CHECK_NOW = "com.campusnet.autologin.action.CHECK_NOW";
    public static final String ACTION_RESTART = "com.campusnet.autologin.action.RESTART";

    private static final String CH_ID = "campusnet_watchdog";
    private static final int NOTI_ID = 0x9001;
    private static final int MAX_LOG_LINES = 6;
    /** 网络事件触发检查的最小间隔：门户网络会被系统反复校验，不节流会打成风暴 */
    private static final long NET_TRIGGER_THROTTLE_MS = 8000L;

    public interface Listener {
        /** 状态文案变化；online 为 null 表示未知。 */
        void onState(String text, Boolean online);
    }

    private static final List<Listener> LISTENERS = new CopyOnWriteArrayList<>();
    private static volatile boolean running = false;
    private static volatile String stateText = "未启动";
    private static volatile Boolean stateOnline = null;

    public static boolean isRunning() {
        return running;
    }

    public static String stateText() {
        return stateText;
    }

    public static Boolean stateOnline() {
        return stateOnline;
    }

    public static void addListener(Listener l) {
        if (l != null && !LISTENERS.contains(l)) LISTENERS.add(l);
    }

    public static void removeListener(Listener l) {
        LISTENERS.remove(l);
    }

    private static void publish(String text, Boolean online) {
        stateText = text;
        stateOnline = online;
        for (Listener l : LISTENERS) {
            try {
                l.onState(text, online);
            } catch (Throwable ignore) {
            }
        }
    }

    // ------------------------------------------------------------------

    private HandlerThread thread;
    private Handler handler;
    private ExecutorService io;
    private ConnectivityManager.NetworkCallback netCallback;
    private NotificationManager nm;

    private volatile boolean stopRequested = false;
    private int failCount = 0;
    private long lastTickAt = 0L;
    private long lastHeartbeatLog = 0L;
    private volatile long lastNetTriggerAt = 0L;
    private final StringBuilder recent = new StringBuilder();

    @Override
    public void onCreate() {
        super.onCreate();
        Logx.init(this);
        Logx.addSink(this);
        Net.init(this);
        nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        thread = new HandlerThread("campusnet-watchdog");
        thread.start();
        handler = new Handler(thread.getLooper());
        io = Executors.newSingleThreadExecutor();
        createChannel();
        registerNetworkCallback();
    }

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        Logx.i("服务收到指令: " + action);

        if (ACTION_STOP.equals(action)) {
            stopRequested = true;
            AppConfig cfg = AppConfig.load(this);
            cfg.enabled = false;
            cfg.save(this);
            stopSelf();
            return START_NOT_STICKY;
        }

        startForegroundCompat(buildNotification("守护已启动", "正在检查网络…"));

        if (!running) {
            running = true;
            stopRequested = false;
            publish("守护中", null);
            schedule(1500);
        } else if (ACTION_CHECK_NOW.equals(action)) {
            handler.post(this::tick);
        }

        return START_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        running = false;
        Logx.removeSink(this);
        if (handler != null) handler.removeCallbacksAndMessages(null);
        if (thread != null) thread.quitSafely();
        if (io != null) io.shutdownNow();
        if (netCallback != null) {
            try {
                ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
                if (cm != null) cm.unregisterNetworkCallback(netCallback);
            } catch (Throwable ignore) {
            }
        }
        if (!stopRequested) {
            // 被系统杀掉而非用户主动停止：排一个闹钟把自己拉回来。
            // 注意要先确认用户没有关掉总开关，否则关掉后又被自己拉起来。
            AppConfig latest = AppConfig.load(this);
            if (latest.enabled) {
                scheduleRevive();
                Logx.w("服务被销毁，已安排 1 分钟后重启");
            } else {
                Logx.i("服务被销毁，总开关已关，不再自启");
                publish("已停止", null);
            }
        } else {
            publish("已停止", null);
        }
        super.onDestroy();
    }

    // ------------------------------------------------------------------
    // 主循环
    // ------------------------------------------------------------------

    private void schedule(long delayMs) {
        if (handler == null) return;
        handler.removeCallbacks(tickRunnable);
        handler.postDelayed(tickRunnable, Math.max(500, delayMs));
    }

    private final Runnable tickRunnable = this::tick;

    private void tick() {
        if (!running || stopRequested) return;
        lastTickAt = System.currentTimeMillis();

        final AppConfig cfg = AppConfig.load(this);
        if (!cfg.hasCredentials()) {
            publish("未填写账号", null);
            updateNotification("等待配置", "请先在应用里填写校园网账号密码");
            schedule(cfg.intervalSec * 1000L);
            return;
        }

        // Wi-Fi 是校园网认证的唯一通道。移动数据开着时系统会把探测请求交给
        // 蜂窝，那样"能上网"就等于"流量通不通"，跟认证状态完全无关。
        // 所以这一步必须排在探测之前，没有 Wi-Fi 就直接停机等待。
        if (!Net.hasWifi()) {
            publish("未连 Wi-Fi", false);
            updateNotification("等待 Wi-Fi",
                    Net.hasCellular()
                            ? "当前只有移动数据，流量不经过校园网关；连上校园 Wi-Fi 后自动开始"
                            : "连上校园 Wi-Fi 后自动开始认证");
            schedule(15_000);
            return;
        }

        if (cfg.onlyWifi && !onTargetWifi(cfg)) {
            publish("非目标 Wi-Fi，暂停", false);
            updateNotification("守护中", "当前 Wi-Fi 不在白名单，暂停检查");
            schedule(30_000);
            return;
        }

        io.execute(() -> {
            Judge.State st;
            try {
                st = LoginEngine.check(WatchdogService.this, cfg);
            } catch (Throwable t) {
                Logx.e("探测异常", t);
                st = new Judge.State();
                st.online = false;
                st.reason = "探测异常：" + t.getMessage();
            }

            long next;
            if (st.online) {
                failCount = 0;
                // 每 5 分钟留一行心跳。看起来是废话，其实是最缺的一条证据：
                // 15:10 登录成功到 15:28 之间日志一片空白，无法判断守护到底
                // 有没有在跑、是不是被系统杀了，只能靠猜。有了心跳就能直接看。
                long nowMs = System.currentTimeMillis();
                if (nowMs - lastHeartbeatLog >= 5 * 60_000L) {
                    lastHeartbeatLog = nowMs;
                    Logx.i("心跳: 已认证在线 · " + Net.outletLabel());
                }
                publish("已认证在线", true);
                updateNotification("已认证在线", "上次检查 " + now() + " · " + Net.outletLabel());
                next = cfg.intervalSec * 1000L;
            } else if (!st.hasWifi) {
                // 探测过程中 Wi-Fi 被断开（信号丢失 / 系统自动切流量）
                failCount = 0;
                publish("未连 Wi-Fi", false);
                updateNotification("等待 Wi-Fi", "Wi-Fi 已断开，等待重新接入");
                next = 15_000;
            } else {
                publish("检测到掉线，正在登录…", false);
                updateNotification("正在登录…", "检测到掉线，尝试重新认证");
                AuthResult r = LoginEngine.loginNow(WatchdogService.this, cfg, true);
                if (r.ok) {
                    failCount = 0;
                    publish("已重新登录", true);
                    updateNotification("已认证在线", "刚刚自动重连成功");
                    next = cfg.intervalSec * 1000L;
                } else if (r.message != null && r.message.contains("已有登录任务")) {
                    // 用户刚点了手动登录，登录锁被占着。这不是失败，
                    // 若照实报"登录失败"会让界面闪一下错误状态。
                    publish("正在登录…", null);
                    next = 3000;
                } else {
                    failCount++;
                    String msg = Str.empty(r.message)
                            ? (Str.empty(st.reason) ? "未知原因" : st.reason)
                            : r.message;
                    publish("登录失败：" + msg, false);
                    updateNotification("登录失败", Str.ellipsis(msg, 60));
                    if (failCount <= Math.max(1, cfg.retryMax)) {
                        next = Math.max(15, cfg.retryBackoffSec) * 1000L;
                    } else {
                        // 连续失败太多，退回常规间隔并清零，避免触发风控
                        failCount = 0;
                        next = cfg.intervalSec * 1000L;
                    }
                }
            }
            schedule(next);
        });
    }

    private String now() {
        return new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                .format(new java.util.Date());
    }

    // ------------------------------------------------------------------
    // 网络判定
    // ------------------------------------------------------------------

    /** 是否连着 WiFi；若配置了 SSID 过滤，还要匹配。 */
    private boolean onTargetWifi(AppConfig cfg) {
        try {
            // 必须用「Wi-Fi 网络」而不是默认网络判定：移动数据是默认网络时，
            // getActiveNetwork() 返回的是蜂窝，会把这个判断整体带偏。
            if (!Net.hasWifi()) return false;

            String filter = Str.nz(cfg.wifiSsidFilter).trim();
            if (filter.isEmpty()) return true;

            String ssid = Net.ssid();
            if (ssid.isEmpty()) return true; // 拿不到 SSID（缺定位权限）就放行，不误杀

            for (String part : filter.split("[,，]")) {
                String p = part.trim();
                if (!p.isEmpty() && ssid.contains(p)) return true;
            }
            Logx.i("当前 SSID=" + ssid + " 不在白名单，跳过");
            return false;
        } catch (Throwable t) {
            return Net.hasWifi();
        }
    }

    /**
     * 只监听 Wi-Fi 网络。
     * <p>
     * 原实现监听的是"任意带 INTERNET 的网络"，于是移动数据上线/下线也会
     * 触发一次完整检查，而那种检查在流量出口下必然误判成"已认证"。
     */
    private void registerNetworkCallback() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return;
            NetworkRequest req = new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .build();
            netCallback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    Logx.i("Wi-Fi 已连接，2 秒后检查");
                    triggerCheck(2000);
                }

                @Override
                public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
                    // 认证通过/失效时系统会更新 VALIDATED 标记，可据此即时跟随。
                    // 未认证的门户网络会被系统反复校验，这里必须节流，否则
                    // 会把请求打成风暴。
                    triggerCheck(800);
                }

                @Override
                public void onLost(Network network) {
                    // 收到 onLost 只说明「这个 Network 对象失效了」，不等于 Wi-Fi 断了。
                    // 重连、漫游、MAC 随机化都会出现「先给你一条新网络，再回收旧的」，
                    // 于是日志里会看到「Wi-Fi 已连接」下一秒「Wi-Fi 已断开」，而实际上
                    // 网络好得很——2026-11-06 15:28 与 15:30 各出现过一次，把界面
                    // 状态打回「未连 Wi-Fi」，也让后续判定白白绕一圈。
                    // 所以这里必须回头核实真实状态，而不是照着回调改状态。
                    if (Net.hasWifi()) {
                        Logx.i("旧 Wi-Fi 网络对象失效，但仍有可用 Wi-Fi，忽略本次断开");
                        return;
                    }
                    Logx.w("Wi-Fi 已断开");
                    publish("未连 Wi-Fi", false);
                    triggerCheck(1500);
                }
            };
            cm.registerNetworkCallback(req, netCallback);
        } catch (Throwable t) {
            Logx.w("注册网络回调失败: " + t.getMessage());
        }
    }

    /** 网络事件触发的检查，带最小间隔节流。 */
    private void triggerCheck(long delayMs) {
        if (handler == null) return;
        long now = SystemClock.elapsedRealtime();
        if (now - lastNetTriggerAt < NET_TRIGGER_THROTTLE_MS) return;
        lastNetTriggerAt = now;
        handler.postDelayed(() -> {
            if (running && !stopRequested) schedule(100);
        }, delayMs);
    }

    // ------------------------------------------------------------------
    // 通知
    // ------------------------------------------------------------------

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || nm == null) return;
        NotificationChannel ch = new NotificationChannel(
                CH_ID, "校园网守护", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("保持校园网认证会话，断线自动重连");
        ch.setShowBadge(false);
        ch.enableLights(false);
        ch.enableVibration(false);
        nm.createNotificationChannel(ch);
    }

    private Notification buildNotification(String title, String content) {
        Intent i = new Intent(this, MainActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) piFlags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getActivity(this, 0, i, piFlags);

        return new NotificationCompat.Builder(this, CH_ID)
                .setSmallIcon(R.drawable.ic_stat_net)
                .setContentTitle(title)
                .setContentText(content)
                .setStyle(new NotificationCompat.BigTextStyle()
                        .bigText(content + (recent.length() == 0 ? "" : "\n" + recent)))
                .setContentIntent(pi)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setShowWhen(false)
                .build();
    }

    private void startForegroundCompat(Notification n) {
        int type = 0;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (Build.VERSION.SDK_INT >= 34) {
                type = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE;
            } else {
                type = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC;
            }
        }
        try {
            if (type != 0) {
                startForeground(NOTI_ID, n, type);
            } else {
                startForeground(NOTI_ID, n);
            }
        } catch (Throwable t) {
            Logx.e("startForeground 失败，降级为普通前台服务", t);
            try {
                startForeground(NOTI_ID, n);
            } catch (Throwable t2) {
                Logx.e("通知也失败，服务将无法常驻", t2);
            }
        }
    }

    private void updateNotification(String title, String content) {
        try {
            if (nm != null) nm.notify(NOTI_ID, buildNotification(title, content));
        } catch (Throwable ignore) {
        }
    }

    private void scheduleRevive() {
        try {
            AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            Intent i = new Intent(this, BootReceiver.class).setAction(ACTION_RESTART);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
            PendingIntent pi = PendingIntent.getBroadcast(this, 1001, i, flags);
            long at = SystemClock.elapsedRealtime() + 60_000;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi);
            } else {
                am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi);
            }
        } catch (Throwable t) {
            Logx.w("安排重启闹钟失败: " + t.getMessage());
        }
    }

    @Override
    public void onLog(String line) {
        synchronized (recent) {
            recent.append(line).append('\n');
            String[] parts = recent.toString().split("\n");
            if (parts.length > MAX_LOG_LINES) {
                recent.setLength(0);
                for (int i = parts.length - MAX_LOG_LINES; i < parts.length; i++) {
                    recent.append(parts[i]).append('\n');
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 启动/停止入口
    // ------------------------------------------------------------------

    public static void start(Context ctx) {
        Intent i = new Intent(ctx, WatchdogService.class).setAction(ACTION_START);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(i);
            } else {
                ctx.startService(i);
            }
        } catch (Throwable t) {
            Logx.e("启动守护服务失败（可能被系统限制后台启动）", t);
        }
    }

    public static void stop(Context ctx) {
        // 用 stopService 而不是 startService(STOP)：应用在后台时 startService 会抛
        // IllegalStateException，导致「关不掉」这个很尴尬的 bug。
        // 调用方负责先把 AppConfig.enabled 置 false，onDestroy 会据此不再自启。
        try {
            ctx.stopService(new Intent(ctx, WatchdogService.class));
        } catch (Throwable t) {
            Logx.w("停止服务失败: " + t.getMessage());
        }
    }

    public static void checkNow(Context ctx) {
        Intent i = new Intent(ctx, WatchdogService.class).setAction(ACTION_CHECK_NOW);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(i);
            } else {
                ctx.startService(i);
            }
        } catch (Throwable ignore) {
        }
    }

    public static String debugInfo() {
        return "running=" + running + " state=" + stateText + " pkg=" + BuildConfig.APPLICATION_ID;
    }
}
