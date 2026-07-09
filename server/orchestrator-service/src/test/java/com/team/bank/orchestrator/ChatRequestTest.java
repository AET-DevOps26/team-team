package com.team.bank.orchestrator;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ChatRequestTest {

  @Test
  void shouldStoreMessage() {
    ChatRequest request = new ChatRequest("hello", null, null);
    assertEquals("hello", request.message());
  }
}
