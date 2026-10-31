package com.datn.gateway.ratelimit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The gateway must start with the filter wired from configuration (a constructor mix-up once stopped it from booting). */
@SpringBootTest(properties = {"eureka.client.register-with-eureka=false", "eureka.client.fetch-registry=false",
        "jwt.secret=unit-test-only-signing-key-32-bytes-minimum-for-gateway", "gateway.rate-limit.sign-in-per-minute=123"})
class RateLimitWiringTest {
    @Autowired RateLimitFilter filter;

    @Test void filterIsWiredFromProperties() {
        assertEquals(123, filter.rules.stream().filter(r -> r.name().equals("sign-in")).findFirst().orElseThrow().perMinute());
    }
}
