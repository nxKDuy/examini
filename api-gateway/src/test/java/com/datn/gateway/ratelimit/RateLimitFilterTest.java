package com.datn.gateway.ratelimit;

import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class RateLimitFilterTest {
    final RateLimitFilter filter = new RateLimitFilter(true, new RateLimitFilter.Limits(5, 20, 10, 60), Clock.systemUTC());
    final AtomicInteger passed = new AtomicInteger();
    final GatewayFilterChain chain = ex -> { passed.incrementAndGet(); return Mono.empty(); };

    HttpStatus call(MockServerHttpRequest req) {
        var ex = MockServerWebExchange.from(req);
        filter.filter(ex, chain).block();
        return (HttpStatus) ex.getResponse().getStatusCode();
    }

    @Test void passwordMailsAreLimitedPerAddressWithRetryAfter() {
        for (int i = 0; i < 5; i++) assertNull(call(MockServerHttpRequest.post("/auth/password/forgot").header("X-Forwarded-For", "10.0.0.1").build()));
        var ex = MockServerWebExchange.from(MockServerHttpRequest.post("/auth/password/forgot").header("X-Forwarded-For", "6.6.6.6, 10.0.0.1").build());
        filter.filter(ex, chain).block();
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, ex.getResponse().getStatusCode());
        assertTrue(Long.parseLong(ex.getResponse().getHeaders().getFirst("Retry-After")) >= 1);
        // a forged first hop does not open a new bucket; another real address has its own; unrelated routes are never limited
        assertNull(call(MockServerHttpRequest.post("/auth/password/forgot").header("X-Forwarded-For", "10.0.0.2").build()));
        for (int i = 0; i < 50; i++) assertNull(call(MockServerHttpRequest.get("/exams").header("X-Forwarded-For", "10.0.0.1").build()));
        assertEquals(56, passed.get());
    }

    @Test void aiCallsCountOnlyWhenAiIsRequested() {
        for (int i = 0; i < 30; i++) assertNull(call(MockServerHttpRequest.get("/analytics/exams/e1/insights?ai=false").header("X-Forwarded-For", "10.0.0.9").build()));
        for (int i = 0; i < 10; i++) assertNull(call(MockServerHttpRequest.get("/analytics/exams/e1/insights?ai=true").header("X-Forwarded-For", "10.0.0.9").build()));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, call(MockServerHttpRequest.post("/question-imports/ai").header("X-Forwarded-For", "10.0.0.9").build()));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, call(MockServerHttpRequest.post("/question-imports/ai/material-url").header("X-Forwarded-For", "10.0.0.9").build()),
                "fetching a web page for AI material counts as an AI call");
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, call(MockServerHttpRequest.post("/grades/session/s1/questions/q1/explain").header("X-Forwarded-For", "10.0.0.9").build()),
                "asking the AI tutor counts as an AI call");
    }

    @Test void disabledFilterPassesEverything() {
        var off = new RateLimitFilter(false, new RateLimitFilter.Limits(1, 1, 1, 1), Clock.systemUTC());
        for (int i = 0; i < 5; i++) {
            var ex = MockServerWebExchange.from(MockServerHttpRequest.post("/auth/login").header("X-Forwarded-For", "10.1.1.1").build());
            off.filter(ex, chain).block();
            assertNull(ex.getResponse().getStatusCode());
        }
    }

    @Test void defaultsLeaveRoomForAClassBehindOneAddress() {
        assertTrue(RateLimitFilter.Limits.DEFAULT.signIn() >= 200);
        assertTrue(RateLimitFilter.Limits.DEFAULT.upload() >= 400);
    }
}
