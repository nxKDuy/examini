package com.datn.exam.dto;

import com.datn.exam.entity.Exam;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class ScheduleDto {
    private ScheduleDto() {}

    public record Update(OffsetDateTime startAt,OffsetDateTime endAt,String timezone,
                        Exam.DeadlineRule deadlineRule,boolean autoOpen,boolean autoClose,
                        List<Integer> reminderOffsetsMinutes,long expectedRevision,String reason) {}
    public record ScheduleState(Instant startAt,Instant endAt,String timezone,Exam.DeadlineRule deadlineRule,
                                boolean autoOpen,boolean autoClose,List<Integer> reminderOffsetsMinutes) {}
    public record Response(UUID examId,Instant startAt,Instant endAt,String timezone,
                           Exam.DeadlineRule deadlineRule,boolean autoOpen,boolean autoClose,
                           List<Integer> reminderOffsetsMinutes,long scheduleRevision,
                           long lifecycleRevision,Instant serverNow) {}
    public record Admission(UUID admissionId,long scheduleRevision,Instant startedAt,Instant deadlineAt) {}
    public record Reservation(UUID examId,UUID admissionId,long scheduleRevision) {}
    public record Progress(UUID userId,boolean started,boolean submitted) {}
    public record Recipient(UUID userId,String relation,boolean started,boolean submitted) {}
    public record RecipientSnapshot(UUID examId,String examTitle,String status,long scheduleRevision,
                                    long lifecycleRevision,long recipientRevision,Instant startAt,Instant endAt,
                                    String timezone,Exam.DeadlineRule deadlineRule,List<Integer> reminderOffsetsMinutes,
                                    List<Recipient> recipients) {}
    public record Availability(UUID examId,String examTitle,String status,Instant serverNow,
                               Instant startAt,Instant endAt,String timezone,int durationMinutes,
                               Exam.DeadlineRule deadlineRule,boolean canView,boolean canJoin,
                               boolean canStart,boolean canResume,String reasonCode,long attemptsRemaining,
                               Instant effectiveExpiresAt,boolean followed,boolean participant,long scheduleRevision,
                               String calendarState,UUID latestSessionId,String latestSessionStatus) {}
    public record CalendarItem(UUID examId,String examTitle,String status,Instant startAt,Instant endAt,
                               String timezone,int durationMinutes,Exam.DeadlineRule deadlineRule,
                               boolean canJoin,boolean canStart,boolean canResume,String reasonCode,
                               long attemptsRemaining,Instant effectiveExpiresAt,String relation,
                               boolean followed,boolean participant,Long participantCount,Integer maxParticipants,
                               Instant serverNow,String calendarState,UUID latestSessionId,String latestSessionStatus) {}
    public record FollowResponse(UUID examId,boolean followed,boolean participant) {}
    public record NotificationEnvelope(UUID eventId,String eventType,int eventVersion,Instant occurredAt,Object payload) {}
    public record ScheduleEvent(UUID examId,String examTitle,long scheduleRevision,String changeType,
                                Instant startAt,Instant endAt,String timezone,Exam.DeadlineRule deadlineRule,
                                List<Integer> reminderOffsetsMinutes,String examStatus,UUID actorUserId) {}
    public record LifecycleEvent(UUID examId,String examTitle,String status,String trigger,
                                 UUID actorUserId,long lifecycleRevision) {}
    public record AdmissionStatus(UUID examId,UUID admissionId,UUID sessionId,boolean started) {}
}
