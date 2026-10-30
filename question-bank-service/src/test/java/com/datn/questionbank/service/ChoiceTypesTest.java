package com.datn.questionbank.service;

import com.datn.questionbank.entity.Category;
import com.datn.questionbank.entity.Question;
import com.datn.questionbank.entity.QuestionOption;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ChoiceTypesTest {
    @Test void trueFalseKeysAcceptTheUsualWritings() {
        assertEquals(List.of(true, false, true, true), SheetImport.truths("Đ, S, Đ, Đ"));
        assertEquals(List.of(true, false, true, true), SheetImport.truths("a) Đ b) S c) Đ d) Đ"));
        assertEquals(List.of(true, false, false, true), SheetImport.truths("ĐSSĐ"));
        assertEquals(List.of(true, false), SheetImport.truths("1 0"));
        assertEquals(List.of(false, true), SheetImport.truths("a. sai; b. đúng"));
        assertNull(SheetImport.truths("A, C"));   // letters of a multiple-response key
        assertNull(SheetImport.truths("B"));
        assertNull(SheetImport.truths(""));
    }

    @Test void typeWordsFromTeachers() {
        assertEquals(Question.QuestionType.TRUE_FALSE_GROUP, SheetImport.type("Đúng/Sai nhiều ý", true));
        assertEquals(Question.QuestionType.TRUE_FALSE_GROUP, SheetImport.type("đúng sai 4 ý", true));
        assertEquals(Question.QuestionType.TRUE_FALSE, SheetImport.type("Đúng/Sai", true));
        assertEquals(Question.QuestionType.MULTIPLE_RESPONSE, SheetImport.type("Nhiều đáp án", true));
        assertEquals(Question.QuestionType.MULTIPLE_CHOICE, SheetImport.type("Trắc nghiệm", true));
    }

    private static Question q(Question.QuestionType t, boolean... correct) {
        var q = Question.builder().category(new Category()).content("Nội dung").questionType(t).difficultyLevel(Question.DifficultyLevel.EASY).build();
        for (int i = 0; i < correct.length; i++)
            q.getOptions().add(QuestionOption.builder().question(q).content("Ý " + i).correct(correct[i]).displayOrder((short) i).build());
        return q;
    }

    @Test void validationPerChoiceType() {
        QuestionValidation.validate(q(Question.QuestionType.MULTIPLE_RESPONSE, true, false, true, false));
        assertThrows(IllegalArgumentException.class, () -> QuestionValidation.validate(q(Question.QuestionType.MULTIPLE_RESPONSE, false, false)));
        QuestionValidation.validate(q(Question.QuestionType.TRUE_FALSE_GROUP, false, false, false, false)); // every statement false is allowed
        assertThrows(IllegalArgumentException.class, () -> QuestionValidation.validate(q(Question.QuestionType.TRUE_FALSE_GROUP, new boolean[9])));
        assertThrows(IllegalArgumentException.class, () -> QuestionValidation.validate(q(Question.QuestionType.MULTIPLE_CHOICE, true, true)));
    }
}
