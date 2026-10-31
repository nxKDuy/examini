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
    /**
     * What a student may see. FILL_BLANK gaps carry no text (the option holds the answers); MATCHING options show only
     * their left item and presentation.matchTargets lists the right items alphabetically, so order reveals nothing.
     */
    public Safe safe() {
        boolean blanks = "FILL_BLANK".equals(questionType), matching = "MATCHING".equals(questionType);
        var shown = options.stream().map(o -> {
            String text = blanks ? "" : matching ? left(o.content()) : o.content();
            return new SafeOption(o.id(), text, o.displayOrder());
        }).toList();
        Map<String, Object> view = presentation;
        if (matching) {
            view = presentation == null ? new LinkedHashMap<>() : new LinkedHashMap<>(presentation);
            view.put("matchTargets", com.datn.exam.service.StructuredKey.targets(options));
        }
        return new Safe(questionId, questionVersion, questionType, content, shown, points, position, view);
    }
    private static String left(String pair) { var p = com.datn.exam.service.StructuredKey.pair(pair); return p == null ? pair : p[0]; }
    public record SafeOption(UUID id, String content, int displayOrder) {}
    public record Safe(UUID questionId, long questionVersion, String questionType, String content,
                       List<SafeOption> options, BigDecimal points, int position,
                       @JsonInclude(JsonInclude.Include.NON_NULL) Map<String, Object> presentation) {}
}
