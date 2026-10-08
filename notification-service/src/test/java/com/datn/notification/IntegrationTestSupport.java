package com.datn.notification;
import com.sun.net.httpserver.HttpServer;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.test.context.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.interfaces.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;

/**
 * One shared Spring context for all integration tests: real PostgreSQL, RabbitMQ and SMTP (Mailpit) from the
 * TEST_* environment, background workers disabled (tests call runDue()), a controllable clock, and loopback HTTP stubs
 * standing in for Exam, Auth and browser push services. Insecure push endpoints are allowed only by this test profile.
 */
@SpringBootTest(properties={"spring.datasource.url=${TEST_DB_URL:jdbc:postgresql://localhost:5432/notification_test}","spring.datasource.username=${TEST_DB_USER:postgres}","spring.datasource.password=${TEST_DB_PASSWORD:}",
    "spring.rabbitmq.host=${TEST_RABBIT_HOST:localhost}","spring.rabbitmq.port=${TEST_RABBIT_PORT:5672}","spring.rabbitmq.username=guest","spring.rabbitmq.password=guest",
    "spring.rabbitmq.listener.simple.retry.initial-interval=100ms","eureka.client.enabled=false","spring.cloud.discovery.enabled=false","jwt.secret="+IntegrationTestSupport.KEY,
    "notification.workers.enabled=false","notification.app-base-url=http://app.test","notification.exam.token=test-exam-notification-token","notification.auth.token=test-contact-token",
    "spring.mail.host=${TEST_SMTP_HOST:localhost}","spring.mail.port=${TEST_SMTP_PORT:1025}","spring.mail.username=","spring.mail.password=",
    "spring.mail.properties.mail.smtp.auth=false","spring.mail.properties.mail.smtp.starttls.enable=false","spring.mail.properties.mail.smtp.starttls.required=false",
    "notification.mail.from=examini-test@example.test","notification.push.vapid-subject=mailto:ops@example.test","notification.push.allow-insecure-test-endpoints=true"})
@AutoConfigureMockMvc @Import(IntegrationTestSupport.TestClockConfig.class)
public abstract class IntegrationTestSupport {
    static final String KEY="test-only-jwt-key-32-bytes-or-longer-never-production";
    static final KeyPair VAPID;
    static final HttpServer STUB;
    /** Per-path handlers; default 404. Requests are recorded for assertions. */
    static final Map<String,Function<Request,Response>> ROUTES=new ConcurrentHashMap<>();
    static final List<Request> REQUESTS=new CopyOnWriteArrayList<>();
    record Request(String method,String path,Map<String,List<String>> headers,byte[] body){String header(String h){var v=headers.get(h);return v==null?null:v.getFirst();}}
    record Response(int status,String body,Map<String,String> headers){Response(int status,String body){this(status,body,Map.of());}}

    static{
        try{
            VAPID=PushTestKeys.keyPair();
            STUB=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            STUB.createContext("/",ex->{
                byte[] body=ex.getRequestBody().readAllBytes();
                var headers=new HashMap<String,List<String>>();ex.getRequestHeaders().forEach((k,v)->headers.put(k.toLowerCase(Locale.ROOT),v));
                var req=new Request(ex.getRequestMethod(),ex.getRequestURI().getPath(),headers,body);REQUESTS.add(req);
                var res=ROUTES.getOrDefault(req.path(),r->new Response(404,"{}")).apply(req);
                res.headers().forEach((k,v)->ex.getResponseHeaders().add(k,v));
                byte[] out=res.body().getBytes(StandardCharsets.UTF_8);ex.getResponseHeaders().add("Content-Type","application/json");
                ex.sendResponseHeaders(res.status(),out.length==0?-1:out.length);if(out.length>0)ex.getResponseBody().write(out);ex.close();
            });
            STUB.setExecutor(Executors.newFixedThreadPool(8));STUB.start();
        }catch(Exception e){throw new ExceptionInInitializerError(e);}
    }
    static String stubUrl(){return "http://127.0.0.1:"+STUB.getAddress().getPort();}

    @DynamicPropertySource static void stubs(DynamicPropertyRegistry r){
        r.add("notification.exam.base-url",IntegrationTestSupport::stubUrl);
        r.add("notification.auth.base-url",IntegrationTestSupport::stubUrl);
        r.add("notification.push.vapid-public-key",()->PushTestKeys.b64(PushTestKeys.point((ECPublicKey)VAPID.getPublic())));
        r.add("notification.push.vapid-private-key",()->PushTestKeys.b64(PushTestKeys.scalar((ECPrivateKey)VAPID.getPrivate())));
    }

    /** Mutable UTC clock shared by consumer, jobs and deliveries. */
    public static class MutableClock extends Clock {
        private volatile Instant now=Instant.now();
        public void set(Instant i){now=i;}
        public void advance(Duration d){now=now.plus(d);}
        @Override public Instant instant(){return now;}
        @Override public ZoneId getZone(){return ZoneOffset.UTC;}
        @Override public Clock withZone(ZoneId z){return this;}
    }
    @TestConfiguration static class TestClockConfig {
        @Bean @Primary MutableClock testClock(){return new MutableClock();}
    }
}
