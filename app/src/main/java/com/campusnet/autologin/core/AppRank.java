package com.campusnet.autologin.core;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 「推荐应用」打分规则。
 * <p>
 * 刻意做成零 Android 依赖的纯函数（同 {@link Judge} 的理由）：判断哪个已装
 * 应用是校园网客户端，直接决定 LSPosed 作用域勾谁，勾错了要么不生效、要么
 * 把账号密码填到无关应用里。这种规则必须能拿构造好的包名/应用名在桌面 JVM
 * 上穷举验证，而不是等真机上试。
 * <p>
 * 分档原则：包名/名称里出现「校园网客户端专有词」才给高分，通用词只给低分，
 * 避免把「网络收音机」「WiFi 分析仪」这类应用排到前面。
 *
 * English: Scoring rule for the "recommended apps" list. Pure Java so the
 * package-name heuristics can be exhausted offline — a wrong pick either does
 * nothing or types the user's password into an unrelated app.
 */
public final class AppRank {

    /** 一个候选应用。 */
    public static final class Cand {
        public final String pkg;
        public final String label;
        public final int score;

        public Cand(String pkg, String label, int score) {
            this.pkg = pkg == null ? "" : pkg;
            this.label = label == null ? "" : label;
            this.score = score;
        }

        /** 界面上显示的一行文字：应用名（包名）。 */
        public String display() {
            if (label.isEmpty()) return pkg;
            if (pkg.isEmpty()) return label;
            return label + "（" + pkg + "）";
        }

        @Override
        public String toString() {
            return display() + " score=" + score;
        }
    }

    /** 强特征（每个 +4）：几乎只出现在校园网 / 网络接入认证类应用里。 */
    private static final String[] STRONG = {
            "campus", "ruijie", "srun", "drcom", "netkeeper", "eportal",
            "校园", "锐捷", "深澜", "哆点", "天翼校园", "校园宽带"
    };

    /** 中特征（每个 +2）：认证 / 接入类应用常见，但别的应用也可能用。 */
    private static final String[] MEDIUM = {
            "portal", "netauth", "认证", "网络接入", "上网", "拨号", "宽带", "wlan"
    };

    /**
     * 弱特征（每个 +1）：只作同分时的微调。
     * <p>
     * 这里刻意不放 "net"：它会被 com.tencent、com.netease 这类包名全量命中，
     * 让打分彻底失效。
     */
    private static final String[] WEAK = {
            "wifi", "wi-fi", "网络", "edu"
    };

    private AppRank() {
    }

    /** 给一个应用打 0~N 分；0 表示完全看不出跟校园网有关。 */
    public static int score(String pkg, String label) {
        String p = lower(pkg);
        String l = lower(label);
        return 4 * hits(STRONG, p, l)
                + 2 * hits(MEDIUM, p, l)
                + hits(WEAK, p, l);
    }

    /** >= 这个分数才进「推荐」区，低于它的只在「显示全部」里出现。 */
    public static final int RECOMMEND_THRESHOLD = 4;

    public static boolean recommendable(int score) {
        return score >= RECOMMEND_THRESHOLD;
    }

    private static int hits(String[] words, String pkgLower, String labelLower) {
        int n = 0;
        for (String w : words) {
            if (pkgLower.contains(w) || labelLower.contains(w)) n++;
        }
        return n;
    }

    private static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }

    /** 分数高的在前；同分按名称升序，保证结果稳定（不随系统返回顺序抖动）。 */
    private static final Comparator<Cand> ORDER = new Comparator<Cand>() {
        @Override
        public int compare(Cand a, Cand b) {
            if (a.score != b.score) return b.score - a.score;
            int c = a.label.compareToIgnoreCase(b.label);
            if (c != 0) return c;
            return a.pkg.compareTo(b.pkg);
        }
    };

    public static void sort(List<Cand> list) {
        if (list == null) return;
        java.util.Collections.sort(list, ORDER);
    }
}
