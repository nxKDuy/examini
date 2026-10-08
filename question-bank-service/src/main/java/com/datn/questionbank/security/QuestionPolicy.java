package com.datn.questionbank.security;

import com.datn.questionbank.entity.Question;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class QuestionPolicy {
    public void requireAuthor(QuestionActor actor) {
        if (!actor.author()) deny();
    }
    public boolean accessible(Question q, QuestionActor actor) {
        if (q.getScope() == Question.QuestionScope.SYSTEM)
            return actor.systemAdmin() || q.getStatus() == Question.QuestionStatus.ACTIVE;
        return q.getScope() == Question.QuestionScope.PERSONAL
                && q.getCreatedBy().equals(actor.userId());
    }
    public void requireRead(Question q, QuestionActor actor) {
        if (!accessible(q, actor)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy câu hỏi");
    }
    public void requireOwner(Question q, QuestionActor actor) {
        if (q.getScope() == Question.QuestionScope.SYSTEM && actor.systemAdmin()) return;
        requireAuthor(actor);
        // ADMIN reviews, but cannot silently overwrite another owner's personal content.
        if (q.getScope() != Question.QuestionScope.PERSONAL || !q.getCreatedBy().equals(actor.userId())) deny();
    }
    public void requireReviewer(QuestionActor actor) {
        if (!actor.admin()) deny();
    }
    public boolean canReadAnswers(Question q, QuestionActor actor) {
        if (q.getScope() == Question.QuestionScope.SYSTEM) return actor.systemAdmin() || actor.internal();
        return accessible(q, actor) && (actor.author() || actor.internal());
    }
    private void deny() { throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Không có quyền truy cập câu hỏi"); }
}
