from __future__ import annotations

from contextlib import asynccontextmanager
from io import BytesIO
from typing import Any

import numpy as np
from fastapi import FastAPI, File, HTTPException, UploadFile
from PIL import Image, UnidentifiedImageError
from paddleocr import PaddleOCR


ocr: PaddleOCR | None = None


@asynccontextmanager
async def lifespan(_: FastAPI):
    global ocr
    ocr = PaddleOCR(
        lang="ch",
        use_doc_orientation_classify=False,
        use_doc_unwarping=False,
        use_textline_orientation=False,
    )
    yield
    ocr = None


app = FastAPI(title="AI Bookkeeping Local OCR", lifespan=lifespan)


def extract_lines(payload: dict[str, Any]) -> tuple[list[str], list[float]]:
    result = payload.get("res", payload)
    texts = result.get("rec_texts", [])
    scores = result.get("rec_scores", [])
    accepted_texts: list[str] = []
    accepted_scores: list[float] = []

    for index, text in enumerate(texts):
        normalized = str(text).strip()
        score = float(scores[index]) if index < len(scores) else 0.0
        if normalized and score >= 0.5:
            accepted_texts.append(normalized)
            accepted_scores.append(score)

    return accepted_texts, accepted_scores


@app.get("/health")
def health() -> dict[str, bool]:
    return {"ready": ocr is not None}


@app.post("/ocr")
async def recognize(image: UploadFile = File(...)) -> dict[str, str | float]:
    if ocr is None:
        raise HTTPException(status_code=503, detail="OCR model is not ready")

    try:
        raw = await image.read()
        source = Image.open(BytesIO(raw)).convert("RGB")
        predictions = ocr.predict(np.asarray(source))
    except (UnidentifiedImageError, ValueError) as exc:
        raise HTTPException(status_code=400, detail="Unsupported image") from exc
    finally:
        await image.close()

    lines: list[str] = []
    scores: list[float] = []
    for prediction in predictions:
        prediction_lines, prediction_scores = extract_lines(prediction.json)
        lines.extend(prediction_lines)
        scores.extend(prediction_scores)

    if not lines:
        raise HTTPException(status_code=422, detail="No text recognized")

    confidence = sum(scores) / len(scores) if scores else 0.0
    return {"text": "\n".join(lines), "confidence": round(confidence, 4)}
