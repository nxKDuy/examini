package com.datn.examsession.entity;
import jakarta.persistence.*;
import lombok.*;
import java.util.UUID;
@Entity @Table(name="submission_events") @Getter @Setter @NoArgsConstructor
public class SubmissionEvent {
    @Id private UUID sessionId;
    private boolean delivered;
    public SubmissionEvent(UUID id) {this.sessionId=id;}
    @Column(name="attempt_count",nullable=false) private int attemptCount;
    @Column(name="next_attempt_at") private java.time.Instant nextAttemptAt;
    @Column(name="last_error",length=500) private String lastError;
    @Column(nullable=false) private boolean parked;
    public void attempted(){attemptCount++;}
    /** Failed attempt: skipped until nextAttempt, or for good once parked (reset parked=false to replay). */
    public void failed(String error,java.time.Instant nextAttempt,boolean park){lastError=error;nextAttemptAt=nextAttempt;parked=park;}
    public void retryCleared(){nextAttemptAt=null;lastError=null;}

}
