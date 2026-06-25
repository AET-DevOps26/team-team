package com.team.bank.account;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class VersionMetrics {

  @Value("${app.version:1.0.0}")
  private String appVersion;

  @Bean
  public Gauge appVersionGauge(MeterRegistry registry) {
    return Gauge.builder("app_version", () -> 1)
        .tag("version", appVersion)
        .tag("service", "account-service")
        .description("Application version info")
        .register(registry);
  }
}
