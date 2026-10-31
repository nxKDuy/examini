package com.datn.gateway.ratelimit;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Per-client limits on the expensive or abusable routes (sign-in, password reset mails, AI calls, uploads), applied
 * before authentication so floods never reach the services. Token bucket per client address and rule, in memory
 * (one gateway instance); over the limit → 429 with Retry-After. The account lockout in auth-service stays the
 * per-account protection; this one is per address.
 */
@Component
public class RateLimitFilter implements GlobalFilter, Ordered {
    record Rule(String name, int perMinute, Predicate<ServerHttpRequest> matches) {}
    /** Per-minute budgets per client address. Generous on purpose: a whole class often shares one school NAT address. */
    record Limits(int passwordMail, int signIn, int ai, int upload) {
        static final Limits DEFAULT = new Limits(20, 300, 60, 600);
    }
    final List<Rule> rules;

    static List<Rule> rules(Limits l) {
        return List.of(
                new Rule("password-mail", l.passwordMail(), r -> post(r) && path(r).matches("/auth/password/(forgot|reset)")),
                new Rule("sign-in", l.signIn(), r -> post(r) && path(r).matches("/auth/(login|register|google)")),
                new Rule("ai", l.ai(), r -> (post(r) && path(r).matches("/question-imports/ai(/material|/material-url)?|/grades/session/[^/]+/questions/[^/]+/(explain|practice)|/submissions/[^/]+/items/[^/]+/ai-suggest|/questions/[^/]+/ai-review|/questions/ai/matrix-plan"))
                        || ("true".equals(r.getQueryParams().getFirst("ai")) && path(r).matches("/analytics/exams/[^/]+/insights|/grades/my/insights"))),
                new Rule("upload", l.upload(), r -> post(r) && path(r).matches("/question-media|/question-imports|/sessions/[^/]+/answers/[^/]+/audio")));
    }

    private static boolean post(ServerHttpRequest r) { return HttpMethod.POST.equals(r.getMethod()); }
    private static String path(ServerHttpRequest r) { return r.getPath().value().replaceAll("/+$", ""); }

    static final class Bucket {
        double tokens; long last;
        Bucket(double tokens, long last) { this.tokens = tokens; this.last = last; }
    }
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final Clock clock;
    private final boolean enabled;

    @Autowired
    public RateLimitFilter(@Value("${gateway.rate-limit.enabled:true}") boolean enabled,
                           @Value("${gateway.rate-limit.password-mail-per-minute:20}") int passwordMail,
                           @Value("${gateway.rate-limit.sign-in-per-minute:300}") int signIn,
                           @Value("${gateway.rate-limit.ai-per-minute:60}") int ai,
                           @Value("${gateway.rate-limit.upload-per-minute:600}") int upload) {
        this(enabled, new Limits(passwordMail, signIn, ai, upload), Clock.systemUTC());
    }
    RateLimitFilter(boolean enabled, Limits limits, Clock clock) { this.enabled = enabled; this.rules = rules(limits); this.clock = clock; }

    @Override public int getOrder() { return Ordered.HIGHEST_PRECEDENCE + 10; }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (!enabled) return chain.filter(exchange);
        var req = exchange.getRequest();
        for (Rule rule : rules) {
            if (!rule.matches().test(req)) continue;
            long wait = take(rule, client(req));
            if (wait > 0) {
                var res = exchange.getResponse();
                res.setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
                res.getHeaders().set("Retry-After", String.valueOf(wait));
                res.getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
                byte[] body = ("{\"type\":\"about:blank\",\"title\":\"Too Many Requests\",\"status\":429,\"detail\":\"Bạn thao tác quá nhanh. Vui lòng thử lại sau "
                        + wait + " giây.\"}").getBytes(StandardCharsets.UTF_8);
                return res.writeWith(Mono.just(res.bufferFactory().wrap(body)));
            }
            break; // one rule per request
        }
        return chain.filter(exchange);
    }

    /**
     * Address of the caller: the last X-Forwarded-For hop (the one our web proxy wrote; earlier hops are whatever the
     * client sent and could be rotated to dodge the limit), else the socket address.
     */
    static String client(ServerHttpRequest req) {
        String fwd = req.getHeaders().getFirst("X-Forwarded-For");
        if (fwd != null && !fwd.isBlank()) { String[] hops = fwd.split(","); return hops[hops.length - 1].strip(); }
        var a = req.getRemoteAddress();
        return a == null || a.getAddress() == null ? "unknown" : a.getAddress().getHostAddress();
    }

    /** Takes one token; returns 0 when allowed, otherwise the seconds until the next token. */
    long take(Rule rule, String client) {
        long now = clock.millis();
        if (buckets.size() > 50_000) buckets.entrySet().removeIf(e -> now - e.getValue().last > 120_000);
        double ratePerMs = rule.perMinute() / 60_000.0;
        var b = buckets.computeIfAbsent(rule.name() + "|" + client, k -> new Bucket(rule.perMinute(), now));
        synchronized (b) {
            b.tokens = Math.min(rule.perMinute(), b.tokens + (now - b.last) * ratePerMs);
            b.last = now;
            if (b.tokens >= 1) { b.tokens -= 1; return 0; }
            return Math.max(1, (long) Math.ceil((1 - b.tokens) / ratePerMs / 1000));
        }
    }
}
