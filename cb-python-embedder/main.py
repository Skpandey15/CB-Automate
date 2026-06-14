import logging
import os
import time
from contextlib import asynccontextmanager
from typing import List

import uvicorn
from fastapi import FastAPI, HTTPException, Request
from fastapi.responses import JSONResponse
from openai import AsyncOpenAI
from prometheus_client import Counter, Histogram, generate_latest, CONTENT_TYPE_LATEST, CollectorRegistry
from pydantic import BaseModel, Field
from starlette.responses import Response

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

EMBED_MODEL = os.getenv("EMBEDDING_MODEL", "text-embedding-3-large")
EMBED_DIMS = int(os.getenv("EMBEDDING_DIMS", "3072"))
OPENAI_API_KEY = os.getenv("OPENAI_API_KEY", "")
MAX_BATCH_SIZE = int(os.getenv("MAX_BATCH_SIZE", "100"))

# Prometheus: use a per-process registry to avoid duplicate-registration errors
# when uvicorn forks multiple worker processes on Linux.
_metrics_registry = CollectorRegistry()
embed_requests = Counter("cb_embed_requests_total", "Total embed requests", ["status"],
                         registry=_metrics_registry)
embed_latency = Histogram("cb_embed_latency_seconds", "Embedding latency", ["operation"],
                          registry=_metrics_registry)
embed_tokens = Counter("cb_embed_tokens_total", "Tokens consumed by embedding",
                       registry=_metrics_registry)

client: AsyncOpenAI = None


@asynccontextmanager
async def lifespan(app: FastAPI):
    global client
    client = AsyncOpenAI(api_key=OPENAI_API_KEY)
    logger.info("AsyncOpenAI client initialized, model=%s dims=%d", EMBED_MODEL, EMBED_DIMS)
    yield
    await client.close()
    logger.info("AsyncOpenAI client closed")


app = FastAPI(
    title="CB Python Embedder",
    description="Text embedding microservice for Compliance Buddy RAG pipeline",
    version="1.0.0",
    lifespan=lifespan,
)


class EmbedRequest(BaseModel):
    text: str = Field(..., min_length=1, max_length=32000)


class EmbedResponse(BaseModel):
    embedding: List[float]
    model: str
    dims: int
    tokens_used: int


class BatchEmbedRequest(BaseModel):
    texts: List[str] = Field(..., min_items=1, max_items=100)


class BatchEmbedResponse(BaseModel):
    embeddings: List[List[float]]
    model: str
    dims: int
    tokens_used: int
    count: int


@app.get("/health")
async def health():
    return {"status": "ok", "model": EMBED_MODEL, "dims": EMBED_DIMS}


@app.get("/metrics")
async def metrics():
    data = generate_latest(_metrics_registry)
    return Response(content=data, media_type=CONTENT_TYPE_LATEST)


@app.post("/embed", response_model=EmbedResponse)
async def embed_text(req: EmbedRequest):
    start = time.perf_counter()
    try:
        response = await client.embeddings.create(
            model=EMBED_MODEL,
            input=req.text,
            dimensions=EMBED_DIMS,
        )
        elapsed = time.perf_counter() - start
        tokens = response.usage.total_tokens if response.usage else 0
        embed_requests.labels(status="success").inc()
        embed_latency.labels(operation="single").observe(elapsed)
        embed_tokens.inc(tokens)
        logger.debug("Embedded %d chars in %.3fs, tokens=%d", len(req.text), elapsed, tokens)
        return EmbedResponse(
            embedding=response.data[0].embedding,
            model=EMBED_MODEL,
            dims=EMBED_DIMS,
            tokens_used=tokens,
        )
    except Exception as e:
        embed_requests.labels(status="error").inc()
        logger.error("Embedding failed: %s", str(e))
        raise HTTPException(status_code=502, detail=f"Embedding failed: {str(e)}")


@app.post("/embed/batch", response_model=BatchEmbedResponse)
async def embed_batch(req: BatchEmbedRequest):
    if len(req.texts) > MAX_BATCH_SIZE:
        raise HTTPException(
            status_code=400,
            detail=f"Batch size {len(req.texts)} exceeds maximum {MAX_BATCH_SIZE}",
        )
    start = time.perf_counter()
    try:
        response = await client.embeddings.create(
            model=EMBED_MODEL,
            input=req.texts,
            dimensions=EMBED_DIMS,
        )
        elapsed = time.perf_counter() - start
        tokens = response.usage.total_tokens if response.usage else 0
        embed_requests.labels(status="success").inc(len(req.texts))
        embed_latency.labels(operation="batch").observe(elapsed)
        embed_tokens.inc(tokens)
        sorted_data = sorted(response.data, key=lambda x: x.index)
        embeddings = [item.embedding for item in sorted_data]
        logger.info("Batch embedded %d texts in %.3fs, tokens=%d", len(req.texts), elapsed, tokens)
        return BatchEmbedResponse(
            embeddings=embeddings,
            model=EMBED_MODEL,
            dims=EMBED_DIMS,
            tokens_used=tokens,
            count=len(embeddings),
        )
    except Exception as e:
        embed_requests.labels(status="error").inc(len(req.texts))
        logger.error("Batch embedding failed: %s", str(e))
        raise HTTPException(status_code=502, detail=f"Batch embedding failed: {str(e)}")


@app.exception_handler(Exception)
async def global_exception_handler(request: Request, exc: Exception):
    logger.error("Unhandled exception: %s", str(exc))
    return JSONResponse(status_code=500, content={"detail": "Internal server error"})


if __name__ == "__main__":
    uvicorn.run(
        "main:app",
        host="0.0.0.0",
        port=int(os.getenv("PORT", "8090")),
        workers=int(os.getenv("WORKERS", "4")),
        log_level="info",
    )
