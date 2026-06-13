package org.example;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.EmptyStackException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LockFreeQueueTest {

    @Test
    @DisplayName("pop() на новой очереди бросает EmptyStackException")
    void popOnEmptyQueueThrows() {
        LockFreeQueue queue = new LockFreeQueue();
        assertThrows(EmptyStackException.class, queue::pop);
    }

    @Test
    @DisplayName("push() + pop() возвращает положенное значение")
    void pushThenPopReturnsValue() {
        LockFreeQueue queue = new LockFreeQueue();
        queue.push(42);
        assertEquals(42, queue.pop());
    }

    @Test
    @DisplayName("FIFO: элементы извлекаются в порядке добавления")
    void fifoOrder() {
        LockFreeQueue queue = new LockFreeQueue();
        queue.push(1);
        queue.push(2);
        queue.push(3);

        assertEquals(1, queue.pop());
        assertEquals(2, queue.pop());
        assertEquals(3, queue.pop());
    }

    @Test
    @DisplayName("pop() после опустошения очереди бросает EmptyStackException")
    void popAfterDrainingThrows() {
        LockFreeQueue queue = new LockFreeQueue();
        queue.push(10);
        queue.pop();
        assertThrows(EmptyStackException.class, queue::pop);
    }

    @Test
    @DisplayName("Поддерживаются отрицательные и нулевые значения")
    void supportsZeroAndNegativeValues() {
        LockFreeQueue queue = new LockFreeQueue();
        queue.push(0);
        queue.push(-1);
        queue.push(Integer.MIN_VALUE);
        queue.push(Integer.MAX_VALUE);

        assertEquals(0, queue.pop());
        assertEquals(-1, queue.pop());
        assertEquals(Integer.MIN_VALUE, queue.pop());
        assertEquals(Integer.MAX_VALUE, queue.pop());
    }

    @Test
    @DisplayName("Допускаются дубликаты")
    void allowsDuplicates() {
        LockFreeQueue queue = new LockFreeQueue();
        queue.push(7);
        queue.push(7);
        queue.push(7);

        assertEquals(7, queue.pop());
        assertEquals(7, queue.pop());
        assertEquals(7, queue.pop());
        assertThrows(EmptyStackException.class, queue::pop);
    }

    @Test
    @DisplayName("Чередование push/pop сохраняет FIFO")
    void interleavedPushAndPop() {
        LockFreeQueue queue = new LockFreeQueue();
        queue.push(1);
        queue.push(2);
        assertEquals(1, queue.pop());

        queue.push(3);
        assertEquals(2, queue.pop());
        assertEquals(3, queue.pop());

        assertThrows(EmptyStackException.class, queue::pop);
    }

    @Test
    @DisplayName("push после полного опустошения очереди работает корректно")
    void pushAfterFullyDrainingWorks() {
        LockFreeQueue queue = new LockFreeQueue();
        queue.push(1);
        assertEquals(1, queue.pop());

        queue.push(2);
        assertEquals(2, queue.pop(), "После опустошения очередь должна снова принимать элементы (выявляет баг с устаревшим tail)");
    }

    @Test
    @DisplayName("Большое количество элементов сохраняет порядок")
    void largeNumberOfElementsPreservesOrder() {
        LockFreeQueue queue = new LockFreeQueue();
        int n = 10_000;
        for (int i = 0; i < n; i++) {
            queue.push(i);
        }
        for (int i = 0; i < n; i++) {
            assertEquals(i, queue.pop());
        }
        assertThrows(EmptyStackException.class, queue::pop);
    }

    @Test
    @DisplayName("Несколько циклов опустошение/наполнение сохраняют FIFO")
    void multipleDrainAndRefillCycles() {
        LockFreeQueue queue = new LockFreeQueue();

        for (int cycle = 0; cycle < 3; cycle++) {
            queue.push(cycle * 10 + 1);
            queue.push(cycle * 10 + 2);

            assertEquals(cycle * 10 + 1, queue.pop());
            assertEquals(cycle * 10 + 2, queue.pop());
            assertThrows(EmptyStackException.class, queue::pop);
        }
    }

    @Test
    @DisplayName("Состояние очереди не меняется при бросании исключения из pop()")
    void failedPopDoesNotCorruptState() {
        LockFreeQueue queue = new LockFreeQueue();
        assertThrows(EmptyStackException.class, queue::pop);

        queue.push(5);
        assertEquals(5, queue.pop());
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisplayName("Конкурентный push: N продюсеров кладут уникальные значения — все попадают в очередь без потерь и дублей")
    void concurrentPushPreservesAllElements() throws InterruptedException {
        LockFreeQueue queue = new LockFreeQueue();
        int producers = 8;
        int perProducer = 5_000;
        int total = producers * perProducer;

        ExecutorService pool = Executors.newFixedThreadPool(producers);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(producers);

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
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertTrue(done.await(20, TimeUnit.SECONDS), "Продюсеры не завершились вовремя");
        } finally {
            pool.shutdownNow();
        }

        Set<Integer> seen = new HashSet<>(total * 2);
        for (int i = 0; i < total; i++) {
            seen.add(queue.pop());
        }
        assertThrows(EmptyStackException.class, queue::pop, "После извлечения всех элементов очередь должна быть пуста");
        assertEquals(total, seen.size(), "Все уникальные значения должны присутствовать ровно один раз");
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisplayName("Конкурентный pop: N потребителей разбирают предзаполненную очередь без потерь и дублей")
    void concurrentPopRetrievesAllElementsExactlyOnce() throws InterruptedException {
        LockFreeQueue queue = new LockFreeQueue();
        int total = 40_000;
        for (int i = 0; i < total; i++) {
            queue.push(i);
        }

        int consumers = 8;
        ExecutorService pool = Executors.newFixedThreadPool(consumers);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(consumers);
        ConcurrentLinkedQueue<Integer> collected = new ConcurrentLinkedQueue<>();

        try {
            for (int c = 0; c < consumers; c++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        while (true) {
                            try {
                                collected.add(queue.pop());
                            } catch (EmptyStackException e) {
                                return;
                            }
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertTrue(done.await(20, TimeUnit.SECONDS), "Потребители не завершились вовремя");
        } finally {
            pool.shutdownNow();
        }

        Set<Integer> seen = new HashSet<>(collected);
        assertEquals(total, collected.size(), "Количество извлечённых элементов должно совпадать с количеством положенных");
        assertEquals(total, seen.size(), "Не должно быть дубликатов");
        assertThrows(EmptyStackException.class, queue::pop);
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("Конкурентные продюсеры + потребители: ни один элемент не потерян и не задублирован")
    void concurrentProducersAndConsumers() throws InterruptedException {
        LockFreeQueue queue = new LockFreeQueue();
        int producers = 4;
        int consumers = 4;
        int perProducer = 10_000;
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
            assertTrue(producersDone.await(30, TimeUnit.SECONDS), "Продюсеры не завершились вовремя");
            assertTrue(consumersDone.await(30, TimeUnit.SECONDS), "Потребители не завершились вовремя");
        } finally {
            pool.shutdownNow();
        }

        Set<Integer> seen = new HashSet<>(collected);
        assertEquals(total, collected.size(), "Все положенные элементы должны быть извлечены");
        assertEquals(total, seen.size(), "Не должно быть дубликатов");
        assertThrows(EmptyStackException.class, queue::pop, "После работы потребителей очередь должна быть пуста");
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisplayName("Конкурентный push сохраняет FIFO внутри одного потока-продюсера")
    void concurrentPushPreservesPerProducerFifo() throws InterruptedException {
        LockFreeQueue queue = new LockFreeQueue();
        int producers = 4;
        int perProducer = 5_000;

        ExecutorService pool = Executors.newFixedThreadPool(producers);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(producers);

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
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertTrue(done.await(20, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }

        List<List<Integer>> perProducerSequences = new ArrayList<>();
        for (int p = 0; p < producers; p++) {
            perProducerSequences.add(new ArrayList<>());
        }

        int total = producers * perProducer;
        for (int i = 0; i < total; i++) {
            int v = queue.pop();
            perProducerSequences.get(v / perProducer).add(v % perProducer);
        }

        for (int p = 0; p < producers; p++) {
            List<Integer> seq = perProducerSequences.get(p);
            assertEquals(perProducer, seq.size(), "Продюсер " + p + ": не все его элементы извлечены");
            for (int i = 0; i < perProducer; i++) {
                assertEquals(i, seq.get(i),
                        "Продюсер " + p + ": нарушен FIFO-порядок на позиции " + i);
            }
        }
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisplayName("Конкурентный push не должен бросать UnsupportedOperationException (ловит незавершённую реализацию push)")
    void concurrentPushDoesNotThrowUnsupportedOperationException() throws InterruptedException {
        int attempts = 50;
        AtomicReference<Throwable> firstFailure = new AtomicReference<>();

        for (int attempt = 0; attempt < attempts && firstFailure.get() == null; attempt++) {
            LockFreeQueue queue = new LockFreeQueue();
            int producers = 16;
            int perProducer = 2_000;

            ExecutorService pool = Executors.newFixedThreadPool(producers);
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(producers);

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
                        } catch (Throwable t) {
                            firstFailure.compareAndSet(null, t);
                            if (t instanceof InterruptedException) {
                                Thread.currentThread().interrupt();
                            }
                        } finally {
                            done.countDown();
                        }
                    });
                }
                start.countDown();
                assertTrue(done.await(10, TimeUnit.SECONDS), "Продюсеры не завершились вовремя");
            } finally {
                pool.shutdownNow();
            }
        }

        Throwable failure = firstFailure.get();
        assertNull(failure,
                "Конкурентный push не должен бросать исключения, но получили: " + failure);
    }
}
