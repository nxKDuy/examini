package com.datn.notification;
import javax.crypto.*;
import javax.crypto.spec.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.*;
import java.security.spec.*;
import java.math.BigInteger;
import java.util.*;

/**
 * TEST-ONLY helpers: browser-side (user agent) P-256 keys and an independent RFC 8291 decoder used to prove that what
 * the production library sends can be decrypted by a receiver. Production code never uses this class.
 */
public final class PushTestKeys {
    private static final Base64.Encoder B64=Base64.getUrlEncoder().withoutPadding();
    private PushTestKeys(){}
    public record Browser(KeyPair keys,byte[] auth){
        public String p256dh(){return b64(point((ECPublicKey)keys.getPublic()));}
        public String authSecret(){return b64(auth);}
    }
    public static Browser browser()throws GeneralSecurityException{var a=new byte[16];new SecureRandom().nextBytes(a);return new Browser(keyPair(),a);}
    public static KeyPair keyPair()throws GeneralSecurityException{var g=KeyPairGenerator.getInstance("EC");g.initialize(new ECGenParameterSpec("secp256r1"));return g.generateKeyPair();}
    public static String b64(byte[] b){return B64.encodeToString(b);}
    public static byte[] point(ECPublicKey k){
        var out=new byte[65];out[0]=4;fixed(k.getW().getAffineX(),out,1);fixed(k.getW().getAffineY(),out,33);return out;
    }
    public static byte[] scalar(ECPrivateKey k){var out=new byte[32];fixed(k.getS(),out,0);return out;}
    private static void fixed(BigInteger v,byte[] out,int at){byte[] b=v.toByteArray();int n=Math.min(32,b.length);System.arraycopy(b,b.length-n,out,at+32-n,n);}

    /** Decrypts an aes128gcm Web Push body for the browser that owns {@code ua}. */
    public static byte[] decrypt(byte[] body,Browser ua)throws GeneralSecurityException{
        byte[] salt=Arrays.copyOfRange(body,0,16);int idlen=body[20]&0xff;byte[] asPublic=Arrays.copyOfRange(body,21,21+idlen);
        byte[] cipher=Arrays.copyOfRange(body,21+idlen,body.length);
        var params=((ECPublicKey)ua.keys().getPublic()).getParams();
        var w=new ECPoint(new BigInteger(1,Arrays.copyOfRange(asPublic,1,33)),new BigInteger(1,Arrays.copyOfRange(asPublic,33,65)));
        var ka=KeyAgreement.getInstance("ECDH");ka.init(ua.keys().getPrivate());ka.doPhase(KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(w,params)),true);
        byte[] uaPublic=point((ECPublicKey)ua.keys().getPublic());
        byte[] ikm=hkdf(ua.auth(),ka.generateSecret(),concat("WebPush: info\0".getBytes(StandardCharsets.US_ASCII),uaPublic,asPublic),32);
        byte[] cek=hkdf(salt,ikm,"Content-Encoding: aes128gcm\0".getBytes(StandardCharsets.US_ASCII),16),nonce=hkdf(salt,ikm,"Content-Encoding: nonce\0".getBytes(StandardCharsets.US_ASCII),12);
        var gcm=Cipher.getInstance("AES/GCM/NoPadding");gcm.init(Cipher.DECRYPT_MODE,new SecretKeySpec(cek,"AES"),new GCMParameterSpec(128,nonce));
        byte[] plain=gcm.doFinal(cipher);int end=plain.length-1;while(end>=0 && plain[end]==0)end--;
        if(end<0 || plain[end]!=2)throw new GeneralSecurityException("missing last-record delimiter");
        return Arrays.copyOf(plain,end);
    }
    private static byte[] hkdf(byte[] salt,byte[] ikm,byte[] info,int len)throws GeneralSecurityException{
        var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(salt,"HmacSHA256"));byte[] prk=mac.doFinal(ikm);
        mac.init(new SecretKeySpec(prk,"HmacSHA256"));mac.update(info);mac.update((byte)1);return Arrays.copyOf(mac.doFinal(),len);
    }
    private static byte[] concat(byte[]... parts){int n=0;for(byte[] p:parts)n+=p.length;var b=ByteBuffer.allocate(n);for(byte[] p:parts)b.put(p);return b.array();}
}
