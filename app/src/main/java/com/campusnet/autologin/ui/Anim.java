package com.campusnet.autologin.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.provider.Settings;
import android.view.View;
import android.view.animation.PathInterpolator;

/**
 * DropLink 动效规范的 Android 落地。只有三档时长、三种曲线，不多加。
 *
 * <p>反馈 0.13–0.20s / 入场 0.22–0.38s / 循环 ≥1.3s。
 * 超过 0.4s 的入场动画在本项目里不存在。
 *
 * <p><b>总开关</b>：跟随系统的「动画时长缩放」。把它置 0 时本类所有动画直接落到终态。
 * 这不是为了省电，而是因为常驻的无限动画会让 {@code uiautomator dump} 永远等不到
 * 界面静止——DropLink 项目在自动化测试里连续踩了 4 次超时。要跑自动化就执行：
 * <pre>adb shell settings put global animator_duration_scale 0</pre>
 *
 * English: DropLink motion tokens in Android form — three durations, three curves,
 * nothing more. Follows the system animator scale, so setting it to 0 freezes
 * every animation; that keeps uiautomator dumps from waiting forever on an
 * infinite loop.
 */
public final class Anim {

    /** cubic-bezier(.22,.9,.3,1)：所有位移类入场 */
    private static final PathInterpolator SOFT =
            new PathInterpolator(0.22f, 0.9f, 0.3f, 1f);

    /** cubic-bezier(0,0,.2,1)：状态点脉冲，前段爆发后段散开 */
    private static final PathInterpolator PING =
            new PathInterpolator(0f, 0f, 0.2f, 1f);

    private static Boolean enabled;

    private Anim() {
    }

    /** 系统关闭动画时返回 false，调用方应直接落终态。 */
    public static boolean on(Context c) {
        if (enabled == null) {
            float scale = 1f;
            try {
                scale = Settings.Global.getFloat(c.getContentResolver(),
                        Settings.Global.ANIMATOR_DURATION_SCALE, 1f);
            } catch (Throwable ignore) {
                // 读不到就当作开，动画不是功能
            }
            enabled = scale > 0f;
        }
        return enabled;
    }

    /**
     * viewIn：opacity 0→1、translateY(10dp)→0、scale(.995)→1，0.28s。
     * 只用于「新页面来了」，同一视图内刷新用 alpha 过渡即可，别重复播。
     */
    public static void viewIn(View v) {
        if (v == null) return;
        if (!on(v.getContext())) {
            v.setAlpha(1f);
            v.setTranslationY(0f);
            v.setScaleX(1f);
            v.setScaleY(1f);
            return;
        }
        float dy = 10f * v.getResources().getDisplayMetrics().density;
        v.setAlpha(0f);
        v.setTranslationY(dy);
        v.setScaleX(0.995f);
        v.setScaleY(0.995f);
        v.animate()
                .alpha(1f)
                .translationY(0f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(280)
                .setInterpolator(SOFT)
                .start();
    }

    /** rise：opacity 0→1、translateY(6dp)→0，0.2s。用于「内容更新了」。 */
    public static void rise(View v) {
        if (v == null) return;
        if (!on(v.getContext())) {
            v.setAlpha(1f);
            v.setTranslationY(0f);
            return;
        }
        float dy = 6f * v.getResources().getDisplayMetrics().density;
        v.setAlpha(0f);
        v.setTranslationY(dy);
        v.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(200)
                .setInterpolator(SOFT)
                .start();
    }

    /**
     * 状态点心跳：scale(.85→1.9)、alpha(.5→0)，2.4s 无限循环。
     *
     * <p>返回的 animator 必须由调用方在 onStop 里 cancel，否则页面退出后还在跑。
     * 系统关闭动画时返回 null 并把外圈隐藏。
     */
    public static ValueAnimator ping(final View ring) {
        if (ring == null) return null;
        if (!on(ring.getContext())) {
            ring.setVisibility(View.GONE);
            return null;
        }
        ring.setVisibility(View.VISIBLE);
        final ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
        a.setDuration(2400);
        a.setRepeatCount(ValueAnimator.INFINITE);
        a.setInterpolator(PING);
        a.addUpdateListener(an -> {
            float f = (float) an.getAnimatedValue();
            float s = 0.85f + (1.9f - 0.85f) * f;
            ring.setScaleX(s);
            ring.setScaleY(s);
            ring.setAlpha(0.5f * (1f - f));
        });
        a.start();
        return a;
    }

    /** 页面退出时收尾：停循环动画，避免后台空转。 */
    public static void stop(ValueAnimator a) {
        if (a != null && a.isRunning()) {
            a.cancel();
        }
    }
}
