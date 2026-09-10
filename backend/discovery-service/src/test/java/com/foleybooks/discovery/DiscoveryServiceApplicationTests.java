package com.foleybooks.discovery;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "EUREKA_SECURITY_USERNAME=test-admin",
        "EUREKA_SECURITY_PASSWORD=test-secret"
})
class DiscoveryServiceApplicationTests {

    @Test
    void startup_whenStandaloneRegistry_bootsSuccessfully() {
    }
}
