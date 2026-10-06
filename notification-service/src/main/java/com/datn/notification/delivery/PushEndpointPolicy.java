package com.datn.notification.delivery;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.net.*;
import java.util.*;

/**
 * SSRF guard for push endpoints: HTTPS on the default port to an allow-listed browser push service host only
 * (no IP literals, localhost, user-info or custom ports). Checked when subscribing and again before every send.
 * {@code notification.push.allow-insecure-test-endpoints} exists solely for the integration-test profile.
 */
@Component
public class PushEndpointPolicy {
    public static final int MAX_ENDPOINT=2048;
    private final List<String> allowedHosts;private final boolean insecureTest;
    public PushEndpointPolicy(@Value("${notification.push.allowed-host-suffixes}") List<String> allowedHosts,
                              @Value("${notification.push.allow-insecure-test-endpoints:false}") boolean insecureTest){
        this.allowedHosts=allowedHosts.stream().map(h->h.strip().toLowerCase(Locale.ROOT)).filter(h->!h.isEmpty()).toList();this.insecureTest=insecureTest;
    }
    /** @throws IllegalArgumentException without echoing the endpoint (it is a bearer capability). */
    public URI validate(String endpoint){
        if(endpoint==null || endpoint.isBlank() || endpoint.length()>MAX_ENDPOINT)throw new IllegalArgumentException("Invalid push endpoint");
        URI uri;try{uri=new URI(endpoint);}catch(URISyntaxException e){throw new IllegalArgumentException("Invalid push endpoint");}
        if(insecureTest && "http".equals(uri.getScheme()) && uri.getHost()!=null)return uri;
        String host=uri.getHost()==null?"":uri.getHost().toLowerCase(Locale.ROOT);
        if(!"https".equals(uri.getScheme()) || uri.getRawUserInfo()!=null || (uri.getPort()!=-1 && uri.getPort()!=443) || uri.getRawFragment()!=null
           || host.isEmpty() || host.startsWith("[") || host.matches("[0-9.]+") || host.equals("localhost") || host.endsWith(".localhost")
           || allowedHosts.stream().noneMatch(a->host.equals(a) || host.endsWith("."+a)))
            throw new IllegalArgumentException("Push endpoint must be an HTTPS browser push service");
        return uri;
    }
    public static String origin(URI uri){return uri.getScheme()+"://"+uri.getHost()+(uri.getPort()==-1?"":":"+uri.getPort());}
}
