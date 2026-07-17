import json
import os
import re
from typing import List, Optional

import requests
from fastapi import FastAPI
from pydantic import BaseModel, Field, field_validator
from prometheus_fastapi_instrumentator import Instrumentator
from prometheus_client import Gauge


# ---------------------------------------------------------------------------
# Data models
# ---------------------------------------------------------------------------


class AccountSummary(BaseModel):
    accountId: str
    customerName: str
    totalBalance: float
    # Optional so callers that only care about balances (orchestrator's DashboardModels
    # only sends the three above) don't get rejected with a 422. Defaults keep the
    # local prompt/fallback code paths null-safe without extra guards.
    totalCreditLimit: float = 0.0
    utilizationRate: float = 0.0


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
    accountName: Optional[str] = None
    balance: Optional[float] = None
    currency: Optional[str] = None


class TransactionItem(BaseModel):
    category: Optional[str] = None
    amount: Optional[float] = None
    direction: Optional[str] = None
    bankName: Optional[str] = None
    counterparty: Optional[str] = None
    createdAt: Optional[str] = None


class MonthlyFlow(BaseModel):
    month: Optional[str] = None
    income: Optional[float] = None
    spending: Optional[float] = None
    net: Optional[float] = None


class BankSpendItem(BaseModel):
    bankName: Optional[str] = None
    spending: Optional[float] = None


class DashboardContext(BaseModel):
    """Snapshot of the user's dashboard we pass to the model so it can answer
    grounded questions ("what's my balance?", "what did I spend at X?" ...)."""

    account: Optional[AccountSummary] = None
    trend: List[BalancePoint] = Field(default_factory=list)
    expenses: List[ExpenseSlice] = Field(default_factory=list)
    connections: List[ConnectionInfo] = Field(default_factory=list)
    transactions: List[TransactionItem] = Field(default_factory=list)
    monthlyFlow: Optional[MonthlyFlow] = None
    spendByBank: List[BankSpendItem] = Field(default_factory=list)

    # The orchestrator serializes absent lists as explicit nulls — treat them
    # as empty instead of rejecting the whole request with a 422.
    @field_validator(
        "trend", "expenses", "connections", "transactions", "spendByBank", mode="before"
    )
    @classmethod
    def _none_as_empty(cls, value):
        return [] if value is None else value


class SummaryRequest(BaseModel):
    account: AccountSummary
    trend: List[BalancePoint]
    expenses: List[ExpenseSlice]
    # Optional multibank enrichment — older orchestrators simply omit these.
    connections: List[ConnectionInfo] = Field(default_factory=list)
    monthlyFlow: Optional[MonthlyFlow] = None

    @field_validator("trend", "expenses", "connections", mode="before")
    @classmethod
    def _none_as_empty(cls, value):
        return [] if value is None else value


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

# Expose application version as a Prometheus metric
# Uses APP_VERSION env var if set, otherwise falls back to the FastAPI app version.
app_version = Gauge(
    "app_version", "Application version info", labelnames=["version", "service"]
)
app_version.labels(
    version=os.environ.get("APP_VERSION", app.version), service="genai-service"
).set(1)


# ---------------------------------------------------------------------------
# Prompt / context building
# ---------------------------------------------------------------------------


SYSTEM_PROMPT = (
    "You are the in-app assistant for Home Banking, a personal finance dashboard that "
    "aggregates the user's linked bank accounts. Answer questions about the user's total "
    "balance, per-bank balances, linked banks, monthly trend, expense breakdown, recent "
    "transactions (with counterparty and source bank), this month's income vs spending and "
    "the per-bank spending breakdown "
    "using the CONTEXT JSON provided in the system message. Be concise (usually 1-4 "
    "sentences). Format every monetary amount exactly as '\u20ac1,234.56' \u2014 the euro "
    "sign directly attached to the number with no space, a comma as the thousands separator, "
    "and a dot as the decimal separator. Never write '\u20ac 1 234,56' or '\u20ac1.234,56'. "
    "When the user asks about data that is missing from the context, say so honestly. Never "
    "invent transactions, account numbers, or personal details."
)


# Matches a euro amount possibly written with a space (regular, NBSP U+00A0, or
# narrow NBSP U+202F) between the sign and the number, and/or spaces used as a
# thousands separator (e.g. "\u20ac 16 251.40" or "\u20ac\u00a01\u202f234,56").
# The capture MUST end with a digit so we never swallow trailing sentence
# punctuation like ", " after "\u20ac164,006" or ". " after "\u20ac205,994".
_EURO_AMOUNT_RE = re.compile(
    r"\u20ac[\s\u00a0\u202f]*(\d[\d\s\u00a0\u202f.,]*\d|\d)"
)


def _normalize_money(text: str) -> str:
    """Rewrite euro amounts to the canonical '\u20ac1,234.56' form regardless of
    what locale style the LLM decided to use, and wrap them in markdown bold so
    the client's AssistantMarkdown renders them in the accent color."""

    def _repl(match: re.Match) -> str:
        raw = match.group(1)
        # Strip every kind of space \u2014 they're only ever thousands separators.
        stripped = re.sub(r"[\s\u00a0\u202f]", "", raw)
        # Decide which of '.' / ',' is the decimal separator: the last one seen,
        # but only if it's followed by 1\u20132 digits (typical cents). Otherwise
        # treat both as thousands separators.
        decimal_sep = None
        last_dot = stripped.rfind(".")
        last_comma = stripped.rfind(",")
        last = max(last_dot, last_comma)
        if last != -1 and len(stripped) - last - 1 in (1, 2):
            decimal_sep = stripped[last]
        if decimal_sep is None:
            digits = re.sub(r"[.,]", "", stripped)
            cents = ""
        else:
            int_part = stripped[:last]
            frac_part = stripped[last + 1:]
            digits = re.sub(r"[.,]", "", int_part) or "0"
            cents = "." + frac_part
        try:
            formatted = f"{int(digits):,}{cents}"
        except ValueError:
            return match.group(0)
        amount = f"\u20ac{formatted}"
        # Skip re-wrapping if the LLM already surrounded the amount with **...**.
        start, end = match.start(), match.end()
        already_bold = (
            text[max(0, start - 2):start] == "**"
            and text[end:end + 2] == "**"
        )

        return amount if already_bold else f"**{amount}**"

    return _EURO_AMOUNT_RE.sub(_repl, text)


def _context_message(context: Optional[DashboardContext]) -> Optional[ChatMessage]:
    if context is None:
        return None
    payload = context.model_dump(exclude_none=True)
    if not payload:
        return None
    return ChatMessage(
        role="system",
        content="CONTEXT (current dashboard snapshot):\n"
                + json.dumps(payload, default=str),
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

    bank_count = len(req.connections)
    across = (
        f"{bank_count} linked bank{'s' if bank_count != 1 else ''}"
        if bank_count
        else "your linked banks"
    )
    parts = [
        f"{req.account.customerName}, your total balance across {across} is "
        f"€{req.account.totalBalance:,.0f}."
    ]
    flow = req.monthlyFlow
    if flow is not None and flow.income is not None and flow.spending is not None:
        net = flow.net if flow.net is not None else flow.income - flow.spending
        sign = "+" if net >= 0 else "−"
        parts.append(
            f"This month ({flow.month}): €{flow.income:,.0f} in, €{flow.spending:,.0f} out "
            f"({sign}€{abs(net):,.0f} net)."
        )
    parts.append(
        f"Your balance trend is {trend_hint}, and the largest expense category is {top_expense}."
    )
    return " ".join(parts)


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
    reply = (
            (payload.get("message") or {}).get("content") or payload.get("response") or ""
    )
    return ChatResponse(reply=reply.strip() or "No response generated.", reasoning=None)


def local_chat(
    messages: List[ChatMessage], context: Optional[DashboardContext] = None
) -> ChatResponse:
    """Deterministic fallback used when no provider is reachable — keeps tests
    green and lets the app work offline without Logos. Grounds balance/bank
    answers in the dashboard context when the caller provided one."""
    last_user = next(
        (m.content for m in reversed(messages) if m.role == "user"),
        "",
    ).lower()
    if "budget" in last_user:
        reply = (
            "You can improve your budget by setting spending caps for your largest expense "
            "categories and reviewing subscriptions weekly."
        )
    elif "bank" in last_user or "balance" in last_user:
        if context is not None and context.account is not None:
            reply = (
                f"Your total balance across your linked banks is "
                f"€{context.account.totalBalance:,.0f}."
            )
            per_bank = ", ".join(
                f"{c.bankName} €{c.balance:,.0f}"
                for c in context.connections
                if c.bankName and c.balance is not None
            )
            if per_bank:
                reply += f" Per bank: {per_bank}."
        else:
            reply = (
                "Your dashboard aggregates the balances of all your linked banks. Check the "
                "linked banks list for each account's individual balance."
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
            response = logos_chat(messages)
        elif provider == "ollama":
            response = ollama_chat(messages)
        else:
            response = local_chat(messages, req.context)
    except Exception:
        # Never surface an upstream failure to the user \u2014 fall back to the
        # local context-grounded assistant so the chat panel stays usable.
        response = local_chat(messages, req.context)

    # LLMs love European locale spacing ("\u20ac 16 251,40"). Normalize before returning.
    return response.model_copy(update={"reply": _normalize_money(response.reply)})