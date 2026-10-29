package com.datn.examsession.controller;

import com.datn.examsession.dto.CameraDto.*;
import com.datn.examsession.service.CameraProctoringService;
import com.datn.examsession.service.FaceIdentityService;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import java.io.IOException;
import java.util.List;
import java.util.UUID;

/** Camera proctoring (contracts/camera-proctoring.md). Student routes need the session owner; review routes need exam staff. */
@RestController @RequestMapping("/sessions") @RequiredArgsConstructor
public class CameraController {
    private final CameraProctoringService service;
    private final ObjectMapper json;
    private final FaceIdentityService identity;

    /** multipart: "identity" = {"descriptor":[128 numbers]}, "photo" = JPEG of the enrollment frame. */
    @PostMapping(value = "/{id}/camera/identity", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Identity verify(@PathVariable UUID id, @RequestAttribute UUID actor, @RequestPart("identity") String body,
                           @RequestPart("photo") MultipartFile photo) {
        if (body.length() > 8192) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Identity too large");
        IdentityRequest input;
        try {
            input = json.readerFor(IdentityRequest.class).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readValue(body);
        } catch (IOException e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid identity JSON"); }
        byte[] image;
        try { image = photo.getBytes(); } catch (IOException e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unreadable photo"); }
        return identity.verify(id, actor, input.descriptor(), image);
    }

    @GetMapping(value = "/{id}/camera/profile-photo", produces = MediaType.IMAGE_JPEG_VALUE)
    public ResponseEntity<byte[]> profilePhoto(@PathVariable UUID id, @RequestAttribute UUID actor, @RequestAttribute String actorRole) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.IMAGE_JPEG).body(identity.profilePhoto(id, actor, actorRole));
    }

    @DeleteMapping("/{id}/camera/profile")
    public ResponseEntity<Void> resetProfile(@PathVariable UUID id, @RequestAttribute UUID actor, @RequestAttribute String actorRole) {
        identity.reset(id, actor, actorRole);
        return ResponseEntity.noContent().build();
    }

    /** multipart: "event" = JSON Report, "snapshot" = JPEG (required except CAMERA_OFF/CAMERA_ON). */
    @PostMapping(value = "/{id}/camera/events", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Receipt report(@PathVariable UUID id, @RequestAttribute UUID actor, @RequestPart("event") String event,
                          @RequestPart(value = "snapshot", required = false) MultipartFile snapshot) {
        if (event.length() > 2048) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Event too large");
        Report input;
        try {
            input = json.readerFor(Report.class).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readValue(event);
        } catch (IOException e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid camera event JSON"); }
        byte[] image;
        try { image = snapshot == null ? null : snapshot.getBytes(); } catch (IOException e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unreadable snapshot"); }
        return service.report(id, actor, input, image);
    }

    /** Raw body = one MediaRecorder chunk; Content-Type video/webm or video/mp4. */
    @PostMapping("/{id}/camera/video")
    public ChunkReceipt chunk(@PathVariable UUID id, @RequestAttribute UUID actor, @RequestParam int seq, HttpServletRequest request) throws IOException {
        byte[] data = request.getInputStream().readNBytes(4 * 1024 * 1024 + 1);
        return service.chunk(id, actor, seq, request.getContentType(), data);
    }

    @GetMapping("/{id}/camera")
    public SessionCamera session(@PathVariable UUID id, @RequestAttribute UUID actor, @RequestAttribute String actorRole) {
        return service.session(id, actor, actorRole);
    }

    @GetMapping("/exam/{examId}/camera")
    public List<SessionSummary> exam(@PathVariable UUID examId, @RequestAttribute UUID actor, @RequestAttribute String actorRole) {
        return service.exam(examId, actor, actorRole);
    }

    @GetMapping(value = "/{id}/camera/events/{eventId}/snapshot", produces = MediaType.IMAGE_JPEG_VALUE)
    public ResponseEntity<byte[]> snapshot(@PathVariable UUID id, @PathVariable UUID eventId, @RequestAttribute UUID actor, @RequestAttribute String actorRole) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.IMAGE_JPEG).body(service.snapshot(id, eventId, actor, actorRole));
    }

    @GetMapping("/{id}/camera/video")
    public void video(@PathVariable UUID id, @RequestAttribute UUID actor, @RequestAttribute String actorRole, HttpServletResponse response) throws IOException {
        var v = service.video(id, actor, actorRole);
        response.setContentType(v.mimeType());
        response.setContentLengthLong(v.length());
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        service.stream(v, response.getOutputStream());
    }
}
