"""
Pydantic models - the Python equivalent of Java's DTO records (IndexRequest,
AuthResponse, etc). FastAPI uses these for both request validation AND
automatic OpenAPI docs generation.
"""

from pydantic import BaseModel, Field
from typing import Optional


class IndexRequest(BaseModel):
    """Matches Java's IndexRequest record exactly - field names must line up
    (or be aliased) since this is what Java's IndexingClient POSTs to /index."""
    repo_id: str
    repo_path: str


class IndexAcceptedResponse(BaseModel):
    status: str = "accepted"


class QueryRequest(BaseModel):
    repo_id: str
    question: str = Field(..., min_length=1)


class QueryResponse(BaseModel):
    answer: str
    sources: list[str]


class IndexStatusCallback(BaseModel):
    """What we PATCH back to Java's /api/repos/{id}/index-status once
    indexing finishes or fails. Mirrors Java's IndexStatusUpdateRequest DTO."""
    status: str  # "READY" or "FAILED" - kept as a plain string here since
                 # Python doesn't share Java's IndexStatus enum; Java's
                 # @Enumerated(EnumType.STRING) will parse it the same way.
    error_message: Optional[str] = None
