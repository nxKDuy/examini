package com.datn.auth.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "users", indexes = {
        @Index(name = "idx_users_email", columnList = "email", unique = true)
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true, length = 255)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    /** Google account id ("sub") once the user has signed in with Google; null otherwise. */
    @Column(name = "google_subject", unique = true, length = 255)
    private String googleSubject;

    @Column(name = "full_name", nullable = false, length = 255)
    private String fullName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role;

    @Enumerated(EnumType.STRING)
    @Column(name = "personal_persona", length = 20)
    private Persona personalPersona;

    @Enumerated(EnumType.STRING)
    @Column(name = "system_privilege", nullable = false, length = 20)
    private SystemPrivilege systemPrivilege;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(length = 30)
    private String phone;

    @Column(length = 255)
    private String school;

    @Column(length = 1000)
    private String bio;

    /** False for accounts created through Google until the user sets a password. */
    @Builder.Default
    @Column(name = "password_set", nullable = false)
    private boolean passwordSet = true;

    @Column(name = "locked_at")
    private java.time.Instant lockedAt;

    @Column(name = "last_login_at")
    private java.time.Instant lastLoginAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public enum Role {
        ADMIN, TEACHER, STUDENT
    }

    public enum Persona { STUDENT, TEACHER }

    public enum SystemPrivilege { NONE, ADMIN }
}
