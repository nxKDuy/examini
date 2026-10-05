package com.datn.grading.service;
import com.datn.grading.client.ExamClient;
import java.text.Normalizer;
public final class ObjectiveScoring {
    private ObjectiveScoring(){}
    public static String normalize(String text){return Normalizer.normalize(text == null ? "" : text,Normalizer.Form.NFC).replaceAll("(?U)\\s+"," ").trim();}
    public static float score(ExamClient.Question q,String answer){
        if(answer==null || answer.isBlank())return 0;
        boolean correct=switch(q.questionType()){
            case "MULTIPLE_CHOICE","TRUE_FALSE" -> q.options().stream().anyMatch(o->o.correct() && o.id().toString().equals(answer));
            case "SHORT_ANSWER" -> normalize(q.expectedAnswer()).equals(normalize(answer));
            default -> throw new IllegalArgumentException("Not an objective question");
        };
        return correct?q.points().floatValue():0f;
    }
}
