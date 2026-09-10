package com.foleybooks.discovery.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "eureka.security")
public record EurekaSecurityProperties(String username, String password) {

    public EurekaSecurityProperties {
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            throw new IllegalStateException(
                    "eureka.security.username and eureka.security.password must be provided (EUREKA_SECURITY_USERNAME/EUREKA_SECURITY_PASSWORD)");
        }
    }
}
