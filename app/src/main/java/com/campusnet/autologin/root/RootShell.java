package com.campusnet.autologin.root;

import com.campusnet.autologin.core.Logx;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.TimeUnit;

/**
 * root 能力封装。
 * <p>
 * 设计取舍：root 循环拉活（while true 里 pidof + monkey）耗电且容易被
 * 系统判定为异常行为，只作为可选项保留。真正有效的是
 * {@link #enableBoost(String)}——一次性把应用从电池优化、后台限制、
 * 待机分桶里捞出来，这一条能解决绝大多数「服务被冻结」问题。
 *
 * English: Root helpers. The loop-based watchdog is opt-in and costs battery; what
 * actually helps is enableBoost(), which removes the app from battery
 * optimisation, background limits and the standby bucket in one shot.
 */
public final class RootShell {

    public static final String TMP_DIR = "/data/local/tmp";
    public static final String WATCHDOG_SCRIPT = TMP_DIR + "/campusnet_watchdog.sh";
    public static final String BOOST_SCRIPT = TMP_DIR + "/campusnet_boost.sh";

    private static volatile Boolean rooted = null;

    private RootShell() {
    }

    public static final class Result {
        public int code = -1;
        public String out = "";
        public boolean timeout = false;
        public boolean noRoot = false;

        public boolean ok() {
            return !timeout && !noRoot && code == 0;
        }

        @Override
        public String toString() {
            if (noRoot) return "无 root 权限";
            if (timeout) return "超时(code=" + code + ")";
            return "exit=" + code + " out=" + (out == null ? "" : out.trim());
        }
    }

    /** 是否具备 root。结果缓存，避免每次都弹授权框。 */
    public static boolean isRooted() {
        if (rooted != null) return rooted;
        synchronized (RootShell.class) {
            if (rooted != null) return rooted;
            Result r = rawExec("id", 6000);
            rooted = r.code == 0 && r.out != null && r.out.contains("uid=0");
            Logx.i("root 检测: " + rooted + " (" + r + ")");
            return rooted;
        }
    }

    public static void invalidate() {
        rooted = null;
    }

    public static Result exec(String cmd) {
        if (cmd == null || cmd.trim().isEmpty()) {
            Result r = new Result();
            r.code = 0;
            return r;
        }
        return rawExec(cmd, 15000);
    }

    private static Result rawExec(String cmd, int timeoutMs) {
        Result r = new Result();
        Process p = null;
        try {
            p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            final Process fp = p;
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            Thread reader = new Thread(() -> {
                try (InputStream is = fp.getInputStream()) {
                    byte[] buf = new byte[4096];
                    int n;
                    while ((n = is.read(buf)) > 0) {
                        bos.write(buf, 0, n);
                        if (bos.size() > 64 * 1024) break;
                    }
                } catch (Throwable ignore) {
                }
            });
            reader.setDaemon(true);
            reader.start();

            boolean done = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            if (!done) {
                r.timeout = true;
                p.destroy();
            } else {
                r.code = p.exitValue();
            }
            reader.join(800);
            r.out = bos.toString("UTF-8");
            if (!done && r.code == -1) {
                // 超时后拿不到 exitValue，视为未知失败
                r.code = -2;
            }
        } catch (Throwable t) {
            r.code = -1;
            r.out = String.valueOf(t.getMessage());
            if (t.getMessage() != null && t.getMessage().contains("Cannot run program")) {
                r.noRoot = true;
            }
        } finally {
            if (p != null) {
                try {
                    p.destroy();
                } catch (Throwable ignore) {
                }
            }
        }
        return r;
    }

    /** 把脚本写到 /data/local/tmp 再执行，避免多层引号转义出错。 */
    public static Result execScript(String path, String script, int timeoutMs) {
        if (!isRooted()) {
            Result r = new Result();
            r.noRoot = true;
            return r;
        }
        Process p = null;
        try {
            p = Runtime.getRuntime().exec(new String[]{"su", "-c", "cat > " + path});
            OutputStream os = p.getOutputStream();
            os.write(script.getBytes("UTF-8"));
            os.flush();
            os.close();
            if (!p.waitFor(8, TimeUnit.SECONDS)) p.destroy();
        } catch (Throwable t) {
            Logx.w("写脚本失败: " + t.getMessage());
        } finally {
            if (p != null) {
                try {
                    p.destroy();
                } catch (Throwable ignore) {
                }
            }
        }
        return rawExec("chmod 700 " + path + " && sh " + path, timeoutMs);
    }

    // ------------------------------------------------------------------
    // 高价值操作
    // ------------------------------------------------------------------

    /**
     * 一次性解除系统对本应用的后台约束：
     * 电池优化白名单、后台运行、唤醒锁、待机分桶、自启。
     * 这是 root 最值得做的一件事，效果远好于循环拉活。
     */
    public static Result enableBoost(String pkg) {
        String script = "#!/system/bin/sh\n"
                + "PKG=\"" + pkg + "\"\n"
                + "dumpsys deviceidle whitelist +$PKG >/dev/null 2>&1\n"
                + "cmd deviceidle whitelist +$PKG >/dev/null 2>&1\n"
                + "appops set $PKG RUN_IN_BACKGROUND allow >/dev/null 2>&1\n"
                + "appops set $PKG RUN_ANY_IN_BACKGROUND allow >/dev/null 2>&1\n"
                + "appops set $PKG WAKE_LOCK allow >/dev/null 2>&1\n"
                + "appops set $PKG START_FOREGROUND allow >/dev/null 2>&1\n"
                + "am set-standby-bucket $PKG active >/dev/null 2>&1\n"
                + "cmd appops set $PKG START_FOREGROUND allow >/dev/null 2>&1\n"
                + "echo boost-done\n";
        Result r = execScript(BOOST_SCRIPT, script, 20000);
        Logx.i("root 免限制脚本: " + r);
        return r;
    }

    /** 可选：进程级看门狗，被杀后自动拉起。默认不启用。 */
    public static Result startWatchdog(String pkg, int intervalSec) {
        int iv = Math.max(30, intervalSec);
        String script = "#!/system/bin/sh\n"
                + "PKG=\"" + pkg + "\"\n"
                + "while true; do\n"
                + "  if ! pidof $PKG >/dev/null 2>&1; then\n"
                + "    monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1\n"
                + "  fi\n"
                + "  sleep " + iv + "\n"
                + "done\n";
        // 先杀掉旧的
        stopWatchdog();
        // 用 nohup 脱离当前 shell，脚本自身写 pid 文件
        String wrapper = "#!/system/bin/sh\n"
                + "sh " + WATCHDOG_SCRIPT + " >/dev/null 2>&1 &\n"
                + "echo $! > " + TMP_DIR + "/campusnet_watchdog.pid\n";
        execScript(WATCHDOG_SCRIPT, script, 8000);
        Result r = execScript(TMP_DIR + "/campusnet_wd_start.sh", wrapper, 8000);
        Logx.i("root 看门狗启动: " + r);
        return r;
    }

    public static Result stopWatchdog() {
        if (!isRooted()) {
            Result r = new Result();
            r.noRoot = true;
            return r;
        }
        String cmd = "PID=$(cat " + TMP_DIR + "/campusnet_watchdog.pid 2>/dev/null); "
                + "[ -n \"$PID\" ] && kill $PID 2>/dev/null; "
                + "for p in $(pgrep -f campusnet_watchdog.sh 2>/dev/null); do kill $p 2>/dev/null; done; "
                + "rm -f " + TMP_DIR + "/campusnet_watchdog.pid; echo stopped";
        return rawExec(cmd, 10000);
    }

    public static boolean isWatchdogRunning() {
        if (!isRooted()) return false;
        Result r = rawExec("pgrep -f campusnet_watchdog.sh 2>/dev/null | head -1", 8000);
        return r.code == 0 && r.out != null && !r.out.trim().isEmpty();
    }
}
