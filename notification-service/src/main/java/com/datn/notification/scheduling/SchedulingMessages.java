package com.datn.notification.scheduling;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Vietnamese wording for Scheduling notifications. Times use the Exam timezone with an explicit
 * offset and zone label; content never includes reasons, join codes, keys, scores or other students.
 */
public final class SchedulingMessages {
    private static final DateTimeFormatter TIME=DateTimeFormatter.ofPattern("HH:mm dd/MM/yyyy '('OOOO", Locale.ROOT);
    private SchedulingMessages(){}

    /** Schedule facts used for one notification (from the event or the Exam snapshot). */
    public record Schedule(String examTitle,Instant startAt,Instant endAt,String timezone,String deadlineRule){}
    public record Content(String type,String title,String message,Map<String,String> metadata){}

    public static String time(Instant at,String zone){return TIME.format(at.atZone(ZoneId.of(zone)))+", "+zone+")";}
    public static String deadlineRule(String rule){
        return switch(rule){
            case "CAPPED_AT_END" -> "Bài làm kết thúc chậm nhất vào giờ kết thúc lịch thi.";
            case "REQUIRE_FULL_WINDOW" -> "Chỉ được bắt đầu khi còn đủ thời lượng làm bài trước giờ kết thúc.";
            default -> "Thời lượng làm bài tính từ lúc bạn bắt đầu.";
        };
    }
    public static String duration(int minutes){
        if(minutes%1440==0)return minutes/1440+" ngày";
        if(minutes%60==0)return minutes/60+" giờ";
        return minutes>60?minutes/60+" giờ "+minutes%60+" phút":minutes+" phút";
    }
    static String window(Schedule s){
        var parts=new ArrayList<String>();
        if(s.startAt()!=null)parts.add("Bắt đầu: "+time(s.startAt(),s.timezone())+".");
        if(s.endAt()!=null)parts.add("Kết thúc: "+time(s.endAt(),s.timezone())+".");
        parts.add(deadlineRule(s.deadlineRule()));
        return String.join(" ",parts);
    }
    private static Map<String,String> meta(Schedule s){
        var m=new HashMap<String,String>();m.put("examTitle",s.examTitle());m.put("timezone",s.timezone());m.put("deadlineRule",s.deadlineRule());
        if(s.startAt()!=null)m.put("startAt",s.startAt().toString());
        if(s.endAt()!=null)m.put("endAt",s.endAt().toString());
        return m;
    }
    public static Content scheduleChanged(String changeType,Schedule s){
        String q="\""+s.examTitle()+"\"";
        return switch(changeType){
            case "UNSCHEDULED" -> new Content("EXAM_UNSCHEDULED","Lịch thi đã được gỡ","Kỳ thi "+q+" hiện không còn lịch cố định. Theo dõi thông báo tiếp theo của giáo viên.",
                Map.of("examTitle",s.examTitle()));
            case "RESCHEDULED" -> new Content("EXAM_RESCHEDULED","Lịch thi đã thay đổi","Kỳ thi "+q+" có lịch mới. "+window(s),meta(s));
            default -> new Content("EXAM_SCHEDULED","Đã có lịch thi","Kỳ thi "+q+" đã có lịch. "+window(s),meta(s));
        };
    }
    public static Content opened(Schedule s,Instant now){
        String wait=s.startAt()!=null && now.isBefore(s.startAt())?" Bạn có thể vào thi từ "+time(s.startAt(),s.timezone())+".":"";
        return new Content("EXAM_OPENED","Kỳ thi đã mở","Kỳ thi \""+s.examTitle()+"\" đã mở."+wait,meta(s));
    }
    public static Content closed(Schedule s){return new Content("EXAM_CLOSED","Kỳ thi đã đóng","Kỳ thi \""+s.examTitle()+"\" đã đóng và không nhận lượt bắt đầu mới.",meta(s));}
    public static Content reminder(Schedule s,int offsetMinutes){
        var m=meta(s);m.put("offsetMinutes",String.valueOf(offsetMinutes));
        return new Content("EXAM_REMINDER","Sắp đến giờ thi","Còn "+duration(offsetMinutes)+" nữa kỳ thi \""+s.examTitle()+"\" bắt đầu. "+window(s),m);
    }
}
