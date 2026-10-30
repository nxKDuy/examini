package com.datn.grading.service;
import com.datn.grading.client.ExamClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.text.Normalizer;
import java.util.*;
public final class ObjectiveScoring {
    private ObjectiveScoring(){}
    private static final ObjectMapper JSON=new ObjectMapper();
    /** THPT 2025 scale for a 4-statement true/false question: share of the points by number of statements judged right. */
    private static final float[] GROUP_OF_FOUR={0f,0.1f,0.25f,0.5f,1f};
    public static String normalize(String text){return Normalizer.normalize(text == null ? "" : text,Normalizer.Form.NFC).replaceAll("(?U)\\s+"," ").trim();}
    public static boolean isObjective(String type){return Set.of("MULTIPLE_CHOICE","TRUE_FALSE","SHORT_ANSWER","MULTIPLE_RESPONSE","TRUE_FALSE_GROUP").contains(type);}
    public static float score(ExamClient.Question q,String answer){
        if(answer==null || answer.isBlank())return 0;
        float points=q.points().floatValue();
        return switch(q.questionType()){
            case "MULTIPLE_CHOICE","TRUE_FALSE" -> q.options().stream().anyMatch(o->o.correct() && o.id().toString().equals(answer))?points:0f;
            case "SHORT_ANSWER" -> normalize(q.expectedAnswer()).equals(normalize(answer))?points:0f;
            // All or nothing: exactly the correct options, no more, no fewer.
            case "MULTIPLE_RESPONSE" -> {
                var chosen=chosen(answer);
                var correct=new HashSet<String>();q.options().stream().filter(ExamClient.Option::correct).forEach(o->correct.add(o.id().toString()));
                yield chosen!=null&&chosen.equals(correct)?points:0f;
            }
            case "TRUE_FALSE_GROUP" -> {
                int n=q.options().size(),right=statementsRight(q,answer);
                float share=n==4?GROUP_OF_FOUR[right]:(float)right/n;
                yield Math.round(points*share*1000f)/1000f;
            }
            default -> throw new IllegalArgumentException("Not an objective question");
        };
    }
    /** Statements whose true/false judgement matches the key; unanswered statements count as wrong. */
    public static int statementsRight(ExamClient.Question q,String answer){
        var judged=judgements(answer);if(judged==null)return 0;
        int right=0;for(var o:q.options()){Boolean v=judged.get(o.id().toString());if(v!=null&&v==o.correct())right++;}
        return right;
    }
    static Set<String> chosen(String answer){
        try{JsonNode n=JSON.readTree(answer);if(n==null||!n.isArray())return null;var out=new HashSet<String>();for(var v:n)out.add(v.asText());return out;}catch(Exception e){return null;}
    }
    static Map<String,Boolean> judgements(String answer){
        try{JsonNode n=JSON.readTree(answer);if(n==null||!n.isObject())return null;var out=new HashMap<String,Boolean>();n.fields().forEachRemaining(f->{if(f.getValue().isBoolean())out.put(f.getKey(),f.getValue().asBoolean());});return out;}catch(Exception e){return null;}
    }
    private static String label(int i){return (char)('a'+i)+")";}
    /** Readable answer for teachers and results: chosen option texts, or "a) Đúng; b) Sai …". */
    public static String display(ExamClient.Question q,String answer){
        if(answer==null||answer.isBlank())return null;
        var options=q.options().stream().sorted(Comparator.comparingInt(ExamClient.Option::displayOrder)).toList();
        switch(q.questionType()){
            case "MULTIPLE_RESPONSE" -> {var c=chosen(answer);if(c==null)return null;var parts=options.stream().filter(o->c.contains(o.id().toString())).map(ExamClient.Option::content).toList();return parts.isEmpty()?null:String.join("; ",parts);}
            case "TRUE_FALSE_GROUP" -> {var j=judgements(answer);if(j==null)return null;var parts=new ArrayList<String>();
                for(int i=0;i<options.size();i++){Boolean v=j.get(options.get(i).id().toString());parts.add(label(i)+" "+(v==null?"—":v?"Đúng":"Sai"));}
                return String.join("; ",parts);}
            case "MULTIPLE_CHOICE","TRUE_FALSE" -> {return q.options().stream().filter(o->o.id().toString().equals(answer)).map(ExamClient.Option::content).findFirst().orElse(null);}
            default -> {return answer;}
        }
    }
    /** The key as students may see it once revealed. */
    public static List<String> key(ExamClient.Question q){
        if(q==null)return List.of();
        if("SHORT_ANSWER".equals(q.questionType()))return q.expectedAnswer()==null?List.of():List.of(q.expectedAnswer());
        if("TRUE_FALSE_GROUP".equals(q.questionType())){
            var options=q.options().stream().sorted(Comparator.comparingInt(ExamClient.Option::displayOrder)).toList();var out=new ArrayList<String>();
            for(int i=0;i<options.size();i++)out.add(label(i)+" "+options.get(i).content()+" — "+(options.get(i).correct()?"Đúng":"Sai"));
            return out;
        }
        return q.options().stream().filter(ExamClient.Option::correct).map(ExamClient.Option::content).toList();
    }
}
