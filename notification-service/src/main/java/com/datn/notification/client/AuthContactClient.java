package com.datn.notification.client;
import com.fasterxml.jackson.databind.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.net.http.HttpRequest;
import java.util.*;

/** POST /auth/internal/contacts with NOTIFICATION_CONTACT_TOKEN; results are used for one send and never persisted. */
@Component
public class AuthContactClient {
    public static final int MAX_IDS=100;
    private final ObjectMapper json;private final String baseUrl,token;
    public AuthContactClient(ObjectMapper json,@Value("${notification.auth.base-url}") String baseUrl,@Value("${notification.auth.token:}") String token){this.json=json;this.baseUrl=baseUrl;this.token=token;}
    public record Contact(UUID userId,String email,String displayName){}

    /** Missing users are simply absent from the result. */
    public Map<UUID,Contact> contacts(Collection<UUID> userIds){
        var ids=new ArrayList<>(new LinkedHashSet<>(userIds));
        if(ids.isEmpty())return Map.of();
        if(ids.size()>MAX_IDS)throw new IllegalArgumentException("At most "+MAX_IDS+" user IDs per lookup");
        String body;try{body=json.writeValueAsString(Map.of("userIds",ids));}catch(Exception e){throw new IllegalStateException(e);}
        JsonNode r=ServiceHttp.call(json,"Auth",token,HttpRequest.newBuilder(ServiceHttp.uri(baseUrl,"/auth/internal/contacts"))
            .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body)));
        if(!r.isArray())throw new ProviderException(ProviderException.Kind.INVALID,"Auth contacts response invalid");
        var out=new HashMap<UUID,Contact>();
        for(JsonNode n:r){
            try{
                var c=new Contact(UUID.fromString(n.path("userId").asText()),n.path("email").asText(""),n.path("displayName").asText(""));
                if(ids.contains(c.userId()) && !c.email().isBlank())out.put(c.userId(),c);
            }catch(IllegalArgumentException e){throw new ProviderException(ProviderException.Kind.INVALID,"Auth contacts response invalid");}
        }
        return out;
    }
    /** Log-safe form: first character and domain only. */
    public static String mask(String email){int at=email.indexOf('@');return at<1?"***":email.charAt(0)+"***"+email.substring(at);}
}
