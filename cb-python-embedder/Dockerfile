FROM python:3.12-slim AS builder

WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir --upgrade pip \
    && pip install --no-cache-dir -r requirements.txt

FROM python:3.12-slim

# Non-root user
RUN groupadd -r cbembed && useradd -r -g cbembed -u 1001 cbembed

WORKDIR /app
COPY --from=builder /usr/local/lib/python3.12/site-packages /usr/local/lib/python3.12/site-packages
COPY --from=builder /usr/local/bin/uvicorn /usr/local/bin/uvicorn
COPY main.py .

RUN chown -R cbembed:cbembed /app
USER cbembed

EXPOSE 8090

ENV PYTHONUNBUFFERED=1 \
    PORT=8090 \
    WORKERS=4

HEALTHCHECK --interval=30s --timeout=10s --start-period=15s --retries=3 \
    CMD python -c "import urllib.request; urllib.request.urlopen('http://localhost:8090/health')"

CMD ["python", "main.py"]
