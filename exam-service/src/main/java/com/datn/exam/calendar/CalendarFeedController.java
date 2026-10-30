package com.datn.exam.calendar;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Calendar sync. {@code GET /exams/calendar/feed/{token}.ics} is the only unauthenticated route: calendar apps cannot
 * send a login, so the long random token is the credential (read-only, revocable, stored hashed).
 */
@RestController
public class CalendarFeedController {
    private static final MediaType ICS = new MediaType("text", "calendar", StandardCharsets.UTF_8);
    private final CalendarFeedService feeds;

    public CalendarFeedController(CalendarFeedService feeds) { this.feeds = feeds; }

    @GetMapping("/exams/calendar/feed")
    public CalendarFeedService.FeedStatus status(@RequestAttribute UUID actor) { return feeds.status(actor); }

    @PostMapping("/exams/calendar/feed")
    @ResponseStatus(HttpStatus.CREATED)
    public CalendarFeedService.FeedLink create(@RequestAttribute UUID actor, @RequestAttribute String actorRole) { return feeds.create(actor, actorRole); }

    @DeleteMapping("/exams/calendar/feed")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@RequestAttribute UUID actor) { feeds.revoke(actor); }

    @GetMapping("/exams/calendar/feed/{token}.ics")
    public ResponseEntity<String> feed(@PathVariable String token) {
        return feeds.feed(token).map(body -> ResponseEntity.ok().contentType(ICS)
                        .cacheControl(CacheControl.noCache())
                        .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"examini.ics\"").body(body))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).build());
    }

    @GetMapping("/exams/{id}/calendar.ics")
    public ResponseEntity<String> exam(@PathVariable UUID id, @RequestAttribute UUID actor) {
        return ResponseEntity.ok().contentType(ICS)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"ky-thi-" + id.toString().substring(0, 8) + ".ics\"")
                .body(feeds.exam(id, actor));
    }
}
