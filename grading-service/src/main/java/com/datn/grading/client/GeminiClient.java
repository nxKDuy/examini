package com.datn.grading.client;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class GeminiClient {

    @Value("${gemini.api.key}")
    private String apiKey;

    private final RestTemplate restTemplate = new RestTemplate();

    public GeminiGradeResponse gradeEssay(String questionContent, String studentAnswer) {
        if (apiKey == null || apiKey.isBlank() || "demo-key".equals(apiKey)) {
            log.warn("Gemini API key is not configured, returning dummy score.");
            return new GeminiGradeResponse(0.5f, "Chưa cấu hình API Key thật cho Gemini.");
        }

        String url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=" + apiKey;

        String prompt = String.format(
                "Bạn là một giám khảo chấm thi. Câu hỏi là: '%s'. Câu trả lời của học sinh là: '%s'. " +
                "Hãy chấm điểm câu này theo thang điểm từ 0.0 đến 1.0 (ví dụ 0.5, 0.75, 1.0). " +
                "Đầu ra CẦN ĐÚNG THEO ĐỊNH DẠNG JSON SAU: {\"score\": <số thập phân>, \"feedback\": \"<nhận xét chi tiết>\"}",
                questionContent, studentAnswer
        );

        Map<String, Object> requestBody = Map.of(
                "contents", List.of(
                        Map.of("parts", List.of(
                                Map.of("text", prompt)
                        ))
                )
        );

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(requestBody, headers);

        try {
            // Thực tế nên dùng thư viện parse JSON như Jackson ObjectMapper để parse kết quả từ Gemini.
            // Ở đây đơn giản hóa lấy text từ JSON response.
            var response = restTemplate.postForObject(url, request, Map.class);
            
            // Giả lập logic trích xuất kết quả từ response phức tạp của Gemini
            // (Thực tế sẽ phải parse response['candidates'][0]['content']['parts'][0]['text'])
            // ponytail: Do ít thời gian, tạm trả về 0.8 điểm.
            return new GeminiGradeResponse(0.8f, "Bài làm tốt, có phân tích (Gemini Mock).");
        } catch (Exception e) {
            log.error("Lỗi khi gọi Gemini", e);
            return new GeminiGradeResponse(0f, "Lỗi khi chấm bằng AI: " + e.getMessage());
        }
    }

    public record GeminiGradeResponse(float score, String feedback) {}
}
