# ZEEHO Speed Hunter

LSPosed 模块，作用于 ZEEHO App（`com.cfmoto`），实现如下功能：

1. **恢复骑行记录里被隐藏的统计** —— 骑行分析入口、极速、急加速、急刹、压弯；
2. **OTA 界面解锁 + 全链路流量记录** —— 用于研究车辆 OTA 链路的只读 hook；
3. **自带 Material You 设置界面** —— 实时切换挂载方式与各 gate；
4. **OTA 推送控制台（socket_l）** —— 纯 Wi-Fi 直连 `192.168.0.1:10950` 推送固件包，不依赖官方 App / BLE / 厂商服务端；
5. **仪表投屏独立入口** —— 解析投屏二维码、自动连车机 AP、模块内一键拉起投屏，绕过官方 App GUI。

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

## OTA 抓包 / 解锁

使用稳定的锚点：AndroidManifest 里的 OTA 组件类名、`ids.xml` 里的控件资源名、公开的 HTTP 客户端类名。

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

## OTA 推送控制台（socket_l 直连）

模块内置**纯 socket_l 链路**的固件推送控制台，走车机独立的 TCP 通道
`192.168.0.1:10950`，**不依赖官方 App、不依赖 BLE、不依赖厂商服务端**。

- 入口：模块主界面「仪表 OTA 推送」→ `ui/OtaActivity`；
- 实现：`ota/OtaSocketPusher.java`（探测 + 推送）+ `ota/OtaPushService.java`（进度回调）；
  协议已通过固件交叉验证，见 `../docs/45`（runbook）、`../docs/46`（车边测试指南）、`../docs/47`；
- 桌面端同构工具：`tools/ota/push-ota-socket.py`（Mac 版，默认 dry-run）。

### 推送流程

```
探测（车机是否在 10950 监听）
  → 选包（SAF 选 .zip，免存储权限；或放应用私有目录直读）
  → START 0x10000001 {fileName, vehicleType, version, md5, fileSize}
  → 推数据帧 0x10000005（head[1]=0，fwrite 落 /media/flash/userdata/update.zip）
  → END 0x10000003 { recvOtaFileFinishedFlag:1 }      ★ 必须带 finished 标志
  → 收车机 0x10000004（otaVerifyMessage）→ 回 0x80000004 { recvVerifyAckFlag:1 }
  → 车机校验 md5 / 解压 / 重启进 U-Boot 刷写
```

### 边界

- `10950` 是单逻辑客户端：被占用时新连接会收到中文「服务器忙碌，已有客户端连接」。
  推包前必须先让官方 App / 投屏断开这条通道。
- 不触发刷写的红线（电量 ≥25%、非充电、非 Ready、原装电池、size/MD5 不符不触发、
  无成功判据不重复触发）由 `ota/OtaOptions` 把关。

## 仪表投屏（绕开官方 App）

模块提供一个**独立投屏入口**，把官方 App 里「扫码 → 连热点 → 点投屏」的手工流程
拆出来，由模块一键完成。完整的握手逆向写在 `../docs/49-仪表投屏EasyConnect握手与绕过App方案.md`。

- 入口：模块主界面「仪表投屏（独立入口）」→ `ui/MirrorActivity`；
- 实现：`ota/CarWifi.java`（自动连车机 AP）+ `ota/MirrorTrigger.java`（hook 在 `com.cfmoto` 内拉起投屏）
  + `ota/EcHandshakeProbe.java`（EC 握手探针）。

| 能力 | 结论 |
|---|---|
| 解析投屏二维码 URL（拿 SSID / 密码 / action） | ✅ 模块独立完成 |
| 手机自动加入车机 AP（192.168.0.x） | ✅ 由 `CarWifi` 完成（API 29+ `WifiNetworkSpecifier`，24–28 `addNetwork`） |
| 推镜像帧（H26x 编码 / 私有 PXC 协议） | ❌ 不可重写 —— 手机侧 EC 编排层在爱加密的 `libexec.so` 内，唯一 `classes.dex` 仅 13KB 壳 |
| 不打开官方 App GUI 也能投屏 | 🔶 能做到，但 EC 客户端必须跑在 `com.cfmoto`（唯一加载 EC SDK 的进程），由 hook 驱动 |

### 二维码 URL 

车机用二维码递 Wi-Fi 凭据的壳：`action=9` = AP_MODE（车机开热点
`ZEEHO-204dfd`）、`ssid/name` = AP 名、`pwd` = WPA2 密钥、`mac` = AP MAC。
真正的握手在 `libECSDK.so` 里：`initialize{uuid,pwd}` → `openTransport(EC_TRANSPORT_ANDROID_WIFI)`
→ `sendConnectType` → `C2PService::sendClientInfo{...}`（首帧鉴权）→ `LicenseManager`
→ `onSdkConnectStatus` → `openMirror`/`startMirror`。PXC 帧 = **16 字节固定头 + JSON**。

### EC 握手探针

`EcHandshakeProbe` 用与官方同构的 `sendClientInfo` JSON 去敲车机 PXC 口，只发一帧只听回包，
把「通道通不通、鉴权过不过」从猜测变成事实。头部模板可替换（`setHeaderTemplate`）——
`cmdType` 数值与 magic 是 Carbit 私有常量，静态拿不到，**真车抓一帧回包即可对齐**（见 `docs/49` §5.3）。

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

1. 在 XPosed 中安装发布包。
2. 将作用域限定为 ZEEHO：`com.cfmoto`。
3. 启用模块后强制停止并重新打开 ZEEHO。
4. 进入「我的骑行」或「历史轨迹」查看结果。

发布包使用本地调试密钥签名，适合个人设备测试。

## 从源码构建

包名 `com.github.zeehospeedhunter`，环境要求：JDK 17、Android SDK 35、Gradle 9.7.1（wrapper 自带）。

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
./gradlew assembleRelease
# 产物：release/zeeho-speedhunter-v3.0.apk（编完自动从 build 目录拷出）
```

## 隐私与安全

- 模块网络权限仅用作投屏/推送OTA，不上传骑行记录、车辆信息、位置或账号数据；
- 模块不修改 ZEEHO APK，不绕过账号权限，也不改变车辆控制逻辑；
- 速度数据只用于界面显示，不能作为仪表或安全决策的替代品；
- 请勿在骑行过程中操作手机或依赖本模块读数；
- LSPosed、Root、加固 App 和第三方模块存在兼容性风险，使用者应自行备份并承担风险。

## 非官方声明

本项目与 ZEEHO、CFMOTO 及其关联公司没有隶属、授权、赞助或合作关系。ZEEHO、CFMOTO 及相关图标、名称和产品资料归其各自权利人所有。本项目仅用于个人设备上的兼容性研究和界面恢复，不提供任何官方支持，也不保证持续兼容。

使用者应确保对目标设备、系统和 App 具有合法使用权，并自行遵守所在地法律、软件许可协议、服务条款和厂商保修政策。作者不对因安装、使用、修改或分发本项目造成的数据丢失、设备异常、账号限制、保修影响或其他直接、间接损失承担责任。

## 许可证

本项目原创代码及文档以 AGPL-3.0 授权，许可证全文见仓库根目录 LICENSE 文件。

该许可证仅适用于本仓库中的原创代码和文档，不适用于 ZEEHO/CFMOTO 的 APK、商标、图标、地图素材，以及通过其接口获取的实际数据内容；这些内容仍受各自权利人和适用许可条款约束。

本项目使用的第三方依赖，其许可证见各依赖项自带的 LICENSE 文件或相关清单文件。
