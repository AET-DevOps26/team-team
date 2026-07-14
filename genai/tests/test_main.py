from pathlib import Path
import sys

from fastapi.testclient import TestClient

sys.path.append(str(Path(__file__).resolve().parents[1]))

from main import app


client = TestClient(app)


def test_health():
    response = client.get("/health")
    assert response.status_code == 200
    assert response.json()["status"] == "UP"


def test_chat_local():
    response = client.post("/chat", json={"message": "how much is my balance"})
    assert response.status_code == 200
    body = response.json()
    assert "balance" in body["reply"].lower()
    assert "reasoning" in body


def test_chat_messages_shape():
    response = client.post(
        "/chat",
        json={"messages": [{"role": "user", "content": "budget tips"}]},
    )
    assert response.status_code == 200
    assert "budget" in response.json()["reply"].lower()


MULTIBANK_CONTEXT = {
    "account": {"accountId": "acc-1", "customerName": "Test User", "totalBalance": 1700.0},
    "trend": [{"month": "Jun", "balance": 1500.0}, {"month": "Jul", "balance": 1700.0}],
    "expenses": [{"category": "Rent", "percentage": 40.0}],
    "connections": [
        {"status": "ACTIVE", "bankName": "N26", "country": "DE", "balance": 800.0, "currency": "EUR"},
        {"status": "ACTIVE", "bankName": "Revolut", "country": "DE", "balance": 900.0, "currency": "EUR"},
    ],
    "transactions": [
        {
            "category": "Groceries",
            "amount": 62.4,
            "direction": "DEBIT",
            "bankName": "N26",
            "counterparty": "REWE",
            "createdAt": "2026-07-03T18:42:00",
        }
    ],
    "monthlyFlow": {"month": "Jul", "income": 4200.0, "spending": 2960.0, "net": 1240.0},
    "spendByBank": [
        {"bankName": "N26", "spending": 1180.0},
        {"bankName": "Revolut", "spending": 540.0},
    ],
}


def test_chat_fallback_grounded_in_multibank_context():
    # With no provider configured the local fallback must answer balance
    # questions from the actual context, not with canned text.
    response = client.post(
        "/chat",
        json={
            "messages": [{"role": "user", "content": "what is my balance?"}],
            "context": MULTIBANK_CONTEXT,
        },
    )
    assert response.status_code == 200
    reply = response.json()["reply"]
    assert "€1,700" in reply
    assert "N26 €800" in reply
    assert "Revolut €900" in reply


def test_summarize_multibank():
    response = client.post(
        "/summarize",
        json={
            "account": MULTIBANK_CONTEXT["account"],
            "trend": MULTIBANK_CONTEXT["trend"],
            "expenses": MULTIBANK_CONTEXT["expenses"],
            "connections": MULTIBANK_CONTEXT["connections"],
            "monthlyFlow": MULTIBANK_CONTEXT["monthlyFlow"],
        },
    )
    assert response.status_code == 200
    summary = response.json()["summary"]
    assert "2 linked banks" in summary
    assert "€1,700" in summary
    assert "€4,200 in" in summary
    assert "+€1,240 net" in summary


def test_chat_context_with_null_lists():
    # The orchestrator serializes absent context lists as explicit nulls
    # (Jackson record defaults) — genai must not 422 on them.
    response = client.post(
        "/chat",
        json={
            "messages": [{"role": "user", "content": "what is my balance?"}],
            "context": {
                "account": MULTIBANK_CONTEXT["account"],
                "trend": None,
                "expenses": None,
                "connections": None,
                "transactions": None,
                "monthlyFlow": None,
                "spendByBank": None,
            },
        },
    )
    assert response.status_code == 200
    assert "€1,700" in response.json()["reply"]


def test_summarize_without_multibank_fields():
    # Older orchestrators send only account/trend/expenses — must still work.
    response = client.post(
        "/summarize",
        json={
            "account": MULTIBANK_CONTEXT["account"],
            "trend": MULTIBANK_CONTEXT["trend"],
            "expenses": MULTIBANK_CONTEXT["expenses"],
        },
    )
    assert response.status_code == 200
    summary = response.json()["summary"]
    assert "€1,700" in summary
    assert "upward" in summary
