import argparse
import asyncio
import datetime as _dt
import io
import sys
import threading
from typing import Optional


def _now_str() -> str:
    return _dt.datetime.now().strftime("%Y-%m-%d %H:%M:%S")


def _open_image(data: bytes):
    from PIL import Image

    return Image.open(io.BytesIO(data))


def _to_ebook_png(
    data: bytes,
    *,
    width: int,
    height: int,
    threshold: int,
    invert: bool,
):
    from PIL import Image, ImageOps

    img = _open_image(data)
    img.load()
    src_w, src_h = img.size

    gray = img.convert("L")
    if invert:
        gray = ImageOps.invert(gray)

    try:
        resample = Image.Resampling.LANCZOS
    except AttributeError:
        resample = Image.LANCZOS

    gray = gray.resize((width, height), resample=resample)

    bw = gray.point(lambda p: 255 if p > threshold else 0, mode="L")
    try:
        dither_none = Image.Dither.NONE
    except AttributeError:
        dither_none = 0
    bw = bw.convert("1", dither=dither_none)

    out = io.BytesIO()
    bw.save(out, format="PNG")
    return out.getvalue(), (src_w, src_h)


def _placeholder_png(*, width: int, height: int) -> bytes:
    from PIL import Image, ImageDraw

    img = Image.new("L", (width, height), 255)
    draw = ImageDraw.Draw(img)
    text = "E-Book 模拟屏\n\n等待 /image 上传图片"
    draw.multiline_text((20, 30), text, fill=0, spacing=8)
    out = io.BytesIO()
    img.save(out, format="PNG")
    return out.getvalue()


def create_app(
    *,
    host: str,
    port: int,
    screen_width: int,
    screen_height: int,
    threshold: int,
    invert: bool,
):
    from fastapi import FastAPI, File, Request, UploadFile, WebSocket, WebSocketDisconnect
    from fastapi.responses import HTMLResponse, Response

    app = FastAPI(title="BlePer Web E-Book")

    lock = threading.Lock()
    latest_png = _placeholder_png(width=screen_width, height=screen_height)
    latest_meta = {
        "updated_at": _now_str(),
        "source": "placeholder",
        "content_type": "image/png",
        "bytes": len(latest_png),
        "src_width": None,
        "src_height": None,
    }

    ws_lock = asyncio.Lock()
    ws_clients: set[WebSocket] = set()

    async def ws_broadcast(text: str) -> int:
        dead: list[WebSocket] = []
        async with ws_lock:
            for ws in list(ws_clients):
                try:
                    await ws.send_text(text)
                except Exception:
                    dead.append(ws)
            for ws in dead:
                ws_clients.discard(ws)
            return len(ws_clients)

    html = f"""<!doctype html>
<html lang=\"zh-CN\">
  <head>
    <meta charset=\"utf-8\" />
    <meta name=\"viewport\" content=\"width=device-width, initial-scale=1\" />
    <title>BlePer E-Book</title>
    <style>
      html, body {{ height: 100%; margin: 0; }}
      body {{
        display: flex;
        flex-direction: column;
        align-items: center;
        justify-content: center;
        gap: 12px;
        background: #fff;
        color: #000;
        font-family: -apple-system, BlinkMacSystemFont, \"Segoe UI\", Roboto, Helvetica, Arial, sans-serif;
      }}
            .screen {{
                border: 1px solid #000;
                /* Keep the internal buffer as screen_width x screen_height,
                     but make the on-page display responsive so it fits in smaller viewports. */
                width: min(calc(100vw - 24px), {screen_width}px);
                height: auto;
                aspect-ratio: {screen_width} / {screen_height};
                image-rendering: pixelated;
                background: #fff;
            }}
      .row {{ display: flex; gap: 8px; }}
            button {{ padding: 10px 14px; font-size: 18px; }}
            .nav button {{ min-width: 140px; }}
      .meta {{ font-size: 12px; opacity: 0.7; }}
    </style>
  </head>
  <body>
    <canvas id=\"c\" class=\"screen\" width=\"{screen_width}\" height=\"{screen_height}\"></canvas>
                <div class=\"row nav\">
                        <button onclick=\"cmd('PREV')\">上一页</button>
                        <button onclick=\"cmd('NEXT')\">下一页</button>
                </div>
                <div class=\"row\">
                        <button onclick=\"cmd('BACK')\">返回</button>
                        <button onclick=\"cmd('RELOAD')\">刷新</button>
                </div>
    <div id=\"meta\" class=\"meta\"></div>
    <script>
      const canvas = document.getElementById('c');
      const ctx = canvas.getContext('2d');
      const metaEl = document.getElementById('meta');
      let lastUpdated = null;

      async function refresh() {{
        try {{
          const r = await fetch('/meta');
          const j = await r.json();
          if (j.ok) {{
            metaEl.textContent = `updated_at=${{j.updated_at}} source=${{j.source}} bytes=${{j.bytes}} src=${{j.src_width}}x${{j.src_height}}`;
            if (lastUpdated !== j.updated_at) {{
              lastUpdated = j.updated_at;
              const img = new Image();
              img.onload = () => {{
                ctx.clearRect(0, 0, canvas.width, canvas.height);
                ctx.drawImage(img, 0, 0);
              }};
              img.src = '/latest.png?ts=' + Date.now();
            }}
          }}
        }} catch (e) {{
          metaEl.textContent = 'meta error: ' + e;
        }}
      }}

      async function press(id) {{
        await fetch('/button/' + id, {{ method: 'POST' }});
      }}

            async function cmd(c) {{
                await fetch('/cmd/' + encodeURIComponent(c), {{ method: 'POST' }});
            }}

      setInterval(refresh, 300);
      refresh();
    </script>
  </body>
</html>"""

    @app.get("/", response_class=HTMLResponse)
    async def index():
        return HTMLResponse(html)

    @app.get("/meta")
    async def meta():
        with lock:
            return {"ok": True, **latest_meta}

    @app.get("/latest.png")
    async def latest():
        with lock:
            data = latest_png
        return Response(content=data, media_type="image/png")

    @app.post("/button/{button_id}")
    async def button_press(button_id: str):
        if button_id not in {"b1", "b2", "b3", "b4"}:
            return {"ok": False, "error": "unknown button"}

        print(f"[{_now_str()}] button={button_id}")
        # Best practice mapping (scheme B): keyboard-like navigation
        # - b1: TAB (move focus)
        # - b2: ENTER (activate)
        # - b3: BACK
        # - b4: FORWARD (Android side will fallback to reload when it can't go forward)
        cmd = {"b1": "TAB", "b2": "ENTER", "b3": "BACK", "b4": "FORWARD"}[button_id]

        try:
            n = await ws_broadcast(cmd)
            print(f"[{_now_str()}] ws_broadcast cmd={cmd} clients={n}")
        except Exception as e:
            print(f"[{_now_str()}] ws_broadcast error: {e}", file=sys.stderr)

        return {"ok": True}

    @app.post("/cmd/{cmd}")
    async def cmd_broadcast(cmd: str):
        """Broadcast an arbitrary command text to all WS clients.

        Examples:
        - POST /cmd/TAB
        - POST /cmd/ENTER
        - POST /cmd/RELOAD
        """
        cmd = (cmd or "").strip().upper()
        if not cmd:
            return {"ok": False, "error": "empty cmd"}

        print(f"[{_now_str()}] cmd={cmd}")
        try:
            n = await ws_broadcast(cmd)
            print(f"[{_now_str()}] ws_broadcast cmd={cmd} clients={n}")
        except Exception as e:
            print(f"[{_now_str()}] ws_broadcast error: {e}", file=sys.stderr)
            return {"ok": False, "error": str(e)}

        return {"ok": True, "cmd": cmd, "clients": n}

    @app.websocket("/ws")
    async def ws_endpoint(websocket: WebSocket):
        await websocket.accept()
        async with ws_lock:
            ws_clients.add(websocket)
            n = len(ws_clients)
        print(f"[{_now_str()}] ws_connected clients={n}")

        try:
            while True:
                msg = await websocket.receive_text()
                if msg.strip().upper() == "PING":
                    await websocket.send_text("PONG")
        except WebSocketDisconnect:
            pass
        except Exception as e:
            print(f"[{_now_str()}] ws_error: {e}", file=sys.stderr)
        finally:
            async with ws_lock:
                ws_clients.discard(websocket)
                n = len(ws_clients)
            print(f"[{_now_str()}] ws_disconnected clients={n}")

    @app.post("/image")
    async def post_image(request: Request, file: UploadFile | None = File(default=None)):
        nonlocal latest_png, latest_meta

        if file is not None:
            data = await file.read()
            source = "multipart"
            content_type = file.content_type
        else:
            data = await request.body()
            source = request.headers.get("content-type") or "application/octet-stream"
            content_type = source

        if not data:
            return {"ok": False, "error": "empty body"}

        try:
            png_bytes, (src_w, src_h) = _to_ebook_png(
                data,
                width=screen_width,
                height=screen_height,
                threshold=threshold,
                invert=invert,
            )
        except Exception as e:
            print(f"[{_now_str()}] /image error: {e}", file=sys.stderr)
            return {"ok": False, "error": str(e)}

        with lock:
            latest_png = png_bytes
            latest_meta = {
                "updated_at": _now_str(),
                "source": source,
                "content_type": content_type,
                "bytes": len(png_bytes),
                "src_width": src_w,
                "src_height": src_h,
            }

        print(f"[{_now_str()}] /image source={source} bytes_in={len(data)} bytes_out={len(png_bytes)}")
        return {"ok": True, "src_width": src_w, "src_height": src_h}

    @app.get("/health")
    async def health():
        return {"ok": True, "host": host, "port": port}

    return app


def main(argv: Optional[list[str]] = None) -> int:
    parser = argparse.ArgumentParser(
        description="Web page with a canvas that simulates an e-book screen (W480xH800) and accepts /image uploads."
    )
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=18080)
    parser.add_argument("--screen-width", type=int, default=480)
    parser.add_argument("--screen-height", type=int, default=800)
    parser.add_argument("--threshold", type=int, default=128, help="binarize threshold (0-255)")
    parser.add_argument("--invert", action="store_true")
    args = parser.parse_args(argv)

    if not (0 <= args.threshold <= 255):
        print("--threshold must be 0..255", file=sys.stderr)
        return 2
    if args.screen_width <= 0 or args.screen_height <= 0:
        print("--screen-width/--screen-height must be positive", file=sys.stderr)
        return 2

    app = create_app(
        host=args.host,
        port=args.port,
        screen_width=args.screen_width,
        screen_height=args.screen_height,
        threshold=args.threshold,
        invert=bool(args.invert),
    )

    import uvicorn

    uvicorn.run(app, host=args.host, port=args.port, log_level="info")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
