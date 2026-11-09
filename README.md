# 校园网自动登录

> Android 校园网 Portal 自动认证与掉线重连工具。
> Campus network captive-portal auto-login for Android — probe, identify, authenticate, keep alive.

一个 APK，三种能力：**纯 app 直连/填表**、**加 root 增强保活**、**加 LSPosed hook 官方客户端**。不装框架就是普通应用，装了也只是多一项能力。

支持锐捷 ePortal、深澜 srun、Dr.COM 及各类地方定制表单门户。没有硬编码任何一所学校的参数 —— 靠探测门户页反推。

---

## 为什么做这个

校园网认证的难点从来不是"发一个请求"，而是三件事：

1. **每所学校参数都不一样。** 深澜用 `srun_bx1` + HMAC-MD5 签名；锐捷新版是 `GET /eportal/portal/login` + JSONP 平铺参数；Dr.COM 和一堆地方定制版又各有表单字段名。硬编码任何一家，换一所学校就失效。
2. **会话会自己断。** 空闲一两分钟就可能被网关回收，"点一下登录"没有意义。
3. **系统会杀后台。** 前台服务、电池优化、厂商省电策略都会让守护进程消失。

所以重心放在：**自动识别** + **心跳重连** + **活的下去**。

---

## 三条路线

| 路线 | 前提 | 能做什么 | 局限 |
|---|---|---|---|
| A 纯 app | 无 | 直连接口、模拟表单、隐藏 WebView 填表 | 对付不了私有协议客户端 |
| B + root | `su` | 上面全部 + 电池白名单 + 进程看门狗 | 进不了别的应用进程 |
| C + LSPosed | Xposed 框架 | 上面全部 + hook 目标客户端自动填表 | 需手动授权作用域 |

三条在同一个包里，互不冲突，按你的权限条件任选。

---

## 工作原理

```
未认证时，网关会把任意 HTTP 请求劫持到登录页
        │
        ▼
GET http://connect.rom.miui.com/generate_204
        │
        ├─ 204 ─────────────────────► 已认证，不用登录
        │
        └─ 302 / 200+HTML ──────────► 被劫持
                    │
                    ▼
            跟随跳转链 → 抓门户页
                    │
                    ▼
        解析 <form> / <input> / <iframe> / 页面脚本
                    │
                    ▼
        识别厂商（eportal / srun / drcom / 通用）
                    │
                    ▼
        推断：提交地址 · 用户名字段 · 密码字段
                    │
                    ▼
        按「最可能 → 最通用」串行尝试认证器
        (直连 → 旧接口 → 通用表单 → WebView 填表)
                    │
                    ▼
        回读探测点确认，通才算成功
```

认证成功后进入守护：每 `intervalSec` 秒探测一次，掉线才重新登录。

### 在线判定只有一条原则

**只有"从 Wi-Fi 网卡出去能通"才叫通过认证。**

移动数据能上网是另一个事实，与校园网认证无关。这是本项目第一个真实 bug 的教训 —— 手机同时开着流量时，`HttpURLConnection` 会走系统默认网络（蜂窝），探测必然成功，于是"能上网"被当成了"已认证"。

修复分两层：`Judge` 先查 Wi-Fi 状态；`Net.bindProcessToWifi()` 把进程出口钉在 Wi-Fi 上，让请求物理上不可能走流量。

---

## 构建

需要 JDK 17 和 Android SDK（compileSdk 34）。

```bash
git clone https://github.com/HU4222/xiaoyuanwang-zidongdenglu.git
cd xiaoyuanwang-zidongdenglu
./gradlew assembleDebug
```

首次构建需要联网拉取 AndroidX 与 Material 依赖。

### 打签名包

签名信息不进仓库。二选一：

**方式一：用户级 Gradle 配置**（推荐，不会被误提交）

写入 `~/.gradle/gradle.properties`：

```properties
CNA_KEYSTORE=/absolute/path/to/your.jks
CNA_STORE_PASSWORD=your_store_password
CNA_KEY_ALIAS=your_alias
CNA_KEY_PASSWORD=your_key_password
```

**方式二：环境变量**

```bash
export CNA_KEYSTORE=/path/to/your.jks
export CNA_STORE_PASSWORD=...
export CNA_KEY_ALIAS=...
export CNA_KEY_PASSWORD=...
```

然后 `./gradlew assembleRelease`。没有配置时 release 会回退到默认 debug 签名，不影响功能验证。

### LSPosed 模块

`xposed-api/` 是编译期桩（`compileOnly`），**不会打进 APK**。运行期由 LSPosed 框架注入真实实现。模块自身入口 `XposedEntry` 必须在包内，由 `assets/xposed_init` 声明。

装好后在 LSPosed 里启用模块 → 作用域勾选学校客户端 → 设置页「推荐应用」可扫描本机应用并打分排序，帮你确认该勾哪个。

---

## 目录结构

```
app/src/main/java/com/campusnet/autologin/
├── core/
│   ├── Http.java              HttpURLConnection 封装（可控重定向/Cookie/字符集）
│   ├── Judge.java             在线判定（纯函数，可桌面单测）
│   ├── PortalProbe.java       门户探测与表单解析、厂商识别
│   ├── Guard.java             凭据去处校验：不往探测点发账号密码
│   ├── EportalQuery.java      锐捷 query 构造（与抓包逐字节比对）
│   ├── Jsonp.java             JSONP 剥壳
│   ├── Sanitize.java          日志脱敏出口
│   ├── Net.java               网络出口判定与进程绑定
│   ├── AppConfig.java         全部配置 + JSON 序列化
│   ├── AppRank.java           推荐应用打分（纯函数）
│   └── AppScanner.java        扫描已装应用
├── auth/
│   ├── EportalWebAuth.java    锐捷网页接口直连
│   ├── EportalAuth.java       锐捷旧 InterFace.do 流程
│   ├── SrunAuth.java          深澜 srun
│   ├── SrunCrypto.java        XXTEA + 自定义 Base64 + HMAC-MD5
│   ├── FormAuth.java          通用表单认证
│   ├── PortalJsAuth.java      从页面脚本反推接口
│   ├── WebAuth.java           隐藏 WebView 真实登录
│   └── AuthFactory.java       工厂 + 自动模式
├── engine/
│   ├── LoginEngine.java       登录调度（串行化 + root 钩子）
│   ├── WatchdogService.java   前台服务：心跳、网络回调、通知
│   └── BootReceiver.java      开机 / 更新 / 闹钟入口
├── root/RootShell.java        su 执行、免限制脚本、看门狗
├── xposed/                    LSPosed 模块
├── ui/                        界面（DropLink 风格，亮/深双主题）
└── util/Str.java

app/src/main/assets/
├── inject.js                  跨 frame 填表 + 请求取证
├── scan.js                    页面取证
└── xposed_init                模块入口声明

xposed-api/                    Xposed API 编译期桩（compileOnly）
selftest/                      桌面 JVM 测试 + node 测试
docs/                          设计说明与知识手册
```

---

## 测试

核心逻辑刻意做成**零 Android 依赖的纯 Java**，可以直接在桌面 JVM 上跑，不需要模拟器。

```bash
# 例：在线判定矩阵（21 项）
javac -encoding UTF-8 -d /tmp/t \
      -sourcepath app/src/main/java \
      selftest/com/campusnet/autologin/core/JudgeSelfTest.java
java -cp /tmp/t com.campusnet.autologin.core.JudgeSelfTest
```

覆盖范围：

| 测试 | 项数 | 验什么 |
|---|---|---|
| `JudgeSelfTest` | 21 | 在线判定矩阵（有 Wi-Fi 无、有流量、204、302、200+HTML…） |
| `LoginVerdictSelfTest` | 11 | 登录结果解析（含"裸 error 不能当失败"） |
| `SanitizeSelfTest` | 25 | 脱敏（密码、账号、URL 参数） |
| `GuardSelfTest` | 34 | 凭据去处校验 |
| `EportalQuerySelfTest` | 13 | query 与真实抓包逐字节比对 |
| `AppRankSelfTest` | 30 | 推荐应用打分 |
| `SrunSelfTest` | 16 | 深澜加密可逆性与字母表映射 |
| `inject.test.js` | — | node + DOM 桩，还原真机页面形状跑注入脚本 |

`EportalQuerySelfTest` / `SanitizeSelfTest` 里的账号密码走系统属性注入，源码内不含真实值：

```bash
java -DcnaUser=你的账号 -DcnaPw=你的密码 -cp /tmp/t com.campusnet.autologin.core.EportalQuerySelfTest
```

---

## 已知限制

- **直连路径的真机验证有限。** 锐捷 `EportalWebAuth` 的请求结构来自一次真实抓包，但只在原开发者的那所学校跑过。换学校大概率需要重新抓包调整。
- **跨域 iframe 填不了。** 如果门户把登录框放在跨域 iframe 里，浏览器安全边界决定了注入脚本无法访问。这是机制问题，不是实现问题 —— 此时只能用可见 WebView 手动登录。
- **`isTrusted` 检查无解。** 部分站点校验事件的 `isTrusted` 标志，脚本派发的事件无法伪造，只能靠 hook 或真实点击。
- **`View.postDelayed` 的坑已规避但不能根除。** 隐藏 WebView 永远不会 attach，所有派生逻辑必须用 `Handler`。
- **厂商省电策略必须手动配置。** 小米神隐、华为启动管理、vivo/OPPO 后台限制都需要用户在系统设置里放行，代码无法代劳。
- **配置里的密码是明文存储。** 存在应用私有 `SharedPreferences`，非 root 设备其他应用读不到；root 设备上不安全。生产环境应换 `EncryptedSharedPreferences`。

---

## 安全说明

这个工具会在本地保存你的校园网账号密码，并向认证网关提交。使用前请知悉：

1. **只用于你自己的账号。** 不要用它登录他人账号或共享给他人。
2. **遵守学校网络管理规定。** 认证绕过、账号共享等行为可能违反校规或服务条款，后果自负。
3. **凭据去处受校验。** `Guard` 会拒绝把账号密码提交到任何连通性探测点（小米、Google、Apple、微软、高通、vivo、华为、Firefox）或非门户地址。这是修复过一次真实泄露事故后加的。
4. **日志统一脱敏。** 密码替换为 `***`，账号只留后 4 位。脱敏在所有文本的最终出口执行，不依赖调用点记得打码。
5. **锐捷 ePortal 的登录请求是明文 HTTP + GET。** 账号密码在 URL 里，同网段抓包可见。这是学校的部署方式，不是本项目的选择 —— 但意味着这套认证本身没有保密性。

---

## 免责声明

本项目仅供学习与技术研究，用于理解 HTTP 协议、Captive Portal 机制、Android 后台保活与 WebView 自动化。使用者应自行确保其使用方式符合所在学校/单位的网络使用规定及当地法律法规。作者不对任何滥用行为或由此产生的后果负责。

---

## License

[MIT](LICENSE)

---

## 相关文档

- [`docs/知识地图与学习手册.md`](docs/知识地图与学习手册.md) —— 这个项目用到的 7 层 38 个知识点，含学习路径与踩坑记录
- [`docs/更新说明.md`](docs/更新说明.md) —— 版本变更与修复原因
- [`docs/视觉重绘说明.md`](docs/视觉重绘说明.md) —— 界面设计规范
