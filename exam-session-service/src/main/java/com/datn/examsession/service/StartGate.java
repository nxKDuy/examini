package com.datn.examsession.service;

import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * Queues concurrent Starts of the same exam in memory, before any database transaction is opened.
 *
 * <p>Start holds the exam's advisory lock while it calls Exam over HTTP, and Exam calls back into this service's
 * internal endpoints. When a class pressed Start together, every waiting request sat on the advisory lock holding a
 * pooled connection, the call-back found the pool empty and all of them failed after 30 s. Waiting here costs no
 * connection, so at most one Start per exam (per instance) holds one; the advisory lock still guards correctness
 * across instances. Fair locks keep first-come, first-served order.
 */
@Component
public class StartGate {
    private final ReentrantLock[] stripes = new ReentrantLock[256];

    public StartGate() {
        for (int i = 0; i < stripes.length; i++) stripes[i] = new ReentrantLock(true);
    }

    public <T> T run(UUID examId, Supplier<T> work) {
        var lock = stripes[Math.floorMod(examId.hashCode(), stripes.length)];
        lock.lock();
        try {
            return work.get();
        } finally {
            lock.unlock();
        }
    }
}
