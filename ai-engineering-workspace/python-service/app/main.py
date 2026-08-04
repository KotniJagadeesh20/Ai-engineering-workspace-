"""
Entry point - equivalent of AiEngineeringApplication.java's main() method.
Run with: uvicorn app.main:app --reload --port 8000
"""

import logging

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from app.routers import index_router, query_router

logging.basicConfig(level=logging.INFO)

app = FastAPI(
    title="AI Engineering Workspace - Python Service",
    description="RAG indexing + query service (Phase 1). Intelligence layer "
                "only - auth, workspace/repo CRUD, and GitHub calls all live "
                "in the Java service.",
    version="0.1.0",
)

# Dev-permissive CORS so the browser-based test harness can call this service
# directly. TIGHTEN before deploying anywhere real - restrict allow_origins
# to your actual frontend's origin(s).
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)

app.include_router(index_router.router)
app.include_router(query_router.router)


@app.get("/health")
async def health():
    return {"status": "ok"}
