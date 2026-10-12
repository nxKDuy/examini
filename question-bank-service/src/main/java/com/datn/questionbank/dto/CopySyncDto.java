package com.datn.questionbank.dto;

import com.datn.questionbank.entity.Question;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class CopySyncDto {
    private CopySyncDto() {}

    public enum Availability { AVAILABLE, UNAVAILABLE }
    public enum Choice { KEEP_LOCAL, TAKE_SOURCE }
    public enum QuestionField { CATEGORY, CONTENT, QUESTION_TYPE, DIFFICULTY, OPTIONS, EXPECTED_ANSWER, EXPLANATION, TITLE, DESCRIPTION }

    public record FieldChange(QuestionField field,boolean sourceChanged,boolean localChanged,boolean conflict,
            boolean redacted,JsonNode reference,JsonNode local,JsonNode latest) {}
    public record QuestionPreview(UUID questionId,long localVersion,long referenceSourceVersion,long cursorRevision,Long latestSourceVersion,
            Availability availability,Question.QuestionStatus localStatus,List<FieldChange> changes) {}
    public record QuestionApplyRequest(@NotNull Long expectedLocalVersion,@NotNull Long expectedReferenceSourceVersion,@NotNull Long expectedCursorRevision,@NotNull Long expectedSourceVersion,
            @NotNull Map<QuestionField,Choice> decisions) {}
    /** localVersion stays as originally recorded; a correction row never rewrites it (V13). */
    public record SyncEvent(UUID id,String resourceKind,long localVersion,long sourceVersion,String actions,
            UUID actorUserId,LocalDateTime createdAt,Long correctedLocalVersion,String correctionReason,LocalDateTime correctedAt) {}

    public record SetItemChange(UUID sourceQuestionId,UUID localQuestionId,String kind,Integer referencePosition,
            Integer localPosition,Integer latestPosition,Long referenceVersion,Long latestVersion,
            boolean localChanged,boolean conflict) {}
    public record SetPreview(UUID questionSetId,long localVersion,long referenceSourceVersion,long cursorRevision,Long latestSourceVersion,
            Availability availability,List<FieldChange> metadataChanges,List<SetItemChange> itemChanges,
            boolean sourceReordered,boolean localReordered,boolean reorderConflict) {}
    public record SetApplyRequest(@NotNull Long expectedLocalVersion,@NotNull Long expectedReferenceSourceVersion,@NotNull Long expectedCursorRevision,@NotNull Long expectedSourceVersion,
            @NotNull Map<String,Choice> decisions) {}
}
