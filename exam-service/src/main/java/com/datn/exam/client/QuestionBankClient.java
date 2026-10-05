package com.datn.exam.client;
import com.datn.exam.dto.Snapshot;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;
import java.util.*;
@FeignClient(name="question-bank-service",url="${question-bank.url:}",path="/questions")
public interface QuestionBankClient {
    @GetMapping("/random") List<UUID> random(@RequestParam UUID categoryId,@RequestParam String difficulty,@RequestParam int limit,
        @RequestHeader("X-Service-Token") String token,@RequestHeader("X-Question-Owner-Id") UUID owner);
    @PostMapping("/batch") List<Question> eligible(@RequestBody List<UUID> ids,
        @RequestHeader("X-Service-Token") String token,@RequestHeader("X-Question-Owner-Id") UUID owner,
        @RequestParam boolean eligible,@RequestParam boolean includeAnswers);
    record Question(UUID id,Long questionVersion,String status,String questionType,String content,List<Snapshot.Option> options,String expectedAnswer,
                    Boolean eligible,String bankType,String publicationStatus) {
        // Legacy/missing provider eligibility must never imply permission to publish.
        public Question(UUID id,Long questionVersion,String status,String questionType,String content,List<Snapshot.Option> options,String expectedAnswer) {
            this(id,questionVersion,status,questionType,content,options,expectedAnswer,null,null,null);
        }
    }
}
