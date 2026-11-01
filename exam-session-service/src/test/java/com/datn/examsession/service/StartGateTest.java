package com.datn.examsession.service;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class StartGateTest {
    final StartGate gate = new StartGate();

    @Test void startsOfOneExamRunOneAtATime() throws Exception {
        UUID exam = UUID.randomUUID();
        AtomicInteger inside = new AtomicInteger(), maxInside = new AtomicInteger(), done = new AtomicInteger();
        var pool = Executors.newFixedThreadPool(16);
        for (int i = 0; i < 40; i++) pool.submit(() -> gate.run(exam, () -> {
            maxInside.accumulateAndGet(inside.incrementAndGet(), Math::max);
            try { Thread.sleep(2); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            inside.decrementAndGet();
            return done.incrementAndGet();
        }));
        pool.shutdown();
        assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS));
        assertEquals(40, done.get());
        assertEquals(1, maxInside.get());
    }

    @Test void differentExamsDoNotWaitForEachOther() throws Exception {
        UUID a = UUID.randomUUID(), b;
        do { b = UUID.randomUUID(); } while (Math.floorMod(a.hashCode(), 256) == Math.floorMod(b.hashCode(), 256));
        CountDownLatch aInside = new CountDownLatch(1), release = new CountDownLatch(1);
        var t = new Thread(() -> gate.run(a, () -> { aInside.countDown(); try { release.await(); } catch (InterruptedException ignored) { } return 0; }));
        t.start();
        assertTrue(aInside.await(5, TimeUnit.SECONDS));
        assertEquals("ran", gate.run(b, () -> "ran"));   // would block forever if exams shared one lock
        release.countDown();
        t.join(5000);
    }

    @Test void failuresReleaseTheGate() {
        UUID exam = UUID.randomUUID();
        assertThrows(IllegalStateException.class, () -> gate.run(exam, () -> { throw new IllegalStateException("boom"); }));
        assertEquals(1, gate.run(exam, () -> 1));
    }
}
