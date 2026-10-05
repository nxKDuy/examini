package com.datn.analytics.ingest;

import com.datn.analytics.config.GradingEventsConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class GradingResultListener {
    private final ResultProjector projector;

    @RabbitListener(queues = GradingEventsConfig.QUEUE)
    public void onResultRecorded(GradingResultEvent event) {
        try {
            if (!projector.apply(event))
                log.debug("Ignored duplicate/stale grading result {} rev {}", event.resultId(), event.resultRevision());
        } catch (ResultProjector.InvalidEventException invalid) {
            // Contract violation: retrying cannot help; dead-letter it for the owner to inspect.
            log.warn("Rejected grading result event: {}", invalid.getMessage());
            throw new AmqpRejectAndDontRequeueException(invalid.getMessage(), invalid);
        }
        // Infrastructure failures escape: retried, then dead-lettered.
    }
}
