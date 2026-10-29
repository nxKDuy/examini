package com.datn.questionbank.controller;

import com.datn.questionbank.dto.ImportDto.*;
import com.datn.questionbank.entity.Question.DifficultyLevel;
import com.datn.questionbank.security.QuestionActor;
import com.datn.questionbank.service.QuestionImportService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import java.util.UUID;

/** Document import jobs (contracts/question-import.md); owner-only, user JWT. */
@RestController
@RequestMapping("/question-imports")
@RequiredArgsConstructor
public class QuestionImportController {
    private final QuestionImportService service;

    private QuestionActor actor(HttpServletRequest request) {
        Object actor = request.getAttribute(QuestionActor.class.getName());
        if (!(actor instanceof QuestionActor)) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Yêu cầu xác thực");
        return (QuestionActor) actor;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ImportJobResponse upload(@RequestParam MultipartFile file, @RequestParam(required = false) UUID categoryId,
            @RequestParam(required = false) DifficultyLevel defaultDifficulty, HttpServletRequest req) {
        return service.upload(file, categoryId, defaultDifficulty, actor(req));
    }
    /** Templates to fill in: excel = one question per row, word = the "Câu N:" text format (also for PDF). */
    @GetMapping("/templates/{kind}")
    public org.springframework.http.ResponseEntity<byte[]> template(@PathVariable String kind, HttpServletRequest req) {
        actor(req);
        boolean excel = kind.equals("excel");
        if (!excel && !kind.equals("word")) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Không có mẫu này");
        byte[] body = excel ? com.datn.questionbank.service.ImportTemplates.excel() : com.datn.questionbank.service.ImportTemplates.word();
        String name = excel ? "mau-nhap-cau-hoi.xlsx" : "mau-nhap-cau-hoi.docx";
        String type = excel ? "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" : "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        return org.springframework.http.ResponseEntity.ok().contentType(MediaType.parseMediaType(type))
                .header("Content-Disposition", "attachment; filename=\"" + name + "\"").body(body);
    }
    /** AI generation (contracts/question-import.md "AI generation"): a JSON request instead of a file. */
    @GetMapping("/ai/status")
    public AiStatus aiStatus(HttpServletRequest req) { return service.aiStatus(actor(req)); }
    @PostMapping(value = "/ai", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ImportJobResponse generate(@Valid @RequestBody AiGenerateRequest body, HttpServletRequest req) {
        return service.generate(body, actor(req));
    }
    @GetMapping
    public Page<ImportJobResponse> list(@PageableDefault(size = 20) Pageable pageable, HttpServletRequest req) {
        return service.list(pageable, actor(req));
    }
    @GetMapping("/{id}")
    public ImportJobResponse job(@PathVariable UUID id, HttpServletRequest req) { return service.get(id, actor(req)); }
    @GetMapping("/{id}/items")
    public Page<ImportItemResponse> items(@PathVariable UUID id, @RequestParam(defaultValue = "ALL") ItemFilter filter,
            @PageableDefault(size = 50) Pageable pageable, HttpServletRequest req) {
        return service.items(id, filter, pageable, actor(req));
    }
    @PutMapping("/{id}/items/{itemId}")
    public ImportItemResponse update(@PathVariable UUID id, @PathVariable UUID itemId, @Valid @RequestBody ItemUpdate body, HttpServletRequest req) {
        return service.update(id, itemId, body, actor(req));
    }
    @PostMapping("/{id}/validate")
    public ImportSummary validate(@PathVariable UUID id, HttpServletRequest req) { return service.validateAll(id, actor(req)); }
    @PostMapping("/{id}/import")
    public ImportJobResult importItems(@PathVariable UUID id, @Valid @RequestBody(required = false) ImportRequest body, HttpServletRequest req) {
        return service.importItems(id, body, actor(req));
    }
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void discard(@PathVariable UUID id, HttpServletRequest req) { service.discard(id, actor(req)); }
}
