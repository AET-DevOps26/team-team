import os
from typing import List

import requests
from fastapi import FastAPI
from pydantic import BaseModel
from prometheus_fastapi_instrumentator import Instrumentator


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


class SummaryRequest(BaseModel):
    account: AccountSummary
    trend: List[BalancePoint]
    expenses: List[ExpenseSlice]


class SummaryResponse(BaseModel):
    summary: str


class ChatRequest(BaseModel):
    message: str


class ChatResponse(BaseModel):
    reply: str


app = FastAPI(title="Bank GenAI Service", version="0.1.0")
Instrumentator().instrument(app).expose(app)


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


def ollama_chat(prompt: str) -> str:
    base_url = os.getenv("OLLAMA_BASE_URL", "http://localhost:11434")
    model = os.getenv("OLLAMA_MODEL", "llama3.1:8b")
    response = requests.post(
        f"{base_url}/api/generate",
        json={"model": model, "prompt": prompt, "stream": False},
        timeout=12,
    )
    response.raise_for_status()
    payload = response.json()
    return payload.get("response", "No response generated.")


def local_chat(prompt: str) -> str:
    if "budget" in prompt.lower():
        return "You can improve your budget by setting spending caps for utilities and supplies and reviewing subscriptions weekly."
    if "credit" in prompt.lower():
        return "A healthy credit utilization target is below 30%. Paying early in the billing cycle can help lower utilization."
    return "I can help summarize your account trends, explain spending categories, and suggest practical budgeting actions."


@app.get("/health")
def health() -> dict:
    return {"status": "UP", "service": "genai-service"}


@app.post("/summarize", response_model=SummaryResponse)
def summarize(req: SummaryRequest) -> SummaryResponse:
    return SummaryResponse(summary=local_summary(req))


@app.post("/chat", response_model=ChatResponse)
def chat(req: ChatRequest) -> ChatResponse:
    provider = os.getenv("MODEL_PROVIDER", "local").lower()

    try:
        if provider == "ollama":
            return ChatResponse(reply=ollama_chat(req.message))
    except Exception:
        return ChatResponse(reply=local_chat(req.message))

    return ChatResponse(reply=local_chat(req.message))
