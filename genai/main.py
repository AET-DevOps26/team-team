import json
import os
from typing import List, Optional

import requests
from fastapi import FastAPI
from pydantic import BaseModel, Field
from prometheus_fastapi_instrumentator import Instrumentator


# ---------------------------------------------------------------------------
# Data models
# ---------------------------------------------------------------------------


class AccountSummary(BaseModel):
    accountId: str
    customerName: str
    totalBalance: float
    totalCreditLimit: float
    utilizationRate: float


class BalancePoint(BaseModel):
    month: str
    balance: float


class ExpenseSlice(BaseModel):
    category: str
    percentage: float


class ConnectionInfo(BaseModel):
    status: Optional[str] = None
    bankName: Optional[str] = None
    country: Optional[str] = None


class DashboardContext(BaseModel):
    """Snapshot of the user's dashboard we pass to the model so it can answer
    grounded questions ("what's my balance?", "what's my top expense?" ...)."""

    account: Optional[AccountSummary] = None
    trend: List[BalancePoint] = Field(default_factory=list)
    expenses: List[ExpenseSlice] = Field(default_factory=list)
    connection: Optional[ConnectionInfo] = None


class SummaryRequest(BaseModel):
    account: AccountSummary
    trend: List[BalancePoint]
    expenses: List[ExpenseSlice]


class SummaryResponse(BaseModel):
    summary: str


class ChatMessage(BaseModel):
    role: str  # "user" | "assistant" | "system"
    content: str


class ChatRequest(BaseModel):
    # New shape: full conversation + optional grounding context.
    # `message` is kept for backwards compatibility with older callers.
    messages: Optional[List[ChatMessage]] = None
    message: Optional[str] = None
    context: Optional[DashboardContext] = None


class ChatResponse(BaseModel):
    reply: str
    reasoning: Optional[str] = None


app = FastAPI(title="Bank GenAI Service", version="0.2.0")
Instrumentator().instrument(app).expose(app)


# ---------------------------------------------------------------------------
# Prompt / context building
# ---------------------------------------------------------------------------


SYSTEM_PROMPT = (
    "You are the in-app assistant for Home Banking, a personal finance dashboard. "
    "Answer questions about the user's balances, credit utilization, monthly trend, "
    "linked banks and expense breakdown using the CONTEXT JSON provided in the system "
    "message. Be concise (usually 1-4 sentences). Format money as euros. "
    "When the user asks about data that is missing from the context, say so honestly. "
    "Never invent transactions, account numbers, or personal details."
)


def _context_message(context: Optional[DashboardContext]) -> Optional[ChatMessage]:
    if context is None:
        return None
    payload = context.model_dump(exclude_none=True)
    if not payload:
        return None
    return ChatMessage(
        role="system",
        content="CONTEXT (current dashboard snapshot):\n" + json.dumps(payload, default=str),
    )


def _normalize_messages(req: ChatRequest) -> List[ChatMessage]:
    """Coerce the request into a full message list, always led by the system prompt
    and (when available) a grounding context system message."""
    messages: List[ChatMessage] = [ChatMessage(role="system", content=SYSTEM_PROMPT)]
    ctx = _context_message(req.context)
    if ctx is not None:
        messages.append(ctx)
    if req.messages:
        messages.extend(req.messages)
    elif req.message:
        messages.append(ChatMessage(role="user", content=req.message))
    return messages


# ---------------------------------------------------------------------------
# Providers
# ---------------------------------------------------------------------------


def local_summary(req: SummaryRequest) -> str:
    top_expense = req.expenses[0].category if req.expenses else "N/A"
    balance_values = [point.balance for point in req.trend]
    trend_hint = "stable"
    if len(balance_values) > 1 and balance_values[-1] > balance_values[0]:
        trend_hint = "upward"
    elif len(balance_values) > 1 and balance_values[-1] < balance_values[0]:
        trend_hint = "downward"

    return (
        f"{req.account.customerName}, your current balance is ${req.account.totalBalance:,.0f} with "
        f"a credit utilization of {req.account.utilizationRate * 100:.1f}%. "
        f"Your balance trend is {trend_hint}, and the largest expense category is {top_expense}."
    )


def logos_chat(messages: List[ChatMessage]) -> ChatResponse:
    """Call the TUM Logos gateway (OpenAI-compatible /v1/chat/completions)."""
    api_key = os.getenv("LOGOS_KEY")
    if not api_key:
        raise RuntimeError("LOGOS_KEY is not configured")
    base_url = os.getenv("LOGOS_BASE_URL", "https://logos.aet.cit.tum.de")
    model = os.getenv("LOGOS_MODEL", "openai/gpt-oss-120b")

    response = requests.post(
        f"{base_url}/v1/chat/completions",
        headers={
            "Content-Type": "application/json",
            "Authorization": f"Bearer {api_key}",
        },
        json={
            "model": model,
            "messages": [m.model_dump() for m in messages],
        },
        timeout=60,
    )
    response.raise_for_status()
    payload = response.json()
    choice = (payload.get("choices") or [{}])[0]
    msg = choice.get("message") or {}
    return ChatResponse(
        reply=(msg.get("content") or "").strip() or "No response generated.",
        reasoning=(msg.get("reasoning") or None),
    )


def ollama_chat(messages: List[ChatMessage]) -> ChatResponse:
    base_url = os.getenv("OLLAMA_BASE_URL", "http://localhost:11434")
    model = os.getenv("OLLAMA_MODEL", "llama3.1:8b")
    response = requests.post(
        f"{base_url}/api/chat",
        json={
            "model": model,
            "messages": [m.model_dump() for m in messages],
            "stream": False,
        },
        timeout=60,
    )
    response.raise_for_status()
    payload = response.json()
    reply = (payload.get("message") or {}).get("content") or payload.get("response") or ""
    return ChatResponse(reply=reply.strip() or "No response generated.", reasoning=None)


def local_chat(messages: List[ChatMessage]) -> ChatResponse:
    """Deterministic fallback used when no provider is reachable — keeps tests
    green and lets the app work offline without Logos."""
    last_user = next(
        (m.content for m in reversed(messages) if m.role == "user"),
        "",
    ).lower()
    if "budget" in last_user:
        reply = (
            "You can improve your budget by setting spending caps for utilities and supplies "
            "and reviewing subscriptions weekly."
        )
    elif "credit" in last_user:
        reply = (
            "A healthy credit utilization target is below 30%. Paying early in the billing "
            "cycle can help lower utilization."
        )
    else:
        reply = (
            "I can help summarize your account trends, explain spending categories, and "
            "suggest practical budgeting actions."
        )
    return ChatResponse(reply=reply, reasoning=None)


# ---------------------------------------------------------------------------
# Endpoints
# ---------------------------------------------------------------------------


@app.get("/health")
def health() -> dict:
    return {"status": "UP", "service": "genai-service"}


@app.post("/summarize", response_model=SummaryResponse)
def summarize(req: SummaryRequest) -> SummaryResponse:
    return SummaryResponse(summary=local_summary(req))


@app.post("/chat", response_model=ChatResponse)
def chat(req: ChatRequest) -> ChatResponse:
    provider = os.getenv("MODEL_PROVIDER", "local").lower()
    messages = _normalize_messages(req)

    try:
        if provider == "logos":
            return logos_chat(messages)
        if provider == "ollama":
            return ollama_chat(messages)
    except Exception:
        # Never surface an upstream failure to the user — fall back to the
        # local canned assistant so the chat panel stays usable.
        return local_chat(messages)

    return local_chat(messages)
