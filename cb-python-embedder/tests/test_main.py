import pytest
from fastapi.testclient import TestClient
from unittest.mock import AsyncMock, MagicMock, patch
import sys
import os

sys.path.insert(0, os.path.dirname(os.path.dirname(__file__)))


@pytest.fixture
def mock_openai_client():
    mock_data = MagicMock()
    mock_data.index = 0
    mock_data.embedding = [0.1] * 3072

    mock_usage = MagicMock()
    mock_usage.total_tokens = 10

    mock_response = MagicMock()
    mock_response.data = [mock_data]
    mock_response.usage = mock_usage

    mock_client = MagicMock()
    mock_client.embeddings = MagicMock()
    mock_client.embeddings.create = AsyncMock(return_value=mock_response)
    mock_client.close = AsyncMock()
    return mock_client


@pytest.fixture
def test_client(mock_openai_client):
    with patch("main.AsyncOpenAI", return_value=mock_openai_client):
        from main import app, client
        import main
        main.client = mock_openai_client
        with TestClient(app) as c:
            yield c


def test_health(test_client):
    response = test_client.get("/health")
    assert response.status_code == 200
    data = response.json()
    assert data["status"] == "ok"
    assert "model" in data
    assert "dims" in data


def test_embed_single(test_client, mock_openai_client):
    response = test_client.post("/embed", json={"text": "SQL injection vulnerability in login method"})
    assert response.status_code == 200
    data = response.json()
    assert "embedding" in data
    assert len(data["embedding"]) == 3072
    assert data["dims"] == 3072
    assert data["tokens_used"] == 10


def test_embed_empty_text_rejected(test_client):
    response = test_client.post("/embed", json={"text": ""})
    assert response.status_code == 422


def test_embed_batch(test_client, mock_openai_client):
    # Batch: mock returns same response for all
    mock_data_list = []
    for i in range(3):
        d = MagicMock()
        d.index = i
        d.embedding = [float(i) / 100] * 3072
        mock_data_list.append(d)

    mock_response = MagicMock()
    mock_response.data = mock_data_list
    mock_response.usage.total_tokens = 30
    mock_openai_client.embeddings.create = AsyncMock(return_value=mock_response)

    response = test_client.post("/embed/batch", json={
        "texts": ["CWE-89 SQL injection", "CWE-79 XSS", "CWE-22 path traversal"]
    })
    assert response.status_code == 200
    data = response.json()
    assert data["count"] == 3
    assert len(data["embeddings"]) == 3


def test_embed_batch_too_large(test_client):
    texts = ["text"] * 101
    response = test_client.post("/embed/batch", json={"texts": texts})
    assert response.status_code == 400


def test_metrics_endpoint(test_client):
    response = test_client.get("/metrics")
    assert response.status_code == 200
    assert b"cb_embed_requests_total" in response.content


def test_embed_upstream_error(test_client, mock_openai_client):
    mock_openai_client.embeddings.create = AsyncMock(side_effect=Exception("OpenAI rate limit"))
    response = test_client.post("/embed", json={"text": "test input"})
    assert response.status_code == 502
    assert "Embedding failed" in response.json()["detail"]
