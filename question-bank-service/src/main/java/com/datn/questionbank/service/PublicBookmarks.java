package com.datn.questionbank.service;

import com.datn.questionbank.entity.PublicQuestion;
import com.datn.questionbank.repository.PublicQuestionRepository;
import com.datn.questionbank.security.QuestionActor;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service @RequiredArgsConstructor
public class PublicBookmarks {
    private final PublicQuestionRepository publications;
    private final NamedParameterJdbcTemplate jdbc;

    @Transactional
    public void save(UUID id, QuestionActor actor) {
        requireUser(actor);
        var post=publications.findByIdForUpdate(id).orElseThrow(this::missing);
        if(post.getStatus()!=PublicQuestion.PublicationStatus.PUBLISHED)throw missing();
        jdbc.update("INSERT INTO public_question_bookmarks(actor_user_id,publication_id) VALUES(:actor,:id) ON CONFLICT DO NOTHING",
                Map.of("actor",actor.userId(),"id",id));
    }

    @Transactional
    public void remove(UUID id, QuestionActor actor) {
        requireUser(actor);
        jdbc.update("DELETE FROM public_question_bookmarks WHERE actor_user_id=:actor AND publication_id=:id",
                Map.of("actor",actor.userId(),"id",id));
    }

    public boolean isSaved(UUID id, QuestionActor actor) {
        if(actor.userId()==null||actor.internal())return false;
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM public_question_bookmarks WHERE actor_user_id=:actor AND publication_id=:id)",
                Map.of("actor",actor.userId(),"id",id),Boolean.class));
    }

    public void requireUser(QuestionActor actor) {
        if(actor.internal()||actor.userId()==null||!Set.of("TEACHER","STUDENT").contains(actor.role()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Lưu bài cần tài khoản Teacher hoặc Student");
    }

    private ResponseStatusException missing() {return new ResponseStatusException(HttpStatus.NOT_FOUND,"Không tìm thấy Public post");}
}
