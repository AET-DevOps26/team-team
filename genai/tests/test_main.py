from pathlib import Path
import sys

from fastapi.testclient import TestClient

sys.path.append(str(Path(__file__).resolve().parents[1]))

from main import app

client = TestClient(app)


# ---------------------------------------------------------------------------
# Health
# ---------------------------------------------------------------------------


def test_health():
    response = client.get("/health")
    assert response.status_code == 200
    body = response.json()
    assert body["status"] == "UP"
    assert body["service"] == "genai-service"


# ---------------------------------------------------------------------------
# Chat – backwards-compatible "message" field
# ---------------------------------------------------------------------------


def test_chat_local():
    response = client.post("/chat", json={"message": "how much is my balance"})
    assert response.status_code == 200
    body = response.json()
    assert "balance" in body["reply"].lower()
    assert "reasoning" in body


def test_chat_credit_advice():
    response = client.post("/chat", json={"message": "credit advice"})
    assert response.status_code == 200
    body = response.json()
    # "credit advice" doesn't match budget/balance/bank keywords → generic fallback
    assert "spending categories" in body["reply"].lower()
    assert "reasoning" in body


def test_chat_budget_tips():
    response = client.post("/chat", json={"message": "budget tips"})
    assert response.status_code == 200
    body = response.json()
    assert "budget" in body["reply"].lower()


def test_chat_generic_query():
    response = client.post("/chat", json={"message": "hello"})
    assert response.status_code == 200
    body = response.json()
    assert len(body["reply"]) > 0
    assert body["reasoning"] is None


# ---------------------------------------------------------------------------
# Chat – "messages" array (new shape)
# ---------------------------------------------------------------------------


def test_chat_messages_shape():
    response = client.post(
        "/chat",
        json={"messages": [{"role": "user", "content": "budget tips"}]},
    )
    assert response.status_code == 200
    assert "budget" in response.json()["reply"].lower()


def test_chat_messages_prefers_over_message():
    """When both 'messages' and 'message' are provided, 'messages' wins."""
    response = client.post(
        "/chat",
        json={
            "messages": [{"role": "user", "content": "credit advice"}],
            "message": "budget tips",
        },
    )
    assert response.status_code == 200
    # 'messages' takes priority, so we should get credit advice, not budget
    # "credit advice" doesn't match budget/balance/bank keywords → generic fallback
    assert "spending categories" in response.json()["reply"].lower()


def test_chat_multi_turn_conversation():
    response = client.post(
        "/chat",
        json={
            "messages": [
                {"role": "user", "content": "how are my finances?"},
                {"role": "assistant", "content": "Your finances look stable."},
                {"role": "user", "content": "budget tips please"},
            ],
        },
    )
    assert response.status_code == 200
    assert "budget" in response.json()["reply"].lower()


# ---------------------------------------------------------------------------
# Chat – with dashboard context
# ---------------------------------------------------------------------------


def test_chat_with_context():
    response = client.post(
        "/chat",
        json={
            "message": "what is my balance?",
            "context": {
                "account": {
                    "accountId": "test-1",
                    "customerName": "Alice",
                    "totalBalance": 5000.0,
                },
                "trend": [
                    {"month": "Jan", "balance": 4000.0},
                    {"month": "Feb", "balance": 5000.0},
                ],
                "expenses": [{"category": "Rent", "percentage": 40}],
            },
        },
    )
    assert response.status_code == 200
    body = response.json()
    assert len(body["reply"]) > 0


def test_chat_empty_context_still_works():
    response = client.post(
        "/chat",
        json={
            "message": "hello",
            "context": {},
        },
    )
    assert response.status_code == 200
    assert len(response.json()["reply"]) > 0


# ---------------------------------------------------------------------------
# Chat – multibank context
# ---------------------------------------------------------------------------

MULTIBANK_CONTEXT = {
    "account": {
        "accountId": "acc-1",
        "customerName": "Test User",
        "totalBalance": 1700.0,
    },
    "trend": [{"month": "Jun", "balance": 1500.0}, {"month": "Jul", "balance": 1700.0}],
    "expenses": [{"category": "Rent", "percentage": 40.0}],
    "connections": [
        {
            "status": "ACTIVE",
            "bankName": "N26",
            "country": "DE",
            "balance": 800.0,
            "currency": "EUR",
        },
        {
            "status": "ACTIVE",
            "bankName": "Revolut",
            "country": "DE",
            "balance": 900.0,
            "currency": "EUR",
        },
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
    "monthlyFlow": {
        "month": "Jul",
        "income": 4200.0,
        "spending": 2960.0,
        "net": 1240.0,
    },
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
    # Amounts are wrapped in **...** by the money-normalizer post-processor so
    # the client renders them in the accent color.
    assert "**€1,700**" in reply
    assert "N26 **€800**" in reply
    assert "Revolut **€900**" in reply


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


# ---------------------------------------------------------------------------
# Summarize
# ---------------------------------------------------------------------------


def test_summarize_upward_trend():
    response = client.post(
        "/summarize",
        json={
            "account": {
                "accountId": "1",
                "customerName": "Bob",
                "totalBalance": 3000.0,
            },
            "trend": [
                {"month": "Jan", "balance": 2000.0},
                {"month": "Feb", "balance": 3000.0},
            ],
            "expenses": [{"category": "Food", "percentage": 35}],
        },
    )
    assert response.status_code == 200
    body = response.json()
    assert "Bob" in body["summary"]
    assert "upward" in body["summary"]
    assert "Food" in body["summary"]


def test_summarize_downward_trend():
    response = client.post(
        "/summarize",
        json={
            "account": {
                "accountId": "2",
                "customerName": "Carol",
                "totalBalance": 1000.0,
            },
            "trend": [
                {"month": "Mar", "balance": 2000.0},
                {"month": "Apr", "balance": 1000.0},
            ],
            "expenses": [],
        },
    )
    assert response.status_code == 200
    body = response.json()
    assert "Carol" in body["summary"]
    assert "downward" in body["summary"]


def test_summarize_stable_trend():
    response = client.post(
        "/summarize",
        json={
            "account": {
                "accountId": "3",
                "customerName": "Dave",
                "totalBalance": 500.0,
            },
            "trend": [
                {"month": "May", "balance": 500.0},
                {"month": "Jun", "balance": 500.0},
            ],
            "expenses": [],
        },
    )
    assert response.status_code == 200
    assert "stable" in response.json()["summary"]


def test_summarize_single_point_is_stable():
    response = client.post(
        "/summarize",
        json={
            "account": {
                "accountId": "4",
                "customerName": "Eve",
                "totalBalance": 100.0,
            },
            "trend": [{"month": "Jul", "balance": 100.0}],
            "expenses": [],
        },
    )
    assert response.status_code == 200
    # Single data point → no trend change → stable
    assert "stable" in response.json()["summary"]


# ---------------------------------------------------------------------------
# Edge cases
# ---------------------------------------------------------------------------


def test_chat_empty_message():
    response = client.post("/chat", json={"message": ""})
    # Empty message → generic fallback reply
    assert response.status_code == 200
    assert len(response.json()["reply"]) > 0


def test_chat_no_message_or_messages():
    """When neither 'message' nor 'messages' is provided, the endpoint still
    returns a valid response (system prompt only, no user query)."""
    response = client.post("/chat", json={})
    assert response.status_code == 200
    assert len(response.json()["reply"]) > 0
