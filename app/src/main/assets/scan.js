/*
 * 取证用：把当前页面（含所有可达 iframe）的 HTML 原样读回来。
 *
 * 为什么需要：账户密码填不进去时，唯一能判断「到底该往哪填」的证据就是
 * 页面的真实结构。仅取主文档往往只拿到一个壳，真正的表单在 iframe 里，
 * 所以这里递归抓每一层。抓不到（跨域）的 frame 也记下 src，至少知道它指向谁。
 */
(function () {
    var out = [];
    var CAP = 60000;

    function safe(fn, dft) {
        try { return fn(); } catch (e) { return dft; }
    }

    function walk(win, path, depth) {
        var doc = safe(function () { return win.document; }, null);
        if (!doc || depth > 4) return;

        var href = safe(function () { return win.location.href; }, "?");
        var html = safe(function () {
            return doc.documentElement ? doc.documentElement.outerHTML : "";
        }, "");
        out.push("<!-- ==== FRAME " + path + " | " + href + " | " + html.length + " 字符 ==== -->");
        out.push(html.length > CAP ? html.slice(0, CAP) + "\n<!-- 截断 -->" : html);

        var kids = safe(function () { return [].slice.call(doc.querySelectorAll("iframe,frame")); }, []);
        for (var k = 0; k < kids.length; k++) {
            var kid = kids[k];
            var w = safe(function () { return kid.contentWindow; }, null);
            var reachable = w ? safe(function () { return !!w.document; }, false) : false;
            if (reachable) {
                walk(w, path + "/" + k, depth + 1);
            } else {
                out.push("<!-- ==== FRAME " + path + "/" + k + " 跨域，取不到内容 src="
                    + safe(function () { return kid.src || kid.getAttribute("src") || ""; }, "")
                    + " ==== -->");
            }
        }
    }

    try {
        walk(window, "top", 0);
    } catch (e) {
        out.push("<!-- 抓取异常: " + e + " -->");
    }

    return JSON.stringify({ h: out.join("\n\n") });
})()
