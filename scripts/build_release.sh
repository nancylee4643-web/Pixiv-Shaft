#!/usr/bin/env bash
# 构建 release APK，跳过 Crashlytics mapping 上传任务。
# assemble*Release 收尾会把 R8 mapping 上传到 firebasecrashlyticssymbols.googleapis.com，
# 本机/国内网络直连超时会让整个构建失败（此时 APK 本身已经编好）。跳过上传的代价只是
# 该包的崩溃堆栈在 Crashlytics 后台不符号化，APK 完整可用。
# 用法: bash scripts/build_release.sh [flavor] [gradle参数...]
#   flavor 可选，github（默认）或 google；之后的参数原样透传给 gradlew。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

FLAVOR="${1:-github}"
case "$FLAVOR" in
  github|google) ;;
  *) echo "错误: flavor 只能是 github 或 google，收到: $FLAVOR" >&2; exit 1 ;;
esac
if [ $# -gt 0 ]; then shift; fi

# github -> Github，拼出 assembleGithubRelease / uploadCrashlyticsMappingFileGithubRelease
CAP="$(echo "${FLAVOR:0:1}" | tr 'a-z' 'A-Z')${FLAVOR:1}"

./gradlew "assemble${CAP}Release" -x "uploadCrashlyticsMappingFile${CAP}Release" "$@"

APK="app/build/outputs/apk/$FLAVOR/release/app-$FLAVOR-release.apk"
echo
echo "构建完成:"
ls -l --time-style=+"%F %T" "$APK"
