package com.datn.questionbank.service;

import com.datn.questionbank.dto.ImportDto.*;
import com.datn.questionbank.dto.QuestionDto.*;
import com.datn.questionbank.entity.*;
import com.datn.questionbank.entity.Question.*;
import com.datn.questionbank.entity.QuestionImportJob.Status;
import com.datn.questionbank.repository.*;
import com.datn.questionbank.security.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.task.TaskExecutor;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

/** Staged document import: Upload → Extract → Parse (async) → Review/Edit → Validate → Import as Personal DRAFT. */
@Service @RequiredArgsConstructor
@Transactional(readOnly = true)
public class QuestionImportService {
    public static final long MAX_BYTES = 20L * 1024 * 1024;
    private final QuestionImportJobRepository jobs;
    private final QuestionImportItemRepository items;
    private final CategoryRepository categories;
    private final DocumentExtractor extractor;
    private final QuestionService questions;
    private final BankRegistry banks;
    private final QuestionPolicy policy;
    private final ObjectMapper json;
    private final NamedParameterJdbcTemplate jdbc;
    private final TaskExecutor executor;
    private final TransactionTemplate tx;

    @Transactional
    public ImportJobResponse upload(MultipartFile file, UUID categoryId, DifficultyLevel defaultDifficulty, QuestionActor actor) {
        policy.requireAuthor(actor);
        if (file == null || file.isEmpty()) throw bad("Tệp rỗng");
        if (file.getSize() > MAX_BYTES) throw bad("Tệp vượt giới hạn 20MB");
        byte[] bytes;
        try { bytes = file.getBytes(); } catch (IOException e) { throw bad("Không đọc được tệp"); }
        String name = fileName(file.getOriginalFilename());
        var kind = DocumentExtractor.detect(bytes, name);
        if (kind == null) throw bad("Chỉ hỗ trợ DOCX, PDF, PNG, JPG");
        if (categoryId != null && !categories.existsById(categoryId)) throw bad("Danh mục không tồn tại");
        var now = LocalDateTime.now();
        var job = jobs.saveAndFlush(QuestionImportJob.builder().ownerUserId(actor.userId()).fileName(name).fileKind(kind)
                .fileSize(bytes.length).status(Status.PROCESSING).warnings("[]").categoryId(categoryId)
                .defaultDifficulty(defaultDifficulty == null ? DifficultyLevel.MEDIUM : defaultDifficulty)
                .createdAt(now).updatedAt(now).build());
        event(job.getId(), null, actor.userId(), "UPLOADED", Map.of("fileName", name, "fileKind", kind, "fileSize", bytes.length));
        UUID id = job.getId(), owner = actor.userId();
        // Extraction (OCR can take minutes) runs after commit so the job row is visible to pollers.
        if (TransactionSynchronizationManager.isSynchronizationActive())
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { executor.execute(() -> process(id, bytes, owner)); }
            });
        return response(job);
    }

    /** Extract outside any transaction, then persist the parsed items atomically. */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.SUPPORTS)
    public void process(UUID jobId, byte[] bytes, UUID actor) {
        var job = jobs.findById(jobId).orElseThrow();
        DocumentExtractor.Extraction extraction;
        try { extraction = extractor.extract(bytes, job.getFileKind()); }
        catch (DocumentExtractor.ExtractionException e) { finish(jobId, actor, e.getMessage(), null); return; }
        catch (RuntimeException e) { finish(jobId, actor, "Không xử lý được tài liệu", null); return; }
        finish(jobId, actor, null, extraction);
    }

    private void finish(UUID jobId, UUID actor, String failure, DocumentExtractor.Extraction extraction) {
        tx.executeWithoutResult(status -> {
            var job = jobs.findByIdForUpdate(jobId).orElseThrow();
            if (job.getStatus() != Status.PROCESSING) return;
            String reason = failure;
            List<ImportParser.Parsed> parsed = List.of();
            if (extraction != null) {
                job.setPageCount(extraction.pageCount()); job.setOcrPageCount(extraction.ocrPageCount());
                job.setWarnings(write(extraction.warnings()));
                parsed = ImportParser.parse(extraction.lines(), job.getDefaultDifficulty());
                if (extraction.lines().isEmpty()) reason = "Không tìm thấy chữ trong tài liệu";
                else if (parsed.isEmpty()) reason = "Không nhận diện được câu hỏi theo mẫu \"Câu N: … / A. … / Đáp án: …\"";
            }
            job.setUpdatedAt(LocalDateTime.now());
            if (reason != null) {
                job.setStatus(Status.FAILED); job.setFailureReason(reason);
                event(jobId, null, actor, "FAILED", Map.of("reason", reason));
                return;
            }
            int position = 0;
            for (var p : parsed) {
                var item = QuestionImportItem.builder().jobId(jobId).position(position++).content(p.content())
                        .questionType(p.type()).difficultyLevel(p.difficulty()).categoryId(job.getCategoryId())
                        .options(write(p.options().stream().map(o -> new ImportOption(o.content(), o.correct(), o.displayOrder())).toList()))
                        .expectedAnswer(p.expectedAnswer()).explanation(p.explanation()).sourcePage(p.page()).sourceMethod(p.method())
                        .confidence(BigDecimal.valueOf(p.confidence()).setScale(2, java.math.RoundingMode.HALF_UP)).rawText(p.rawText())
                        .flags(write(p.flags().stream().map(Enum::name).toList())).reviewRequired(p.reviewRequired()).build();
                validate(item);
                items.save(item);
            }
            job.setStatus(Status.READY);
            event(jobId, null, actor, "EXTRACTED", Map.of("items", parsed.size(), "pages", extraction.pageCount(), "ocrPages", extraction.ocrPageCount()));
        });
    }

    @EventListener(ApplicationReadyEvent.class) @Transactional
    public void failInterruptedJobs() {
        jobs.failStale(LocalDateTime.now().minusMinutes(1), LocalDateTime.now(), "Xử lý bị gián đoạn; vui lòng tải lại tệp");
    }

    public Page<ImportJobResponse> list(Pageable page, QuestionActor actor) {
        policy.requireAuthor(actor); bounds(page);
        return jobs.findByOwnerUserId(actor.userId(), PageRequest.of(page.getPageNumber(), page.getPageSize(),
                Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by("id")))).map(this::response);
    }
    public ImportJobResponse get(UUID id, QuestionActor actor) { return response(owned(id, actor)); }

    public Page<ImportItemResponse> items(UUID id, ItemFilter filter, Pageable page, QuestionActor actor) {
        var job = owned(id, actor); bounds(page);
        Specification<QuestionImportItem> spec = (r, q, b) -> b.equal(r.get("jobId"), job.getId());
        Specification<QuestionImportItem> open = (r, q, b) -> b.and(b.isNull(r.get("importedQuestionId")), b.isFalse(r.get("excluded")));
        spec = switch (filter == null ? ItemFilter.ALL : filter) {
            case ALL -> spec;
            case NEEDS_REVIEW -> spec.and(open).and((r, q, b) -> b.and(b.isTrue(r.get("reviewRequired")), b.isFalse(r.get("reviewed"))));
            case INVALID -> spec.and(open).and((r, q, b) -> b.isFalse(r.get("valid")));
            case IMPORTABLE -> spec.and(open).and((r, q, b) -> b.and(b.isTrue(r.get("valid")),
                    b.or(b.isFalse(r.get("reviewRequired")), b.isTrue(r.get("reviewed")))));
            case IMPORTED -> spec.and((r, q, b) -> b.isNotNull(r.get("importedQuestionId")));
            case EXCLUDED -> spec.and((r, q, b) -> b.and(b.isTrue(r.get("excluded")), b.isNull(r.get("importedQuestionId"))));
        };
        return items.findAll(spec, PageRequest.of(page.getPageNumber(), page.getPageSize(), Sort.by("position")))
                .map(i -> item(i, job));
    }

    @Transactional
    public ImportItemResponse update(UUID id, UUID itemId, ItemUpdate request, QuestionActor actor) {
        var job = editable(id, actor);
        var item = items.findByIdAndJobId(itemId, job.getId()).orElseThrow(this::missing);
        if (item.getImportedQuestionId() != null) throw conflict("Mục đã được import; sửa câu hỏi trong ngân hàng");
        if (!Objects.equals(request.version(), item.getVersion())) throw conflict("Mục đã thay đổi; tải lại trước khi sửa");
        var changed = new ArrayList<String>();
        if (request.content() != null) { item.setContent(request.content().strip()); changed.add("content"); }
        if (request.questionType() != null) { item.setQuestionType(request.questionType()); changed.add("questionType"); }
        if (request.difficultyLevel() != null) { item.setDifficultyLevel(request.difficultyLevel()); changed.add("difficultyLevel"); }
        if (request.categoryId() != null) { item.setCategoryId(request.categoryId()); changed.add("categoryId"); }
        if (request.options() != null) { item.setOptions(write(request.options().stream().map(o -> new ImportOption(o.content().strip(), o.correct(), o.displayOrder())).toList())); changed.add("options"); }
        if (request.expectedAnswer() != null) { item.setExpectedAnswer(blankNull(request.expectedAnswer())); changed.add("expectedAnswer"); }
        if (request.explanation() != null) { item.setExplanation(blankNull(request.explanation())); changed.add("explanation"); }
        if (request.reviewed() != null) { item.setReviewed(request.reviewed()); changed.add("reviewed"); }
        if (request.excluded() != null) { item.setExcluded(request.excluded()); changed.add("excluded"); }
        validate(item);
        item = items.saveAndFlush(item);
        event(job.getId(), item.getId(), actor.userId(), "ITEM_UPDATED", Map.of("fields", changed, "version", item.getVersion()));
        return item(item, job);
    }

    @Transactional
    public ImportSummary validateAll(UUID id, QuestionActor actor) {
        var job = editable(id, actor);
        var all = items.findByJobIdOrderByPosition(job.getId());
        for (var i : all) if (i.getImportedQuestionId() == null) validate(i);
        var summary = summary(all);
        event(job.getId(), null, actor.userId(), "VALIDATED", summary);
        return summary;
    }

    @Transactional
    public ImportJobResult importItems(UUID id, ImportRequest request, QuestionActor actor) {
        policy.requireAuthor(actor);
        var job = jobs.findByIdForUpdate(id).filter(j -> j.getOwnerUserId().equals(actor.userId())).orElseThrow(this::missing);
        if (job.getStatus() != Status.READY) throw conflict("Job chưa sẵn sàng hoặc đã huỷ");
        UUID bankId = request == null || request.bankId() == null ? banks.defaultPersonal(actor.userId()) : request.bankId();
        var bank = banks.get(bankId); banks.requireWrite(bank, actor);
        if (bank.getType() != QuestionBank.BankType.PERSONAL) throw bad("Import chỉ vào Personal Bank");
        var all = items.findByJobIdOrderByPosition(job.getId());
        var byId = new LinkedHashMap<UUID, QuestionImportItem>(); all.forEach(i -> byId.put(i.getId(), i));
        var wanted = request == null || request.itemIds() == null ? new ArrayList<>(byId.keySet()) : new ArrayList<>(new LinkedHashSet<>(request.itemIds()));
        var imported = new ArrayList<ImportedItem>(); var skipped = new ArrayList<SkippedItem>();
        boolean activate = request != null && Boolean.TRUE.equals(request.activate());
        var now = LocalDateTime.now();
        for (UUID itemId : wanted) {
            var item = byId.get(itemId);
            if (item == null) { skipped.add(new SkippedItem(itemId, "NOT_IN_JOB")); continue; }
            if (item.getImportedQuestionId() != null) { skipped.add(new SkippedItem(itemId, "ALREADY_IMPORTED")); continue; }
            validate(item);
            String reason = item.isExcluded() ? "EXCLUDED" : !item.isValid() ? "INVALID" : item.isReviewRequired() && !item.isReviewed() ? "NEEDS_REVIEW" : null;
            if (reason != null) { skipped.add(new SkippedItem(itemId, reason)); continue; }
            String label = job.getFileName() + (item.getSourcePage() == null ? "" : " · trang " + item.getSourcePage());
            var created = questions.importDraft(new CreateRequest(item.getCategoryId(), item.getContent(), item.getQuestionType(),
                    item.getDifficultyLevel(), truncate(label, 500), options(item).stream().map(o -> new OptionRequest(o.content(), o.correct(), o.displayOrder())).toList(),
                    item.getExpectedAnswer(), QuestionScope.PERSONAL, SourceType.USER_DOCUMENT, "import:" + job.getId() + ":" + item.getId(),
                    truncate(label, 500), null, item.getRawText(), item.getExplanation()), bankId, actor);
            if (activate) created = questions.activate(created.id(), created.questionVersion(), actor);
            item.setImportedQuestionId(created.id()); item.setImportedAt(now);
            imported.add(new ImportedItem(item.getId(), created.id()));
        }
        items.flush();
        job.setUpdatedAt(now);
        event(job.getId(), null, actor.userId(), "IMPORTED", Map.of("bankId", bankId, "imported", imported, "skipped", skipped, "activated", activate));
        return new ImportJobResult(imported, skipped, summary(all));
    }

    @Transactional
    public void discard(UUID id, QuestionActor actor) {
        policy.requireAuthor(actor);
        var job = jobs.findByIdForUpdate(id).filter(j -> j.getOwnerUserId().equals(actor.userId())).orElseThrow(this::missing);
        if (job.getStatus() == Status.DISCARDED) return;
        job.setStatus(Status.DISCARDED); job.setUpdatedAt(LocalDateTime.now());
        event(job.getId(), null, actor.userId(), "DISCARDED", Map.of());
    }

    /** Same rules as manual create (QuestionValidation) plus a required, existing category. */
    void validate(QuestionImportItem item) {
        var errors = new ArrayList<ItemError>();
        Category category = null;
        if (item.getCategoryId() == null) errors.add(new ItemError("CATEGORY_REQUIRED", "Chọn danh mục"));
        else if ((category = categories.findById(item.getCategoryId()).orElse(null)) == null) errors.add(new ItemError("CATEGORY_NOT_FOUND", "Danh mục không tồn tại"));
        var options = options(item);
        boolean choice = item.getQuestionType() == QuestionType.MULTIPLE_CHOICE || item.getQuestionType() == QuestionType.TRUE_FALSE;
        if (choice && options.stream().noneMatch(ImportOption::correct))
            errors.add(new ItemError("MISSING_CORRECT_ANSWER", "Chưa có đáp án đúng; hệ thống không tự đoán"));
        else {
            var q = Question.builder().category(category == null ? new Category() : category).content(item.getContent())
                    .questionType(item.getQuestionType()).difficultyLevel(item.getDifficultyLevel())
                    .expectedAnswer(item.getExpectedAnswer()).sourceExcerpt(item.getRawText()).build();
            for (var o : options) q.getOptions().add(QuestionOption.builder().question(q).content(o.content()).correct(o.correct()).displayOrder(o.displayOrder()).build());
            try { QuestionValidation.validate(q); }
            catch (IllegalArgumentException e) { errors.add(new ItemError("INVALID", e.getMessage())); }
        }
        item.setValid(errors.isEmpty());
        item.setErrors(write(errors));
    }

    private ImportItemResponse item(QuestionImportItem i, QuestionImportJob job) {
        return new ImportItemResponse(i.getId(), i.getPosition(), i.getVersion(), i.getContent(), i.getQuestionType(), i.getDifficultyLevel(),
                i.getCategoryId(), options(i), i.getExpectedAnswer(), i.getExplanation(),
                new SourceRef(job.getFileName(), i.getSourcePage(), i.getSourceMethod()), i.getConfidence(), i.getRawText(),
                read(i.getFlags(), new TypeReference<List<String>>() {}), i.isReviewRequired(), i.isReviewed(), i.isExcluded(), i.isValid(),
                read(i.getErrors(), new TypeReference<List<ItemError>>() {}), i.importable(), i.getImportedQuestionId(), i.getImportedAt());
    }
    private ImportJobResponse response(QuestionImportJob job) {
        return new ImportJobResponse(job.getId(), job.getFileName(), job.getFileKind(), job.getFileSize(), job.getPageCount(),
                job.getOcrPageCount(), job.getStatus(), job.getFailureReason(), read(job.getWarnings(), new TypeReference<List<String>>() {}),
                job.getCategoryId(), job.getDefaultDifficulty(), job.getCreatedAt(), job.getUpdatedAt(),
                summary(items.findByJobIdOrderByPosition(job.getId())));
    }
    private static ImportSummary summary(List<QuestionImportItem> all) {
        long valid = 0, invalid = 0, review = 0, importable = 0, imported = 0, excluded = 0;
        for (var i : all) {
            if (i.getImportedQuestionId() != null) { imported++; continue; }
            if (i.isExcluded()) { excluded++; continue; }
            if (i.isValid()) valid++; else invalid++;
            if (i.isReviewRequired() && !i.isReviewed()) review++;
            if (i.importable()) importable++;
        }
        return new ImportSummary(all.size(), valid, invalid, review, importable, imported, excluded);
    }
    private List<ImportOption> options(QuestionImportItem i) { return read(i.getOptions(), new TypeReference<List<ImportOption>>() {}); }
    private QuestionImportJob owned(UUID id, QuestionActor actor) {
        policy.requireAuthor(actor);
        return jobs.findById(id).filter(j -> j.getOwnerUserId().equals(actor.userId())).orElseThrow(this::missing);
    }
    private QuestionImportJob editable(UUID id, QuestionActor actor) {
        var job = owned(id, actor);
        if (job.getStatus() != Status.READY) throw conflict("Job chưa sẵn sàng hoặc đã huỷ");
        return job;
    }
    private void event(UUID job, UUID item, UUID actor, String action, Object detail) {
        jdbc.update("INSERT INTO question_import_events(job_id,item_id,actor_user_id,action,detail) VALUES(:job,:item,:actor,:action,:detail)",
                new MapSqlParameterSource().addValue("job", job).addValue("item", item).addValue("actor", actor)
                        .addValue("action", action).addValue("detail", write(detail)));
    }
    static String fileName(String original) {
        String name = original == null ? "" : original.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).replaceAll("\\p{Cntrl}", "").strip();
        if (name.isEmpty()) name = "tai-lieu";
        return name.length() > 255 ? name.substring(name.length() - 255) : name;
    }
    private static String truncate(String s, int max) { return s.length() > max ? s.substring(0, max) : s; }
    private static String blankNull(String s) { return s == null || s.isBlank() ? null : s.strip(); }
    private void bounds(Pageable page) { if (page.getPageSize() > 100) throw bad("size tối đa 100"); }
    private String write(Object value) { try { return json.writeValueAsString(value); } catch (Exception e) { throw new IllegalStateException(e); } }
    private <T> T read(String value, TypeReference<T> type) { try { return json.readValue(value, type); } catch (Exception e) { throw new IllegalStateException(e); } }
    private ResponseStatusException missing() { return new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy job import"); }
    private static ResponseStatusException bad(String m) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, m); }
    private static ResponseStatusException conflict(String m) { return new ResponseStatusException(HttpStatus.CONFLICT, m); }
}
