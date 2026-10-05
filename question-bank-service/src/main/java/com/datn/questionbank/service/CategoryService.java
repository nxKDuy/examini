package com.datn.questionbank.service;

import com.datn.questionbank.dto.CategoryDto;
import com.datn.questionbank.entity.Category;
import com.datn.questionbank.entity.Subject;
import com.datn.questionbank.repository.CategoryRepository;
import com.datn.questionbank.repository.SubjectRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CategoryService {

    private final CategoryRepository repo;
    private final SubjectRepository subjectRepo;

    public List<CategoryDto.Response> findBySubject(UUID subjectId) {
        return repo.findBySubjectId(subjectId).stream().map(this::toResponse).toList();
    }

    public CategoryDto.Response findById(UUID id) {
        return toResponse(getOrThrow(id));
    }

    @Transactional
    public CategoryDto.Response create(CategoryDto.CreateRequest req) {
        Subject subject = subjectRepo.findById(req.subjectId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Môn học không tồn tại"));

        Category parent = null;
        if (req.parentCategoryId() != null) {
            parent = getOrThrow(req.parentCategoryId());
        }

        Category c = Category.builder()
                .subject(subject)
                .name(req.name())
                .parent(parent)
                .build();
        return toResponse(repo.save(c));
    }

    @Transactional
    public CategoryDto.Response update(UUID id, CategoryDto.CreateRequest req) {
        Category c = getOrThrow(id);
        c.setName(req.name());
        if (req.parentCategoryId() != null) {
            c.setParent(getOrThrow(req.parentCategoryId()));
        }
        return toResponse(repo.save(c));
    }

    @Transactional
    public void delete(UUID id) {
        if (!repo.existsById(id)) throw notFound(id);
        repo.deleteById(id);
    }

    Category getOrThrow(UUID id) {
        return repo.findById(id).orElseThrow(() -> notFound(id));
    }

    private ResponseStatusException notFound(UUID id) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy danh mục: " + id);
    }

    CategoryDto.Response toResponse(Category c) {
        return new CategoryDto.Response(
                c.getId(),
                c.getSubject().getId(),
                c.getSubject().getName(),
                c.getName(),
                c.getParent() != null ? c.getParent().getId() : null
        );
    }
}
