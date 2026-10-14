"""
Tests for the Python service's access control and path validation. The heavy
indexing/RAG modules (LangChain, embeddings, pgvector) are stubbed out so
these run without a database or model downloads.
"""

import sys
import types

import pytest
from fastapi.testclient import TestClient

SECRET = "test-secret"


@pytest.fixture()
def client(tmp_path, monkeypatch):
    calls = []

    indexing = types.ModuleType("app.services.indexing_service")
    indexing.index_repository = lambda repo_id, path: calls.append((repo_id, path))
    claimed = set()

    def claim(repo_id):
        if repo_id in claimed:
            return False
        claimed.add(repo_id)
        return True

    indexing.try_claim_indexing = claim
    rag = types.ModuleType("app.services.rag_service")
    rag.answer_question = lambda repo_id, q: {"answer": "hi", "sources": ["a.py"]}
    monkeypatch.setitem(sys.modules, "app.services.indexing_service", indexing)
    monkeypatch.setitem(sys.modules, "app.services.rag_service", rag)
    for mod in ("app.main", "app.routers", "app.routers.index_router", "app.routers.query_router"):
        monkeypatch.delitem(sys.modules, mod, raising=False)

    from app.config import settings
    monkeypatch.setattr(settings, "internal_service_secret", SECRET)
    monkeypatch.setattr(settings, "repo_storage_path", str(tmp_path))

    from app.main import app
    c = TestClient(app)
    c.storage = tmp_path
    c.calls = calls
    return c


H = {"X-Internal-Secret": SECRET}


def test_health_is_open(client):
    assert client.get("/health").status_code == 200


@pytest.mark.parametrize("headers", [{}, {"X-Internal-Secret": "wrong"}])
def test_index_requires_secret(client, headers):
    r = client.post("/index", json={"repo_id": "r1", "repo_path": str(client.storage)}, headers=headers)
    assert r.status_code == 401


def test_query_requires_secret(client):
    assert client.post("/rag/query", json={"repo_id": "r1", "question": "q"}).status_code == 401


def test_fails_closed_when_secret_unset(client, monkeypatch):
    from app.config import settings
    monkeypatch.setattr(settings, "internal_service_secret", "")
    r = client.post("/rag/query", json={"repo_id": "r1", "question": "q"}, headers={"X-Internal-Secret": ""})
    assert r.status_code == 401


def test_index_accepts_path_inside_storage(client):
    repo = client.storage / "abc"
    repo.mkdir()
    r = client.post("/index", json={"repo_id": "r1", "repo_path": str(repo)}, headers=H)
    assert r.status_code == 200


@pytest.mark.parametrize("path", ["/etc", "/", "{storage}", "{storage}/../outside"])
def test_index_rejects_path_outside_storage(client, path):
    path = path.format(storage=client.storage)
    r = client.post("/index", json={"repo_id": "r1", "repo_path": path}, headers=H)
    assert r.status_code == 400
    assert client.calls == []


def test_index_rejects_symlink_escape(client, tmp_path_factory):
    outside = tmp_path_factory.mktemp("outside")
    link = client.storage / "link"
    link.symlink_to(outside)
    r = client.post("/index", json={"repo_id": "r1", "repo_path": str(link)}, headers=H)
    assert r.status_code == 400


def test_second_index_for_same_repo_conflicts(client):
    repo = client.storage / "abc"
    repo.mkdir()
    body = {"repo_id": "r1", "repo_path": str(repo)}
    assert client.post("/index", json=body, headers=H).status_code == 200
    assert client.post("/index", json=body, headers=H).status_code == 409
