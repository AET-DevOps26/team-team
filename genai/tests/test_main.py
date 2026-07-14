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
