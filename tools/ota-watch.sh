#!/bin/zsh
# ota-watch.sh — 监控 ZEEHO OTA 任务下发，发现 fileInfoList 直链立即下载
# 用法: ./tools/ota-watch.sh [轮询秒数，默认20]
ADB=/Users/ryanfish/Library/Android/sdk/platform-tools/adb
LOG=/sdcard/Android/data/com.cfmoto/files/zeeho_hook.log
OUT="$(cd "$(dirname "$0")/.." && pwd)/ota-capture"
INTERVAL="${1:-20}"
mkdir -p "$OUT"
echo "[ota-watch] start $(date '+%F %T') interval=${INTERVAL}s out=$OUT"

while true; do
  # 只匹配非空数组（[^]] 保证 [ 后至少有 1 个字符），规避 BSD grep 255 重复上限
  BODY=$($ADB shell cat $LOG 2>/dev/null | grep -oE '"fileInfoList":\[[^]]+' | tail -1)
  if [ -n "$BODY" ]; then
    echo "[ota-watch] !! $(date '+%F %T') 发现非空 fileInfoList"
    echo "$BODY" > "$OUT/fileInfoList-$(date '+%H%M%S').json"
    # 提取直链（zip/bin/img/tar/apk/s19/hex 等）
    URLS=$(echo "$BODY" | grep -oE 'https?://[^"\\ ]+' | sort -u)
    # 私有桶直链会 403，真正可靠的办法是从设备 okdownload 落盘目录取
    NAMES=$(echo "$BODY" | grep -oE '"fileName":"[^"]+"' | sed 's/.*:"//;s/"//')
    for N in $NAMES; do
      DEV="/sdcard/Android/data/com.cfmoto/files/oss/$N"
      if $ADB shell "[ -s $DEV ]" 2>/dev/null; then
        echo "[ota-watch] 从设备取包 $N"
        $ADB pull "$DEV" "$OUT/$N" >/dev/null 2>&1 \
          && echo "[ota-watch] 完成 -> $OUT/$N ($(stat -f%z "$OUT/$N" 2>/dev/null) bytes)"
      else
        echo "[ota-watch] 设备上暂无 $DEV（App 可能还没下完）"
      fi
    done
    for U in $URLS; do
      F="$OUT/$(basename ${U%%\?*})"
      if [ ! -s "$F" ]; then
        echo "[ota-watch] 尝试直链 $U"
        curl -sL --max-time 3600 -o "$F" "$U"
        # 私有桶返回的是 403 XML，不是固件，删掉避免误判
        if head -c 20 "$F" | grep -q "AccessDenied"; then
          echo "[ota-watch] 403（私有桶需鉴权）→ 删除，改为从设备取"
          rm -f "$F"
        else
          echo "[ota-watch] 完成 -> $F ($(stat -f%z "$F") bytes)"
        fi
      fi
    done
  fi
  # 顺带监控多任务升级内容
  MULTI=$($ADB shell cat $LOG 2>/dev/null | grep -oE '"multiDownloadUpgradeContent":\{.' | tail -1)
  if [ -n "$MULTI" ]; then
    echo "$MULTI" > "$OUT/multi-$(date '+%H%M%S').json"
    echo "[ota-watch] !! 发现 multiDownloadUpgradeContent"
  fi
  sleep "$INTERVAL"
done
