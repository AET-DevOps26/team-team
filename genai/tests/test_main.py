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


def test_chat_credit_advice():
    response = client.post("/chat", json={"message": "credit advice"})
    assert response.status_code == 200
    body = response.json()
    assert "credit" in body["reply"].lower()
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
    assert "credit" in response.json()["reply"].lower()


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
                    "totalCreditLimit": 10000.0,
                    "utilizationRate": 0.3,
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
                "totalCreditLimit": 5000.0,
                "utilizationRate": 0.2,
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
                "totalCreditLimit": 5000.0,
                "utilizationRate": 0.5,
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
                "totalCreditLimit": 1000.0,
                "utilizationRate": 0.5,
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
                "totalCreditLimit": 1000.0,
                "utilizationRate": 0.1,
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
