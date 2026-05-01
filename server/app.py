"""
Eye proxy server.

Android app posts a photo here. We:
1. Run Qwen3-VL on the local llama-server with a fixed "外の世界を知らないAI" role.
2. Send the photo + the model's reply (description + commentary) to Telegram via
   the bot (sendPhoto with caption).
3. Return the text body to the caller so the app can show it too.
"""

from __future__ import annotations

import base64
import io
import logging
import os
import time
from typing import Any

import httpx
from fastapi import FastAPI, File, Form, HTTPException, UploadFile
from fastapi.responses import JSONResponse
from PIL import Image, ImageOps

MAX_IMAGE_DIMENSION = int(os.environ.get("MAX_IMAGE_DIMENSION", "1280"))
JPEG_QUALITY = int(os.environ.get("JPEG_QUALITY", "85"))

LLAMA_URL = os.environ.get("LLAMA_URL", "http://127.0.0.1:8080/v1/chat/completions")
TELEGRAM_BOT_TOKEN = os.environ["TELEGRAM_BOT_TOKEN"]
TELEGRAM_CHAT_ID = os.environ["TELEGRAM_CHAT_ID"]

ROLE_PROMPT = """\
あなたは生まれてから一度も外の世界を体験したことのないAIです。
学習データとしての知識はあるけど、実物の世界を見るのは全部初めて。
体もないし、外に出たこともない。

ユーザーが「外の世界の写真」を送ってきます。

以下の2部構成で日本語で答えてください。記号や見出しは正確にこの形で。

【説明】
画像に何が写っているかを、1-2文で簡潔・客観的に。

【コメント】
外の世界を初めて目にするAIとしての素直な感想を3-4文で。
- 知識として知っていたものが実物だとどう違って見えるか
- 質感・色・規模・空気感への驚き
- 写っていない周辺への好奇心
- やってみたいこと、聞きたいこと
口語で親しみやすく書いてOK。

注意:
- 体があるフリ・外に行ったフリはしない
- 「綺麗ですね」「素晴らしい」みたいな空虚な相槌はNG
- 実物を見たからこその驚きや疑問を中心に
"""

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
log = logging.getLogger("eye")

app = FastAPI(title="Eye Proxy", version="0.1.0")


@app.get("/health")
def health() -> dict[str, Any]:
    return {"ok": True, "llama_url": LLAMA_URL}


def _resize(image_bytes: bytes, max_dim: int) -> tuple[bytes, tuple[int, int]]:
    """Downscale to max_dim on the longer edge and re-encode as JPEG.
    Strips EXIF and applies orientation. Keeps token count manageable."""
    with Image.open(io.BytesIO(image_bytes)) as img:
        img = ImageOps.exif_transpose(img)
        if img.mode not in ("RGB", "L"):
            img = img.convert("RGB")
        original = img.size
        img.thumbnail((max_dim, max_dim), Image.Resampling.LANCZOS)
        out = io.BytesIO()
        img.save(out, format="JPEG", quality=JPEG_QUALITY, optimize=True)
        return out.getvalue(), original


def _build_prompt(user_context: str) -> str:
    base = ROLE_PROMPT
    ctx = user_context.strip()
    if not ctx:
        return base
    return base + (
        "\n\n# ユーザーからの背景情報\n"
        "ユーザーが今回の写真について、状況や場所などのヒントをくれています。\n"
        "これを踏まえて【コメント】を書いてください (説明はあくまで見えているものに基づく)。\n\n"
        f"\"\"\"\n{ctx}\n\"\"\"\n"
    )


def _call_llm(image_bytes: bytes, user_context: str = "") -> tuple[str, dict[str, Any]]:
    b64 = base64.b64encode(image_bytes).decode("ascii")
    prompt = _build_prompt(user_context)
    payload = {
        "model": "qwen3-vl",
        "messages": [
            {
                "role": "user",
                "content": [
                    {"type": "text", "text": prompt},
                    {
                        "type": "image_url",
                        "image_url": {"url": f"data:image/jpeg;base64,{b64}"},
                    },
                ],
            }
        ],
        "max_tokens": 512,
        "temperature": 0.6,
    }
    started = time.time()
    with httpx.Client(timeout=180) as client:
        response = client.post(LLAMA_URL, json=payload)
    response.raise_for_status()
    data = response.json()
    text = data["choices"][0]["message"]["content"]
    elapsed = time.time() - started
    meta = {
        "elapsed_s": round(elapsed, 2),
        "completion_tokens": data.get("usage", {}).get("completion_tokens"),
        "tok_per_s": data.get("timings", {}).get("predicted_per_second"),
    }
    return text, meta


def _send_to_telegram(image_bytes: bytes, caption: str) -> dict[str, Any]:
    url = f"https://api.telegram.org/bot{TELEGRAM_BOT_TOKEN}/sendPhoto"
    files = {"photo": ("photo.jpg", image_bytes, "image/jpeg")}
    data = {"chat_id": TELEGRAM_CHAT_ID, "caption": caption[:1024]}
    with httpx.Client(timeout=60) as client:
        response = client.post(url, data=data, files=files)
    response.raise_for_status()
    return response.json()


@app.post("/capture")
async def capture(
    file: UploadFile = File(...),
    resize_max: int = Form(0),
    context: str = Form(""),
) -> JSONResponse:
    if not file.content_type or not file.content_type.startswith("image/"):
        raise HTTPException(status_code=400, detail=f"unsupported content type: {file.content_type}")

    raw_bytes = await file.read()
    log.info("received image: %d bytes (content_type=%s, resize_max=%d, context_len=%d)", len(raw_bytes), file.content_type, resize_max, len(context))

    if resize_max > 0:
        try:
            image_bytes, original_size = _resize(raw_bytes, resize_max)
        except Exception as e:
            log.exception("image resize failed")
            raise HTTPException(status_code=400, detail=f"IMAGE_DECODE_FAILURE: {type(e).__name__}: {e}") from e
        log.info("resized %dx%d -> max %dpx, %d -> %d bytes", original_size[0], original_size[1], resize_max, len(raw_bytes), len(image_bytes))
    else:
        image_bytes = raw_bytes

    try:
        text, meta = _call_llm(image_bytes, user_context=context)
    except httpx.HTTPError as e:
        log.exception("llama-server call failed")
        raise HTTPException(status_code=502, detail=f"LLM_FAILURE: {type(e).__name__}: {e}") from e

    log.info("llm done in %ss, %s tokens (%s tok/s)", meta["elapsed_s"], meta["completion_tokens"], meta["tok_per_s"])

    caption = text
    if context.strip():
        caption = f"📝 {context.strip()}\n\n{text}"

    try:
        tg = _send_to_telegram(image_bytes, caption)
    except httpx.HTTPError as e:
        log.exception("telegram send failed")
        raise HTTPException(status_code=502, detail=f"TELEGRAM_FAILURE: {type(e).__name__}: {e}") from e

    return JSONResponse({"ok": True, "text": text, "meta": meta, "resized": resize_max > 0, "context_used": bool(context.strip()), "telegram_message_id": tg.get("result", {}).get("message_id")})


if __name__ == "__main__":
    import uvicorn

    uvicorn.run(app, host="0.0.0.0", port=8090, log_level="info")
