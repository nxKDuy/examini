package com.datn.notification.client;
import com.fasterxml.jackson.databind.*;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;

/** Service-to-service JSON over HTTP with X-Service-Token only (never a user Bearer token); redirects are not followed. */
final class ServiceHttp {
    private static final HttpClient CLIENT=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();
    private ServiceHttp(){}
    static JsonNode call(ObjectMapper json,String provider,String token,HttpRequest.Builder request){
        if(token==null || token.isBlank())throw new ProviderException(ProviderException.Kind.CONFIG,provider+" credential not configured");
        HttpResponse<byte[]> res;
        try{res=CLIENT.send(request.timeout(Duration.ofSeconds(5)).header("X-Service-Token",token).header("Accept","application/json").build(),HttpResponse.BodyHandlers.ofByteArray());}
        catch(IOException e){throw new ProviderException(ProviderException.Kind.TRANSIENT,provider+" unreachable");}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new ProviderException(ProviderException.Kind.TRANSIENT,provider+" call interrupted");}
        int s=res.statusCode();
        if(s==401 || s==403)throw new ProviderException(ProviderException.Kind.CONFIG,provider+" rejected service credential ("+s+")");
        if(s==404)throw new ProviderException(ProviderException.Kind.NOT_FOUND,provider+" resource not found");
        if(s==429 || s>=500)throw new ProviderException(ProviderException.Kind.TRANSIENT,provider+" unavailable ("+s+")");
        if(s!=200)throw new ProviderException(ProviderException.Kind.INVALID,provider+" returned "+s);
        try{return json.readTree(res.body());}catch(IOException e){throw new ProviderException(ProviderException.Kind.INVALID,provider+" returned invalid JSON");}
    }
    static URI uri(String base,String path){return URI.create(base.replaceAll("/+$","")+path);}
}
