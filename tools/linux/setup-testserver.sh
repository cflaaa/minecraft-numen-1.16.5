#!/usr/bin/env bash
# ============================================================================
# Numen 1.16.5 —— Linux 测试服引导（标准 Fabric 服务器，不依赖图形客户端）
#
#   用法：tools/linux/setup-testserver.sh
#
# 在仓库根的 .testserver/ 里装一套可以跑 Numen 的专用服务端。
# 与 Windows 的 .testserver/（借 PCL 客户端库 + KnotServer -cp）不同：
# 这里是**自包含**的标准 Fabric 服务端，`java -jar fabric-server-launch.jar` 即可，
# 因为本机没有图形客户端可借。
#
# 网络很脆（maven.fabricmc.net 常抖），所以：
#   · 所有下载都带重试 + 无进度条；
#   · fabric-api / vanilla server 若在 Gradle(loom) 缓存里，直接拿缓存，不走网。
# 环境变量可覆盖：NUMEN_TS / NUMEN_JAVA / NUMEN_MCVER / NUMEN_LOADER / NUMEN_FABRIC_API /
# NUMEN_PORT / NUMEN_MOTD / NUMEN_TOOLCHAIN / NUMEN_FABRIC_MAVEN。
# ============================================================================
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$HERE/../.." && pwd)"

TS="${NUMEN_TS:-$REPO_ROOT/.testserver}"
TOOL="${NUMEN_TOOLCHAIN:-$HOME/.local/share/numen-toolchain}"
JAVA="${NUMEN_JAVA:-$TOOL/jdk-17.0.20.1+1/bin/java}"
GH="${GRADLE_USER_HOME:-$TOOL/gradle-home}"
MCVER="${NUMEN_MCVER:-1.16.5}"
LOADER="${NUMEN_LOADER:-0.14.25}"
FABRIC_API="${NUMEN_FABRIC_API:-0.42.0+1.16}"
PORT="${NUMEN_PORT:-25565}"
MOTD="${NUMEN_MOTD:-Numen 1.16.5 Linux test server}"
MCP_PORT="${NUMEN_MCP_PORT:-8766}"
INSTALLER_VER="${NUMEN_INSTALLER_VER:-1.0.1}"
FABRIC_MAVEN="${NUMEN_FABRIC_MAVEN:-https://maven.fabricmc.net}"
MOD_JAR="$REPO_ROOT/numen-1.16.5/core/build/libs/Numen-fabric-${MCVER}-0.1.4-${MCVER}.jar"

say() { printf '\033[1;36m>>\033[0m %s\n' "$*"; }
die() { printf '\033[1;31m!!\033[0m %s\n' "$*" >&2; exit 1; }

CURL=(curl -fSL --noproxy '*' --no-progress-meter --retry 8 --retry-all-errors
      --retry-delay 3 --connect-timeout 20)

[ -x "$JAVA" ] || die "找不到 Java：$JAVA（用 NUMEN_JAVA 覆盖）"
mkdir -p "$TS/mods"

# ---- 1) Fabric 安装器（带重试）--------------------------------------------
INSTALLER="$TS/fabric-installer-$INSTALLER_VER.jar"
if [ ! -f "$INSTALLER" ]; then
  say "下载 Fabric 安装器 $INSTALLER_VER（maven.fabricmc.net 抖，会重试）"
  "${CURL[@]}" -o "$INSTALLER" \
    "$FABRIC_MAVEN/net/fabricmc/fabric-installer/$INSTALLER_VER/fabric-installer-$INSTALLER_VER.jar" \
    || die "装 Fabric 下载失败。可换镜像：NUMEN_FABRIC_MAVEN=https://<mirror>/maven 再跑。"
fi

# ---- 2) 装服务端（installer 会下载原版 server）-----------------------------
if [ ! -f "$TS/fabric-server-launch.jar" ]; then
  say "安装 Fabric Loader $LOADER on MC $MCVER（顺带下载原版 server）"
  ( cd "$TS" && "$JAVA" -jar "$INSTALLER" server \
      -dir "$TS" -mcversion "$MCVER" -loader "$LOADER" -downloadMinecraft ) \
    || die "Fabric 安装失败（多半是下载抖动，重跑本脚本即可）"
else
  say "Fabric 服务端已存在：$TS/fabric-server-launch.jar"
fi

# ---- 3) eula + server.properties ------------------------------------------
[ -f "$TS/eula.txt" ] || echo "eula=true" > "$TS/eula.txt"
PROPS="$TS/server.properties"
if [ ! -f "$PROPS" ]; then
  say "写 server.properties（offline-mode，端口 $PORT）"
  cat > "$PROPS" <<EOF
online-mode=false
server-port=$PORT
motd=$MOTD
enable-command-block=true
spawn-protection=0
max-tick-time=-1
level-type=flat
level-name=world
sync-chunk-writes=false
EOF
fi

# ---- 4) fabric-api ---------------------------------------------------------
# ⚠️ maven 上的 fabric-api 是个 ~2KB 的聚合桩（只声明依赖，不含模块）。生产服需要
# “bundle”版（模块嵌在 META-INF/jars/）。这里改成把 loom 缓存里的 **各个模块 jar**
# 直接铺进 mods/（等价于 bundle），避免下载且版本与 mod 编译时完全一致。
API_MARK="$TS/mods/.fabric-api-installed"
if [ ! -f "$API_MARK" ]; then
  rm -f "$TS"/mods/fabric-api-*.jar
  MODS_DIR="$TS/mods"
  n=0
  while IFS= read -r j; do cp -f "$j" "$MODS_DIR/"; n=$((n+1)); done < <(
    find "$GH/caches/modules-2/files-2.1/net.fabricmc.fabric-api" \
         -name '*.jar' ! -name '*-sources.jar' 2>/dev/null)
  if [ "$n" -gt 0 ]; then
    say "铺入 $n 个 fabric-api 模块 jar（取自 loom 缓存，等价 bundle）"
    touch "$API_MARK"
  else
    say "缓存里没有 fabric-api 模块，回退下载 bundle"
    # bundle 下载地址（若可用）：直接下官方 bundle jar
    "${CURL[@]}" -o "$TS/mods/fabric-api-$FABRIC_API.jar" \
      "$FABRIC_MAVEN/net/fabricmc/fabric-api/fabric-api/$FABRIC_API/fabric-api-$FABRIC_API.jar" \
      && touch "$API_MARK" || die "fabric-api 获取失败"
  fi
fi

# ---- 5) Numen jar ----------------------------------------------------------
if [ -f "$MOD_JAR" ]; then
  say "部署 Numen：$(basename "$MOD_JAR")"
  cp -f "$MOD_JAR" "$TS/mods/"
else
  say "⚠️ 还没构建 Numen jar：$MOD_JAR"
  say "   先构建：tools/linux/build.sh :core:build"
fi

# ---- 6) 打开外接大脑（MCP）——测试要经 MCP 驱动同伴 --------------------
CFG="$TS/config/numen/mcp_server.json"
mkdir -p "$TS/config/numen"
if [ ! -f "$CFG" ]; then
  cat > "$CFG" <<EOF
{"enabled":true,"host":"127.0.0.1","port":$MCP_PORT,"token":"","call_timeout_seconds":300,"hidden_tools":[],"quiet_fallback":false}
EOF
  say "写 $CFG（enabled=true, port=$MCP_PORT）"
else
  python3 - "$CFG" "$MCP_PORT" <<'PY' && say "更新 $CFG（enabled=true, port=$MCP_PORT）"
import json, sys
p, port = sys.argv[1], int(sys.argv[2])
d = json.load(open(p))
d["enabled"] = True
d["host"] = "127.0.0.1"
d["port"] = port
json.dump(d, open(p, "w"))
PY
fi

say "测试服就绪：$TS"
say "启动：tools/linux/server.sh start      查看：tools/linux/server.sh status"
say "MCP 端点（起服后）：http://127.0.0.1:$MCP_PORT/mcp"
