package com.foleybooks.order;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * order-service (:8083, owns order_db): the per-user persistent cart (FR-10..FR-13).
 * East-west catalog lookups go through OpenFeign against the Eureka-registered
 * {@code catalog-service} (AGENTS.md §4, ADR-005); {@code cart/client/CatalogClient}
 * (OR-05) is the single scanned client and the only consumer-side face of the
 * catalog read model.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableFeignClients
public class OrderServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrderServiceApplication.class, args);
    }
}
