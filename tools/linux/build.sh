#!/usr/bin/env bash
# ============================================================================
# Numen 1.16.5 —— Linux 构建包装
#
#   用法：tools/linux/build.sh :core:build
#
# 用 Linux 工具链（$NUMEN_TOOLCHAIN，默认 ~/.local/share/numen-toolchain）跑仓库根
# 的 Gradle 7.6.4（发行包跨平台，但缺执行位 → 用 sh 调）。带 BMCLAPI 修正 init 脚本：
# BMCLAPI 会把未缓存的 com.mojang 制品 302 到一个已失效的主机，init 脚本把它排除，
# 让解析落到 libraries.minecraft.net。
#
# 环境变量：NUMEN_TOOLCHAIN（工具链根） / NUMEN_INIT（init 脚本） / 额外 gradle 参数照传。
# ============================================================================
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$HERE/../.." && pwd)"
PROJECT="$REPO_ROOT/numen-1.16.5"

TOOL="${NUMEN_TOOLCHAIN:-$HOME/.local/share/numen-toolchain}"
export JAVA_HOME="${JAVA_HOME:-$TOOL/jdk-17.0.20.1+1}"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$TOOL/gradle-home}"
export PATH="$JAVA_HOME/bin:$PATH"

GRADLE="$REPO_ROOT/.toolchain/gradle-7.6.4/bin/gradle"
INIT="${NUMEN_INIT:-$TOOL/bmclapi-fix.init.gradle}"

[ -x "$JAVA_HOME/bin/java" ] || { echo "!! 找不到 Linux JDK：$JAVA_HOME（设 NUMEN_TOOLCHAIN 或 JAVA_HOME）" >&2; exit 1; }
[ -f "$GRADLE" ] || { echo "!! 找不到 gradle：$GRADLE" >&2; exit 1; }

ARGS=(--console=plain)
[ -f "$INIT" ] && ARGS+=(--init-script "$INIT")

cd "$PROJECT"
exec sh "$GRADLE" "${ARGS[@]}" "$@"
