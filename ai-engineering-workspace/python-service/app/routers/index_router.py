"""
Equivalent of Java's @RestController. FastAPI's APIRouter groups related
endpoints the same way a Spring @RequestMapping-annotated controller does.
"""

from fastapi import APIRouter, BackgroundTasks

from app.models.schemas import IndexRequest, IndexAcceptedResponse
from app.services.indexing_service import index_repository

router = APIRouter()


@router.post("/index", response_model=IndexAcceptedResponse)
async def trigger_index(request: IndexRequest, background_tasks: BackgroundTasks):
    """
    Called by Java's IndexingClient.triggerIndex(). Returns immediately with
    "accepted" and does the actual work in a background task - this mirrors
    Java firing the request with .subscribe() and not waiting on the response
    body. Indexing finishes asynchronously; Python reports back via PATCH to
    Java's /api/repos/{id}/index-status when done (or failed).
    """
    background_tasks.add_task(index_repository, request.repo_id, request.repo_path)
    return IndexAcceptedResponse()
