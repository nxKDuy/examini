package com.datn.examsession.service;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** One-shot replay of exam.attempt.started for historical sessions; off unless exam.events.backfill-started=true. */
@Component @RequiredArgsConstructor
@ConditionalOnProperty(name="exam.events.backfill-started",havingValue="true")
public class StartedEventBackfill {
    private final SessionService sessions;
    @EventListener(ApplicationReadyEvent.class) public void run(){
        org.slf4j.LoggerFactory.getLogger(StartedEventBackfill.class).info("exam.attempt.started backfill enqueued {} events",sessions.backfillStartedEvents());
    }
}
