"""
RAG query service. Deliberately a plain retrieve-then-generate chain for
Phase 1 - no agent loop, no multi-step reasoning yet. That's Phase 2's job
(LangGraph), once the coding agent needs to plan/act/observe in a loop.
This stays single-shot: one question in, one grounded answer out.
"""

from langchain_anthropic import ChatAnthropic
from langchain.chains import RetrievalQA

from app.config import settings
from app.models.schemas import QueryResponse
from app.services.indexing_service import get_vectorstore


def answer_question(repo_id: str, question: str) -> QueryResponse:
    vectorstore = get_vectorstore(repo_id)
    retriever = vectorstore.as_retriever(search_kwargs={"k": 5})

    llm = ChatAnthropic(
        model="claude-sonnet-4-6",
        temperature=0,  # deterministic-leaning answers for factual code questions
        api_key=settings.anthropic_api_key,
    )

    qa_chain = RetrievalQA.from_chain_type(
        llm=llm,
        retriever=retriever,
        return_source_documents=True,
    )

    result = qa_chain.invoke({"query": question})

    sources = sorted({
        doc.metadata.get("source", "unknown")
        for doc in result.get("source_documents", [])
    })

    return QueryResponse(answer=result["result"], sources=sources)
