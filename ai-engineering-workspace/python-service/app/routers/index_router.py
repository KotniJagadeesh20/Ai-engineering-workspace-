"""
Equivalent of Java's @RestController. FastAPI's APIRouter groups related
endpoints the same way a Spring @RequestMapping-annotated controller does.
"""

import os

from fastapi import APIRouter, BackgroundTasks, HTTPException

from app.config import settings

from app.models.schemas import IndexRequest, IndexAcceptedResponse
from app.services.indexing_service import index_repository, try_claim_indexing

router = APIRouter()


@router.post("/index", response_model=IndexAcceptedResponse)
async def trigger_index(request: IndexRequest, background_tasks: BackgroundTasks):
    """
    Called by Java's IndexingClient.triggerIndex(). Returns immediately with
    "accepted" and does the actual work in a background task - this mirrors
    Java firing the request with .subscribe() and not waiting on the response
    body. Indexing finishes asynchronously; Python reports back via PATCH to
    Java's /internal/repos/{id}/index-status when done (or failed).
    """
    root = os.path.realpath(settings.repo_storage_path)
    resolved = os.path.realpath(request.repo_path)
    if os.path.commonpath([root, resolved]) != root or resolved == root:
        raise HTTPException(status_code=400, detail="repo_path must be inside the repo storage directory")
    if not os.path.isdir(resolved):
        raise HTTPException(status_code=400, detail="repo_path does not exist")

    if not try_claim_indexing(request.repo_id):
        raise HTTPException(status_code=409, detail="Indexing already in progress for this repo")

    background_tasks.add_task(index_repository, request.repo_id, resolved)
    return IndexAcceptedResponse()
