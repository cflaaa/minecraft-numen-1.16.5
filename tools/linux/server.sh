#!/usr/bin/env bash
# ============================================================================
# Numen 1.16.5 —— Linux 测试服启停（后台 + 控制台 FIFO + pid 文件）
#
#   用法：
#     tools/linux/server.sh start     后台起服（日志 .testserver/logs/latest.log）
#     tools/linux/server.sh stop      关服（杀整个进程组，避免孤儿占 session.lock）
#     tools/linux/server.sh status    在跑吗
#     tools/linux/server.sh console '/numen summon Alice'   往服务端 stdin 发一行
#     tools/linux/server.sh tail [n]  看日志尾部
#
# 先跑 tools/linux/setup-testserver.sh 建好 .testserver/。
# 控制台靠一个命名管道（.testserver/.console）；`tail -f` 持有它喂 java 的 stdin。
# ⚠️ 别在运行中热替换 mods/ 里的 jar（Fabric 懒加载会 Failed to load class file）——换 jar 先 stop。
# ============================================================================
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$HERE/../.." && pwd)"
TS="${NUMEN_TS:-$REPO_ROOT/.testserver}"
JAVA="${NUMEN_JAVA:-$HOME/.local/share/numen-toolchain/jdk-17.0.20.1+1/bin/java}"
PIDFILE="$TS/.server.pid"
FIFO="$TS/.console"
LOG="$TS/logs/latest.log"
OUT="$TS/server-stdout.log"
LAUNCH="$TS/fabric-server-launch.jar"

is_running() { [ -f "$PIDFILE" ] && kill -0 "$(cat "$PIDFILE")" 2>/dev/null; }

case "${1:-status}" in
  start)
    is_running && { echo "已经在跑（pid $(cat "$PIDFILE")）"; exit 0; }
    [ -f "$LAUNCH" ] || { echo "!! 没装服务端：$LAUNCH（先跑 setup-testserver.sh）" >&2; exit 1; }
    [ -x "$JAVA" ] || { echo "!! 找不到 Java：$JAVA" >&2; exit 1; }
    mkdir -p "$TS/logs"
    [ -p "$FIFO" ] || mkfifo "$FIFO"
    # 旋转日志：不然 "Done (" 会匹配到上一次启动的旧行，误报“就绪”。
    [ -f "$LOG" ] && mv -f "$LOG" "$LOG.prev" || true
    echo "起服（cwd=$TS，日志 $LOG）…"
    # 新会话（setsid）→ pgid = pid：stop 时 kill 整个组，tail/java 一起收。
    # ⚠️ 必须 cd 进 TS：fabric-server-launch 按 cwd 找 server.jar / 写 logs/。
    setsid bash -c 'cd "$1" && tail -f "$2" | exec "$3" -Xmx2G -Dfile.encoding=UTF-8 -jar "$4" nogui' \
      _ "$TS" "$FIFO" "$JAVA" "$LAUNCH" >>"$OUT" 2>&1 </dev/null &
    disown 2>/dev/null || true
    echo $! > "$PIDFILE"
    for _ in $(seq 1 180); do
      sleep 1
      grep -q "Done (" "$LOG" 2>/dev/null && { echo "✅ 服务端就绪（pid $(cat "$PIDFILE")）"; exit 0; }
      is_running || { echo "!! 服务端提前退出，看 $OUT"; tail -20 "$OUT"; exit 1; }
    done
    echo "⚠️ 180s 没等到 Done，仍在后台跑（pid $(cat "$PIDFILE")）"
    ;;
  stop)
    if is_running; then
      pgid="$(cat "$PIDFILE")"
      kill -TERM -- "-$pgid" 2>/dev/null || kill -TERM "$pgid" 2>/dev/null || true
      for _ in $(seq 1 60); do sleep 1; kill -0 "$pgid" 2>/dev/null || break; done
      kill -0 "$pgid" 2>/dev/null && kill -KILL -- "-$pgid" 2>/dev/null || true
      echo "已关服（pid $pgid）"
    else
      echo "没在跑"
    fi
    rm -f "$PIDFILE"
    ;;
  status)
    if is_running; then echo "在跑（pid $(cat "$PIDFILE")）"; else echo "没在跑"; fi
    ;;
  console)
    shift
    [ -p "$FIFO" ] || { echo "!! 没有控制台管道（服务端没在跑？）" >&2; exit 1; }
    printf '%s\n' "$*" > "$FIFO"
    echo ">> $*"
    ;;
  tail)
    n="${2:-40}"; tail -n "$n" "$LOG" 2>/dev/null || echo "(没有日志)"
    ;;
  *)
    echo "用法：$0 {start|stop|status|console <命令>|tail [n]}" >&2; exit 2
    ;;
esac
