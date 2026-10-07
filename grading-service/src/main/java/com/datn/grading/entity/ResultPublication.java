package com.datn.grading.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name="result_publications",uniqueConstraints=@UniqueConstraint(name="uq_result_publication_revision",columnNames={"result_id","result_revision"}))
@Getter @NoArgsConstructor
public class ResultPublication {
    @Id private UUID id;
    @Column(name="result_id",nullable=false) private UUID resultId;
    @Column(name="result_revision",nullable=false) private long resultRevision;
    @Column(name="total_score",nullable=false) private float totalScore;
    @Column(columnDefinition="TEXT") private String feedback;
    @Column(name="public_reason",columnDefinition="TEXT") private String publicReason;
    @Column(name="grading_mode",nullable=false,length=16) private String gradingMode;
    @Column(name="released_at",nullable=false) private Instant releasedAt;
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    @Column(name="items",nullable=false,columnDefinition="jsonb") private String items;

    public ResultPublication(UUID resultId,long revision,float totalScore,String feedback,String publicReason,String gradingMode,Instant releasedAt,String items) {
        this.id=UUID.randomUUID();this.resultId=resultId;this.resultRevision=revision;this.totalScore=totalScore;this.feedback=feedback;
        this.publicReason=publicReason;this.gradingMode=gradingMode;this.releasedAt=releasedAt;this.items=items;
    }
}
