#!/usr/bin/env zsh
# 网络层能力位改写 —— 日志速查。
#
#   ./tools/net-gate-watch.sh          改写命中 + 字段原值
#   ./tools/net-gate-watch.sh --all    额外打印 okhttp 请求行（看 App 有没有去拉分析数据）
#   ./tools/net-gate-watch.sh --clear  清一次日志（重新注入后从干净状态开始看）
#
# 判断有没有生效，只看三件事：
#   1. G1/G2 OLD 有没有出现 —— 出现 = 响应里就有这两个 key，改写发生了
#   2. patched ... analyse=N event=N —— N>0 才真的往客户端注入了能力位
#   3. App 是否去请求骑行分析 / myRideInfo 数据接口（App 自己决定，不再是 Web 行为例外）

set -euo pipefail

ADB=${ADB:-/Users/ryanfish/Library/Android/sdk/platform-tools/adb}
LOG=${LOG:-/sdcard/Android/data/com.cfmoto/files/zeeho_hook.log}

MODE=${1:-}

case "$MODE" in
  --clear)
    $ADB shell "cat /dev/null > $LOG" 2>/dev/null && echo "cleared $LOG" || echo "clear failed"
    exit 0
    ;;
  --all)
    $ADB shell cat "$LOG" | grep -E "ZeehoNet|ZeehoHTTP .*(analyse|myRideInfo|vehicle)" || true
    ;;
  *)
    $ADB shell cat "$LOG" | grep -E "ZeehoNet" || echo "(no ZeehoNet line yet — 模块没注入？先看 ZeehoSpeedHunter attached)"
    ;;
esac
