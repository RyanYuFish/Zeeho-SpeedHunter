# ZEEHO Speed Hunter

LSPosed 模块，作用于 ZEEHO App（`com.cfmoto`），实现如下功能：

1. **恢复骑行记录里被隐藏的统计** —— 骑行分析入口、极速、急加速、急刹、压弯；
2. **OTA 界面解锁 + 全链路流量记录** —— 用于研究车辆 OTA 链路的只读 hook；
3. **自带 Material You 设置界面** —— 实时切换挂载方式与各 gate。

## 骑行记录恢复（主机制：网络层能力位改写）

隐藏判据是**服务端下发的车型能力位**（iOS 端 8 轮消融实验验证，见 `../docs/04-iOS消融实验-结果.md`），
所以模块在网络层把这两个字段写对，App 自己就把 UI 渲染出来：

| gate | 字段 | 原值 | 改成 | 控制 |
|---|---|---|---|---|
| G1 | `vehicleKinds` | `1` | `2` | 骑行分析入口 + 极速 |
| G2 | `cyclingEventStatisticFlag` | `false` | `true` | 急加速 / 急刹 / 压弯 |

- 实现：`app/src/main/java/com/github/zeehospeedhunter/net/`，hook okhttp `Response$Builder#build()`；
- **不按 URL 过滤**，按「响应体里有没有这两个 key」判定，递归不限层级（列表响应里值为 `null` 也改写）；
- **不改任何数据字段**，显示的数值是服务端真值；
- **不改车型标识**（`vehicleType` / `vehType` / `regulationType`）—— 冗余，且会让 App 按别的车型发控制指令。

旧方案（沿祖先链点亮被 GONE 的控件）保留为**兜底**，默认关闭：`ride/RideOptions#VIEW_FALLBACK`。
只在网络层失效时打开（App 换了判定源时）。

## Android 16 启动崩溃规避（MobGuard）

在 Android 16（SDK 36，Nothing A024 / MetroidIND）上，ZEEHO App（v3.0.5）**一启动就 SIGSEGV 闪退**，
与模块无关（禁用模块、关闭 MTE、放宽 hidden_api 策略都照样崩）。根因是 App 打包的
**MobTech / ShareSDK（`com.mob.tools`）JNI 反射层**在新系统上的兼容性崩溃
（`fault addr 0x0000200000000401`，栈帧 `com.mob.tools.a.c$c.a` / `com.mob.tools.a.c$a.a`）。

模块提供一个**保命 hook** `core/MobGuard`：hook 这两个类的全部 `a` 方法并直接 `return null`，
作为 `MainHook` 的**第一个 install 调用**（先于网络层 gate，确保 App 能先活下来）。

- 默认开启，`core/MobOptions#GUARD`（`mob_guard` 开关，`core/Keys#KEY_MOB_GUARD`）可遥控关闭；
- 只在 `com.cfmoto` 进程内生效；
- 验证：装好带 MobGuard 的 v2.0 后，`logcat` 该进程 `has died` = **0**（此前每次启动必崩），
  HookLog 打印 `mob guard hooked com.mob.tools.a.c$c (#a x7)` / `com.mob.tools.a.c$a (#a x10)`。

> 这是针对 App 自身 SDK 缺陷的规避，不属于「恢复隐藏字段」的功能逻辑；若未来 App 升级替换掉
> 有问题的 SDK，可把 `mob_guard` 关掉恢复原始行为。

## 设置界面

模块自带原生设置界面（`ui.MainActivity`，桌面图标或 LSPosed 里点开模块进入）：

- 状态卡实时显示当前挂载方式（WEB / WEB+VIEW / VIEW / OFF）；
- 开关：网络层改写（主）、G1、G2、视图层兜底；
- 开关通过**广播 + 自落盘**跨进程送达运行中的 ZEEHO App，**实时生效**。
  通道细节见 `../docs/02-Android网络层gate实现.md`。

## OTA 抓包 / 解锁

业务类名被加固加密拿不到，因此只使用稳定的锚点：AndroidManifest 里的 OTA 组件类名、`ids.xml` 里的控件资源名、公开的 HTTP 客户端类名。

| 开关           | 默认 | 作用                                  |
| -------------- | ---- | ------------------------------------- |
| `UNLOCK_UI`    | 开   | 强制显示升级入口与「立即升级」按钮    |
| `LOG_HTTP`     | 开   | 打印所有 okhttp 请求                  |
| `LOG_RESPONSE` | 开   | 响应体捕获，OTA 接口族全打            |
| `LOG_DOWNLOAD` | 开   | 固件包探针：URL + 体积 + Content-Type |
| `LOG_WEBVIEW`  | 开   | 抓 WebView 加载的 OTA 说明页地址      |
| `LOG_SERVICE`  | 开   | 打印 OTA 服务 Intent extras           |

### 边界

这一组 hook 只做界面解锁和流量记录，不伪造任何服务端数据。升级包是否存在由服务端决定——OTA 红点接口返回的 `fileInfoList` 为 `null` 时，即使界面解锁了也不会有下载动作。

升级按钮只改 `setEnabled`，不碰点击监听，不会破坏 App 原有逻辑。

## 日志

模块把日志写进目标 App 的文件：

```
/sdcard/Android/data/com.cfmoto/files/zeeho_hook.log
```

```bash
LOG=/sdcard/Android/data/com.cfmoto/files/zeeho_hook.log
adb shell cat $LOG | grep ZeehoHTTP | sed 's/[?&].*//' | sort -u   # 去重后的接口清单
adb shell cat $LOG | grep '★'                                      # 固件下载线索
adb shell cat $LOG | grep 'body '                                   # 响应体

./tools/net-gate-watch.sh                                           # G1/G2 OLD + patched 速查
adb shell cat $LOG | grep ZeehoCfg                                  # 配置通道
```

排查口诀：App 升级后失效 → 看 `G1/G2 OLD` 两行。**还在** = 判定值变了；**消失** = 换了判定源。

## 安装

1. 在 LSPosed 中安装发布包。
2. 将作用域限定为 ZEEHO：`com.cfmoto`。
3. 启用模块后强制停止并重新打开 ZEEHO。
4. 进入「我的骑行」或「历史轨迹」查看结果。

发布包使用本地调试密钥签名，适合个人设备测试。公开分发前请使用自己的密钥重新构建和签名。

## 从源码构建

包名 `com.github.zeehospeedhunter`，当前版本 **2.0**（versionCode 20）。
环境要求：JDK 17、Android SDK 35、Gradle 9.7.1（wrapper 自带）。

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
./gradlew assembleRelease
# 产物：release/zeeho-speedhunter-v2.0.apk（编完自动从 build 目录拷出）
```

### 签名

密钥库 `release/release.jks`（alias `speedhunter-fish`）。口令从 `gradle.properties`
或环境变量读取（`STORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD`）：

```bash
./gradlew assembleRelease \
  -PSTORE_PASSWORD=xxx -PKEY_ALIAS=speedhunter-fish -PKEY_PASSWORD=xxx
```

三者不齐全时自动回退 debug 签名（本地随手可编）。
`gradle.properties` 与 `release/*.jks`、`release/*.apk` 均已排除在 git 之外。

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

本项目原创源码按 MIT License 发布。许可证只适用于本仓库中的原创源码和文档，不适用于 ZEEHO/CFMOTO 的 APK、商标、图标、地图素材、接口数据或其他第三方依赖；这些内容仍受各自权利人和适用许可条款约束。
