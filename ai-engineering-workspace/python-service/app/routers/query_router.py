from fastapi import APIRouter

from app.models.schemas import QueryRequest, QueryResponse
from app.services.rag_service import answer_question

router = APIRouter()


@router.post("/rag/query", response_model=QueryResponse)
async def query_repo(request: QueryRequest):
    """
    Called by Java after it's confirmed the requesting user has access to
    this repo_id (Java validates auth/permissions BEFORE calling here -
    this endpoint stays a pure reasoning service with no auth of its own,
    per the architecture split: Python never talks to Postgres user tables
    or does permission checks).
    """
    return answer_question(request.repo_id, request.question)
