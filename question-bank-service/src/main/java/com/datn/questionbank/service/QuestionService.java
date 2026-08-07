package com.datn.questionbank.service;

import com.datn.questionbank.dto.QuestionDto;
import com.datn.questionbank.entity.Category;
import com.datn.questionbank.entity.Question;
import com.datn.questionbank.entity.QuestionOption;
import com.datn.questionbank.repository.QuestionRepository;
import com.opencsv.CSVReader;
import com.opencsv.exceptions.CsvValidationException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class QuestionService {

    private final QuestionRepository repo;
    private final CategoryService categoryService;

    public Page<QuestionDto.QuestionResponse> findAll(UUID categoryId,
                                                      Question.DifficultyLevel difficulty,
                                                      Pageable pageable) {
        if (categoryId != null && difficulty != null) {
            // ponytail: Spring Data Specification mạnh hơn nhưng overkill cho 2 filter — query method đủ dùng
            return repo.findByCategoryId(categoryId, pageable).map(this::toResponse);
        }
        if (categoryId != null) return repo.findByCategoryId(categoryId, pageable).map(this::toResponse);
        if (difficulty != null) return repo.findByDifficultyLevel(difficulty, pageable).map(this::toResponse);
        return repo.findAll(pageable).map(this::toResponse);
    }

    public QuestionDto.QuestionResponse findById(UUID id) {
        return toResponse(getOrThrow(id));
    }

    @Transactional
    public QuestionDto.QuestionResponse create(QuestionDto.CreateRequest req, UUID createdBy) {
        Category category = categoryService.getOrThrow(req.categoryId());
        Question q = buildQuestion(req, category, createdBy);
        return toResponse(repo.save(q));
    }

    @Transactional
    public QuestionDto.QuestionResponse update(UUID id, QuestionDto.UpdateRequest req, UUID updatedBy) {
        Question q = getOrThrow(id);
        if (req.categoryId() != null) q.setCategory(categoryService.getOrThrow(req.categoryId()));
        if (req.content() != null) q.setContent(req.content());
        if (req.questionType() != null) q.setQuestionType(req.questionType());
        if (req.difficultyLevel() != null) q.setDifficultyLevel(req.difficultyLevel());
        if (req.source() != null) q.setSource(req.source());
        if (req.options() != null) {
            q.getOptions().clear();
            addOptions(q, req.options());
        }
        return toResponse(repo.save(q));
    }

    @Transactional
    public void delete(UUID id) {
        if (!repo.existsById(id)) throw notFound(id);
        repo.deleteById(id);
    }

    public List<UUID> getRandomQuestionIds(UUID categoryId, Question.DifficultyLevel difficulty, int limit) {
        return repo.findRandomByCategory(categoryId, difficulty, Pageable.ofSize(limit))
                .stream()
                .map(Question::getId)
                .toList();
    }

    public List<QuestionDto.QuestionResponse> getByIds(List<UUID> ids) {
        return repo.findAllById(ids).stream().map(this::toResponse).toList();
    }

    /**
     * Import câu hỏi từ file CSV.
     * Format header: content,type,difficulty,source,option1,correct1,option2,correct2,option3,correct3,option4,correct4
     * correct = "true" hoặc "false"
     * Bỏ qua dòng lỗi, ghi vào danh sách errors.
     */
    @Transactional
    public QuestionDto.ImportResult importCsv(MultipartFile file, UUID categoryId, UUID createdBy) {
        Category category = categoryService.getOrThrow(categoryId);
        List<String> errors = new ArrayList<>();
        int imported = 0, row = 1;

        try (CSVReader csv = new CSVReader(new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
            csv.readNext(); // bỏ header
            String[] cols;
            while ((cols = csv.readNext()) != null) {
                row++;
                try {
                    Question q = parseRow(cols, category, createdBy, row);
                    repo.save(q);
                    imported++;
                } catch (IllegalArgumentException e) {
                    errors.add("Dòng " + row + ": " + e.getMessage());
                }
            }
        } catch (IOException | CsvValidationException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Không thể đọc file CSV: " + e.getMessage());
        }

        return new QuestionDto.ImportResult(row - 1, imported, errors.size(), errors);
    }

    // ─── private helpers ────────────────────────────────────────────────────

    private Question parseRow(String[] cols, Category category, UUID createdBy, int row) {
        if (cols.length < 4) throw new IllegalArgumentException("Thiếu cột (cần ít nhất 4)");

        String content = cols[0].trim();
        if (content.isBlank()) throw new IllegalArgumentException("Nội dung câu hỏi trống");

        Question.QuestionType type;
        Question.DifficultyLevel difficulty;
        try {
            type = Question.QuestionType.valueOf(cols[1].trim().toUpperCase());
            difficulty = Question.DifficultyLevel.valueOf(cols[2].trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Loại câu hỏi hoặc độ khó không hợp lệ");
        }

        Question q = Question.builder()
                .category(category)
                .content(content)
                .questionType(type)
                .difficultyLevel(difficulty)
                .source(cols.length > 3 ? cols[3].trim() : null)
                .createdBy(createdBy)
                .options(new ArrayList<>())
                .build();

        // Options: cặp (content, correct) từ cột 4 trở đi
        for (int i = 4; i + 1 < cols.length; i += 2) {
            String optContent = cols[i].trim();
            if (optContent.isBlank()) continue;
            boolean correct = "true".equalsIgnoreCase(cols[i + 1].trim());
            QuestionOption opt = QuestionOption.builder()
                    .question(q)
                    .content(optContent)
                    .correct(correct)
                    .displayOrder((short) ((i - 4) / 2))
                    .build();
            q.getOptions().add(opt);
        }
        return q;
    }

    private Question buildQuestion(QuestionDto.CreateRequest req, Category category, UUID createdBy) {
        Question q = Question.builder()
                .category(category)
                .content(req.content())
                .questionType(req.questionType())
                .difficultyLevel(req.difficultyLevel())
                .source(req.source())
                .createdBy(createdBy)
                .options(new ArrayList<>())
                .build();
        addOptions(q, req.options());
        return q;
    }

    private void addOptions(Question q, List<QuestionDto.OptionRequest> opts) {
        for (QuestionDto.OptionRequest o : opts) {
            q.getOptions().add(QuestionOption.builder()
                    .question(q).content(o.content()).correct(o.correct()).displayOrder(o.displayOrder())
                    .build());
        }
    }

    private Question getOrThrow(UUID id) {
        return repo.findById(id).orElseThrow(() -> notFound(id));
    }

    private ResponseStatusException notFound(UUID id) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy câu hỏi: " + id);
    }

    private QuestionDto.QuestionResponse toResponse(Question q) {
        List<QuestionDto.OptionResponse> opts = q.getOptions().stream()
                .map(o -> new QuestionDto.OptionResponse(o.getId(), o.getContent(), o.isCorrect(), o.getDisplayOrder()))
                .toList();
        return new QuestionDto.QuestionResponse(
                q.getId(),
                q.getCategory().getId(),
                q.getCategory().getName(),
                q.getContent(),
                q.getQuestionType(),
                q.getDifficultyLevel(),
                q.getSource(),
                q.getCreatedAt(),
                opts
        );
    }
}
