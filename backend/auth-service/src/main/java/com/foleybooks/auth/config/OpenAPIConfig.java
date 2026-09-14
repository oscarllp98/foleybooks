package com.foleybooks.auth.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenAPIConfig {

    @Bean
    OpenAPI authServiceOpenApi() {
        return new OpenAPI().info(new Info()
                .title("Foley Books — Auth Service API")
                .description("Registration, email verification, login, refresh tokens and JWKS")
                .version("v1"));
    }
}
