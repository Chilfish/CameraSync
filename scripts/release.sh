#!/bin/bash
# CameraSync 发版脚本：版本单源（gradle.properties）→ 门禁 → commit → tag → push。
# 用法：bash scripts/release.sh <version>   例：bash scripts/release.sh 1.1.0
#
# 纪律（见 docs/engineering/release-checklist.md「版本纪律」）：
#   - 版本单源在 gradle.properties，versionCode 由脚本自动 +1，tag/versionName/versionCode 三件套联动
#   - tag 只打在门禁绿的 commit 上（本脚本先跑 gate 再 commit+tag）
set -euo pipefail

cd "$(dirname "$0")/.."

VERSION="${1:?usage: release.sh <version>}"
PROPS="gradle.properties"

# 工作树必须干净：未提交改动会混进 release commit
if [ -n "$(git status --porcelain)" ]; then
    echo "❌ 工作树有未提交改动，先 commit/stash 再发版。"
    exit 1
fi

if git rev-parse "v$VERSION" >/dev/null 2>&1; then
    echo "❌ tag v$VERSION 已存在。"
    exit 1
fi

OLD_NAME=$(grep -E '^VERSION_NAME=' "$PROPS" | cut -d= -f2)
OLD_CODE=$(grep -E '^VERSION_CODE=' "$PROPS" | cut -d= -f2)
NEW_CODE=$((OLD_CODE + 1))

echo "🔖 当前 v$OLD_NAME (versionCode $OLD_CODE) → v$VERSION (versionCode $NEW_CODE)"

# 更新版本单源（sed -i.bak 兼容 GNU/BSD）
sed -i.bak "s/^VERSION_NAME=.*/VERSION_NAME=$VERSION/" "$PROPS"
sed -i.bak "s/^VERSION_CODE=.*/VERSION_CODE=$NEW_CODE/" "$PROPS"
rm -f "$PROPS.bak"

# 门禁：release 必须全绿（本地 pre-push 钩子可能被跳过，这里显式跑）
echo "▶ 门禁：ktfmtCheck + detekt + lintDebug + testDebugUnitTest + assembleDebug + assembleRelease ..."
./gradlew ktfmtCheck detekt lintDebug testDebugUnitTest \
    assembleDebug assembleRelease --console=plain

# commit + tag + push（tag 打在这个门禁绿的 commit 上）
git add "$PROPS"
git commit -m "release: v$VERSION"
git tag "v$VERSION"
git push origin master --tags
echo "✅ v$VERSION 已发布，CI（release.yml）将构建签名 APK"
