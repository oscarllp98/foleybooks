package com.foleybooks.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "eureka.client.enabled=false",
        "EUREKA_USERNAME=test-user",
        "EUREKA_PASSWORD=test-secret"
})
class GatewayApplicationTests {

    @Test
    void startup_whenNoRegistryConfigured_bootsSuccessfully() {
    }
}
