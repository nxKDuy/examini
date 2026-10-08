package com.datn.notification.delivery;
import nl.martijndwars.webpush.Encoding;
import nl.martijndwars.webpush.Notification;
import nl.martijndwars.webpush.PushService;
import nl.martijndwars.webpush.Urgency;
import nl.martijndwars.webpush.Utils;
import org.bouncycastle.jce.interfaces.ECPublicKey;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.security.*;
import java.time.Duration;
import java.util.*;

/**
 * Web Push via nl.martijndwars:web-push: the library only encrypts (RFC 8291 aes128gcm) and signs the VAPID JWT
 * (RFC 8292) through preparePost(); the request is sent with JDK HttpClient without redirects after the SSRF policy.
 * Missing/invalid VAPID configuration disables push (deliveries stay unsent), never a fake success.
 * Endpoints and keys are never logged.
 */
@Component
public class WebPushSender {
    private static final HttpClient CLIENT=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
    /** Headers the JDK client sets itself and refuses from callers. */
    private static final Set<String> RESTRICTED=Set.of("content-length","host","connection","expect","upgrade");
    private static final Base64.Encoder B64=Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder D64=Base64.getUrlDecoder();
    private final PushEndpointPolicy policy;private final PushService push;private final String publicKey;
    private final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(WebPushSender.class);

    static{if(Security.getProvider(BouncyCastleProvider.PROVIDER_NAME)==null)Security.addProvider(new BouncyCastleProvider());}

    public WebPushSender(PushEndpointPolicy policy,@Value("${notification.push.vapid-public-key:}") String publicKey,
                         @Value("${notification.push.vapid-private-key:}") String privateKey,@Value("${notification.push.vapid-subject:}") String subject){
        this.policy=policy;
        PushService service=null;String pub=null;
        boolean any=!publicKey.isBlank() || !privateKey.isBlank() || !subject.isBlank();
        if(!publicKey.isBlank() && !privateKey.isBlank() && (subject.startsWith("mailto:") || subject.startsWith("https://")))try{
            PublicKey p=Utils.loadPublicKey(publicKey.strip());PrivateKey k=Utils.loadPrivateKey(privateKey.strip());
            if(Utils.verifyKeyPair(k,p)){service=new PushService(new KeyPair(p,k),subject);pub=B64.encodeToString(Utils.encode((ECPublicKey)p));}
            else log.error("VAPID key pair mismatch: push unavailable");
        }catch(GeneralSecurityException|RuntimeException e){log.error("Invalid VAPID configuration: push unavailable");}
        else if(any)log.error("Incomplete VAPID configuration (public key, private key and mailto:/https: subject required): push unavailable");
        this.push=service;this.publicKey=pub;
    }
    public boolean configured(){return push!=null;}
    public String publicKey(){return publicKey;}

    /** Browser subscription keys: p256dh must be an uncompressed P-256 point on the curve, auth exactly 16 bytes. */
    public static void validateKeys(String p256dh,String auth){
        try{
            byte[] point=D64.decode(p256dh),secret=D64.decode(auth);
            if(point.length!=65 || point[0]!=4 || secret.length!=16)throw new IllegalArgumentException();
            Utils.loadPublicKey(point);
        }catch(GeneralSecurityException|RuntimeException e){throw new IllegalArgumentException("Invalid push subscription keys");}
    }

    /** HTTP outcome of one push attempt; status -1 means no HTTP response (network error). */
    public record Result(int status,Duration retryAfter){}

    public Result send(String endpoint,String p256dh,String auth,byte[] payload,Duration ttl,boolean urgent,String topic){
        if(!configured())throw new IllegalStateException("VAPID not configured");
        URI uri=policy.validate(endpoint);
        org.apache.http.client.methods.HttpPost post;
        try{
            var n=Notification.builder().endpoint(uri.toString()).userPublicKey(p256dh).userAuth(auth).payload(payload)
                .ttl((int)Math.max(0,Math.min(Integer.MAX_VALUE,ttl.toSeconds()))).urgency(urgent?Urgency.HIGH:Urgency.NORMAL).topic(topic).build();
            post=push.preparePost(n,Encoding.AES128GCM);
        }catch(GeneralSecurityException|IOException|org.jose4j.lang.JoseException|RuntimeException e){throw new IllegalArgumentException("Push encryption failed");}
        try{
            var req=HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10));
            for(var h:post.getAllHeaders())if(!RESTRICTED.contains(h.getName().toLowerCase(Locale.ROOT)))req.header(h.getName(),h.getValue());
            byte[] body=post.getEntity().getContent().readAllBytes();
            var res=CLIENT.send(req.POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(),HttpResponse.BodyHandlers.discarding());
            Duration retry=res.headers().firstValue("Retry-After").flatMap(v->{try{return Optional.of(Duration.ofSeconds(Long.parseLong(v.strip())));}catch(NumberFormatException e){return Optional.empty();}}).orElse(null);
            return new Result(res.statusCode(),retry);
        }catch(IOException e){return new Result(-1,null);}
        catch(InterruptedException e){Thread.currentThread().interrupt();return new Result(-1,null);}
    }
    static String b64(byte[] b){return B64.encodeToString(b);}
}
