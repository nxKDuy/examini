package com.datn.questionbank.service;

import com.datn.questionbank.dto.ImportDto.*;
import com.datn.questionbank.entity.Question.*;
import com.datn.questionbank.entity.QuestionImportJob.Status;
import com.datn.questionbank.repository.CategoryRepository;
import com.datn.questionbank.security.QuestionActor;
import com.datn.questionbank.service.ai.AiQuestionGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** No Gemini key, no Ollama, no web: the real provider chain still produces reviewable questions locally. */
@SpringBootTest(properties={"eureka.client.enabled=false","spring.cloud.discovery.enabled=false",
        "jwt.secret=test-only-jwt-key-32-bytes-or-longer-never-production","question-bank.internal-token=isolated-test-service-token",
        "question-bank.ai.gemini-api-key=","question-bank.ai.ollama-url=","question-bank.ai.web-knowledge=false"})
@EnabledIfEnvironmentVariable(named="QB_TEST_DATABASE",matches="true")
@Transactional
class AiFallbackPersistenceTest {
    @Autowired QuestionImportService imports;
    @Autowired CategoryRepository categories;
    @Autowired AiQuestionGenerator ai;
    @MockBean AuthIdentityClient identities;
    final QuestionActor owner=new QuestionActor(UUID.randomUUID(),"TEACHER",false);

    @Test void localGeneratorServesWhenNoModelIsConfigured() {
        var status=imports.aiStatus(owner);
        assertTrue(status.available());assertEquals("local/rule-based",status.model());assertFalse(status.webKnowledge());
        assertEquals(List.of("gemini","ollama","local"),status.providers().stream().map(ProviderInfo::name).toList());
        assertEquals(List.of(false,false,true),status.providers().stream().map(ProviderInfo::available).toList());

        String material="Năm 1010, vua Lý Thái Tổ dời đô từ Hoa Lư về Đại La và đổi tên thành Thăng Long.\n"
                +"Năm 1802, Nguyễn Ánh lên ngôi hoàng đế và chọn Phú Xuân làm kinh đô của triều Nguyễn.\n"
                +"Văn Miếu được xây dựng năm 1070 và được xem là trường đại học đầu tiên của Việt Nam.\n"
                +"Năm 1945, Chủ tịch Hồ Chí Minh đọc Tuyên ngôn độc lập tại Quảng trường Ba Đình ở Hà Nội.";
        var req=new AiGenerateRequest("Lịch sử kinh đô",material,categories.findAll().getFirst().getId(),3,
                List.of(QuestionType.MULTIPLE_CHOICE,QuestionType.TRUE_FALSE),DifficultyLevel.MEDIUM,"vi",false,null);
        var job=imports.generate(req,owner);
        imports.runGeneration(job.id(),new AiQuestionGenerator.Prompt(req.topic(),material,List.of(),3,req.questionTypes(),DifficultyLevel.MEDIUM,"vi"),List.of(),owner.userId());
        var ready=imports.get(job.id(),owner);
        assertEquals(Status.READY,ready.status(),ready.failureReason());
        assertEquals("local/rule-based",ready.generation().model());
        assertEquals("Tài liệu của giáo viên",ready.generation().sources().getFirst().title());
        assertTrue(ready.warnings().getFirst().startsWith("Câu hỏi do bộ sinh nội bộ tạo"));
        var items=imports.items(job.id(),ItemFilter.ALL,PageRequest.of(0,10),owner).getContent();
        assertEquals(3,items.size());
        assertTrue(items.stream().allMatch(i->i.valid()&&i.reviewRequired()),items.toString());

        // Without material nor web knowledge the local generator explains what it needs.
        var none=new AiGenerateRequest("Chủ đề trống",null,req.categoryId(),2,null,null,"vi",false,true);
        var other=new QuestionActor(UUID.randomUUID(),"TEACHER",false);
        var failed=imports.generate(none,other);
        imports.runGeneration(failed.id(),new AiQuestionGenerator.Prompt("Chủ đề trống",null,List.of(),2,List.of(QuestionType.MULTIPLE_CHOICE),DifficultyLevel.MEDIUM,"vi"),List.of(),other.userId(),true);
        var f=imports.get(failed.id(),other);
        assertEquals(Status.FAILED,f.status());
        assertTrue(f.failureReason().startsWith("Bộ sinh nội bộ cần tài liệu"),f.failureReason());
    }
}
