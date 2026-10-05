package com.datn.examsession.entity;
import jakarta.persistence.*;
import lombok.*;
import java.util.UUID;
@Entity @Table(name="submission_events") @Getter @Setter @NoArgsConstructor
public class SubmissionEvent {
    @Id private UUID sessionId;
    private boolean delivered;
    public SubmissionEvent(UUID id) {this.sessionId=id;}
}
