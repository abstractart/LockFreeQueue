package org.example;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.EmptyStackException;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExchangerEliminationStackTest {

    @Test
    void lifoOrder() {
        ExchangerEliminationStack s = new ExchangerEliminationStack();
        assertTrue(s.isEmpty());
        s.push(1); s.push(2); s.push(3);
        assertEquals(false, s.isEmpty());
        assertEquals(3, s.pop());
        assertEquals(2, s.pop());
        assertEquals(1, s.pop());
        assertTrue(s.isEmpty());
        assertThrows(EmptyStackException.class, s::pop);
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void concurrentPushAndPopNoLoss() throws InterruptedException {
        ExchangerEliminationStack stack = new ExchangerEliminationStack();
        int producers = 4;
        int consumers = 4;
        int perProducer = 5_000;
        int total = producers * perProducer;
        ConcurrentLinkedQueue<Integer> collected = new ConcurrentLinkedQueue<>();
        AtomicInteger remaining = new AtomicInteger(total);

        ExecutorService pool = Executors.newFixedThreadPool(producers + consumers);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(producers + consumers);

        try {
            for (int p = 0; p < producers; p++) {
                final int producerId = p;
                pool.submit(() -> {
                    try {
                        start.await();
                        int base = producerId * perProducer;
                        for (int i = 0; i < perProducer; i++) stack.push(base + i);
                    } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    finally { done.countDown(); }
                });
            }
            for (int c = 0; c < consumers; c++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        while (remaining.get() > 0) {
                            try {
                                collected.add(stack.pop());
                                remaining.decrementAndGet();
                            } catch (EmptyStackException ignore) { Thread.yield(); }
                        }
                    } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    finally { done.countDown(); }
                });
            }
            start.countDown();
            assertTrue(done.await(45, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }

        assertEquals(total, collected.size());
        assertEquals(total, new HashSet<>(collected).size());
        assertThrows(EmptyStackException.class, stack::pop);
    }
}
