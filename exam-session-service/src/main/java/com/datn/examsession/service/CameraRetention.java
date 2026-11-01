package com.datn.examsession.service;

import com.datn.examsession.entity.CameraMediaPurge;
import com.datn.examsession.repository.CameraMediaPurgeRepository;
import com.datn.examsession.repository.CameraRecordingRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import java.io.IOException;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Deletes camera snapshots and video {@code retention-days} after an attempt's deadline (0 keeps them forever).
 * Files go first, then the rows are marked, so a crash in between only repeats an idempotent delete.
 */
@Component @Slf4j
public class CameraRetention {
    static final int BATCH = 200;
    private final CameraMediaPurgeRepository purges;
    private final CameraRecordingRepository recordings;
    private final CameraStorage storage;
    private final TransactionTemplate tx;
    private final int days;

    public CameraRetention(CameraMediaPurgeRepository purges, CameraRecordingRepository recordings, CameraStorage storage,
                           TransactionTemplate tx, @Value("${exam.camera.retention-days:30}") int days) {
        this.purges = purges; this.recordings = recordings; this.storage = storage; this.tx = tx;
        this.days = Math.max(0, days);
    }

    public int days() { return days; }
    public boolean enabled() { return days > 0; }
    /** When the media of an attempt with this deadline will be deleted (null when kept forever). */
    public Instant deleteAt(Instant deadline) { return enabled() && deadline != null ? deadline.plus(Duration.ofDays(days)) : null; }

    @Scheduled(cron = "${exam.camera.retention-cron:0 30 3 * * *}", zone = "Asia/Ho_Chi_Minh")
    public void scheduled() { purge(Instant.now()); }

    /** @return number of attempts whose media were deleted */
    public int purge(Instant now) {
        if (!enabled()) return 0;
        Instant cutoff = now.minus(Duration.ofDays(days));
        int total = 0;
        for (int round = 0; round < 50; round++) {
            var due = purges.due(cutoff, BATCH);
            if (due.isEmpty()) break;
            int done = 0;
            for (var d : due) if (purgeOne(d.getSessionId(), d.getExamId(), now)) done++;
            total += done;
            if (done == 0 || due.size() < BATCH) break;
        }
        if (total > 0) log.info("Camera retention: deleted media of {} attempt(s) older than {} days", total, days);
        return total;
    }

    private boolean purgeOne(UUID session, UUID exam, Instant now) {
        CameraStorage.Deleted deleted;
        try { deleted = storage.deleteSession(exam, session); }
        catch (IOException e) { log.warn("Camera retention: could not delete files of session {}: {}", session, e.toString()); return false; }
        tx.executeWithoutResult(status -> {
            purges.clearSnapshots(session);
            recordings.findById(session).ifPresent(r -> { r.setChunks(0); r.setBytes(0); recordings.save(r); });
            purges.save(CameraMediaPurge.builder().sessionId(session).examId(exam).purgedAt(now.truncatedTo(ChronoUnit.MICROS))
                    .files(deleted.files()).bytes(deleted.bytes()).build());
        });
        return true;
    }
}
