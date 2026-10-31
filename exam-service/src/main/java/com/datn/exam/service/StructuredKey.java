package com.datn.exam.service;

import com.datn.exam.dto.Snapshot;
import java.util.*;
import java.util.regex.Pattern;

/**
 * FILL_BLANK and MATCHING keys as Question Bank defines them (contracts/question-media-and-skills.md):
 * gaps are runs of 3+ underscores, option i = accepted answers of gap i; MATCHING options are "left => right".
 */
public final class StructuredKey {
    private StructuredKey(){}
    private static final Pattern BLANK=Pattern.compile("_{3,}");
    public static int blanks(String content){if(content==null)return 0;var m=BLANK.matcher(content);int n=0;while(m.find())n++;return n;}
    /** {left, right} or null. */
    public static String[] pair(String option){
        if(option==null)return null;int i=option.indexOf("=>");if(i<0)return null;
        String l=option.substring(0,i).strip(),r=option.substring(i+2).strip();
        return l.isEmpty()||r.isEmpty()?null:new String[]{l,r};
    }
    static boolean valid(String type,String content,List<Snapshot.Option> options){
        if(options.stream().anyMatch(o->o.id()==null||o.content()==null||o.content().isBlank())||options.stream().map(Snapshot.Option::id).distinct().count()!=options.size())return false;
        if("FILL_BLANK".equals(type)){int n=blanks(content);return n>=1&&n<=20&&options.size()==n;}
        if(options.size()<2||options.size()>10)return false;
        var lefts=new HashSet<String>();var rights=new HashSet<String>();
        for(var o:options){var p=pair(o.content());if(p==null||!lefts.add(p[0].toLowerCase(Locale.ROOT))||!rights.add(p[1].toLowerCase(Locale.ROOT)))return false;}
        return true;
    }
    /** Right-hand items in a stable order that says nothing about the key (alphabetical). */
    public static List<String> targets(List<Snapshot.Option> options){
        var out=new ArrayList<String>();for(var o:options){var p=pair(o.content());if(p!=null)out.add(p[1]);}
        out.sort(Comparator.comparing((String s)->s.toLowerCase(Locale.ROOT)).thenComparing(Comparator.naturalOrder()));
        return out;
    }
}
