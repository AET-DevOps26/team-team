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
    response = client.post("/chat", json={"message": "credit advice"})
    assert response.status_code == 200
    assert "credit" in response.json()["reply"].lower()
