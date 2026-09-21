package com.foleybooks.catalog.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenAPIConfig {

    @Bean
    OpenAPI catalogServiceOpenApi() {
        return new OpenAPI().info(new Info()
                .title("Foley Books — Catalog Service API")
                .description("Books and categories: browse, search, filter, availability")
                .version("v1"));
    }
}
