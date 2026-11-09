package com.campusnet.autologin.core;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 轻量日志：内存环形缓冲 + 落盘 + 界面实时回调。
 * <p>
 * 落盘上限 200KB，超出后截断重开，避免长期守护把存储写满。
 *
 * English: Ring buffer plus file sink plus live UI callback. The on-disk log is
 * capped at 200KB and reopened, so a long-running watchdog cannot fill storage.
 */
public final class Logx {

    public interface Sink {
        void onLog(String line);
    }

    public static final String TAG = "CampusNet";
    private static final int CAP = 1000;
    private static final long MAX_FILE = 200L * 1024L;

    private static final ArrayDeque<String> LINES = new ArrayDeque<>();
    private static final List<Sink> SINKS = new CopyOnWriteArrayList<>();
    private static final SimpleDateFormat TS = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US);

    private static File file;

    private Logx() {
    }

    public static void init(Context ctx) {
        if (file != null || ctx == null) return;
        File dir = new File(ctx.getFilesDir(), "logs");
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        file = new File(dir, "daemon.log");
    }

    public static void addSink(Sink s) {
        if (s != null && !SINKS.contains(s)) SINKS.add(s);
    }

    public static void removeSink(Sink s) {
        SINKS.remove(s);
    }

    public static void d(String msg) {
        write("D", msg, null);
    }

    public static void i(String msg) {
        write("I", msg, null);
    }

    public static void w(String msg) {
        write("W", msg, null);
    }

    public static void e(String msg) {
        write("E", msg, null);
    }

    public static void e(String msg, Throwable t) {
        write("E", msg, t);
    }

    private static synchronized void write(String level, String msg, Throwable t) {
        // 统一脱敏出口：密码 / 账号不进日志文件、不进 logcat、不进界面。
        // 锐捷 ePortal 把密码明文放在 URL 里，任何记录请求的地方都可能带出来，
        // 与其在每个记录点记得打码，不如在这里兜住。
        String line = TS.format(new Date()) + " " + level + " " + Sanitize.mask(msg)
                + (t == null ? "" : "  <" + t.getClass().getSimpleName() + ": "
                + Sanitize.mask(t.getMessage()) + ">");
        LINES.addLast(line);
        while (LINES.size() > CAP) LINES.removeFirst();

        if ("E".equals(level)) {
            Log.e(TAG, line);
        } else {
            Log.i(TAG, line);
        }

        persist(line);

        for (Sink s : SINKS) {
            try {
                s.onLog(line);
            } catch (Throwable ignore) {
            }
        }
    }

    private static void persist(String line) {
        if (file == null) return;
        try {
            if (file.length() > MAX_FILE) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            }
            FileWriter w = new FileWriter(file, true);
            try {
                w.write(line);
                w.write('\n');
            } finally {
                w.close();
            }
        } catch (Throwable ignore) {
        }
    }

    public static synchronized String dump() {
        StringBuilder sb = new StringBuilder();
        for (String l : LINES) sb.append(l).append('\n');
        return sb.toString();
    }

    public static synchronized List<String> snapshot() {
        return new ArrayList<>(LINES);
    }

    public static synchronized void clear() {
        LINES.clear();
        if (file != null && file.exists()) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }
}
