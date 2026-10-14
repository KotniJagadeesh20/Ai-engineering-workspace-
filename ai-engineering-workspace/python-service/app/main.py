"""
Entry point - equivalent of AiEngineeringApplication.java's main() method.
Run with: uvicorn app.main:app --reload --port 8000
"""

import logging

from fastapi import Depends, FastAPI

from app.security import require_internal_secret
from app.routers import index_router, query_router

logging.basicConfig(level=logging.INFO)

app = FastAPI(
    title="AI Engineering Workspace - Python Service",
    description="RAG indexing + query service (Phase 1). Intelligence layer "
                "only - auth, workspace/repo CRUD, and GitHub calls all live "
                "in the Java service.",
    version="0.1.0",
)

# No CORS middleware: this service is only called server-to-server by the
# Java service, never from a browser.

app.include_router(index_router.router, dependencies=[Depends(require_internal_secret)])
app.include_router(query_router.router, dependencies=[Depends(require_internal_secret)])


@app.get("/health")
async def health():
    return {"status": "ok"}
