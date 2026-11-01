package com.datn.examsession.repository;

import com.datn.examsession.entity.FaceProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface FaceProfileRepository extends JpaRepository<FaceProfile, UUID> {}
