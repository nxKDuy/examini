package com.datn.grading.service;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** One-shot replay of grading.result.published for historical publications; off unless grading.events.backfill-published=true. */
@Component @RequiredArgsConstructor
@ConditionalOnProperty(name="grading.events.backfill-published",havingValue="true")
public class PublishedEventBackfill {
    private final GradingService grading;
    @EventListener(ApplicationReadyEvent.class) public void run(){
        org.slf4j.LoggerFactory.getLogger(PublishedEventBackfill.class).info("grading.result.published backfill enqueued {} events",grading.backfillPublishedEvents());
    }
}
