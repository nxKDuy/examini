package com.datn.exam.service;

import com.datn.exam.dto.ScheduleDto;
import com.datn.exam.entity.Exam;
import com.datn.exam.entity.ExamOutboxEvent;
import com.datn.exam.repository.ExamOutboxRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.UUID;

@Service @RequiredArgsConstructor
public class ExamEventPublisher {
    private final ExamOutboxRepository outbox;
    private final ObjectMapper json;

    public void scheduleChanged(Exam e,String changeType,UUID actor) {
        enqueue("exam.schedule.changed",new ScheduleDto.ScheduleEvent(e.getId(),e.getTitle(),e.getScheduleRevision(),
                changeType,e.getStartAt(),e.getEndAt(),e.getTimezone(),e.getDeadlineRule(),
                ListCopy.copy(e.getReminderOffsetsMinutes()),e.getStatus().name(),actor));
    }
    public void lifecycleChanged(Exam e,String trigger,UUID actor) {
        enqueue("exam.lifecycle.changed",new ScheduleDto.LifecycleEvent(e.getId(),e.getTitle(),e.getStatus().name(),
                trigger,actor,e.getLifecycleRevision()));
    }
    public void recipientsChanged(Exam e,String cause,UUID actor) {
        enqueue("exam.recipients.changed",new RecipientEvent(e.getId(),e.getRecipientRevision(),cause,actor));
    }
    /** Participant lifecycle for Notification/Analytics; cause: ASSIGNED, REINSTATED, JOINED, REVOKED, ATTEMPT_GRANTED. No private data. */
    public void participantChanged(Exam e,UUID user,String cause,UUID actor,Integer quantity,String publicReason,ExamCore.ParticipantCounts counts) {
        enqueue("exam.participant.changed",new ParticipantEvent(e.getId(),e.getTitle(),user,cause,actor,quantity,publicReason,e.getRecipientRevision(),counts.registered(),counts.eligible(),counts.revoked()));
    }
    private void enqueue(String type,Object payload) {
        try {
            Instant now=Instant.now();UUID id=UUID.randomUUID();
            outbox.save(new ExamOutboxEvent(id,type,json.writeValueAsString(payload),now));
        } catch(JsonProcessingException ex) { throw new IllegalStateException("Cannot encode scheduling event",ex); }
    }
    public record ParticipantEvent(UUID examId,String examTitle,UUID userId,String cause,UUID actorUserId,Integer quantity,String publicReason,long recipientRevision,long registeredCount,long eligibleCount,long revokedCount){}
    public record RecipientEvent(UUID examId,long recipientRevision,String cause,UUID actorUserId){}
    private static final class ListCopy {
        static java.util.List<Integer> copy(java.util.List<Integer> list){return list==null?java.util.List.of():java.util.List.copyOf(list);}
    }
}
