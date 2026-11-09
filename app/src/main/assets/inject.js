/*
 * 在门户页里填表并提交。
 *
 * 为什么要单独放成资源文件而不是 Java 字符串：这段脚本会越写越长，
 * 塞进 Java 字符串里要一路转义，改一行就看不出来了。
 *
 * 关键点一：**要遍历所有 frame**。很多校园网门户（锐捷 a79.htm 就是典型）
 * 顶页只是个壳，真正的账号密码框在 iframe 里，只在主文档里找永远 NOPW。
 * 同源 iframe 可以直接拿 contentWindow.document；跨域的取不到，记下来报回去。
 *
 * 关键点二：**点了按钮不一定等于发了请求**。真机上出现过连点三次、页面纹丝
 * 不动、日志里一个请求都没有的情况，光看"CLICK:登录"完全无法判断是被页面
 * 拦下了、还是请求发出去就失败了。所以这里顺手装了三个探针：
 *   1. hook XMLHttpRequest / fetch，记录每一次请求的地址与状态；
 *   2. 抓弹层文字（layer / layui / aui / toast 这些校园门户常用组件）；
 *   3. 包住 window.alert，把它显示的内容记下来（隐藏 WebView 里弹窗本来就看不见）。
 * 三者都会随结果一起回传，失败时能直接看到"页面到底说了什么"。
 *
 * 占位符（USER / PASS 那两个双下划线标记）由 Java 侧替换成 JSON 字符串字面量。
 */
(function () {
    var USER = __USER__, PASS = __PASS__;
    var frames = [];
    var filled = 0;
    var action = null;
    var done = false;

    function safe(fn, dft) {
        try { return fn(); } catch (e) { return dft; }
    }

    function trim(s) {
        s = (s || "") + "";
        return s.length > 120 ? s.slice(0, 120) : s;
    }

    // ---------------- 探针：网络 ----------------
    var net = (window.__cnaNet = window.__cnaNet || []);

    function rec(what) {
        try {
            net.push(trim(what));
            if (net.length > 8) net.shift();
        } catch (e) { }
    }

    function installHooks() {
        if (window.__cnaHooked) return;
        window.__cnaHooked = 1;
        try {
            var oOpen = XMLHttpRequest.prototype.open;
            var oSend = XMLHttpRequest.prototype.send;
            XMLHttpRequest.prototype.open = function (m, u) {
                try { this.__cnaU = (m || "?") + " " + trim(u); } catch (e) { }
                return oOpen.apply(this, arguments);
            };
            XMLHttpRequest.prototype.send = function () {
                var self = this;
                try {
                    self.addEventListener("loadend", function () {
                        rec("xhr " + (self.__cnaU || "?") + " -> " + self.status);
                    });
                    self.addEventListener("error", function () {
                        rec("xhr 失败 " + (self.__cnaU || "?"));
                    });
                } catch (e) { }
                return oSend.apply(this, arguments);
            };
        } catch (e) { }

        try {
            if (window.fetch) {
                var of = window.fetch;
                window.fetch = function (a) {
                    var u = typeof a === "string" ? a : ((a && a.url) || "");
                    return of.apply(this, arguments).then(function (r) {
                        rec("fetch " + trim(u) + " -> " + r.status);
                        return r;
                    }, function (e) {
                        rec("fetch 失败 " + trim(u));
                        throw e;
                    });
                };
            }
        } catch (e) { }

        try {
            // 隐藏 WebView 里的 alert 本来就不会显示给人看，直接记下来更有用。
            window.alert = function (m) {
                rec("alert " + m);
            };
        } catch (e) { }
    }

    // 同一个页面最多尝试 3 次，避免死循环刷请求
    window.__cnaTry = (window.__cnaTry || 0) + 1;
    installHooks();
    if (window.__cnaTry > 3) {
        return JSON.stringify({ s: "GIVEUP", m: popupText(document), n: net.slice(-4), f: frames });
    }

    function visible(el) {
        if (!el) return false;
        var r = safe(function () { return el.getBoundingClientRect(); }, null);
        if (!r || r.width <= 2 || r.height <= 2) return false;
        var s = safe(function () { return window.getComputedStyle(el); }, null);
        if (!s) return true;
        return s.display !== "none" && s.visibility !== "hidden" && s.opacity !== "0";
    }

    // 校园门户的提示框翻来覆去就那几个库：layer / layui / aui / weui / 原生 alert
    var POPUP_SEL = [
        ".layui-layer-content", ".layui-layer-msg", ".layui-layer-dialog",
        ".aui_msg", ".aui-toast", ".weui-toast", ".weui-dialog__bd",
        ".modal-body", ".alert", "[class*=toast]", "[class*=popup]", "[class*=msg]"
    ].join(",");

    function popupText(doc) {
        var out = [];
        if (!doc) return "";
        var els = safe(function () { return [].slice.call(doc.querySelectorAll(POPUP_SEL)); }, []);
        for (var i = 0; i < els.length && out.length < 3; i++) {
            if (!visible(els[i])) continue;
            var t = ((els[i].innerText || els[i].textContent || "") + "")
                .replace(/\s+/g, " ").trim();
            if (!t || t.length > 120) continue;
            if (out.indexOf(t) < 0) out.push(t);
        }
        return out.join(" | ");
    }

    function setVal(el, v) {
        // 走原型上的 setter，Vue / React 渲染的输入框才能感知到变化
        try {
            var d = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, "value");
            if (d && d.set) { d.set.call(el, v); } else { el.value = v; }
        } catch (e) {
            try { el.value = v; } catch (e2) { }
        }
        try { el.dispatchEvent(new Event("input", { bubbles: true })); } catch (e) { }
        try { el.dispatchEvent(new Event("change", { bubbles: true })); } catch (e) { }
        try { el.dispatchEvent(new KeyboardEvent("keyup", { bubbles: true })); } catch (e) { }
        try { el.dispatchEvent(new Event("blur")); } catch (e) { }
    }

    var WORDS = ["登录", "登陆", "连接", "认证", "上网", "确定", "提交",
        "login", "log in", "sign in", "signin", "connect", "submit"];

    function press(doc, pw) {
        var cands = safe(function () {
            return [].slice.call(doc.querySelectorAll(
                "input[type=submit],input[type=button],input[type=image],button,a,span,div,li,p,strong"));
        }, []);
        var i, k;
        for (i = 0; i < cands.length; i++) {
            var el = cands[i];
            // 容器类元素只接受「自己直接兜着文字」的，否则会点到整页外壳
            var tag = (el.tagName || "").toUpperCase();
            if (tag === "DIV" || tag === "LI" || tag === "P" || tag === "STRONG") {
                if (el.children && el.children.length > 0) continue;
            }
            if (!visible(el)) continue;
            var tx = ((el.innerText || el.value || el.getAttribute("value") || "") + "").trim();
            if (!tx || tx.length > 20) continue;
            var low = tx.toLowerCase();
            for (k = 0; k < WORDS.length; k++) {
                if (low.indexOf(WORDS[k].toLowerCase()) >= 0) {
                    try {
                        el.click();
                        return "CLICK:" + tx;
                    } catch (e) { }
                }
            }
        }
        if (pw && pw.form) {
            try { pw.form.submit(); return "FORM"; } catch (e) { }
        }
        try {
            pw.dispatchEvent(new KeyboardEvent("keydown",
                { key: "Enter", keyCode: 13, which: 13, bubbles: true }));
            return "ENTER";
        } catch (e) { }
        return "NOBTN";
    }

    function walk(win, path, depth) {
        var doc = safe(function () { return win.document; }, null);
        if (!doc || depth > 4) return;

        var inputs = safe(function () { return [].slice.call(doc.querySelectorAll("input")); }, []);
        var pw = null, i;
        for (i = 0; i < inputs.length; i++) {
            if ((inputs[i].type || "").toLowerCase() === "password") { pw = inputs[i]; break; }
        }

        frames.push({
            p: path,
            u: safe(function () { return win.location.href; }, "?"),
            n: inputs.length,
            pw: pw ? 1 : 0,
            v: pw ? (pw.value === PASS ? 1 : 0) : 0,
            t: safe(function () {
                return ((doc.body && doc.body.innerText) || "").replace(/\s+/g, " ").slice(0, 90);
            }, ""),
            m: popupText(doc)
        });

        if (pw && !action) {
            var user = null;
            var re = /user|account|name|num|zh|stu|login|id|card|no\b/i;
            for (i = 0; i < inputs.length && !user; i++) {
                var e = inputs[i];
                if (e === pw || !visible(e)) continue;
                var tp = (e.type || "text").toLowerCase();
                if (tp !== "text" && tp !== "tel" && tp !== "number" && tp !== "") continue;
                if (re.test((e.name || "") + " " + (e.id || "") + " " + (e.placeholder || ""))) user = e;
            }
            if (!user) {
                // 猜不到名字就取密码框前面最后一个可见文本框
                for (i = inputs.length - 1; i >= 0; i--) {
                    var e2 = inputs[i];
                    if (e2 === pw || !visible(e2)) continue;
                    var t2 = (e2.type || "text").toLowerCase();
                    if (t2 === "text" || t2 === "tel" || t2 === "number" || t2 === "") { user = e2; break; }
                }
            }
            if (user) {
                setVal(user, USER);
                setVal(pw, PASS);
                filled = 1;
                var cbs = safe(function () {
                    return [].slice.call(doc.querySelectorAll("input[type=checkbox]"));
                }, []);
                for (i = 0; i < cbs.length; i++) {
                    try { if (!cbs[i].checked) cbs[i].click(); } catch (e) { }
                }
                action = press(doc, pw);
                done = true;
            }
        }

        var kids = safe(function () { return [].slice.call(doc.querySelectorAll("iframe,frame")); }, []);
        for (var k = 0; k < kids.length; k++) {
            var kid = kids[k];
            var w = safe(function () { return kid.contentWindow; }, null);
            var reachable = w ? safe(function () { return !!w.document; }, false) : false;
            if (reachable) {
                walk(w, path + "/" + k, depth + 1);
            } else {
                frames.push({
                    p: path + "/" + k,
                    u: safe(function () { return kid.src || kid.getAttribute("src") || ""; }, "?"),
                    n: -1,
                    pw: 0,
                    v: 0,
                    t: "跨域 frame，脚本进不去",
                    m: ""
                });
            }
        }
    }

    try {
        walk(window, "top", 0);
    } catch (e) {
        frames.push({ p: "top", u: "?", n: -1, pw: 0, v: 0, t: "扫描异常: " + e, m: "" });
    }

    if (done) window.__cnaDone = 1;

    return JSON.stringify({
        s: action || (filled ? "FILLED" : "NOPW"),
        m: popupText(document),
        n: net.slice(-4),
        f: frames
    });
})()
