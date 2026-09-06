#!/usr/bin/env bash
# 启动 AVD 模拟器，并安装 app/build/outputs/apk/*/debug/ 下最新的 debug APK，装完自动拉起 app。
# 用法: bash scripts/emulator_install_debug.sh [flavor]
#   flavor 可选，指定 github 或 google；缺省时取所有 debug APK 中 mtime 最新的一个。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SDK="${ANDROID_HOME:-G:/Android/Sdk}"
AVD_NAME="${AVD_NAME:-shaft_test}"
# 模拟器靠 ANDROID_AVD_HOME 找 AVD（本机 AVD 放在 G 盘，不在默认的 C 盘用户目录）
export ANDROID_AVD_HOME="${ANDROID_AVD_HOME:-G:/Android/avd}"

ADB="$SDK/platform-tools/adb.exe"
EMU="$SDK/emulator/emulator.exe"

FLAVOR="${1:-}"
shopt -s nullglob
if [ -n "$FLAVOR" ]; then
  candidates=("$ROOT/app/build/outputs/apk/$FLAVOR/debug/"*.apk)
else
  candidates=("$ROOT/app/build/outputs/apk/"*/debug/*.apk)
fi
shopt -u nullglob
if [ ${#candidates[@]} -eq 0 ]; then
  echo "错误: 没找到 debug APK，先构建一次，例如: ./gradlew assembleGithubDebug" >&2
  exit 1
fi
APK="${candidates[0]}"
for f in "${candidates[@]}"; do
  [ "$f" -nt "$APK" ] && APK="$f"
done

# Git Bash 的 /g/... 路径 adb.exe 不认，显式转成 Windows 路径
APK_WIN="$(cygpath -w "$APK" 2>/dev/null || echo "$APK")"
echo "选中的 APK: $APK_WIN"

# 从同目录 output-metadata.json 拿包名，用于装完后拉起
PKG="$(sed -n 's/.*"applicationId"\s*:\s*"\([^"]*\)".*/\1/p' "$(dirname "$APK")/output-metadata.json" | head -1)"
echo "包名: ${PKG:-未知（装完不自动启动）}"

if "$ADB" devices | grep -q "emulator-"; then
  echo "已有模拟器在运行，直接复用"
else
  echo "启动模拟器 $AVD_NAME ...（日志: $ROOT/.emulator.log）"
  # -no-snapshot 冷启动，本机 WHPX 加速约 70 秒；去掉该参数可启用快照快速启动
  "$EMU" -avd "$AVD_NAME" -no-snapshot > "$ROOT/.emulator.log" 2>&1 &
fi

echo "等待设备上线..."
ok=0
for _ in $(seq 1 60); do
  if "$ADB" devices | grep -q "emulator-.*device$"; then ok=1; break; fi
  sleep 2
done
[ $ok -eq 1 ] || { echo "错误: 模拟器迟迟未上线，查看 $ROOT/.emulator.log" >&2; exit 1; }

echo "等待系统启动完成..."
ok=0
for _ in $(seq 1 120); do
  boot="$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')"
  if [ "$boot" = "1" ]; then ok=1; break; fi
  sleep 3
done
[ $ok -eq 1 ] || { echo "错误: 系统启动超时，查看 $ROOT/.emulator.log" >&2; exit 1; }
echo "系统已启动"

"$ADB" install -r -t "$APK_WIN"
echo "安装完成"

if [ -n "$PKG" ]; then
  "$ADB" shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 > /dev/null
  echo "已启动 $PKG"
fi
