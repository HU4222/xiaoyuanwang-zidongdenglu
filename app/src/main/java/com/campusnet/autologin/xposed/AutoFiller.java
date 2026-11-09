package com.campusnet.autologin.xposed;

import android.app.Activity;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 在被 hook 的校园网客户端里做「填表 + 点登录」。
 * <p>
 * 不依赖任何具体控件 id：递归遍历视图树，用 inputType 认密码框，
 * 用控件文案认登录按钮。这样同一份代码能覆盖绝大多数学校的官方客户端。
 * 只用公开 API，不引用宿主 App 的任何类。
 *
 * English: Fills and submits the login form inside the hooked client. Walks the
 * view tree and identifies fields by inputType and button labels, so no vendor
 * resource id is hard-coded.
 */
final class AutoFiller {

    private static final long SAME_ACTIVITY_COOLDOWN_MS = 5000L;

    private static final String[] LOGIN_WORDS = {
            "登录", "登陆", "认证", "连接", "上线", "login", "sign in", "connect"
    };

    private static String lastKey = "";
    private static long lastAt = 0L;

    private AutoFiller() {
    }

    static void run(Activity act, String username, String password,
                    boolean autoFill, boolean autoClick) {
        if (act == null || !autoFill) return;

        String key = act.getClass().getName() + "@" + System.identityHashCode(act);
        long now = System.currentTimeMillis();
        if (key.equals(lastKey) && now - lastAt < SAME_ACTIVITY_COOLDOWN_MS) return;

        View root;
        try {
            if (act.isFinishing()) return;
            if (act.getWindow() == null) return;
            root = act.getWindow().getDecorView();
        } catch (Throwable t) {
            return;
        }
        if (root == null) return;

        List<EditText> edits = new ArrayList<>();
        List<View> clickables = new ArrayList<>();
        collect(root, edits, clickables, 0);

        EditText passEt = null;
        EditText userEt = null;
        for (EditText e : edits) {
            boolean isPassword;
            try {
                int type = e.getInputType();
                isPassword = (type & InputType.TYPE_TEXT_VARIATION_PASSWORD) != 0
                        || (type & InputType.TYPE_NUMBER_VARIATION_PASSWORD) != 0
                        || (type & InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD) != 0;
            } catch (Throwable t) {
                isPassword = false;
            }
            if (isPassword) {
                if (passEt == null) passEt = e;
            } else if (userEt == null) {
                userEt = e;
            }
        }

        // 没有密码框，基本可以断定不是登录页，直接退出不打扰
        if (passEt == null) return;

        boolean changed = false;
        try {
            if (userEt != null && isEmpty(userEt)) {
                userEt.setText(username);
                changed = true;
            }
        } catch (Throwable ignore) {
        }
        try {
            if (isEmpty(passEt)) {
                passEt.setText(password);
                changed = true;
            }
        } catch (Throwable ignore) {
        }

        lastKey = key;
        lastAt = now;
        XLog.i("已填充登录表单 activity=" + act.getClass().getName() + " changed=" + changed);

        if (!autoClick) return;

        final View button = findLoginButton(clickables);
        if (button == null) {
            XLog.i("未找到明显的登录按钮，需要手动点一下");
            return;
        }
        try {
            button.postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        button.performClick();
                        XLog.i("已自动点击登录按钮");
                    } catch (Throwable t) {
                        XLog.i("点击失败: " + t);
                    }
                }
            }, 700);
        } catch (Throwable ignore) {
        }
    }

    private static boolean isEmpty(EditText e) {
        try {
            CharSequence t = e.getText();
            return t == null || t.length() == 0;
        } catch (Throwable t) {
            return true;
        }
    }

    private static void collect(View v, List<EditText> edits, List<View> clickables, int depth) {
        if (v == null || depth > 40) return;
        try {
            if (v instanceof EditText) {
                edits.add((EditText) v);
            } else if (v instanceof Button) {
                clickables.add(v);
            } else if (v instanceof TextView) {
                TextView tv = (TextView) v;
                String text = safeText(tv);
                if (!text.isEmpty() && matchesLoginWord(text)) {
                    clickables.add(v);
                } else if (tv.isClickable()) {
                    clickables.add(v);
                }
            }
        } catch (Throwable ignore) {
        }

        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            int n;
            try {
                n = g.getChildCount();
            } catch (Throwable t) {
                return;
            }
            for (int i = 0; i < n; i++) {
                View c;
                try {
                    c = g.getChildAt(i);
                } catch (Throwable t) {
                    continue;
                }
                collect(c, edits, clickables, depth + 1);
            }
        }
    }

    private static boolean matchesLoginWord(String text) {
        if (text.length() > 14) return false;
        String s = text.toLowerCase(Locale.US);
        for (String w : LOGIN_WORDS) {
            if (s.contains(w)) return true;
        }
        return false;
    }

    private static View findLoginButton(List<View> clickables) {
        // 先按文案精确命中，命中多个时取文字最短的（通常是按钮本身而非标题）
        View best = null;
        int bestLen = Integer.MAX_VALUE;
        for (View v : clickables) {
            if (!(v instanceof TextView)) continue;
            String s = safeText((TextView) v);
            if (s.isEmpty() || !matchesLoginWord(s)) continue;
            if (s.length() < bestLen) {
                bestLen = s.length();
                best = v;
            }
        }
        return best;
    }

    private static String safeText(TextView t) {
        try {
            CharSequence c = t.getText();
            return c == null ? "" : c.toString().trim();
        } catch (Throwable e) {
            return "";
        }
    }
}
