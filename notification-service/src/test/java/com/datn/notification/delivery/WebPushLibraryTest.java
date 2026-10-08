package com.datn.notification.delivery;
import com.datn.notification.PushTestKeys;
import nl.martijndwars.webpush.*;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.jupiter.api.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.*;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** The approved library combination (web-push 5.1.2 + bcprov-jdk18on 1.86 + jose4j 0.9.7) against RFC vectors and JDK verification. */
class WebPushLibraryTest {
    static final Base64.Decoder D=Base64.getUrlDecoder();
    @BeforeAll static void provider(){if(Security.getProvider("BC")==null)Security.addProvider(new BouncyCastleProvider());}

    @Test void rfc8291AppendixAVectorWithApprovedBouncyCastle()throws Exception{
        assertEquals("1.86",Security.getProvider("BC").getVersionStr());
        var as=new KeyPair(Utils.loadPublicKey("BP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A8"),
            Utils.loadPrivateKey("yfWPiYE-n46HLnH0KqZOF1fJJU3MYrct3AELtAQ-oRw"));
        var ua=(org.bouncycastle.jce.interfaces.ECPublicKey)Utils.loadPublicKey("BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4");
        byte[] body=new HttpEce(Map.of("server-key-id",as),Map.of("server-key-id","P-256")).encrypt("When I grow up, I want to be a watermelon".getBytes(StandardCharsets.UTF_8),
            D.decode("DGv6ra1nlYgDCS1FRnbzlw"),null,"server-key-id",ua,D.decode("BTBZMqHH6r4Tts7J_aSIgg"),Encoding.AES128GCM);
        assertEquals("DGv6ra1nlYgDCS1FRnbzlwAAEABBBP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A_yl95bQpu6cVPTpK4Mqgkf1CXztLVBSt2Ks3oZwbuwXPXLWyouBWLVWGNWQexSgSxsj_Qulcy4a-fN",
            PushTestKeys.b64(body));
    }

    @Test void preparedRequestHasVerifiableVapidAndDecryptablePayload()throws Exception{
        var vapid=PushTestKeys.keyPair();var browser=PushTestKeys.browser();
        var service=new PushService(new KeyPair(Utils.loadPublicKey(PushTestKeys.b64(PushTestKeys.point((ECPublicKey)vapid.getPublic()))),
            Utils.loadPrivateKey(PushTestKeys.b64(PushTestKeys.scalar((ECPrivateKey)vapid.getPrivate())))),"mailto:ops@example.test");
        byte[] payload="{\"title\":\"Sắp đến giờ thi\"}".getBytes(StandardCharsets.UTF_8);
        var post=service.preparePost(Notification.builder().endpoint("https://fcm.googleapis.com/fcm/send/abc").userPublicKey(browser.p256dh()).userAuth(browser.authSecret())
            .payload(payload).ttl(900).urgency(Urgency.HIGH).topic("t0").build(),Encoding.AES128GCM);
        var h=new HashMap<String,String>();for(var x:post.getAllHeaders())h.put(x.getName(),x.getValue());
        assertEquals("aes128gcm",h.get("Content-Encoding"));assertEquals("900",h.get("TTL"));assertEquals("high",h.get("Urgency"));assertEquals("t0",h.get("Topic"));
        String auth=h.get("Authorization");assertTrue(auth.startsWith("vapid t="),auth.substring(0,6));
        String jwt=auth.substring(8,auth.indexOf(", k="));
        assertEquals(PushTestKeys.b64(PushTestKeys.point((ECPublicKey)vapid.getPublic())),auth.substring(auth.indexOf(", k=")+4));
        String[] parts=jwt.split("\\.");
        var verify=Signature.getInstance("SHA256withECDSAinP1363Format");verify.initVerify(vapid.getPublic());verify.update((parts[0]+"."+parts[1]).getBytes(StandardCharsets.US_ASCII));
        assertTrue(verify.verify(D.decode(parts[2])),"JDK verifies the ES256 VAPID signature");
        String claims=new String(D.decode(parts[1]),StandardCharsets.UTF_8);
        assertTrue(claims.contains("\"aud\":\"https://fcm.googleapis.com\"") && claims.contains("\"sub\":\"mailto:ops@example.test\""),claims);
        long exp=Long.parseLong(claims.replaceAll(".*\"exp\":(\\d+).*","$1"));
        assertTrue(exp>Instant.now().getEpochSecond() && exp<=Instant.now().plusSeconds(24*3600).getEpochSecond(),"exp within 24h");
        assertArrayEquals(payload,PushTestKeys.decrypt(post.getEntity().getContent().readAllBytes(),browser),"independent receiver decrypts the library output");
    }

    @Test void subscriptionKeysValidated()throws Exception{
        var b=PushTestKeys.browser();
        WebPushSender.validateKeys(b.p256dh(),b.authSecret());
        byte[] off=D.decode(b.p256dh());off[64]^=1;
        byte[] compressed=Arrays.copyOf(D.decode(b.p256dh()),33);compressed[0]=2;
        for(String[] bad:List.of(new String[]{PushTestKeys.b64(off),b.authSecret()},new String[]{PushTestKeys.b64(compressed),b.authSecret()},
                new String[]{b.p256dh(),PushTestKeys.b64(new byte[15])},new String[]{"!!",b.authSecret()},new String[]{b.p256dh(),"%%"}))
            assertThrows(IllegalArgumentException.class,()->WebPushSender.validateKeys(bad[0],bad[1]));
    }

    @Test void missingOrMismatchedVapidDisablesPush()throws Exception{
        var policy=new PushEndpointPolicy(List.of("fcm.googleapis.com"),false);
        var a=PushTestKeys.keyPair();var other=PushTestKeys.keyPair();
        String pub=PushTestKeys.b64(PushTestKeys.point((ECPublicKey)a.getPublic())),priv=PushTestKeys.b64(PushTestKeys.scalar((ECPrivateKey)a.getPrivate()));
        assertTrue(new WebPushSender(policy,pub,priv,"mailto:ops@example.test").configured());
        assertEquals(pub,new WebPushSender(policy,pub,priv,"mailto:ops@example.test").publicKey());
        assertFalse(new WebPushSender(policy,"","","").configured());
        assertFalse(new WebPushSender(policy,pub,priv,"").configured(),"subject required");
        assertFalse(new WebPushSender(policy,pub,PushTestKeys.b64(PushTestKeys.scalar((ECPrivateKey)other.getPrivate())),"mailto:ops@example.test").configured(),"mismatched pair");
        assertFalse(new WebPushSender(policy,"bm90LWEta2V5",priv,"mailto:ops@example.test").configured(),"garbage key");
        var off=new WebPushSender(policy,"","","");
        assertThrows(IllegalStateException.class,()->off.send("https://fcm.googleapis.com/x","a","b",new byte[1],java.time.Duration.ofMinutes(1),false,"t"));
    }
}
