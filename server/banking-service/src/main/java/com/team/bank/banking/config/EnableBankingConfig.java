package com.team.bank.banking.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

@Configuration
public class EnableBankingConfig {

    @Value("${enablebanking.app-id}")
    private String appId;

    @Value("${enablebanking.private-key-path}")
    private String privateKeyPath;

    @Value("${enablebanking.redirect-url}")
    private String redirectUrl;

    @Value("${enablebanking.base-url:https://api.enablebanking.com}")
    private String baseUrl;

    public String getAppId() {
        return appId;
    }

    public String getPrivateKeyPath() {
        return privateKeyPath;
    }

    public String getRedirectUrl() {
        return redirectUrl;
    }

    public String getBaseUrl() {
        return baseUrl;
    }
}
