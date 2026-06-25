package com.team.bank.orchestrator;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class HttpConfig {

  @Bean
  public WebClient webClient() {
    return WebClient.builder().build();
  }
}
