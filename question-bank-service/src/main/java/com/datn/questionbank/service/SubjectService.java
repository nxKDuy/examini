package com.datn.questionbank.service;

import com.datn.questionbank.dto.SubjectDto;
import com.datn.questionbank.entity.Subject;
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
public class SubjectService {

    private final SubjectRepository repo;

    public List<SubjectDto.Response> findAll() {
        return repo.findAll().stream().map(this::toResponse).toList();
    }

    public SubjectDto.Response findById(UUID id) {
        return toResponse(getOrThrow(id));
    }

    @Transactional
    public SubjectDto.Response create(SubjectDto.CreateRequest req) {
        if (repo.existsByNameIgnoreCase(req.name())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Môn học '" + req.name() + "' đã tồn tại");
        }
        Subject s = Subject.builder().name(req.name()).description(req.description()).build();
        return toResponse(repo.save(s));
    }

    @Transactional
    public SubjectDto.Response update(UUID id, SubjectDto.CreateRequest req) {
        Subject s = getOrThrow(id);
        s.setName(req.name());
        s.setDescription(req.description());
        return toResponse(repo.save(s));
    }

    @Transactional
    public void delete(UUID id) {
        if (!repo.existsById(id)) throw notFound(id);
        repo.deleteById(id);
    }

    private Subject getOrThrow(UUID id) {
        return repo.findById(id).orElseThrow(() -> notFound(id));
    }

    private ResponseStatusException notFound(UUID id) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy môn học: " + id);
    }

    private SubjectDto.Response toResponse(Subject s) {
        return new SubjectDto.Response(s.getId(), s.getName(), s.getDescription());
    }
}
