#!/usr/bin/env bash
# LSPosed 模块「装了但看不到日志 / 像没生效」的一键排查脚本
#
# 适配环境：KernelSU/SukiSU + LSPosed IT 分支（寄生管理器，跑在 com.android.shell 里）
#
# 关键事实（2026-09-28 实测，别再按老思路排查）：
#   1. 管理器不是独立 APK。设备上【没有】org.lsposed.manager 包是正常的，
#      它寄生在 com.android.shell 进程里，Activity 名为
#      org.lsposed.manager.ui.activity.MainActivity，且是运行时动态注入的，
#      所以 `am start -n com.android.shell/org.lsposed.manager...` 会报 "does not exist"。
#      正确唤起方式：拨号盘输入 *#*#5776733#*#*（5776733 = LSPOSED），或跑
#        adb shell am broadcast -a android.telephony.action.SECRET_CODE \
#            -d android_secret_code://5776733 android
#   2. 这个分支【不把模块的 XposedBridge.log 写进 logcat，也不写进
#      /data/adb/lspd/log/modules_*.log】。所以在 adb logcat 里 grep 模块 tag
#      永远是空的 —— 这不代表模块没生效。
#   3. 判断模块是否真的生效，用官方 CLI：lspctl hook-debug dump
#   4. 读模块日志，看 Zeeho-SpeedHunter 自己写的文件（见第 5 步）。
#
# 用法：
#   ./lsposed-diag.sh                 # 只诊断，不改设备状态
#   ./lsposed-diag.sh --restart-app   # 附带强制停止 + 重启目标 App，并读日志
#   ./lsposed-diag.sh --ota           # 重启后用 root 直接拉起 OTA 页面再读日志
#   ./lsposed-diag.sh --clear-log     # 先清空模块日志文件再开始

set -uo pipefail

MODULE_PKG="${MODULE_PKG:-io.github.codex.zeehospeed}"
TARGET_PKG="${TARGET_PKG:-com.cfmoto}"
TARGET_ACTIVITY="${TARGET_ACTIVITY:-com.cfmoto/.ui.login.SplashActivity}"
OTA_ACTIVITY="${OTA_ACTIVITY:-com.cfmoto/com.cfmoto.ui.mine.ota.OTAUpgradeActivity}"
OTA_SETTINGS_ACTIVITY="${OTA_SETTINGS_ACTIVITY:-com.cfmoto/com.cfmoto.ui.mine.ota.OTAUpgradeSettingsActivity}"

RESTART_APP=0
DO_OTA=0
CLEAR_LOG=0
for arg in "$@"; do
  case "$arg" in
    --restart-app) RESTART_APP=1 ;;
    --ota)         RESTART_APP=1; DO_OTA=1 ;;
    --clear-log)   CLEAR_LOG=1 ;;
    *) echo "未知参数: $arg"; exit 2 ;;
  esac
done

find_adb() {
  for p in "$(command -v adb 2>/dev/null)" \
           "$HOME/Library/Android/sdk/platform-tools/adb" \
           /opt/homebrew/bin/adb /usr/local/bin/adb; do
    [ -n "$p" ] && [ -x "$p" ] && { echo "$p"; return 0; }
  done
  return 1
}
ADB="$(find_adb)" || { echo "找不到 adb，请装 platform-tools 或改脚本里的路径"; exit 1; }
echo "adb: $ADB"

if [ "$("$ADB" devices | sed -n '2,$p' | grep -c 'device$')" -eq 0 ]; then
  echo
  echo "!! 没有已连接的设备。插 USB，或先开「无线调试」再用 adb connect <ip:port>。"
  exit 1
fi

sh()     { "$ADB" shell "$@" 2>&1 | tr -d '\r'; }
shroot() { "$ADB" shell su -c "$1" 2>&1 | tr -d '\r'; }
LSPCTL=/data/adb/modules/zygisk_lsposed/lspctl

# 模块自己写的日志（两个位置，任一可读即可）
LOG_EXT="/sdcard/Android/data/$TARGET_PKG/files/zeeho_hook.log"
LOG_INT="/data/user/0/$TARGET_PKG/files/zeeho_hook.log"

read_log() {
  local data
  data="$(sh cat "$LOG_EXT" 2>/dev/null)"
  if [ -z "$data" ]; then
    data="$(shroot "cat $LOG_INT" 2>/dev/null)"
  fi
  printf '%s' "$data"
}

echo
echo "================ 1. 目标 App / 模块 ================"
printf '  %-16s %s\n' "model:" "$(sh getprop ro.product.model)"
printf '  %-16s %s\n' "android:" "$(sh getprop ro.build.version.release) (API $(sh getprop ro.build.version.sdk))"
printf '  %-16s %s\n' "target pid:" "$(sh "ps -A" | awk -v p="$TARGET_PKG" '$NF==p {print $2}' | head -1)"
sh "dumpsys package $MODULE_PKG" | grep -E "versionName|versionCode" | head -2 | sed 's/^/  module /'

echo
echo "================ 2. LSPosed 框架 ================"
shroot "cat /data/adb/modules/zygisk_lsposed/module.prop" | grep -E '^(name|version)=' | sed 's/^/  /'
echo "  管理器：寄生在 com.android.shell（本版本不装独立 APK，没有 org.lsposed.manager 是正常的）"
echo "  唤起方式：拨号盘 *#*#5776733#*#*"

echo
echo "================ 3. 官方 CLI：status / module / scope ================"
shroot "$LSPCTL status" | sed 's/^/  /'
echo "  --- module show ---"
shroot "$LSPCTL module show $MODULE_PKG" | sed 's/^/  /'
echo "  --- scope list ---"
shroot "$LSPCTL scope list $MODULE_PKG" | sed 's/^/  /'

echo
echo "================ 4. 真的注入了吗（hook-debug dump）================"
DUMP="$(shroot "$LSPCTL hook-debug dump")"
if [ -z "$DUMP" ]; then
  echo "  !! dump 为空：Hook debug 未开启，或框架没在跑"
  echo "     试：$ADB shell su -c '$LSPCTL hook-debug enable'"
else
  echo "  目标 App 进程段："
  printf '%s\n' "$DUMP" | grep -E "name=$TARGET_PKG " | sed 's/^/    /'
  n="$(printf '%s\n' "$DUMP" | grep -c 'hooked=')"
  echo "  已生效的 hook 点总数：$n"
  printf '%s\n' "$DUMP" | grep -oE 'int com\.cfmoto\.ui\.mine\.ota\.[A-Za-z]+\.onStartCommand' \
    | sort -u | sed 's/^/    OTA: /'
  if printf '%s\n' "$DUMP" | grep -q "name=$TARGET_PKG "; then
    echo "  ✓ 模块已注入目标 App"
  else
    echo "  ✗ 目标 App 进程段没找到 —— 强制停止并重开 App（注入只发生在进程启动时）"
  fi
fi

echo
echo "================ 5. 模块日志（文件通道）================"
echo "  外部（无需 root）：$LOG_EXT"
echo "  内部（需 root）  ：$LOG_INT"
L="$(read_log)"
if [ -z "$L" ]; then
  echo "  还没有日志文件。说明模块代码没跑起来 —— 回到第 4 步。"
else
  echo "  总行数：$(printf '%s\n' "$L" | wc -l | tr -d ' ')"
  echo "  ---- 启动横幅 / hook 装配结果 ----"
  printf '%s\n' "$L" | grep -E "attached|hooks installed|install failed|skip " | head -12 | sed 's/^/    /'
  echo "  ---- OTA 接口（仅请求行，去重）----"
  printf '%s\n' "$L" | grep '\[ZeehoHTTP\]' \
    | grep -oE "https://[^ ]*" | sed 's/[?&].*//' | sort -u | sed 's/^/    /'
fi

if [ "$CLEAR_LOG" -eq 1 ]; then
  shroot "rm -f $LOG_INT" >/dev/null
  sh "rm -f $LOG_EXT" >/dev/null
  echo "  已清空模块日志文件"
fi

if [ "$RESTART_APP" -eq 1 ]; then
  echo
  echo "================ 6. 重启目标 App ================"
  sh "am force-stop $TARGET_PKG"
  echo "  已 force-stop"
  sh "am start -n $TARGET_ACTIVITY" | tail -1 | sed 's/^/  /'
  sleep 12
  if [ "$DO_OTA" -eq 1 ]; then
    echo "  ---- 用 root 拉起 OTA 页面 ----"
    shroot "am start -n $OTA_SETTINGS_ACTIVITY" | tail -1 | sed 's/^/  /'
    sleep 5
    shroot "am start -n $OTA_ACTIVITY" | tail -1 | sed 's/^/  /'
    sleep 10
  fi
  echo "  ---- 新增日志 ----"
  L="$(read_log)"
  printf '%s\n' "$L" | tail -35 | sed 's/^/    /'
fi

echo
echo "完成。常用过滤："
echo "  只看 OTA 接口   : $ADB shell cat $LOG_EXT | grep ZeehoHTTP | sed 's/[?&].*//' | sort -u"
echo "  只看固件线索   : $ADB shell cat $LOG_EXT | grep '★'"
echo "  只看响应体     : $ADB shell cat $LOG_EXT | grep 'body '"
