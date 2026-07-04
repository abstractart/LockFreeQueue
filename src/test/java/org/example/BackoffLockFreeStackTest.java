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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackoffLockFreeStackTest {

    @Test
    void lifoOrderAndIsEmpty() {
        BackoffLockFreeStack s = new BackoffLockFreeStack();
        assertTrue(s.isEmpty());
        s.push(1); s.push(2); s.push(3);
        assertFalse(s.isEmpty());
        assertEquals(3, s.pop());
        assertEquals(2, s.pop());
        assertEquals(1, s.pop());
        assertTrue(s.isEmpty());
        assertThrows(EmptyStackException.class, s::pop);
    }

    // Concurrent producers only: заставляет push CAS фейлить регулярно,
    // покрывает backoff-ветку в push. Нет consumer'ов → CAS-shootout только
    // между push'ами.
    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void concurrentPushOnly() throws InterruptedException {
        BackoffLockFreeStack stack = new BackoffLockFreeStack();
        int producers = 8;
        int perProducer = 5_000;
        int total = producers * perProducer;

        ExecutorService pool = Executors.newFixedThreadPool(producers);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(producers);

        try {
            for (int p = 0; p < producers; p++) {
                final int id = p;
                pool.submit(() -> {
                    try {
                        start.await();
                        int base = id * perProducer;
                        for (int i = 0; i < perProducer; i++) stack.push(base + i);
                    } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    finally { done.countDown(); }
                });
            }
            start.countDown();
            assertTrue(done.await(20, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }

        Set<Integer> seen = new HashSet<>(total * 2);
        for (int i = 0; i < total; i++) seen.add(stack.pop());
        assertEquals(total, seen.size());
        assertThrows(EmptyStackException.class, stack::pop);
    }

    // Concurrent consumer only: аналогично, покрывает backoff-ветку в pop.
    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void concurrentPopOnly() throws InterruptedException {
        BackoffLockFreeStack stack = new BackoffLockFreeStack();
        int total = 40_000;
        for (int i = 0; i < total; i++) stack.push(i);

        ConcurrentLinkedQueue<Integer> collected = new ConcurrentLinkedQueue<>();
        int consumers = 8;
        ExecutorService pool = Executors.newFixedThreadPool(consumers);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(consumers);

        try {
            for (int c = 0; c < consumers; c++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        while (true) {
                            try {
                                collected.add(stack.pop());
                            } catch (EmptyStackException e) {
                                return;
                            }
                        }
                    } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    finally { done.countDown(); }
                });
            }
            start.countDown();
            assertTrue(done.await(20, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }

        assertEquals(total, collected.size());
        assertEquals(total, new HashSet<>(collected).size());
    }

    // Mixed load — на всякий случай, чтобы гонки на разных типах операций
    // тоже покрывались.
    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void concurrentPushAndPop() throws InterruptedException {
        BackoffLockFreeStack stack = new BackoffLockFreeStack();
        int producers = 4;
        int consumers = 4;
        int perProducer = 10_000;
        int total = producers * perProducer;
        ConcurrentLinkedQueue<Integer> collected = new ConcurrentLinkedQueue<>();
        AtomicInteger remaining = new AtomicInteger(total);

        ExecutorService pool = Executors.newFixedThreadPool(producers + consumers);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(producers + consumers);

        try {
            for (int p = 0; p < producers; p++) {
                final int id = p;
                pool.submit(() -> {
                    try {
                        start.await();
                        int base = id * perProducer;
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
