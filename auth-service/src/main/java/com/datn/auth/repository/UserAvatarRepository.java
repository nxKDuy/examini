package com.datn.auth.repository;

import com.datn.auth.entity.UserAvatar;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface UserAvatarRepository extends JpaRepository<UserAvatar, UUID> {
    @Query("select a.updatedAt from UserAvatar a where a.userId = :id")
    Optional<Instant> updatedAt(UUID id);
}
