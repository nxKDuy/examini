# Camera proctoring (2026-10-29)

Provider: `exam-session-service` (`CameraController`, `CameraProctoringService`, migration `V5`). Consumer: APP.
Evidence for exam staff only, never an automatic verdict: camera events do not count toward the browser-integrity
budget and never submit or invalidate an attempt. Staff act with the existing integrity lock / grant controls.

## Policy

`IntegrityPolicy.requireCamera` (boolean, additive; absent = false) in both exam-service and exam-session-service.
Frozen into the attempt at start like the other monitors. New exams default to false (needs a device camera).

## Browser (APP)

- Gate before the first question: camera permission → face models (`@vladmandic/face-api` 1.7.15, tiny detector +
  tiny 68 landmarks + recognition, served from `/models/face`) → enrollment photo with exactly one face.
- Every 1.5 s the largest face is compared with the enrollment descriptor (Euclidean, threshold 0.55); head pose is
  measured relative to the enrollment pose. A condition is reported only when sustained:
  `NO_FACE` 3 s, `MULTIPLE_FACES` 1.5 s, `FACE_MISMATCH` 4.5 s, `LOOKING_AWAY` (|yaw| > 32° or |pitch| > 28°) 5 s;
  the same violation at most every 20 s. The student sees a live warning at once.
- Recording: `MediaRecorder` 320×240, 15 fps, 250 kbit/s, WebM (VP9/VP8) or MP4 on Safari, 10 s chunks uploaded in
  order with retries. Camera lost → `CAMERA_OFF`, questions hidden until it is back (`CAMERA_ON`, new segment).

## Routes

| Route | Who | Request | Response |
| --- | --- | --- | --- |
| `POST /sessions/{id}/camera/events` | session owner, attempt IN_PROGRESS, policy on | multipart `event` (JSON `{clientEventId, type, occurredAt?, detail?}`) + `snapshot` (JPEG ≤ 300 KB; required except `CAMERA_OFF`/`CAMERA_ON`) | `{event, duplicate}` |
| `POST /sessions/{id}/camera/video?seq=N` | session owner; until 2 min after the attempt ended | raw body ≤ 4 MB, `Content-Type: video/webm` or `video/mp4` | `{seq, stored, chunks, bytes}` |
| `GET /sessions/{id}/camera` | OWNER / MANAGER / PROCTOR | — | `{sessionId, userId, reference, recording, events[]}` |
| `GET /sessions/exam/{examId}/camera` | OWNER / MANAGER / PROCTOR | — | per attempt `{counts, violations, lastEventAt, recording}`, most violations first |
| `GET /sessions/{id}/camera/events/{eventId}/snapshot` | staff | — | `image/jpeg` |
| `GET /sessions/{id}/camera/video` | staff | — | chunks concatenated in seq order |

`type` ∈ `REFERENCE, NO_FACE, MULTIPLE_FACES, FACE_MISMATCH, LOOKING_AWAY, CAMERA_OFF, CAMERA_ON`. `detail` accepts only
numeric `faces, distance, threshold, yaw, pitch, seconds, score` (≤ 8 fields). Unknown JSON fields → 400. Same
`clientEventId` → idempotent (different type → 409). Limits: 30 events/min, 2000/attempt (429, answering unaffected),
600 MB recording per attempt, a stored chunk `seq` is never rewritten. Each event carries a Vietnamese `reason`.

## Storage and privacy

Files on the `camera_data` volume (`CAMERA_STORAGE_DIR`, default `/data/camera`):
`{examId}/{sessionId}/snapshots/{eventId}.jpg`, `{examId}/{sessionId}/video/{seq}.webm`. Paths are built from server
UUIDs only. Students see a notice before the camera starts; only exam staff can read the evidence. A retention policy
(deleting evidence N days after the exam) is not implemented yet.

## Retention (auto-delete)

- `exam.camera.retention-days` (`CAMERA_RETENTION_DAYS`, default 30; 0 keeps media forever). A daily job
  (`exam.camera.retention-cron`, default 03:30 Asia/Ho_Chi_Minh) deletes the snapshots and video chunks of finished
  attempts whose `expiresAt` is older than the retention period, then records the purge in `camera_media_purges`.
- The violation log (type, time, measurements) is kept; events get `hasSnapshot=false`, the recording reports 0 bytes.
- Staff views carry `retention: { days, deleteAt, purgedAt }` on `SessionCamera` and `SessionSummary`.
- After a purge, `GET …/camera/video` and `GET …/camera/events/{id}/snapshot` return **410 Gone**.

## Face verification at exam entry

Only for attempts whose policy has `requireCamera`. The browser sends the enrollment descriptor before the
`REFERENCE` event:

- `POST /sessions/{id}/camera/identity` (owner, attempt in progress), multipart `identity` = `{"descriptor":[128 numbers]}`,
  `photo` = JPEG → `Identity { outcome: ENROLLED|MATCH|MISMATCH, distance, threshold, attempts, maxAttempts, mayContinue, checkedAt, profileCreatedAt }`.
  - No profile yet → the profile is created from this photo (`ENROLLED`).
  - Euclidean distance ≤ `exam.camera.profile-threshold` (default 0.6) → `MATCH`.
  - Otherwise `MISMATCH`, `attempts` + 1 and a server-recorded `PROFILE_MISMATCH` event with the photo.
    `mayContinue` turns true after 3 mismatches: the attempt continues and is flagged, never blocked or failed.
  - A settled `ENROLLED`/`MATCH` is returned as is (no re-enrollment within the attempt).
- `GET /sessions/{id}/camera/profile-photo` (exam staff) — the student's profile photo.
- `DELETE /sessions/{id}/camera/profile` (exam OWNER/MANAGER) — forget the profile; the next camera exam re-creates it.
- `SessionCamera` gains `identity` and `profile` (photo available); `SessionSummary` gains `identity` (outcome).
- Clients cannot report `PROFILE_MISMATCH` through `/camera/events` (400).
- Descriptors are computed in the browser, so this is a deterrent plus evidence for proctors, not proof of identity.
