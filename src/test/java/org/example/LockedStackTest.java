package org.example;

import org.junit.jupiter.api.DisplayName;
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

class LockedStackTest {

    @Test
    @DisplayName("pop() на новом стеке бросает EmptyStackException")
    void popOnEmptyStackThrows() {
        LockedStack stack = new LockedStack();
        assertThrows(EmptyStackException.class, stack::pop);
    }

    @Test
    @DisplayName("isEmpty() отражает состояние до и после push/pop")
    void isEmptyReflectsState() {
        LockedStack stack = new LockedStack();
        assertTrue(stack.isEmpty());

        stack.push(1);
        assertFalse(stack.isEmpty());

        stack.pop();
        assertTrue(stack.isEmpty());
    }

    @Test
    @DisplayName("LIFO: элементы извлекаются в обратном порядке добавления")
    void lifoOrder() {
        LockedStack stack = new LockedStack();
        stack.push(1);
        stack.push(2);
        stack.push(3);

        assertEquals(3, stack.pop());
        assertEquals(2, stack.pop());
        assertEquals(1, stack.pop());
        assertThrows(EmptyStackException.class, stack::pop);
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisplayName("Конкурентные продюсеры + потребители: ни один элемент не потерян и не задублирован")
    void concurrentProducersAndConsumers() throws InterruptedException {
        LockedStack stack = new LockedStack();
        int producers = 4;
        int consumers = 4;
        int perProducer = 5_000;
        int total = producers * perProducer;

        ExecutorService pool = Executors.newFixedThreadPool(producers + consumers);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch producersDone = new CountDownLatch(producers);
        CountDownLatch consumersDone = new CountDownLatch(consumers);
        ConcurrentLinkedQueue<Integer> collected = new ConcurrentLinkedQueue<>();
        AtomicInteger remaining = new AtomicInteger(total);

        try {
            for (int p = 0; p < producers; p++) {
                final int producerId = p;
                pool.submit(() -> {
                    try {
                        start.await();
                        int base = producerId * perProducer;
                        for (int i = 0; i < perProducer; i++) {
                            stack.push(base + i);
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        producersDone.countDown();
                    }
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
                            } catch (EmptyStackException ignore) {
                                Thread.yield();
                            }
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        consumersDone.countDown();
                    }
                });
            }

            start.countDown();
            assertTrue(producersDone.await(20, TimeUnit.SECONDS));
            assertTrue(consumersDone.await(20, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }

        Set<Integer> seen = new HashSet<>(collected);
        assertEquals(total, collected.size(), "Все положенные элементы должны быть извлечены");
        assertEquals(total, seen.size(), "Не должно быть дубликатов");
        assertTrue(stack.isEmpty(), "После работы потребителей стек должен быть пуст");
    }
}
