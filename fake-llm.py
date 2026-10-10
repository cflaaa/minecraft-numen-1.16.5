#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""一个假的 OpenAI 兼容端点，用来在本地把 numen 的「模型 → 工具调用 → 回灌」整条链走通。

为什么要它：
  1. 真端点要 key、要联网、要花钱，而且返回什么完全不可控；
  2. 1.16.5 这版新写的工具实现（inspect_block / scan_blocks / goto / mine …）只有在
     模型真的回一个 tool_call 时才会被跑到——不测就等于没验证；
  3. 假端点能把「第几轮回哪个工具」写成确定性的剧本，于是「工具到底跑没跑」可以在
     服务端日志里断言，而不是靠肉眼。

剧本（按请求里 role=="tool" 的消息条数决定这一轮该回什么）：
  0 条 → get_status                     （测无参工具 + 结果序列化）
  1 条 → inspect_block(脚下那格)         （从上一轮 tool 结果里正则抠坐标）
  2 条 → scan_blocks(radius=4)           （测整数参数 + 世界扫描）
  3 条 → mine(身旁那格)                  （测「玩家挖方块」那条路）
  4 条 → goto(往 +2 格)                  （测跨刻长动作 Walker）
  5 条 → say(总结) + finish_reason=stop  （测收尾发言）
  6+  → 纯文本 stop

用法：python fake-llm.py [port]
"""
import json
import os
import re
import sys
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HERE = os.path.dirname(os.path.abspath(__file__))
TS = os.path.join(HERE, ".testserver")
REQLOG = os.path.join(TS, "fake-llm-requests.jsonl")

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 18765
MODEL = "gpt-4o-mini"

SCRIPT = ["get_status", "inspect_block", "scan_blocks", "mine", "goto", "say"]


def coords_from(text):
    """从工具结果文本里抠第一个 'x,y,z' 三元组（get_status 的输出是 'Alice 在 -134,4,-104'）。"""
    m = re.search(r"(-?\d+)\s*,\s*(-?\d+)\s*,\s*(-?\d+)", text or "")
    if not m:
        return None
    return int(m.group(1)), int(m.group(2)), int(m.group(3))


def pick_action(messages):
    """看对话里已经有多少条 tool 结果，决定这一轮回哪个工具。"""
    tool_msgs = [m for m in messages if m.get("role") == "tool"]
    n = len(tool_msgs)

    # 坐标一律从第一条 tool 结果（get_status）里取，保证整条链前后自洽。
    base = coords_from(tool_msgs[0].get("content", "")) if tool_msgs else None
    if base is None:
        base = (-134, 4, -104)          # 兜底：上一轮没拿到就用一个固定值
    x, y, z = base

    if n == 0:
        return "get_status", {}
    if n == 1:
        return "inspect_block", {"x": x, "y": y - 1, "z": z}
    if n == 2:
        return "scan_blocks", {"radius": 4}
    if n == 3:
        return "mine", {"x": x + 1, "y": y - 1, "z": z}
    if n == 4:
        return "goto", {"x": x + 2, "y": y, "z": z}
    if n == 5:
        return "say", {"text": "看完了：脚下是草方块，旁边也差不多。我往旁边走了两步。"}
    return None, None                    # 之后就是收尾


def chunk(delta, finish=None):
    return {
        "id": "chatcmpl-fake", "object": "chat.completion.chunk",
        "created": int(time.time()), "model": MODEL,
        "choices": [{"index": 0, "delta": delta, "finish_reason": finish}],
    }


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *a):
        pass                              # 静音，我们自己写 jsonl

    def _record(self, body, reply):
        try:
            os.makedirs(TS, exist_ok=True)
            with open(REQLOG, "a", encoding="utf-8") as f:
                f.write(json.dumps({"at": time.time(), "reply": reply,
                                    "body": body}, ensure_ascii=False) + "\n")
        except OSError:
            pass

    def do_GET(self):
        payload = b'{"ok":true}'
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def do_POST(self):
        length = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(length) if length else b""
        try:
            body = json.loads(raw.decode("utf-8"))
        except ValueError:
            body = {}

        messages = body.get("messages") or []
        n_tools = len(body.get("tools") or [])
        name, args = pick_action(messages)

        # 把「模型看到了几个工具、这一轮回了什么」记下来，便于事后核对线格式。
        self._record(
            {"n_messages": len(messages), "n_tools": n_tools,
             "tool_names": [t.get("function", {}).get("name")
                            for t in (body.get("tools") or [])],
             "roles": [m.get("role") for m in messages],
             "stream": body.get("stream")},
            name or "stop")

        # 关键：不写 Content-Length，用 Connection: close 让响应体以「连接关闭」收尾。
        # 真 OpenAI 也是发完 [DONE] 就断；不断的话客户端那条 SSE 流会一直挂着，
        # 直到 transport 的 15s 空闲看门狗把它当「半死连接」杀掉——那一轮就白跑了。
        self.close_connection = True
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream; charset=utf-8")
        self.send_header("Cache-Control", "no-cache")
        self.send_header("Connection", "close")
        self.end_headers()

        def emit(obj):
            self.wfile.write(("data: " + json.dumps(obj, ensure_ascii=False) + "\n\n")
                             .encode("utf-8"))
            self.wfile.flush()

        emit(chunk({"role": "assistant", "content": ""}))

        if name is None:
            emit(chunk({"content": "好，就这样。"}))
            emit(chunk({}, finish="stop"))
        elif name == "say":
            # say 也是工具；说完这一轮就停。
            emit(chunk({"tool_calls": [{
                "index": 0, "id": "call_%d" % n_tools, "type": "function",
                "function": {"name": name, "arguments": json.dumps(args, ensure_ascii=False)},
            }]}))
            emit(chunk({}, finish="tool_calls"))
        else:
            emit(chunk({"tool_calls": [{
                "index": 0, "id": "call_%d" % n_tools, "type": "function",
                "function": {"name": name, "arguments": json.dumps(args, ensure_ascii=False)},
            }]}))
            emit(chunk({}, finish="tool_calls"))

        self.wfile.write(b"data: [DONE]\n\n")
        self.wfile.flush()


if __name__ == "__main__":
    srv = ThreadingHTTPServer(("127.0.0.1", PORT), Handler)
    print("fake-llm listening on http://127.0.0.1:%d/v1/chat/completions" % PORT)
    print("剧本：" + " -> ".join(SCRIPT))
    sys.stdout.flush()
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        pass
