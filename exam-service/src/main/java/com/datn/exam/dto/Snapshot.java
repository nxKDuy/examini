package com.datn.exam.dto;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.*;
import java.math.BigDecimal;
/**
 * A question frozen at publication. {@code presentation} carries media ids, the reading passage and per-type limits
 * exactly as Question Bank defined them (contracts/question-bank-api.md); it is passed on, never interpreted here.
 */
public record Snapshot(UUID questionId, long questionVersion, String questionType, String content,
                       List<Option> options, BigDecimal points, String expectedAnswer, String explanation, int position,
                       @JsonInclude(JsonInclude.Include.NON_NULL) Map<String, Object> presentation) {
    public Snapshot(UUID questionId,long questionVersion,String questionType,String content,List<Option> options,BigDecimal points,String expectedAnswer,int position) {
        this(questionId,questionVersion,questionType,content,options,points,expectedAnswer,null,position,null);
    }
    public Snapshot(UUID questionId,long questionVersion,String questionType,String content,List<Option> options,BigDecimal points,String expectedAnswer,String explanation,int position) {
        this(questionId,questionVersion,questionType,content,options,points,expectedAnswer,explanation,position,null);
    }
    public record Option(UUID id, String content, int displayOrder, boolean correct) {}
    public Safe safe() { return new Safe(questionId, questionVersion, questionType, content,
        options.stream().map(o -> new SafeOption(o.id(), o.content(), o.displayOrder())).toList(), points, position, presentation); }
    public record SafeOption(UUID id, String content, int displayOrder) {}
    public record Safe(UUID questionId, long questionVersion, String questionType, String content,
                       List<SafeOption> options, BigDecimal points, int position,
                       @JsonInclude(JsonInclude.Include.NON_NULL) Map<String, Object> presentation) {}
}
