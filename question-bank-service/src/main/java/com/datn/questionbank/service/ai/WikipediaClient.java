package com.datn.questionbank.service.ai;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.*;
import org.springframework.web.util.UriComponentsBuilder;
import java.net.URI;
import java.util.*;

/** Free encyclopedic knowledge for topics without teacher material (MediaWiki Action API, no key). */
@Component
public class WikipediaClient {
    static final int MAX_PAGES = 2, MAX_CHARS_PER_PAGE = 8000;
    private final String endpoint;
    private final String userAgent;
    private final RestTemplate http;

    public record Page(String title, String url, String text) {}
    public record Search(List<Page> pages, String warning) {}

    @org.springframework.beans.factory.annotation.Autowired
    public WikipediaClient(@Value("${question-bank.ai.wikipedia-endpoint:https://%s.wikipedia.org/w/api.php}") String endpoint,
                           @Value("${question-bank.ai.wikipedia-user-agent:EXAMINI/1.0 (online exam platform; DATN TDTU)}") String userAgent) {
        this.endpoint = endpoint;
        this.userAgent = userAgent;
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(10_000);
        this.http = new RestTemplate(factory);
    }

    /** Never throws: failures come back as a warning and no pages, so generation continues without web knowledge. */
    public Search search(String topic, String language) {
        String lang = "en".equalsIgnoreCase(language) ? "en" : "vi";
        String api = endpoint.contains("%s") ? endpoint.formatted(lang) : endpoint;
        try {
            JsonNode found = get(UriComponentsBuilder.fromUriString(api).queryParam("action", "query").queryParam("list", "search")
                    .queryParam("srsearch", topic).queryParam("srlimit", MAX_PAGES).queryParam("format", "json").queryParam("utf8", 1)
                    .encode().build().toUri());
            var pages = new ArrayList<Page>();
            for (JsonNode hit : found.path("query").path("search")) {
                String title = hit.path("title").asText("");
                if (title.isEmpty()) continue;
                JsonNode ex = get(UriComponentsBuilder.fromUriString(api).queryParam("action", "query").queryParam("prop", "extracts")
                        .queryParam("explaintext", 1).queryParam("exsectionformat", "plain").queryParam("redirects", 1)
                        .queryParam("titles", title).queryParam("format", "json").queryParam("utf8", 1).encode().build().toUri());
                for (JsonNode page : ex.path("query").path("pages")) {
                    String text = page.path("extract").asText("").strip();
                    if (text.isEmpty()) continue;
                    if (text.length() > MAX_CHARS_PER_PAGE) text = text.substring(0, MAX_CHARS_PER_PAGE);
                    String t = page.path("title").asText(title);
                    String host = URI.create(api).getHost();
                    pages.add(new Page(t, "https://" + host + "/wiki/" + t.replace(' ', '_'), text));
                }
            }
            return new Search(pages, pages.isEmpty() ? "Không tìm thấy bài Wikipedia phù hợp với chủ đề" : null);
        } catch (HttpStatusCodeException e) {
            return new Search(List.of(), e.getStatusCode().value() == 429 ? "Wikipedia đang giới hạn lượt truy cập; bỏ qua tìm kiến thức tự động"
                    : "Không truy cập được Wikipedia (" + e.getStatusCode().value() + ")");
        } catch (RestClientException | IllegalArgumentException e) {
            return new Search(List.of(), "Không truy cập được Wikipedia");
        }
    }

    private JsonNode get(URI uri) {
        var headers = new HttpHeaders();
        headers.set(HttpHeaders.USER_AGENT, userAgent);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        String body = http.exchange(uri, HttpMethod.GET, new HttpEntity<>(headers), String.class).getBody();
        try { return AiJson.JSON.readTree(body == null ? "{}" : body); } catch (Exception e) { throw new RestClientException("Bad JSON", e); }
    }
}
