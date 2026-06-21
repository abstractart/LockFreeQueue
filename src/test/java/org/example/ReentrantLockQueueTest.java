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

class ReentrantLockQueueTest {

    @Test
    @DisplayName("pop() на новой очереди бросает EmptyStackException")
    void popOnEmptyQueueThrows() {
        ReentrantLockQueue queue = new ReentrantLockQueue();
        assertThrows(EmptyStackException.class, queue::pop);
    }

    @Test
    @DisplayName("isEmpty() отражает состояние до и после push/pop")
    void isEmptyReflectsState() {
        ReentrantLockQueue queue = new ReentrantLockQueue();
        assertTrue(queue.isEmpty());

        queue.push(1);
        assertFalse(queue.isEmpty());

        queue.pop();
        assertTrue(queue.isEmpty());
    }

    @Test
    @DisplayName("FIFO: элементы извлекаются в порядке добавления")
    void fifoOrder() {
        ReentrantLockQueue queue = new ReentrantLockQueue();
        queue.push(1);
        queue.push(2);
        queue.push(3);

        assertEquals(1, queue.pop());
        assertEquals(2, queue.pop());
        assertEquals(3, queue.pop());
        assertThrows(EmptyStackException.class, queue::pop);
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisplayName("Конкурентные продюсеры + потребители: ни один элемент не потерян и не задублирован")
    void concurrentProducersAndConsumers() throws InterruptedException {
        ReentrantLockQueue queue = new ReentrantLockQueue();
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
                            queue.push(base + i);
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
                                collected.add(queue.pop());
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
        assertTrue(queue.isEmpty(), "После работы потребителей очередь должна быть пуста");
    }
}
