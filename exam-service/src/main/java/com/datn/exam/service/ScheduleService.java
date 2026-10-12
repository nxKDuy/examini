package com.datn.exam.service;

import com.datn.exam.client.SessionClient;
import com.datn.exam.dto.ScheduleDto;
import com.datn.exam.entity.Exam;
import com.datn.exam.entity.ExamParticipant;
import com.datn.exam.repository.ExamParticipantRepository;
import com.datn.exam.repository.ExamRepository;
import com.datn.exam.repository.StartAdmissionRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

@Service @RequiredArgsConstructor @Transactional
public class ScheduleService {
    private final ExamCore core;
    private final ExamRepository exams;
    private final ExamParticipantRepository participants;
    private final StartAdmissionRepository admissions;
    private final SessionClient sessions;
    private final ExamEventPublisher events;
    private final ObjectMapper json;
    private final Clock clock;
    @Value("${exam.internal-token:}") private String internalToken="";

    public ScheduleDto.Response get(UUID id,UUID actor) {
        Exam exam=core.load(id);core.require(exam,actor,false);return response(exam);
    }

    public ScheduleDto.Response update(UUID id,UUID actor,ScheduleDto.Update request) {
        Exam exam=core.load(id);core.require(exam,actor,true);
        if(request==null)throw ExamCore.error(400,"Schedule payload required");
        if(request.expectedRevision()!=exam.getScheduleRevision())throw ExamCore.error(409,"SCHEDULE_REVISION_MISMATCH");
        ZoneId zone=zone(request.timezone());
        ScheduleRules.validateLocal(request.startAt(),zone,"startAt");
        ScheduleRules.validateLocal(request.endAt(),zone,"endAt");
        Instant start=request.startAt()==null?null:request.startAt().toInstant();
        Instant end=request.endAt()==null?null:request.endAt().toInstant();
        List<Integer> reminders=request.reminderOffsetsMinutes()==null?List.of():List.copyOf(request.reminderOffsetsMinutes());
        ScheduleRules.validate(start,end,zone.getId(),request.deadlineRule(),request.autoOpen(),request.autoClose(),reminders,exam.getDurationMinutes());
        if(end!=null && !end.isAfter(clock.instant()))throw ExamCore.error(400,"Cannot publish or reschedule a schedule with endAt in the past");
        if(exam.getStatus()==Exam.Status.CLOSED || exam.getStatus()==Exam.Status.COMPLETED)throw ExamCore.error(409,"CLOSED_EXAM_SCHEDULE_IMMUTABLE");
        if(exam.getStatus()!=Exam.Status.DRAFT && exam.getStatus()!=Exam.Status.PUBLISHED && exam.getStatus()!=Exam.Status.OPEN)
            throw ExamCore.error(409,"Schedule cannot be edited in this state");

        ScheduleDto.ScheduleState old=state(exam);
        ScheduleDto.ScheduleState proposed=new ScheduleDto.ScheduleState(start,end,zone.getId(),request.deadlineRule(),request.autoOpen(),request.autoClose(),reminders);
        if(old.equals(proposed))return response(exam);
        boolean hasStarted=hasStarted(exam);
        if((exam.getStatus()==Exam.Status.DRAFT || exam.getStatus()==Exam.Status.PUBLISHED) && hasStarted)
            throw ExamCore.error(409,"SCHEDULE_LOCKED_AFTER_STUDENT_START");
        if(exam.getStatus()==Exam.Status.OPEN) {
            if(!Objects.equals(start,exam.getStartAt()) || !zone.getId().equals(exam.getTimezone()) || request.autoOpen()!=exam.isAutoOpen() ||
                    request.autoClose()!=exam.isAutoClose() || !reminders.equals(exam.getReminderOffsetsMinutes()))
                throw ExamCore.error(409,"OPEN_EXAM_ONLY_END_AT_AND_UNSTARTED_DEADLINE_RULE_MAY_CHANGE");
            if(exam.getEndAt()==null || end==null || end.isBefore(exam.getEndAt()))throw ExamCore.error(409,"OPEN_EXAM_END_AT_MUST_EXTEND");
            if(request.deadlineRule()!=exam.getDeadlineRule() && hasStarted)throw ExamCore.error(409,"DEADLINE_RULE_LOCKED_AFTER_STUDENT_START");
        }
        if(exam.getStatus()!=Exam.Status.DRAFT && (request.reason()==null || request.reason().isBlank()))
            throw ExamCore.error(400,"Reason required for a published schedule change");

        if(hasActiveAdmission(exam))throw ExamCore.error(409,"START_ADMISSION_IN_PROGRESS");
        String change=changeType(old,proposed);
        exam.setStartAt(start);exam.setEndAt(end);exam.setTimezone(zone.getId());exam.setDeadlineRule(request.deadlineRule());
        exam.setAutoOpen(request.autoOpen());exam.setAutoClose(request.autoClose());exam.setReminderOffsetsMinutes(new ArrayList<>(reminders));
        exam.setStartTime(localUtc(start));exam.setEndTime(localUtc(end));exam.setScheduleRevision(exam.getScheduleRevision()+1);
        core.audit(exam,actor,"EXAM_SCHEDULE_CHANGED",audit(old,proposed,request.reason(),exam.getScheduleRevision()));
        if(change!=null)events.scheduleChanged(exam,change,actor);
        return response(exam);
    }

    public ScheduleDto.FollowResponse follow(UUID id,UUID actor,boolean follow) {
        Exam exam=core.load(id);
        if(exam.getAccessMode()!=Exam.AccessMode.PUBLIC)throw ExamCore.error(404,"PUBLIC_EXAM_NOT_AVAILABLE");
        boolean wasFollowing=exam.getFollowers().containsKey(actor);
        if(wasFollowing==follow)return new ScheduleDto.FollowResponse(id,follow,participants.existsByExamIdAndUserIdAndStatus(id,actor,ExamParticipant.Status.ACTIVE));
        if(follow && exam.getStatus()!=Exam.Status.PUBLISHED && exam.getStatus()!=Exam.Status.OPEN)
            throw ExamCore.error(404,"PUBLIC_EXAM_NOT_AVAILABLE");
        if(follow)exam.getFollowers().put(actor,clock.instant());else exam.getFollowers().remove(actor);
        exam.setRecipientRevision(exam.getRecipientRevision()+1);
        events.recipientsChanged(exam,follow?"FOLLOWED":"UNFOLLOWED",actor);
        core.audit(exam,actor,follow?"PUBLIC_EXAM_FOLLOWED":"PUBLIC_EXAM_UNFOLLOWED",null);
        return new ScheduleDto.FollowResponse(id,follow,participants.existsByExamIdAndUserIdAndStatus(id,actor,ExamParticipant.Status.ACTIVE));
    }

    public ScheduleDto.Availability availability(UUID id,UUID actor,String code) {
        Exam exam=exams.findById(id).orElseThrow(()->ExamCore.error(404,"Exam not found"));
        return availability(exam,actor,code,participants.findByExamIdAndUserId(id,actor).orElse(null),participants.countByExamIdAndStatus(id,ExamParticipant.Status.ACTIVE));
    }

    public Page<ScheduleDto.CalendarItem> calendar(UUID actor,String role,Instant from,Instant to,boolean includeUnscheduled,Pageable pageable) {
        if(from==null || to==null || !from.isBefore(to) || Duration.between(from,to).compareTo(Duration.ofDays(366))>0)
            throw ExamCore.error(400,"Calendar range must be positive and no longer than 366 days");
        if(pageable.getPageSize()>100)throw ExamCore.error(400,"Calendar page size cannot exceed 100");
        boolean teacher="TEACHER".equals(role);
        Page<Exam> page=teacher?exams.findTeacherCalendar(actor,from,to,includeUnscheduled,pageable):
                exams.findStudentCalendar(actor,from,to,includeUnscheduled,List.of(Exam.Status.PUBLISHED,Exam.Status.OPEN,Exam.Status.CLOSED),pageable);
        // Two batched queries for the whole page instead of per-item participant lookups.
        List<UUID> ids=page.getContent().stream().map(Exam::getId).toList();
        Map<UUID,ExamParticipant> rows=new HashMap<>();Map<UUID,Long> active=new HashMap<>();
        if(!ids.isEmpty()) {
            if(!teacher)participants.findByUserIdAndExamIdIn(actor,ids).forEach(r->rows.put(r.getExamId(),r));
            participants.activeCounts(ids).forEach(r->active.put((UUID)r[0],(Long)r[1]));
        }
        return page.map(e->calendarItem(e,actor,teacher,rows.get(e.getId()),active.getOrDefault(e.getId(),0L)));
    }

    public List<Exam> myExams(UUID actor) {
        Map<UUID,Exam> examsById=new LinkedHashMap<>();
        exams.findAssigned(actor).stream().filter(ScheduleService::studentVisible).forEach(e->examsById.put(e.getId(),e));
        // FOLLOWER exams are intentionally account-scoped and never count as participants.
        exams.findFollowed(actor).stream().filter(ScheduleService::studentVisible).forEach(e->examsById.put(e.getId(),e));
        return new ArrayList<>(examsById.values());
    }

    public ScheduleDto.RecipientSnapshot notificationRecipients(UUID id,String suppliedToken,String notificationToken) {
        if(notificationToken==null || notificationToken.isBlank() || suppliedToken==null ||
                !MessageDigest.isEqual(notificationToken.getBytes(StandardCharsets.UTF_8),suppliedToken.getBytes(StandardCharsets.UTF_8)))
            throw ExamCore.error(401,"Notification service authentication required");
        Exam e=exams.findById(id).orElseThrow(()->ExamCore.error(404,"Exam not found"));
        Map<UUID,String> relations=new HashMap<>();
        if(e.getStatus()!=Exam.Status.COMPLETED) {
            relations.put(e.getOwnerUserId(),"OWNER");
            e.getMembers().forEach((user,memberRole)->{if(memberRole==Exam.MemberRole.MANAGER)putRelation(relations,user,"MANAGER");});
        }
        if(e.getStatus()!=Exam.Status.DRAFT && e.getStatus()!=Exam.Status.COMPLETED) {
            participants.findByExamIdAndStatus(e.getId(),ExamParticipant.Status.ACTIVE).forEach(p->putRelation(relations,p.getUserId(),"ASSIGNED".equals(p.getSource())?"ASSIGNED":"JOINED"));
            e.getFollowers().keySet().forEach(user->putRelation(relations,user,"FOLLOWER"));
        }
        Map<UUID,ScheduleDto.Progress> progress=new HashMap<>();
        List<UUID> users=relations.entrySet().stream().filter(entry->"ASSIGNED".equals(entry.getValue())||"JOINED".equals(entry.getValue())).map(Map.Entry::getKey).toList();
        if(!users.isEmpty()) {
            if(internalToken.isBlank())throw ExamCore.error(503,"Session integration not configured");
            for(int from=0;from<users.size();from+=100) {
                List<UUID> chunk=users.subList(from,Math.min(from+100,users.size()));
                sessions.recipientProgress(id,chunk,internalToken).forEach(value->progress.put(value.userId(),value));
            }
        }
        List<ScheduleDto.Recipient> recipients=relations.entrySet().stream()
                .sorted(Comparator.<Map.Entry<UUID,String>>comparingInt(entry->priority(entry.getValue())).thenComparing(entry->entry.getKey().toString()))
                .map(entry->{var p=progress.get(entry.getKey());return new ScheduleDto.Recipient(entry.getKey(),entry.getValue(),p!=null&&p.started(),p!=null&&p.submitted());}).toList();
        return new ScheduleDto.RecipientSnapshot(e.getId(),e.getTitle(),e.getStatus().name(),e.getScheduleRevision(),e.getLifecycleRevision(),e.getRecipientRevision(),e.getStartAt(),e.getEndAt(),e.getTimezone(),e.getDeadlineRule(),List.copyOf(e.getReminderOffsetsMinutes()),recipients);
    }

    private ScheduleDto.CalendarItem calendarItem(Exam e,UUID actor,boolean teacher,ExamParticipant row,long eligible) {
        ScheduleDto.Availability a=availability(e,actor,null,row,eligible);
        String relation=teacher?core.role(e,actor):row!=null?(!row.isActive()?"REVOKED":"ASSIGNED".equals(row.getSource())?"ASSIGNED":"JOINED"):e.getFollowers().containsKey(actor)?"FOLLOWER":"NONE";
        return new ScheduleDto.CalendarItem(e.getId(),e.getTitle(),e.getStatus().name(),e.getStartAt(),e.getEndAt(),e.getTimezone(),e.getDurationMinutes(),e.getDeadlineRule(),a.canJoin(),a.canStart(),a.canResume(),a.reasonCode(),a.attemptsRemaining(),a.effectiveExpiresAt(),relation,e.getFollowers().containsKey(actor),row!=null&&row.isActive(),teacher?(Long)eligible:null,teacher?e.getMaxParticipants():null,a.serverNow(),a.calendarState(),teacher?null:a.latestSessionId(),teacher?null:a.latestSessionStatus());
    }

    private ScheduleDto.Availability availability(Exam e,UUID actor,String code,ExamParticipant row,long eligible) {
        boolean staff=!"NONE".equals(core.role(e,actor));
        boolean registered=row!=null,participant=row!=null&&row.isActive(),revoked=registered&&!participant,followed=e.getFollowers().containsKey(actor);
        long limit=(long)e.getAttemptLimit()+(row==null?0:row.getExtraAttempts());
        boolean published=e.getStatus()!=Exam.Status.DRAFT && e.getStatus()!=Exam.Status.COMPLETED;
        boolean publicBrowse=e.getAccessMode()==Exam.AccessMode.PUBLIC && (e.getStatus()==Exam.Status.PUBLISHED || e.getStatus()==Exam.Status.OPEN);
        boolean codePreview=code!=null && e.getAccessMode()==Exam.AccessMode.JOIN_CODE && e.getCode().equals(code) && published;
        boolean canView=staff || published && (registered || followed || publicBrowse || codePreview);
        if(!canView)throw ExamCore.error(404,"EXAM_NOT_AVAILABLE");
        Instant now=clock.instant();boolean access=!revoked && (e.getAccessMode()==Exam.AccessMode.PUBLIC || participant) || e.getAccessMode()==Exam.AccessMode.JOIN_CODE && code!=null && e.getCode().equals(code);
        boolean timeForJoin=(e.getStartAt()==null || !now.isBefore(e.getStartAt())) && (e.getEndAt()==null || now.isBefore(e.getEndAt()));
        long attempts=0,active=0;boolean resume=false;Instant activeExpiry=null;UUID latestId=null;String latestStatus=null;
        if(!staff && canView && (access || revoked) && internalToken.isBlank())throw ExamCore.error(503,"Session integration not configured");
        if(!staff && canView && (access || revoked)) {
            var status=sessions.availability(e.getId(),actor,internalToken);
            attempts=status.attempts();active=status.activeSessions();resume=status.resumable();activeExpiry=status.activeExpiresAt();latestId=status.latestSessionId();latestStatus=status.latestSessionStatus();
        }
        boolean canJoin=!staff && published && (e.getStatus()==Exam.Status.PUBLISHED || e.getStatus()==Exam.Status.OPEN) && access && timeForJoin &&
                attempts<limit && (participant || eligible<e.getMaxParticipants());
        long remaining=Math.max(0,limit-attempts);
        String reason=reason(e,actor,canView,participant,revoked,resume,attempts,limit,active,now);
        boolean canStart=reason==null;
        Instant effectiveExpiry=resume?activeExpiry:canStart?ScheduleRules.deadline(now,e.getDurationMinutes(),e.getEndAt(),e.getDeadlineRule()):null;
        return new ScheduleDto.Availability(e.getId(),e.getTitle(),e.getStatus().name(),now,e.getStartAt(),e.getEndAt(),e.getTimezone(),e.getDurationMinutes(),e.getDeadlineRule(),canView,canJoin,canStart,resume,reason,remaining,effectiveExpiry,followed,participant,e.getScheduleRevision(),staff?e.getStatus().name():calendarState(e,reason,canStart,resume,attempts,latestStatus,now),staff?null:latestId,staff?null:latestStatus);
    }

    private String reason(Exam e,UUID actor,boolean canView,boolean participant,boolean revoked,boolean resumable,long attempts,long limit,long active,Instant now) {
        if(!canView)return "NOT_AUTHORIZED";
        if(resumable)return null;
        if(revoked)return "PARTICIPATION_REVOKED";
        if(e.getStatus()==Exam.Status.DRAFT)return "EXAM_NOT_PUBLISHED";
        if(e.getStatus()==Exam.Status.CLOSED || e.getStatus()==Exam.Status.COMPLETED)return "EXAM_CLOSED";
        if(e.isIntegrityLocked())return "EXAM_INTEGRITY_LOCKED";
        if(e.getStartAt()!=null && now.isBefore(e.getStartAt()))return "BEFORE_START_AT";
        if(e.getEndAt()!=null && !now.isBefore(e.getEndAt()))return "AFTER_END_AT";
        if(e.getStatus()!=Exam.Status.OPEN)return "EXAM_NOT_OPEN";
        if(!participant)return "JOIN_REQUIRED";
        if(attempts>=limit)return "ATTEMPT_LIMIT";
        if(e.getDeadlineRule()==Exam.DeadlineRule.REQUIRE_FULL_WINDOW && Duration.between(now,e.getEndAt()).compareTo(Duration.ofMinutes(e.getDurationMinutes()))<0)return "INSUFFICIENT_TIME";
        if(active>=Math.min(platformLimit(),e.getMaxConcurrentSessions()))return "CAPACITY_FULL";
        if(e.getSnapshots().isEmpty())return "SNAPSHOT_UNAVAILABLE";
        return null;
    }

    @Value("${exam.capacity.platform-limit:100}") private int configuredPlatformLimit=100;
    private int platformLimit(){return configuredPlatformLimit;}
    private boolean hasStarted(Exam exam) {
        if(exam.getFirstStudentStartedAt()!=null)return true;
        if(internalToken.isBlank())throw ExamCore.error(503,"Session integration not configured");
        return sessions.started(exam.getId(),internalToken);
    }
    private boolean hasActiveAdmission(Exam exam) {
        // An admission blocks schedule mutation only while its short lease is unresolved.
        return admissions.existsByExamIdAndStateIn(exam.getId(),List.of(com.datn.exam.entity.StartAdmission.State.PENDING,com.datn.exam.entity.StartAdmission.State.CLAIMED));
    }
    private ZoneId zone(String id) {
        try {
            if(id==null || !("UTC".equals(id) || id.contains("/")) || !ZoneId.getAvailableZoneIds().contains(id) && !"UTC".equals(id))throw new DateTimeException("Not an IANA region");
            return ZoneId.of(id);
        } catch(DateTimeException ex) {throw ExamCore.error(400,"timezone must be an IANA region or UTC");}
    }
    private static LocalDateTime localUtc(Instant value){return value==null?null:LocalDateTime.ofInstant(value,ZoneOffset.UTC);}
    private ScheduleDto.Response response(Exam e){return new ScheduleDto.Response(e.getId(),e.getStartAt(),e.getEndAt(),e.getTimezone(),e.getDeadlineRule(),e.isAutoOpen(),e.isAutoClose(),List.copyOf(e.getReminderOffsetsMinutes()),e.getScheduleRevision(),e.getLifecycleRevision(),clock.instant());}
    private static ScheduleDto.ScheduleState state(Exam e){return new ScheduleDto.ScheduleState(e.getStartAt(),e.getEndAt(),e.getTimezone(),e.getDeadlineRule(),e.isAutoOpen(),e.isAutoClose(),List.copyOf(e.getReminderOffsetsMinutes()));}
    public static String changeType(Instant oldStart,Instant oldEnd,Instant start,Instant end) {
        boolean wasScheduled=oldStart!=null||oldEnd!=null,nowScheduled=start!=null||end!=null;
        if(!wasScheduled&&!nowScheduled)return null;
        if(!wasScheduled)return "SCHEDULED";
        if(!nowScheduled)return "UNSCHEDULED";
        return "RESCHEDULED";
    }
    private static String changeType(ScheduleDto.ScheduleState old,ScheduleDto.ScheduleState next) {
        if(Objects.equals(old.startAt(),next.startAt())&&Objects.equals(old.endAt(),next.endAt()))
            return old.startAt()==null && old.endAt()==null ? null : "RESCHEDULED";
        return changeType(old.startAt(),old.endAt(),next.startAt(),next.endAt());
    }
    private String audit(ScheduleDto.ScheduleState old,ScheduleDto.ScheduleState next,String reason,long revision) {
        try{return json.writeValueAsString(Map.of("old",old,"new",next,"reason",reason==null?"":reason,"revision",revision));}
        catch(JsonProcessingException e){throw new IllegalStateException("Cannot encode schedule audit",e);}
    }
    private static void putRelation(Map<UUID,String> map,UUID id,String relation){if(priority(relation)<priority(map.getOrDefault(id,"NONE")))map.put(id,relation);}
    private static int priority(String relation){return switch(relation){case "OWNER"->0;case "MANAGER"->1;case "ASSIGNED"->2;case "JOINED"->3;case "FOLLOWER"->4;default->5;};}
    private static boolean studentVisible(Exam exam){return exam.getStatus()==Exam.Status.PUBLISHED || exam.getStatus()==Exam.Status.OPEN || exam.getStatus()==Exam.Status.CLOSED;}
    private static String calendarState(Exam exam,String reason,boolean canStart,boolean resumable,long attempts,String latestStatus,Instant now) {
        if(resumable)return "IN_PROGRESS";
        if("IN_PROGRESS".equals(latestStatus))return "IN_PROGRESS";
        if("SUBMITTED".equals(latestStatus))return "SUBMITTED";
        if("AUTO_SUBMITTED".equals(latestStatus))return "AUTO_SUBMITTED";
        if("CANCELLED".equals(latestStatus))return "CANCELLED";
        if(attempts==0 && exam.getEndAt()!=null && !now.isBefore(exam.getEndAt()))return "MISSED";
        if(exam.getStatus()==Exam.Status.CLOSED || exam.getStatus()==Exam.Status.COMPLETED)return "CLOSED";
        if(canStart)return "AVAILABLE";
        if("JOIN_REQUIRED".equals(reason))return "JOIN_REQUIRED";
        if("BEFORE_START_AT".equals(reason))return "UPCOMING";
        if("EXAM_NOT_OPEN".equals(reason))return "NOT_OPEN";
        return reason;
    }
}
