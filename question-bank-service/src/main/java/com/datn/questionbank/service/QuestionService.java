package com.datn.questionbank.service;

import com.datn.questionbank.dto.QuestionDto.*;
import com.datn.questionbank.entity.*;
import com.datn.questionbank.repository.*;
import com.datn.questionbank.security.*;
import com.fasterxml.jackson.core.JsonProcessingException;
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

    public Page<QuestionProjection> findAll(UUID categoryId, DifficultyLevel difficulty, QuestionType type,
            QuestionStatus status, CreationMethod method, SourceType sourceType, String text,
            Pageable pageable, QuestionActor actor) {
        Specification<Question> spec = accessible(actor);
        if (categoryId != null) spec = spec.and((r,q,b) -> b.equal(r.get("category").get("id"), categoryId));
        if (difficulty != null) spec = spec.and((r,q,b) -> b.equal(r.get("difficultyLevel"), difficulty));
        if (type != null) spec = spec.and((r,q,b) -> b.equal(r.get("questionType"), type));
        if (status != null) spec = spec.and((r,q,b) -> b.equal(r.get("status"), status));
        if (method != null) spec = spec.and((r,q,b) -> b.equal(r.get("creationMethod"), method));
        if (sourceType != null) spec = spec.and((r,q,b) -> b.equal(r.get("sourceType"), sourceType));
        if (text != null && !text.isBlank()) {
            if (text.length() > 200) throw bad("Tìm kiếm tối đa 200 ký tự");
            String term = "%" + text.trim().toLowerCase(Locale.ROOT).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
            spec = spec.and((r,q,b) -> b.like(b.lower(r.get("content")), term, '\\'));
        }
        return repo.findAll(spec, pageable).map(q -> fullResponse(q).withoutAnswers());
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
                .expectedAnswer(req.expectedAnswer()).createdBy(actor.userId()).bankId(bankId).scope(scope).updatedAt(LocalDateTime.now())
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
        if (q.getStatus() == QuestionStatus.PENDING_REVIEW || q.getStatus() == QuestionStatus.ARCHIVED)
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
        if (req.options() != null) replaceOptions(q, req.options());
        validate(q);
        q.setStatus(QuestionStatus.DRAFT);
        q.setQuestionVersion(q.getQuestionVersion() + 1);
        q.setUpdatedAt(LocalDateTime.now());
        saveRevision(q, actor.userId());
        return fullResponse(q);
    }

    @Transactional
    public QuestionResponse submit(UUID id, long version, QuestionActor actor) {
        Question q = lock(id);
        policy.requireOwner(q, actor);
        matchVersion(q, version);
        if (q.getStatus() != QuestionStatus.DRAFT) throw conflict("Chỉ DRAFT được gửi duyệt; REJECTED cần sửa trước");
        validate(q);
        q.setStatus(QuestionStatus.PENDING_REVIEW);
        q.setUpdatedAt(LocalDateTime.now());
        return fullResponse(q);
    }

    @Transactional
    public QuestionResponse review(UUID id, ReviewRequest req, QuestionActor actor) {
        policy.requireReviewer(actor);
        Question q = lock(id);
        if (q.getScope() == QuestionScope.SYSTEM && !actor.systemAdmin())
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "System review yêu cầu explicit authority");
        policy.requireRead(q, actor);
        matchVersion(q, req.questionVersion());
        if (q.getStatus() != QuestionStatus.PENDING_REVIEW) throw conflict("Chỉ PENDING_REVIEW được quyết định duyệt");
        if (req.decision() == null) throw bad("Thiếu quyết định duyệt");
        if (req.decision() == QuestionReview.Decision.REJECTED && (req.reviewNote() == null || req.reviewNote().isBlank()))
            throw bad("Từ chối cần ghi rõ lý do");
        validate(q);
        q.setStatus(req.decision() == QuestionReview.Decision.APPROVED ? QuestionStatus.APPROVED : QuestionStatus.REJECTED);
        q.setUpdatedAt(LocalDateTime.now());
        reviews.save(QuestionReview.builder().questionId(id).questionVersion(q.getQuestionVersion())
                .reviewerId(actor.userId()).decision(req.decision()).reviewNote(req.reviewNote())
                .reviewedAt(q.getUpdatedAt()).build());
        return fullResponse(q);
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
        if (limit < 1 || limit > 200) throw bad("limit phải từ 1 đến 200");
        // Database LIMIT plus random ordering avoids loading an entire bank to shuffle it.
        return repo.findEligibleRandom(category, difficulty.name(), type == null ? null : type.name(), actor.userId(),
                actor.admin(), Pageable.ofSize(limit)).stream().map(Question::getId).toList();
    }

    public List<QuestionProjection> getByIds(List<UUID> ids, boolean eligible, boolean includeAnswers, QuestionActor actor) {
        if (ids == null || ids.isEmpty() || ids.size() > 1000 || ids.stream().anyMatch(Objects::isNull))
            throw bad("Cần batch ID hợp lệ (tối đa 1000 phần tử thô)");
        ids = new ArrayList<>(new LinkedHashSet<>(ids));
        if (ids.size() > 200) throw bad("Tối đa 200 ID riêng biệt");
        if (actor.internal() && !eligible) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Service chỉ được đọc batch đủ điều kiện");
        Map<UUID, Question> found = new HashMap<>();
        repo.findAllById(ids).forEach(q -> found.put(q.getId(), q));
        Map<UUID, QuestionVersion> snapshots = new HashMap<>();
        versions.findCurrentSnapshots(ids).forEach(v -> snapshots.put(v.getQuestionId(), v));
        List<QuestionProjection> result = new ArrayList<>();
        for (UUID id : ids) {
            Question q = found.get(id);
            if (q == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy câu hỏi");
            policy.requireRead(q, actor);
            if (eligible && (q.getScope() != QuestionScope.PERSONAL || q.getStatus() != QuestionStatus.APPROVED))
                throw conflict("Batch chứa câu hỏi không đủ điều kiện PERSONAL APPROVED");
            requireAnswers(q, includeAnswers, actor);
            QuestionVersion v = snapshots.get(id);
            if (v == null || v.getVersionNumber() != q.getQuestionVersion()) throw conflict("Thiếu snapshot phiên bản câu hỏi");
            QuestionResponse frozen = readSnapshot(v).withContext(q.getStatus(), q.getUpdatedAt());
            result.add(includeAnswers ? frozen : frozen.withoutAnswers());
        }
        return result;
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
        return (r,q,b) -> actor.admin() ? b.equal(r.get("scope"), QuestionScope.PERSONAL)
                : b.and(b.equal(r.get("scope"), QuestionScope.PERSONAL), b.equal(r.get("createdBy"), actor.userId()));
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
    QuestionResponse copySnapshot(QuestionResponse source, UUID bankId, SourceType sourceType, String sourceRef, QuestionActor actor) {
        var bank = banks.get(bankId);
        banks.requireWrite(bank, actor);
        if (bank.getType() != QuestionBank.BankType.PERSONAL) throw bad("Fork target phải là PERSONAL");
        Question q = Question.builder().bankId(bankId).category(categoryService.getOrThrow(source.categoryId()))
                .content(source.content()).questionType(source.questionType()).difficultyLevel(source.difficultyLevel())
                .expectedAnswer(source.expectedAnswer()).source(source.source()).sourceLabel(source.sourceLabel())
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
                .creationMethod(q.getCreationMethod()).sourceType(q.getSourceType()).sourceRefId(q.getSourceRefId())
                .sourceLabel(q.getSourceLabel()).sourceUrl(q.getSourceUrl()).sourceExcerpt(q.getSourceExcerpt()).expectedAnswer(q.getExpectedAnswer())
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
