package com.foleybooks.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The public frontend origin (D-01): confirmation emails link to
 * {@code <base-url>/verify-email?token=…}, and the same {@code FRONTEND_BASE_URL}
 * env var drives gateway CORS (.env.example "Frontend"). Bound from
 * {@code app.frontend.*} per the AGENTS.md §3 config convention
 * ({@code @ConfigurationProperties} records live in {@code config/}).
 */
@ConfigurationProperties(prefix = "app.frontend")
public record FrontendProperties(String baseUrl) {
}
