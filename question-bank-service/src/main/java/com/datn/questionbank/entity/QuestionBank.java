package com.datn.questionbank.entity;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.util.UUID;
@Entity @Table(name="question_banks")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class QuestionBank {
    @Id @GeneratedValue(strategy=GenerationType.UUID) private UUID id;
    @Enumerated(EnumType.STRING) @Column(nullable=false,length=30) private BankType type;
    @Column(name="owner_user_id") private UUID ownerUserId;
    @Column(name="organization_id") private UUID organizationId;
    @Column(nullable=false,length=200) private String name;
    @Enumerated(EnumType.STRING) @Column(nullable=false,length=30) private BankStatus status;
    @Column(name="is_default",nullable=false) private boolean defaultBank;
    @Column(name="created_at",nullable=false,updatable=false) private LocalDateTime createdAt;
    public enum BankType { PERSONAL, ORGANIZATION, SYSTEM, PUBLIC }
    public enum BankStatus { ACTIVE, PENDING_AUTHORIZATION }
}
