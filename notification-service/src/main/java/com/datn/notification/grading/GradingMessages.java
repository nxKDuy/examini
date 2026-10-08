package com.datn.notification.grading;
import com.datn.notification.scheduling.SchedulingMessages.Content;
import java.util.*;

/**
 * Vietnamese wording for manual-grading notices. Never contains scores, answers, keys, feedback, audit reasons or student
 * identity: only the Exam title and a deep link. Links are SPA routes (frontend/app).
 */
public final class GradingMessages {
    public static final String REVIEW_REQUIRED="GRADING_REVIEW_REQUIRED",RESULT_AVAILABLE="RESULT_AVAILABLE",RESULT_REGRADED="RESULT_REGRADED";
    private GradingMessages(){}

    /** Teacher grading queue for the Exam. */
    public static String queueLink(UUID examId){return "/grading?examId="+examId;}
    /** Student result page; the route id is the session ID. */
    public static String resultLink(UUID sessionId){return "/exam/result/"+sessionId;}

    public static Content reviewRequired(String examTitle,UUID examId,UUID sessionId,UUID resultId,long revision){
        return new Content(REVIEW_REQUIRED,"Có bài làm cần chấm","Kỳ thi \""+examTitle+"\" có bài làm cần giáo viên xem xét và chấm điểm. Mở hàng đợi chấm bài để xử lý.",
            Map.of("examTitle",examTitle,"sessionId",sessionId.toString(),"resultId",resultId.toString(),"resultRevision",String.valueOf(revision),"link",queueLink(examId)));
    }
    public static Content released(String examTitle,UUID sessionId,String gradingMode,Long revision){
        var m=new HashMap<String,String>(Map.of("examTitle",examTitle,"gradingMode",gradingMode,"link",resultLink(sessionId)));
        if(revision!=null)m.put("resultRevision",String.valueOf(revision));
        return new Content(RESULT_AVAILABLE,"Đã có kết quả thi",
            (gradingMode.equals("MANUAL")?"Giáo viên đã chấm xong bài kỳ thi \"":"Kết quả kỳ thi \"")+examTitle+"\" đã sẵn sàng. Xem kết quả của bạn ngay.",m);
    }
    public static Content regraded(String examTitle,UUID sessionId,long revision){
        return new Content(RESULT_REGRADED,"Kết quả thi đã được cập nhật","Kết quả kỳ thi \""+examTitle+"\" của bạn đã được giáo viên cập nhật sau khi chấm lại. Xem kết quả mới nhất.",
            Map.of("examTitle",examTitle,"resultRevision",String.valueOf(revision),"link",resultLink(sessionId)));
    }
}
