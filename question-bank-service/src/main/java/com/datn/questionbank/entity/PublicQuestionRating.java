package com.datn.questionbank.entity;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name="public_question_ratings", uniqueConstraints=@UniqueConstraint(name="uq_public_rating_actor",columnNames={"publication_id","actor_user_id"}))
@Getter @Builder @NoArgsConstructor @AllArgsConstructor
public class PublicQuestionRating {
    @Id @GeneratedValue(strategy=GenerationType.UUID) private UUID id;
    @Column(name="publication_id",nullable=false,updatable=false) private UUID publicationId;
    @Column(name="actor_user_id",nullable=false,updatable=false) private UUID actorUserId;
    @Column(nullable=false,columnDefinition="NUMERIC") private BigDecimal rating;
    @Column(name="created_at",nullable=false,updatable=false) private LocalDateTime createdAt;
    @Column(name="updated_at",nullable=false) private LocalDateTime updatedAt;
}
