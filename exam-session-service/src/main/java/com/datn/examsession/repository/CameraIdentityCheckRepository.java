package com.datn.examsession.repository;

import com.datn.examsession.entity.CameraIdentityCheck;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;

public interface CameraIdentityCheckRepository extends JpaRepository<CameraIdentityCheck, UUID> {
    List<CameraIdentityCheck> findBySessionIdIn(Collection<UUID> sessionIds);
}
