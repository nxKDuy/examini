package com.datn.questionbank.service;

import com.datn.questionbank.dto.QuestionDto.*;
import com.datn.questionbank.dto.CopySyncDto.QuestionField;
import com.datn.questionbank.entity.*;
import com.datn.questionbank.repository.*;
import com.datn.questionbank.security.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencsv.CSVReader;
import com.opencsv.exceptions.CsvValidationException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Supplier;
import static com.datn.questionbank.entity.Question.*;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class QuestionService {
    private final QuestionRepository repo;
    private final CategoryService categoryService;
    private final QuestionVersionRepository versions;
    private final QuestionReviewRepository reviews;
    private final QuestionPolicy policy;
    private final ObjectMapper json;
    private final BankRegistry banks;
    private final ExamEligibilityService eligibility;

    public Page<QuestionProjection> findAll(UUID categoryId, DifficultyLevel difficulty, QuestionType type,
            QuestionStatus status, CreationMethod method, SourceType sourceType, String text,
            Pageable pageable, QuestionActor actor) {
        return findAll(categoryId,difficulty,type,status,method,sourceType,text,null,pageable,actor);
    }
    public Page<QuestionProjection> findAll(UUID categoryId, DifficultyLevel difficulty, QuestionType type,
            QuestionStatus status, CreationMethod method, SourceType sourceType, String text, QuestionScope scope,
            Pageable pageable, QuestionActor actor) {
        Specification<Question> spec = accessible(actor);
        if (scope != null) spec = spec.and((r,q,b) -> b.equal(r.get("scope"),scope));
        if (categoryId != null) spec = spec.and((r,q,b) -> b.equal(r.get("category").get("id"), categoryId));
        if (difficulty != null) spec = spec.and((r,q,b) -> b.equal(r.get("difficultyLevel"), difficulty));
        if (type != null) spec = spec.and((r,q,b) -> b.equal(r.get("questionType"), type));
        if (status != null) spec = spec.and((r,q,b) -> b.equal(r.get("status"), status));
        if (method != null) spec = spec.and((r,q,b) -> b.equal(r.get("creationMethod"), method));
        if (sourceType != null) spec = spec.and((r,q,b) -> b.equal(r.get("sourceType"), sourceType));
        if (text != null && text.length() > 200) throw bad("Tìm kiếm tối đa 200 ký tự");
        if (text != null && !text.isBlank()) {
            String term = "%" + text.trim().toLowerCase(Locale.ROOT).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
            spec = spec.and((r,q,b) -> b.like(b.lower(r.get("content")), term, '\\'));
        }
        return repo.findAll(spec, PageRequest.of(pageable.getPageNumber(),pageable.getPageSize(),pageable.getSort().and(Sort.by("id"))))
                .map(q -> fullResponse(q).withoutAnswers());
    }

    public QuestionProjection findById(UUID id, boolean includeAnswers, QuestionActor actor) {
        Question q = get(id);
        policy.requireRead(q, actor);
        return projection(q, includeAnswers, actor);
    }

    @Transactional
    public QuestionResponse create(CreateRequest req, QuestionActor actor) {
        policy.requireAuthor(actor);
        if (req.scope() != null && req.scope() != QuestionScope.PERSONAL) throw bad("Phase 1 chỉ hỗ trợ PERSONAL");
        return createInBank(req, banks.defaultPersonal(actor.userId()), actor);
    }

    @Transactional
    public QuestionResponse createInBank(CreateRequest req, UUID bankId, QuestionActor actor) {
        var bank = banks.get(bankId);
        banks.requireWrite(bank, actor);
        QuestionScope scope = QuestionScope.valueOf(bank.getType().name());
        if (req.scope() != null && req.scope() != scope) throw bad("scope không khớp bank");
        Question q = Question.builder().category(categoryService.getOrThrow(req.categoryId()))
                .content(req.content()).questionType(req.questionType()).difficultyLevel(req.difficultyLevel())
                .source(req.source()).sourceLabel(req.sourceLabel() != null ? req.sourceLabel() : req.source())
                .sourceType(req.sourceType() == null ? SourceType.NONE : req.sourceType())
                .sourceRefId(req.sourceRefId()).sourceUrl(req.sourceUrl()).sourceExcerpt(req.sourceExcerpt())
                .expectedAnswer(req.expectedAnswer()).explanation(req.explanation()==null||req.explanation().isBlank()?null:req.explanation().trim())
                .createdBy(actor.userId()).bankId(bankId).scope(scope).updatedAt(LocalDateTime.now())
                .build();
        replaceOptions(q, req.options());
        validate(q);
        saveRevision(q, actor.userId());
        return fullResponse(q);
    }

    @Transactional
    public QuestionResponse update(UUID id, UpdateRequest req, QuestionActor actor) {
        Question q = lock(id);
        policy.requireOwner(q, actor);
        matchVersion(q, req.questionVersion());
        if (q.getStatus() == QuestionStatus.ARCHIVED)
            throw conflict("Không thể sửa câu hỏi đang chờ duyệt hoặc đã lưu trữ");
        if (req.categoryId() != null) q.setCategory(categoryService.getOrThrow(req.categoryId()));
        if (req.content() != null) q.setContent(req.content());
        if (req.questionType() != null) q.setQuestionType(req.questionType());
        if (req.difficultyLevel() != null) q.setDifficultyLevel(req.difficultyLevel());
        if (req.source() != null) { q.setSource(req.source()); if (req.sourceLabel() == null) q.setSourceLabel(req.source()); }
        if (req.sourceLabel() != null) q.setSourceLabel(req.sourceLabel());
        if (req.sourceType() != null) q.setSourceType(req.sourceType());
        if (req.sourceRefId() != null) q.setSourceRefId(req.sourceRefId());
        if (req.sourceUrl() != null) q.setSourceUrl(req.sourceUrl());
        if (req.sourceExcerpt() != null) q.setSourceExcerpt(req.sourceExcerpt());
        if (req.expectedAnswer() != null) q.setExpectedAnswer(req.expectedAnswer());
        if (req.explanation() != null) q.setExplanation(req.explanation().isBlank()?null:req.explanation().trim());
        if (req.options() != null) replaceOptions(q, req.options());
        validate(q);
        q.setStatus(QuestionStatus.DRAFT);
        q.setQuestionVersion(q.getQuestionVersion() + 1);
        q.setUpdatedAt(LocalDateTime.now());
        saveRevision(q, actor.userId());
        return fullResponse(q);
    }

    @Transactional
    public QuestionResponse activate(UUID id, long version, QuestionActor actor) {
        Question q = lock(id); policy.requireOwner(q, actor); matchVersion(q, version);
        if (q.getStatus() != QuestionStatus.DRAFT) throw conflict("Chỉ DRAFT được activate");
        validate(q); q.setStatus(QuestionStatus.ACTIVE); q.setUpdatedAt(LocalDateTime.now());
        return fullResponse(q);
    }

    @Transactional
    public QuestionResponse applyCopySync(UUID id,long expectedVersion,Map<QuestionField,JsonNode> chosen,QuestionActor actor) {
        var q=lock(id);policy.requireOwner(q,actor);matchVersion(q,expectedVersion);
        if(q.getStatus()==QuestionStatus.ARCHIVED)throw conflict("Không thể đồng bộ Question đã archive");
        if(chosen.isEmpty())return fullResponse(q);
        chosen.forEach((field,value)->{
            switch(field) {
                case CATEGORY -> q.setCategory(categoryService.getOrThrow(UUID.fromString(value.asText())));
                case CONTENT -> q.setContent(value.asText());
                case QUESTION_TYPE -> q.setQuestionType(QuestionType.valueOf(value.asText()));
                case DIFFICULTY -> q.setDifficultyLevel(DifficultyLevel.valueOf(value.asText()));
                case OPTIONS -> replaceOptions(q,json.convertValue(value,new com.fasterxml.jackson.core.type.TypeReference<List<OptionRequest>>(){}));
                case EXPECTED_ANSWER -> q.setExpectedAnswer(value.isNull()?null:value.asText());
                case EXPLANATION -> q.setExplanation(value.isNull()?null:value.asText());
                default -> throw bad("Field không thuộc Question");
            }
        });
        validate(q);q.setStatus(QuestionStatus.DRAFT);q.setQuestionVersion(q.getQuestionVersion()+1);
        q.setUpdatedAt(LocalDateTime.now());saveRevision(q,actor.userId());return fullResponse(q);
    }
    public QuestionResponse submit(UUID id, long version, QuestionActor actor) {
        throw new ResponseStatusException(HttpStatus.GONE, "Review Question đã bỏ; dùng /activate hoặc Organization contribution");
    }
    public QuestionResponse review(UUID id, ReviewRequest req, QuestionActor actor) {
        throw new ResponseStatusException(HttpStatus.GONE, "Review Question đã bỏ; review Organization contribution tại bank");
    }

    @Transactional
    public void archive(UUID id, long version, QuestionActor actor) {
        Question q = lock(id);
        policy.requireOwner(q, actor);
        matchVersion(q, version);
        if (q.getStatus() == QuestionStatus.ARCHIVED) throw conflict("Câu hỏi đã được lưu trữ");
        q.setStatus(QuestionStatus.ARCHIVED);
        q.setUpdatedAt(LocalDateTime.now());
    }

    public List<VersionResponse> history(UUID id, boolean includeAnswers, QuestionActor actor) {
        Question q = get(id);
        policy.requireRead(q, actor);
        requireAnswers(q, includeAnswers, actor);
        return versions.findByQuestionIdOrderByVersionNumberDesc(id).stream().map(v -> versionResponse(v, q, includeAnswers, actor)).toList();
    }

    public VersionResponse version(UUID id, long number, boolean includeAnswers, QuestionActor actor) {
        Question q = get(id);
        policy.requireRead(q, actor);
        return versionResponse(versions.findByQuestionIdAndVersionNumber(id, number)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy phiên bản")), q, includeAnswers, actor);
    }

    public List<ReviewResponse> reviewHistory(UUID id, QuestionActor actor) {
        policy.requireRead(get(id), actor);
        return reviews.findByQuestionIdOrderByReviewedAtDesc(id).stream().map(r -> new ReviewResponse(
                r.getId(), r.getQuestionVersion(), r.getReviewerId(), r.getDecision(), r.getReviewNote(), r.getReviewedAt())).toList();
    }

    public List<UUID> getRandomQuestionIds(UUID category, DifficultyLevel difficulty, int limit,
            QuestionType type, QuestionActor actor) {
        return eligibility.random(category, difficulty, limit, type, null, actor);
    }
    public List<UUID> getRandomQuestionIds(UUID category, DifficultyLevel difficulty, int limit,
            QuestionType type, UUID bankId, QuestionActor actor) {
        return eligibility.random(category, difficulty, limit, type, bankId, actor);
    }
    public List<QuestionProjection> getByIds(List<UUID> ids, boolean eligible, boolean includeAnswers, QuestionActor actor) {
        if (eligible) return eligibility.batch(ids, includeAnswers, actor);
        if (actor.internal()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Service chỉ được đọc eligible batch");
        return ExamEligibilityService.ids(ids).stream().map(id -> findById(id, includeAnswers, actor)).toList();
    }

    @Transactional
    public ImportResult importCsv(MultipartFile file, UUID categoryId, QuestionActor actor) {
        policy.requireAuthor(actor);
        if (file == null || file.isEmpty() || file.getSize() > 10 * 1024 * 1024) throw bad("CSV rỗng hoặc vượt 10MB");
        Category category = categoryService.getOrThrow(categoryId);
        List<String> errors = new ArrayList<>();
        List<Question> valid = new ArrayList<>();
        int total = 0;
        try (CSVReader csv = new CSVReader(new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
            String[] header = csv.readNext();
            if (header == null) throw bad("Thiếu header CSV");
            header[0] = header[0].replace("\uFEFF", "");
            Map<String, Integer> columns = new LinkedHashMap<>();
            for (int i = 0; i < header.length; i++) {
                String name = header[i].trim();
                if (name.isEmpty() || columns.put(name, i) != null) throw bad("Header CSV trống hoặc trùng");
                if (!Set.of("content", "type", "difficulty", "source", "expectedAnswer", "sourceType", "sourceRefId",
                        "sourceLabel", "sourceUrl", "sourceExcerpt").contains(name) && !name.matches("(option|correct)[1-9][0-9]?"))
                    throw bad("Cột CSV không hỗ trợ: " + name);
            }
            if (!columns.keySet().containsAll(Set.of("content", "type", "difficulty"))) throw bad("Thiếu content,type,difficulty");
            for (String name : columns.keySet()) {
                if (name.startsWith("option") && !columns.containsKey("correct" + name.substring(6))) throw bad("Thiếu cột correct cho " + name);
                if (name.startsWith("correct") && !columns.containsKey("option" + name.substring(7))) throw bad("Thiếu cột option cho " + name);
            }
            String[] row;
            while ((row = csv.readNext()) != null) {
                if (++total > 1000) throw bad("Tối đa 1000 dòng mỗi lần import");
                try {
                    if (row.length != header.length) throw new IllegalArgumentException("Số cột không khớp header");
                    Question q = csvQuestion(row, columns, category, actor.userId());
                    QuestionValidation.validate(q);
                    valid.add(q);
                } catch (IllegalArgumentException e) { errors.add("Dòng " + (total + 1) + ": " + e.getMessage()); }
            }
        } catch (IOException | CsvValidationException e) { throw bad("CSV không hợp lệ gần dòng " + (total + 2)); }
        // Parse/validate before persistence; database failures roll back the import rather than reporting false success.
        UUID bankId = banks.defaultPersonal(actor.userId());
        for (Question q : valid) { q.setBankId(bankId); saveRevision(q, actor.userId()); }
        return new ImportResult(total, valid.size(), errors.size(), errors);
    }

    private Question csvQuestion(String[] row, Map<String,Integer> columns, Category category, UUID owner) {
        Question q = Question.builder().category(category).createdBy(owner).content(cell(row, columns, "content"))
                .questionType(enumValue(QuestionType.class, cell(row, columns, "type"), "Loại câu hỏi"))
                .difficultyLevel(enumValue(DifficultyLevel.class, cell(row, columns, "difficulty"), "Độ khó"))
                .creationMethod(CreationMethod.IMPORT).source(cell(row, columns, "source"))
                .sourceType(columns.containsKey("sourceType") && !cell(row, columns, "sourceType").isBlank()
                        ? enumValue(SourceType.class, cell(row, columns, "sourceType"), "Loại nguồn") : SourceType.NONE)
                .sourceRefId(cell(row, columns, "sourceRefId")).sourceUrl(cell(row, columns, "sourceUrl"))
                .sourceExcerpt(cell(row, columns, "sourceExcerpt")).expectedAnswer(cell(row, columns, "expectedAnswer"))
                .sourceLabel(columns.containsKey("sourceLabel") ? cell(row, columns, "sourceLabel") : cell(row, columns, "source"))
                .updatedAt(LocalDateTime.now()).build();
        for (int n = 1; n <= 99; n++) {
            if (!columns.containsKey("option" + n)) continue;
            String content = cell(row, columns, "option" + n), correct = cell(row, columns, "correct" + n);
            if (content.isBlank() && (correct.isBlank() || correct.equalsIgnoreCase("false"))) continue;
            if (!(correct.equalsIgnoreCase("true") || correct.equalsIgnoreCase("false")))
                throw new IllegalArgumentException("correct" + n + " chỉ nhận true/false");
            q.getOptions().add(QuestionOption.builder().question(q).content(content)
                    .correct(Boolean.parseBoolean(correct)).displayOrder((short)(n - 1)).build());
        }
        return q;
    }
    private String cell(String[] row, Map<String,Integer> columns, String name) {
        return columns.containsKey(name) ? row[columns.get(name)].trim() : "";
    }
    private <E extends Enum<E>> E enumValue(Class<E> cls, String value, String label) {
        try { return Enum.valueOf(cls, value.toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException(label + " không hợp lệ"); }
    }
    private Specification<Question> accessible(QuestionActor actor) {
        return (r,q,b) -> b.and(b.equal(r.get("scope"), QuestionScope.PERSONAL), b.equal(r.get("createdBy"), actor.userId()));
    }
    private void replaceOptions(Question q, List<OptionRequest> options) {
        q.getOptions().clear();
        if (options != null) for (var o : options) q.getOptions().add(QuestionOption.builder().question(q)
                .content(o.content()).correct(o.correct()).displayOrder(o.displayOrder()).build());
    }
    private void saveRevision(Question q, UUID actor) {
        repo.saveAndFlush(q);
        try {
            versions.save(QuestionVersion.builder().questionId(q.getId()).versionNumber(q.getQuestionVersion())
                    .snapshot(json.writeValueAsString(fullResponse(q))).createdAt(q.getUpdatedAt()).changedBy(actor).build());
        } catch (JsonProcessingException e) { throw new IllegalStateException("Cannot serialize question revision", e); }
    }

    /** Internal domain operation: caller validates source visibility and target bank before copying. */
    @Transactional
    public QuestionResponse copySnapshot(QuestionResponse source, UUID bankId, SourceType sourceType, String sourceRef, QuestionActor actor) {
        var bank = banks.get(bankId);
        banks.requireWrite(bank, actor);
        if (bank.getType() != QuestionBank.BankType.PERSONAL) throw bad("Fork target phải là PERSONAL");
        Question q = Question.builder().bankId(bankId).category(categoryService.getOrThrow(source.categoryId()))
                .content(source.content()).questionType(source.questionType()).difficultyLevel(source.difficultyLevel())
                .expectedAnswer(source.expectedAnswer()).explanation(source.explanation()).source(source.source()).sourceLabel(source.sourceLabel())
                .sourceUrl(source.sourceUrl()).sourceExcerpt(source.sourceExcerpt()).sourceType(sourceType).sourceRefId(sourceRef)
                .creationMethod(CreationMethod.COPIED).createdBy(actor.userId()).updatedAt(LocalDateTime.now()).build();
        replaceOptions(q, source.options().stream().map(o -> new OptionRequest(o.content(), Boolean.TRUE.equals(o.correct()), o.displayOrder())).toList());
        validate(q); saveRevision(q, actor.userId()); return fullResponse(q);
    }
    private QuestionResponse readSnapshot(QuestionVersion v) {
        try { return json.readValue(v.getSnapshot(), QuestionResponse.class); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Cannot read question revision", e); }
    }
    private VersionResponse versionResponse(QuestionVersion v, Question current, boolean includeAnswers, QuestionActor actor) {
        requireAnswers(current, includeAnswers, actor);
        QuestionResponse snapshot = readSnapshot(v);
        return new VersionResponse(v.getVersionNumber(), v.getCreatedAt(), v.getChangedBy(),
                includeAnswers ? snapshot : snapshot.withoutAnswers());
    }
    private QuestionProjection projection(Question q, boolean includeAnswers, QuestionActor actor) {
        requireAnswers(q, includeAnswers, actor);
        return includeAnswers ? fullResponse(q) : fullResponse(q).withoutAnswers();
    }
    private void requireAnswers(Question q, boolean requested, QuestionActor actor) {
        if (requested && !policy.canReadAnswers(q, actor))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Không được đọc đáp án");
    }
    private QuestionResponse fullResponse(Question q) {
        return QuestionResponse.builder().id(q.getId()).categoryId(q.getCategory().getId()).categoryName(q.getCategory().getName())
                .content(q.getContent()).questionType(q.getQuestionType()).difficultyLevel(q.getDifficultyLevel())
                .source(q.getSource()).createdAt(q.getCreatedAt()).updatedAt(q.getUpdatedAt()).createdBy(q.getCreatedBy())
                .questionVersion(q.getQuestionVersion()).status(q.getStatus()).scope(q.getScope())
                .bankId(q.getBankId()).bankType(QuestionBank.BankType.valueOf(q.getScope().name()))
                .creationMethod(q.getCreationMethod()).sourceType(q.getSourceType()).sourceRefId(q.getSourceRefId())
                .sourceLabel(q.getSourceLabel()).sourceUrl(q.getSourceUrl()).sourceExcerpt(q.getSourceExcerpt()).expectedAnswer(q.getExpectedAnswer()).explanation(q.getExplanation())
                .options(q.getOptions().stream().sorted(Comparator.comparingInt(QuestionOption::getDisplayOrder))
                        .map(o -> new OptionResponse(o.getId(), o.getContent(), o.isCorrect(), o.getDisplayOrder())).toList()).build();
    }
    private void validate(Question q) {
        try { QuestionValidation.validate(q); } catch (IllegalArgumentException e) { throw bad(e.getMessage()); }
    }
    private void matchVersion(Question q, Long version) {
        if (version == null || q.getQuestionVersion() != version) throw conflict("Phiên bản đã thay đổi; tải lại câu hỏi");
    }
    private Question get(UUID id) { return repo.findById(id).orElseThrow(missing()); }
    private Question lock(UUID id) { return repo.findForUpdate(id).orElseThrow(missing()); }
    private Supplier<ResponseStatusException> missing() {
        return () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy câu hỏi");
    }
    private ResponseStatusException bad(String msg) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, msg); }
    private ResponseStatusException conflict(String msg) { return new ResponseStatusException(HttpStatus.CONFLICT, msg); }
}
