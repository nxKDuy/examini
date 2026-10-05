package com.datn.grading.client;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import java.util.*;
@Component
public class GeminiClient {
    @Value("${gemini.api.key:}") private String apiKey="";
    @Value("${gemini.api.model:gemini-1.5-flash}") private String model;
    private final ObjectMapper json=new ObjectMapper();
    public GeminiGradeResponse gradeEssay(String question,String answer){
        if(apiKey.isBlank() || "demo-key".equals(apiKey))throw new IllegalStateException("Essay AI unavailable; manual review required");
        try{
            var factory=new SimpleClientHttpRequestFactory();factory.setConnectTimeout(5000);factory.setReadTimeout(15000);
            var client=new RestTemplate(factory);
            var headers=new HttpHeaders();headers.setContentType(MediaType.APPLICATION_JSON);headers.set("x-goog-api-key",apiKey);
            var body=Map.of("contents",List.of(Map.of("parts",List.of(Map.of("text","Grade this answer from 0 to 1; return JSON score and feedback. Question: "+question+" Answer: "+answer)))),"generationConfig",Map.of("responseMimeType","application/json"));
            var raw=client.postForObject("https://generativelanguage.googleapis.com/v1beta/models/"+model+":generateContent",new HttpEntity<>(body,headers),String.class);
            var tree=json.readTree(raw);var result=json.readTree(tree.path("candidates").get(0).path("content").path("parts").get(0).path("text").asText());
            if(!result.path("score").isNumber())throw new IllegalArgumentException("Missing score");
            float score=(float)result.path("score").asDouble();if(!Float.isFinite(score) || score<0 || score>1)throw new IllegalArgumentException("Invalid score");
            return new GeminiGradeResponse(score,result.path("feedback").asText());
        }catch(Exception failure){throw new IllegalStateException("Essay AI unavailable; manual review required");}
    }
    public record GeminiGradeResponse(float score,String feedback){}
}
