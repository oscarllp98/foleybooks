package com.foleybooks.order;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * order-service (:8083, owns order_db): the per-user persistent cart (FR-10..FR-13).
 * East-west catalog lookups go through OpenFeign against the Eureka-registered
 * {@code catalog-service} (AGENTS.md §4, ADR-005); no {@code @FeignClient} exists yet —
 * the scan is empty until OR-05 contributes {@code cart/client/CatalogClient}.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableFeignClients
public class OrderServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrderServiceApplication.class, args);
    }
}
