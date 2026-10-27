"""VA 自检脚本：健康检查 + 能力元信息 + （可选）真实跑一条流水线。

用法：
    python scripts/smoke_test.py --base http://127.0.0.1:9000 [--api-key KEY]
    python scripts/smoke_test.py --run --image path/to/pack.png --bizno customer-a
"""

from __future__ import annotations

import argparse
import json
import sys
import time
import urllib.error
import urllib.request


def call(base: str, path: str, *, api_key: str | None = None, data: dict | None = None, files: dict | None = None):
    url = base.rstrip("/") + path
    headers = {"X-API-Key": api_key} if api_key else {}
    body, content_type = None, None
    if files is not None:
        boundary = "----vasmoke"
        parts = []
        for name, (filename, content, ctype) in files.items():
            parts.append(
                f"--{boundary}\r\nContent-Disposition: form-data; name=\"{name}\"; filename=\"{filename}\"\r\n"
                f"Content-Type: {ctype}\r\n\r\n".encode()
                + content + b"\r\n"
            )
        for name, value in (data or {}).items():
            parts.append(
                f"--{boundary}\r\nContent-Disposition: form-data; name=\"{name}\"\r\n\r\n{value}\r\n".encode()
            )
        parts.append(f"--{boundary}--\r\n".encode())
        body = b"".join(parts)
        content_type = f"multipart/form-data; boundary={boundary}"
    elif data is not None:
        from urllib.parse import urlencode

        body = urlencode(data).encode()
        content_type = "application/x-www-form-urlencoded"
    if content_type:
        headers["Content-Type"] = content_type
    request = urllib.request.Request(url, data=body, headers=headers, method="POST" if body else "GET")
    with urllib.request.urlopen(request, timeout=30) as resp:
        return resp.status, json.loads(resp.read().decode("utf-8"))


def main() -> int:
    parser = argparse.ArgumentParser(description="vision-assistant 自检")
    parser.add_argument("--base", default="http://127.0.0.1:9000")
    parser.add_argument("--api-key", default=None)
    parser.add_argument("--run", action="store_true", help="真实跑一条 ip-consistency-full（需下游都在线 + 已建库）")
    parser.add_argument("--image", help="包装图路径")
    parser.add_argument("--bizno", help="已建库的项目编号")
    args = parser.parse_args()

    ok = True

    print("① /api/v1/health")
    try:
        _, health = call(args.base, "/api/v1/health")
        print(f"   service={health['service']} version={health['version']} auth_required={health['auth_required']}")
        for name, cap in health["capabilities"].items():
            mark = "✓" if cap["ok"] else "✗"
            print(f"   {mark} {name}: {'ok' if cap['ok'] else cap['detail']}")
            ok = ok and cap["ok"]
    except urllib.error.URLError as exc:
        print(f"   ✗ VA 不可达: {exc}")
        return 2

    print("② /api/v1/capabilities")
    _, caps = call(args.base, "/api/v1/capabilities", api_key=args.api_key)
    for provider in caps["providers"]:
        print(f"   provider {provider['name']} → {provider['base_url']} ops={list(provider['operations'])}")
    for recipe in caps["recipes"]:
        print(f"   recipe   {recipe['name']} — {recipe['title']}")

    if args.run:
        if not (args.image and args.bizno):
            print("③ --run 需要 --image 和 --bizno")
            return 1
        print("③ 运行 ip-consistency-full")
        with open(args.image, "rb") as fh:
            _, submitted = call(
                args.base, "/api/v1/recipes/ip-consistency-full/run",
                api_key=args.api_key,
                data={"params": json.dumps({"bizno": args.bizno})},
                files={"image": ("pack.png", fh.read(), "image/png")},
            )
        task_id = submitted["task_id"]
        print(f"   task_id={task_id}")
        deadline = time.time() + 600
        while time.time() < deadline:
            _, task = call(args.base, f"/api/v1/tasks/{task_id}", api_key=args.api_key)
            print(f"   status={task['status']} steps={[s['name'] + ':' + s['status'] for s in task['steps']]}")
            if task["status"] == "done":
                print(json.dumps(task["result"]["summary"], ensure_ascii=False, indent=2))
                return 0 if task["result"]["summary"]["overall"] == "pass" else 1
            if task["status"] == "error":
                print(f"   ✗ {task['error']}")
                return 1
            time.sleep(3)
        print("   ✗ 轮询超时")
        return 1

    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
