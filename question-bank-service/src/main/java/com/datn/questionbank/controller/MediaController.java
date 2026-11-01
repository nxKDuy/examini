package com.datn.questionbank.controller;

import com.datn.questionbank.security.QuestionActor;
import com.datn.questionbank.service.MediaService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import java.io.IOException;
import java.time.Duration;
import java.util.UUID;

/** Images and audio attached to questions (contracts/question-bank-api.md, "Question media"). */
@RestController @RequestMapping("/question-media") @RequiredArgsConstructor
public class MediaController {
    private final MediaService media;

    private QuestionActor actor(HttpServletRequest request) {
        if (!(request.getAttribute(QuestionActor.class.getName()) instanceof QuestionActor a) || a.internal())
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Yêu cầu xác thực");
        return a;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public MediaService.Uploaded upload(@RequestPart("file") MultipartFile file, HttpServletRequest request) {
        var a = actor(request);
        try { return media.upload(file.getBytes(), file.getOriginalFilename(), a); }
        catch (IOException e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Không đọc được file"); }
    }

    /** Any signed-in user with the id (students receive ids only through the exam they take). Immutable content. */
    @GetMapping("/{id}")
    public ResponseEntity<byte[]> read(@PathVariable UUID id, HttpServletRequest request) {
        actor(request);
        var f = media.read(id);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(f.contentType()))
                .cacheControl(CacheControl.maxAge(Duration.ofDays(7)).cachePrivate()).header("X-Content-Type-Options", "nosniff").body(f.bytes());
    }
}
