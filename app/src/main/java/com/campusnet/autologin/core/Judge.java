package com.campusnet.autologin.core;

import com.campusnet.autologin.util.Str;

/**
 * 校园网认证状态判定。
 * <p>
 * 这里刻意做成零 Android 依赖的纯函数，原因是：这个判定最容易出错，
 * 而它出错的表现（开着移动数据就显示已登录）在真机上很难稳定复现。
 * 抽成纯函数之后可以拿构造好的探测响应在桌面 JVM 上穷举各种组合，
 * 不必依赖手机。
 * <p>
 * 判定原则只有一条：<b>只有"从 Wi-Fi 网卡出去能通"才叫通过认证。</b>
 * 移动数据能上网是另一个事实，与校园网认证无关，不能拿来当证据。
 *
 * English: Decides whether the campus network is actually authenticated. Pure
 * Java on purpose: the failure mode (reporting "online" while mobile data is
 * carrying the request) is hard to reproduce on a device, so the matrix is
 * exhausted on a desktop JVM. Rule: only traffic leaving through the Wi-Fi
 * interface counts as proof.
 */
public final class Judge {

    public static final class State {
        /** 是否有 Wi-Fi 连接 */
        public boolean hasWifi;
        /** 是否已通过校园网认证 */
        public boolean online;
        /** 给界面看的原因，空串表示正常 */
        public String reason = "";

        @Override
        public String toString() {
            return "hasWifi=" + hasWifi + " online=" + online
                    + (Str.empty(reason) ? "" : " reason=" + reason);
        }
    }

    private Judge() {
    }

    /**
     * 没有 Wi-Fi 时的结论。
     * <p>
     * 关键：即使此刻移动数据能上网，也必须返回 online=false。这正是原实现
     * 的漏洞——它把"能发 HTTP 且拿到 204"当成了认证通过，而请求实际走的是
     * 蜂窝出口。
     */
    public static State noWifi(boolean hasCellular) {
        State st = new State();
        st.hasWifi = false;
        st.online = false;
        st.reason = hasCellular
                ? "未连接 Wi-Fi（当前只有移动数据，流量不走校园网关）"
                : "未连接 Wi-Fi";
        return st;
    }

    /**
     * 有 Wi-Fi 时的判定。
     *
     * @param r 绑定到 Wi-Fi 网卡后得到的探测响应；null 表示请求没发出去
     */
    public static State evaluate(Http.Result r) {
        State st = new State();
        st.hasWifi = true;
        if (r == null) {
            st.online = false;
            st.reason = "探测请求失败";
            return st;
        }
        if (!r.ok()) {
            st.online = false;
            st.reason = Str.empty(r.error) ? "探测请求失败" : r.error;
            return st;
        }
        st.online = online(r);
        if (!st.online) st.reason = "门户拦截，未认证";
        return st;
    }

    /**
     * 单看一份响应是否代表"已放行"。
     * <p>
     * 用 204 探测点：已认证时返回空 204；未认证时会被网关拦截成 302
     * 或 200 + 登录页 HTML，这两种都算未认证。
     */
    public static boolean online(Http.Result r) {
        if (r == null || !r.ok()) return false;
        if (r.code == 204 || r.code == 205) return true;
        if (r.isRedirect()) return false;
        if (r.code == 200) {
            String b = r.body == null ? "" : r.body.trim();
            // 真正的 204 探测点正常应无 body；有 HTML 一定是被劫持的登录页
            if (b.isEmpty()) return true;
            if (b.length() < 64 && b.indexOf('<') < 0) return true;
            return false;
        }
        return false;
    }
}
