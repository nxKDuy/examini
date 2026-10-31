package com.datn.examsession.service;

import com.datn.examsession.client.ExamClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.text.Normalizer;
import java.util.*;

/**
 * Verdict of one practice answer, with the same rules grading-service applies at submission (ObjectiveScoring there):
 * single choice and short answer all or nothing, MULTIPLE_RESPONSE exact set, TRUE_FALSE_GROUP THPT scale,
 * FILL_BLANK / MATCHING share per gap / pair.
 */
final class PracticeScoring {
    private PracticeScoring(){}
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final double[] GROUP_OF_FOUR={0,0.1,0.25,0.5,1};
    static boolean checkable(String type){
        return Set.of("MULTIPLE_CHOICE","TRUE_FALSE","SHORT_ANSWER","MULTIPLE_RESPONSE","TRUE_FALSE_GROUP","FILL_BLANK","MATCHING").contains(type);
    }
    static String normalize(String s){return Normalizer.normalize(s==null?"":s,Normalizer.Form.NFC).replaceAll("(?U)\\s+"," ").trim();}
    private static String fold(String s){return normalize(s).toLowerCase(Locale.ROOT);}
    private static String[] pair(String o){int i=o==null?-1:o.indexOf("=>");return i<0?null:new String[]{o.substring(0,i).strip(),o.substring(i+2).strip()};}
    private static List<String> alternatives(String o){return Arrays.stream((o==null?"":o).split("\\|")).map(String::strip).filter(x->!x.isEmpty()).toList();}
    private static JsonNode read(String a){try{return JSON.readTree(a);}catch(Exception e){return null;}}

    static SessionService.PracticeCheck check(ExamClient.KeyQuestion q,String answer){
        var options=q.options()==null?List.<ExamClient.KeyOption>of():q.options().stream().sorted(Comparator.comparingInt(ExamClient.KeyOption::displayOrder)).toList();
        var parts=new LinkedHashMap<String,Boolean>();
        var key=new ArrayList<String>();
        var correctIds=new ArrayList<UUID>();
        double share;
        String a=answer==null?"":answer.trim();
        switch(q.questionType()){
            case "MULTIPLE_CHOICE","TRUE_FALSE" -> {
                options.stream().filter(ExamClient.KeyOption::correct).forEach(o->{correctIds.add(o.id());key.add(o.content());});
                share=options.stream().anyMatch(o->o.correct()&&o.id().toString().equals(a))?1:0;
            }
            case "MULTIPLE_RESPONSE" -> {
                options.stream().filter(ExamClient.KeyOption::correct).forEach(o->{correctIds.add(o.id());key.add(o.content());});
                var chosen=new HashSet<String>();var n=read(a);if(n!=null&&n.isArray())n.forEach(v->chosen.add(v.asText()));
                var want=new HashSet<String>();correctIds.forEach(id->want.add(id.toString()));
                share=chosen.equals(want)?1:0;
            }
            case "TRUE_FALSE_GROUP" -> {
                var n=read(a);int right=0;
                for(int i=0;i<options.size();i++){
                    var o=options.get(i);var v=n!=null&&n.isObject()?n.get(o.id().toString()):null;
                    boolean ok=v!=null&&v.isBoolean()&&v.asBoolean()==o.correct();if(ok)right++;parts.put(o.id().toString(),ok);
                    key.add((char)('a'+i)+") "+o.content()+" — "+(o.correct()?"Đúng":"Sai"));
                }
                share=options.size()==4?GROUP_OF_FOUR[right]:options.isEmpty()?0:(double)right/options.size();
            }
            case "FILL_BLANK","MATCHING" -> {
                boolean matching="MATCHING".equals(q.questionType());var n=read(a);int right=0;
                for(int i=0;i<options.size();i++){
                    var o=options.get(i);var v=n!=null&&n.isObject()&&n.get(o.id().toString())!=null?n.get(o.id().toString()).asText(""):"";
                    boolean ok;
                    if(matching){var p=pair(o.content());ok=p!=null&&!v.isBlank()&&fold(p[1]).equals(fold(v));key.add(p==null?o.content():p[0]+" → "+p[1]);}
                    else{ok=!v.isBlank()&&alternatives(o.content()).stream().anyMatch(x->fold(x).equals(fold(v)));key.add("("+(i+1)+") "+String.join(" / ",alternatives(o.content())));}
                    if(ok)right++;parts.put(o.id().toString(),ok);
                }
                share=options.isEmpty()?0:(double)right/options.size();
            }
            case "SHORT_ANSWER" -> {
                if(q.expectedAnswer()!=null)key.add(q.expectedAnswer());
                share=normalize(q.expectedAnswer()).equals(normalize(a))?1:0;
            }
            default -> throw new IllegalArgumentException("Not checkable");
        }
        share=Math.round(share*1000)/1000.0;
        return new SessionService.PracticeCheck(q.questionId(),share,share>=1,key,correctIds,parts,q.explanation());
    }
}
