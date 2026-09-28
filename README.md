# ZEEHO Speed Hunter

LSPosed 模块，作用域只有 ZEEHO App（`com.cfmoto`）。两件事：

1. **恢复骑行记录里被隐藏的完整数据** —— 速度、极速、急加速/急减速、压弯等统计字段；
2. **OTA 界面解锁 + 全链路流量记录** —— 为了研究车辆 OTA 链路额外加的一组只读 hook。

模块名 / 描述在 LSPosed 管理器里显示为：
**ZEEHO Speed Hunter** — 「解锁 ZEEHO 骑行记录完整数据。」

## 代码结构

```
app/src/main/java/io/github/codex/zeehospeed/
├── MainHook.java            入口：只做「包名判断 → 初始化 → 装配」三件事
├── core/                    与业务无关的基础设施
│   ├── HookLog.java         统一日志出口（框架 + 落盘）
│   ├── HookKit.java         hookIfExists：类不存在就跳过，不让整包安装失败
│   ├── ViewKit.java         资源名 / 递归恢复可见 / 找宿主 Activity
│   ├── Targets.java         目标包名、组件类名、页面归属判断
│   └── ActivityTracker.java 记录当前页面（控件拿不到 Activity 时兜底）
├── ride/                    骑行记录
│   ├── RideHooks.java       4 条 hook 通道（生命周期 / setVisibility / attach / setText）
│   └── RideViews.java       控件识别，全部基于资源 ID 名 + 同行标签文案
└── ota/                     车辆 OTA
    ├── OtaOptions.java      开关与常量表 —— 调行为只改这里
    ├── OtaHooks.java        装配入口
    ├── OtaPageUnlock.java   「已是最新版本」界面解锁
    └── OtaTraffic.java      请求 / 响应体 / 固件包探针 / WebView / 服务 Intent
```

`assets/xposed_init` 注册的入口类是 `io.github.codex.zeehospeed.MainHook`。

## 骑行记录恢复

针对 ZEEHO 3.0.4 开发，基于 2.6.14 与新版资源对比定位。模块不处理导航实时速度，
也不读取或伪造 GPS 速度 —— 只恢复 App 已经绑定但界面隐藏的值。

四条互补通道（因为「什么时候被藏」不确定）：

| 通道 | 覆盖的场景 |
| --- | --- |
| `onResume` 后轮询扫视图树（0～12 s，500 ms 一次） | 接口回包后的异步渲染 |
| `View#setVisibility` | 隐藏动作发生在任意时刻 |
| `View#onAttachedToWindow` | RecyclerView 复用出来的列表项 |
| `TextView#setText` | 数据一到就恢复，并记录实际赋到的值 |

已确认恢复的位置：「历史轨迹」列表里的骑行速度、轨迹详情中的统计速度与回放速度、
「我的骑行 → 当日数据」中的最高速度；详情页与骑行分析页会尝试按资源名或同行标签恢复
极速、急加速、急减速、压弯等统计。新增指标需在目标 App 版本上实机确认。

不同车型、账号、地区和后续 App 版本的接口或页面可能不同，不能据此保证所有环境都能显示。

## OTA 抓包 / 解锁

业务类名被加固加密拿不到，所以只用三类稳定锚点：AndroidManifest 里的 OTA 组件类名、
`ids.xml` 里的控件资源名、公开的 HTTP 客户端类名（okhttp / okdownload）。

| 开关（`ota/OtaOptions.java`） | 默认 | 作用 |
| --- | --- | --- |
| `UNLOCK_UI` | 开 | 强制显示升级入口与「立即升级」按钮，隐藏「已是最新版本」分支 |
| `LOG_HTTP` | 开 | 打印所有 okhttp 请求：`[ZeehoHTTP] <方法> <URL>`，可疑下载 URL 前加 `★` |
| `LOG_RESPONSE` | 开 | 响应体捕获（`Response#peekBody`，非破坏性），OTA 接口族全打，其余按关键词过滤 |
| `LOG_DOWNLOAD` | 开 | 固件包探针：URL + 体积 + Content-Type，≥1 MB 或 URL 可疑就打印 |
| `LOG_WEBVIEW` | 开 | 抓 WebView 加载的 OTA 说明页地址 |
| `LOG_SERVICE` | 开 | 打印 `OTADownloadService` / `OTAService` 的 Intent extras |
| `DISCOVER_CLASSES` | 关 | 诊断用，打印 okhttp / okdownload / ota 相关类名 |

### 边界

这一组 hook **只做界面解锁和流量记录，不伪造任何服务端数据**。
升级包是否存在由服务端决定 —— OTA 红点接口返回的 `fileInfoList` 为 `null` 时，
即使界面解锁了也不会有下载动作。不构造假的 `fileInfoList`、不注入固件 URL：
那样真会把来路不明的固件推给车辆。

升级按钮只改 `setEnabled`，**不碰点击监听**，因此不会破坏 App 原有逻辑。

## 日志怎么读（重要）

**不要用 `adb logcat`。** 这套 LSPosed（IT 分支，寄生管理器）**不把 `XposedBridge.log`
写进 logcat**，`/data/adb/lspd/log/modules_*.log` 也收不到模块输出。
实测 logcat 全 buffer 里 `LSPosed-Bridge` 出现 0 次 —— grep 不到不等于模块没生效。

模块把每条日志同时写进目标 App 自己的两个文件：

| 位置 | 需要 root |
| --- | --- |
| `/sdcard/Android/data/com.cfmoto/files/zeeho_hook.log` | 否 |
| `/data/user/0/com.cfmoto/files/zeeho_hook.log` | 是 |

```bash
LOG=/sdcard/Android/data/com.cfmoto/files/zeeho_hook.log
adb shell cat $LOG | grep ZeehoHTTP | sed 's/[?&].*//' | sort -u   # 去重后的接口清单
adb shell cat $LOG | grep '★'                                      # 固件下载线索
adb shell cat $LOG | grep 'body '                                   # 响应体
```

日志 tag 定义在 `core/HookLog.java`：`[ZeehoSpeed]` / `[ZeehoOTA]` / `[ZeehoHTTP]` /
`[ZeehoWebView]` / `[ZeehoClass]` / `[ZeehoControl]`。

### 判断模块有没有生效：用 lspctl，不要靠翻日志

```bash
L=/data/adb/modules/zygisk_lsposed/lspctl
adb shell su -c "$L status"
adb shell su -c "$L module show io.github.codex.zeehospeed"
adb shell su -c "$L scope list io.github.codex.zeehospeed"
adb shell su -c "$L hook-debug dump" | grep -A3 "name=com.cfmoto"   # ← 最硬的证据
```

`hook-debug dump` 会列出每个已注入进程的每个 hook 点及其 `hooked=` 目标。
完整排查脚本：`tools/lsposed-diag.sh`（支持 `--restart-app` / `--ota` / `--clear-log`）。

### 关于这台设备的 LSPosed

`LSPosed IT (GitHub@RyanYuFish) v2.2.0-it`，**管理器是寄生的**：
设备上没有独立管理器 APK（`org.lsposed.manager` 不存在是正常的），
管理器跑在 **`com.android.shell` 进程**里，且是运行时动态注入 Activity。

- 打开方式：拨号盘 `*#*#5776733#*#*`（5776733 = LSPOSED）
- 或：`adb shell am broadcast -a android.telephony.action.SECRET_CODE -d android_secret_code://5776733 android`
- 注意 `am start -n com.android.shell/org.lsposed.manager.ui.activity.MainActivity` 会报
  `does not exist`，这是正常的。

## 安装

1. 在 LSPosed 中安装发布包。
2. 将作用域限定为 ZEEHO：`com.cfmoto`。
3. 启用模块后强制停止并重新打开 ZEEHO。
4. 进入「我的骑行」或「历史轨迹」查看结果。

发布包使用本地调试密钥签名，适合个人设备测试。公开分发前请使用自己的密钥重新构建和签名。

## 从源码构建

环境要求：JDK 17、Android SDK 34（compileSdk 34 / targetSdk 34）、Gradle 9.7.1（wrapper 自带）。

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
./gradlew assembleRelease
# 产物：app/build/outputs/apk/release/app-release.apk
```

源码使用传统 Xposed API（`de.robv.android.xposed:api:82`），生成的 APK 通过
`assets/xposed_init` 注册入口类。

## 备份

`backups/` 保留改造前的原始文件，便于回溯：

| 文件 | 说明 |
| --- | --- |
| `MainHook.java.bak-20260928-1129` | 加入 OTA 区块之前的单文件版本 |
| `app-build.gradle.bak-20260928-1129` | 对应时期的构建脚本 |
| `app-build.gradle.bak-20260928-1230` | 改成日志落盘之前的构建脚本 |

## 隐私与安全

- 模块没有网络权限，不上传骑行记录、车辆信息、位置或账号数据；
- 模块不修改 ZEEHO APK，不绕过账号权限，也不改变车辆控制逻辑；
- 速度数据只用于界面显示，不能作为仪表或安全决策的替代品；
- 请勿在骑行过程中操作手机或依赖本模块读数；
- LSPosed、Root、加固 App 和第三方模块存在兼容性风险，使用者应自行备份并承担风险。

## 非官方声明

本项目与 ZEEHO、CFMOTO 及其关联公司没有隶属、授权、赞助或合作关系。ZEEHO、CFMOTO 及相关图标、名称和产品资料归其各自权利人所有。本项目仅用于个人设备上的兼容性研究和界面恢复，不提供任何官方支持，也不保证持续兼容。

使用者应确保对目标设备、系统和 App 具有合法使用权，并自行遵守所在地法律、软件许可协议、服务条款和厂商保修政策。作者不对因安装、使用、修改或分发本项目造成的数据丢失、设备异常、账号限制、保修影响或其他直接、间接损失承担责任。

## 许可证

本项目原创源码按 [MIT License](LICENSE) 发布。许可证只适用于本仓库中的原创源码和文档，不适用于 ZEEHO/CFMOTO 的 APK、商标、图标、地图素材、接口数据或其他第三方依赖；这些内容仍受各自权利人和适用许可条款约束。
