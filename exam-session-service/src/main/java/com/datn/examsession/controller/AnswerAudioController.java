package com.datn.examsession.controller;

import com.datn.examsession.service.AnswerAudioService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;
import java.util.UUID;

/** Voice answers of SPEAKING questions (contracts/exam-session-api.md, "Speaking answers"). */
@RestController @RequestMapping("/sessions") @RequiredArgsConstructor
public class AnswerAudioController {
    private final AnswerAudioService audio;

    /** Raw body = the recording; Content-Type audio/webm|ogg|mp4|mpeg; seconds = recorded length. */
    @PostMapping("/{id}/answers/{questionId}/audio")
    public AnswerAudioService.Saved upload(@PathVariable UUID id, @PathVariable UUID questionId, @RequestParam int seconds,
                                           @RequestAttribute UUID actor, HttpServletRequest request) throws IOException {
        byte[] data = request.getInputStream().readNBytes(AnswerAudioService.MAX_BYTES + 1);
        return audio.upload(id, actor, questionId, request.getContentType(), data, seconds);
    }

    @GetMapping("/{id}/answers/{questionId}/audio")
    public ResponseEntity<byte[]> read(@PathVariable UUID id, @PathVariable UUID questionId, @RequestAttribute UUID actor, @RequestAttribute String actorRole) {
        var a = audio.read(id, actor, actorRole, questionId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.parseMediaType(a.type())).body(a.bytes());
    }
}
