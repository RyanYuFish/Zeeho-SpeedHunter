# ZEEHO Speed Hunter

LSPosed 模块，作用于 ZEEHO App（`com.cfmoto`），实现如下功能：

1. **恢复骑行记录里被隐藏的完整数据** —— 速度、极速、急加速/急减速、压弯等统计字段；
2. **OTA 界面解锁 + 全链路流量记录** —— 用于研究车辆 OTA 链路的只读 hook。

## 骑行记录恢复

针对 ZEEHO 3.0.5 开发，基于 2.6.11 与新版资源对比定位。模块不处理导航实时速度，也不读取或伪造 GPS 速度——只恢复 App 已经绑定但界面隐藏的值。

已确认恢复的位置：「历史轨迹」列表里的骑行速度、轨迹详情中的统计速度与回放速度、「我的骑行 → 当日数据」中的最高速度；详情页与骑行分析页会尝试按资源名或同行标签恢复极速、急加速、急减速、压弯等统计。新增指标需在目标 App 版本上实机确认。

不同车型、账号、地区和后续 App 版本的接口或页面可能不同，不能据此保证所有环境都能显示。

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
```

## 安装

1. 在 LSPosed 中安装发布包。
2. 将作用域限定为 ZEEHO：`com.cfmoto`。
3. 启用模块后强制停止并重新打开 ZEEHO。
4. 进入「我的骑行」或「历史轨迹」查看结果。

发布包使用本地调试密钥签名，适合个人设备测试。公开分发前请使用自己的密钥重新构建和签名。

## 从源码构建

环境要求：JDK 17、Android SDK 34、Gradle 9.7.1（wrapper 自带）。

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
./gradlew assembleRelease
# 产物：app/build/outputs/apk/release/app-release.apk
```

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
