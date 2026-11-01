package com.datn.examsession.service;

import com.datn.examsession.dto.CameraDto.Identity;
import com.datn.examsession.entity.*;
import com.datn.examsession.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

import static com.datn.examsession.service.CameraProctoringService.error;

/**
 * Face verification at exam entry, only for attempts with camera proctoring. The first such attempt stores the
 * student's face profile; later attempts compare the enrollment descriptor with it. After {@link #MAX_ATTEMPTS}
 * mismatches the student may continue: the attempt is flagged for proctors, never blocked or failed automatically.
 * The descriptor comes from the browser, so this is a deterrent and evidence, not proof of identity.
 */
@Service @RequiredArgsConstructor
public class FaceIdentityService {
    public static final int DESCRIPTOR_SIZE = 128, MAX_ATTEMPTS = 3;
    private static final Set<String> RESET_PERMISSIONS = Set.of("OWNER", "MANAGER");

    private final CameraProctoringService camera;
    private final FaceProfileRepository profiles;
    private final CameraIdentityCheckRepository checks;
    private final CameraStorage storage;
    @Value("${exam.camera.profile-threshold:0.6}") private double threshold;

    public static Identity dto(CameraIdentityCheck c) {
        boolean done = c.getOutcome() != CameraIdentityCheck.Outcome.MISMATCH || c.getAttempts() >= MAX_ATTEMPTS;
        return new Identity(c.getOutcome(), c.getDistance(), c.getThreshold(), c.getAttempts(), MAX_ATTEMPTS, done, c.getCheckedAt(), c.getProfileCreatedAt());
    }

    static double distance(List<Double> a, List<Double> b) {
        double sum = 0;
        for (int i = 0; i < a.size(); i++) { double d = a.get(i) - b.get(i); sum += d * d; }
        return Math.sqrt(sum);
    }

    static List<Double> descriptor(List<Double> in) {
        if (in == null || in.size() != DESCRIPTOR_SIZE) throw error(400, "descriptor must have " + DESCRIPTOR_SIZE + " numbers");
        for (Double v : in) if (v == null || !Double.isFinite(v) || Math.abs(v) > 2) throw error(400, "descriptor values out of range");
        return List.copyOf(in);
    }

    /** Student: verify the enrollment photo of this attempt (or create the profile on the first camera exam). */
    @Transactional
    public Identity verify(UUID sessionId, UUID actor, List<Double> input, byte[] photo) {
        var s = camera.owned(sessionId, actor, true);
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        if (!CameraProctoringService.active(s, now)) throw error(409, "Attempt is not in progress");
        var existing = checks.findById(sessionId).orElse(null);
        if (existing != null && existing.getOutcome() != CameraIdentityCheck.Outcome.MISMATCH) return dto(existing);
        if (existing != null && existing.getAttempts() >= MAX_ATTEMPTS + 5) throw error(429, "Too many verification attempts");
        var vector = descriptor(input);
        if (photo == null || photo.length == 0) throw error(400, "Photo required");
        CameraProctoringService.jpeg(photo);

        var profile = profiles.findById(s.getUserId()).orElse(null);
        if (profile == null) {
            String path;
            try { path = storage.saveProfilePhoto(s.getUserId(), photo); } catch (IOException e) { throw error(503, "Profile storage unavailable"); }
            profiles.saveAndFlush(FaceProfile.builder().userId(s.getUserId()).descriptor(vector).photoPath(path)
                    .sourceSessionId(sessionId).createdAt(now).build());
            return dto(checks.saveAndFlush(CameraIdentityCheck.builder().sessionId(sessionId).userId(s.getUserId())
                    .outcome(CameraIdentityCheck.Outcome.ENROLLED).threshold(threshold).attempts(0).checkedAt(now).profileCreatedAt(now).build()));
        }
        double d = Math.round(distance(vector, profile.getDescriptor()) * 1000) / 1000.0;
        var check = existing != null ? existing : CameraIdentityCheck.builder().sessionId(sessionId).userId(s.getUserId()).attempts(0).build();
        check.setDistance(d); check.setThreshold(threshold); check.setCheckedAt(now); check.setProfileCreatedAt(profile.getCreatedAt());
        if (d <= threshold) check.setOutcome(CameraIdentityCheck.Outcome.MATCH);
        else {
            check.setOutcome(CameraIdentityCheck.Outcome.MISMATCH);
            check.setAttempts(check.getAttempts() + 1);
            camera.record(s, CameraEvent.Type.PROFILE_MISMATCH, Map.of("distance", d, "threshold", threshold), photo);
        }
        return dto(checks.saveAndFlush(check));
    }

    /** Staff of an exam the student took: the profile photo to compare with this attempt. */
    @Transactional(readOnly = true)
    public byte[] profilePhoto(UUID sessionId, UUID actor, String role) {
        var s = camera.reviewed(sessionId, actor, role);
        var p = profiles.findById(s.getUserId()).orElseThrow(() -> error(404, "No face profile"));
        try { return storage.readSnapshot(p.getPhotoPath()); } catch (IOException e) { throw error(404, "Profile photo missing"); }
    }

    /** Exam owner/manager: forget the student's profile (new look, wrong first photo); the next camera exam re-creates it. */
    @Transactional
    public void reset(UUID sessionId, UUID actor, String role) {
        var s = camera.sessionOf(sessionId);
        camera.review(s.getExamId(), actor, role, RESET_PERMISSIONS);
        profiles.findById(s.getUserId()).ifPresent(p -> {
            profiles.delete(p);
            String path = p.getPhotoPath();
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { try { storage.deleteFile(path); } catch (IOException ignored) { /* an orphan file is harmless */ } }
            });
        });
    }
}
