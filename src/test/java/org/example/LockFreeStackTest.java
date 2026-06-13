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

class LockFreeStackTest {

    @Test
    @DisplayName("pop() на новом стеке бросает EmptyStackException")
    void popOnEmptyStackThrows() {
        LockFreeStack stack = new LockFreeStack();
        assertThrows(EmptyStackException.class, stack::pop);
    }

    @Test
    @DisplayName("push() + pop() возвращает положенное значение")
    void pushThenPopReturnsValue() {
        LockFreeStack stack = new LockFreeStack();
        stack.push(42);
        assertEquals(42, stack.pop());
    }

    @Test
    @DisplayName("LIFO: элементы извлекаются в обратном порядке добавления")
    void lifoOrder() {
        LockFreeStack stack = new LockFreeStack();
        stack.push(1);
        stack.push(2);
        stack.push(3);

        assertEquals(3, stack.pop());
        assertEquals(2, stack.pop());
        assertEquals(1, stack.pop());
    }

    @Test
    @DisplayName("pop() после опустошения стека бросает EmptyStackException")
    void popAfterDrainingThrows() {
        LockFreeStack stack = new LockFreeStack();
        stack.push(10);
        stack.pop();
        assertThrows(EmptyStackException.class, stack::pop);
    }

    @Test
    @DisplayName("Поддерживаются отрицательные и нулевые значения")
    void supportsZeroAndNegativeValues() {
        LockFreeStack stack = new LockFreeStack();
        stack.push(0);
        stack.push(-1);
        stack.push(Integer.MIN_VALUE);
        stack.push(Integer.MAX_VALUE);

        assertEquals(Integer.MAX_VALUE, stack.pop());
        assertEquals(Integer.MIN_VALUE, stack.pop());
        assertEquals(-1, stack.pop());
        assertEquals(0, stack.pop());
    }

    @Test
    @DisplayName("Допускаются дубликаты")
    void allowsDuplicates() {
        LockFreeStack stack = new LockFreeStack();
        stack.push(7);
        stack.push(7);
        stack.push(7);

        assertEquals(7, stack.pop());
        assertEquals(7, stack.pop());
        assertEquals(7, stack.pop());
        assertThrows(EmptyStackException.class, stack::pop);
    }

    @Test
    @DisplayName("Чередование push/pop сохраняет LIFO")
    void interleavedPushAndPop() {
        LockFreeStack stack = new LockFreeStack();
        stack.push(1);
        stack.push(2);
        assertEquals(2, stack.pop());

        stack.push(3);
        assertEquals(3, stack.pop());
        assertEquals(1, stack.pop());

        assertThrows(EmptyStackException.class, stack::pop);
    }

    @Test
    @DisplayName("push после полного опустошения стека работает корректно")
    void pushAfterFullyDrainingWorks() {
        LockFreeStack stack = new LockFreeStack();
        stack.push(1);
        assertEquals(1, stack.pop());

        stack.push(2);
        assertEquals(2, stack.pop());
    }

    @Test
    @DisplayName("Большое количество элементов сохраняет LIFO-порядок")
    void largeNumberOfElementsPreservesOrder() {
        LockFreeStack stack = new LockFreeStack();
        int n = 10_000;
        for (int i = 0; i < n; i++) {
            stack.push(i);
        }
        for (int i = n - 1; i >= 0; i--) {
            assertEquals(i, stack.pop());
        }
        assertThrows(EmptyStackException.class, stack::pop);
    }

    @Test
    @DisplayName("Несколько циклов опустошение/наполнение сохраняют LIFO")
    void multipleDrainAndRefillCycles() {
        LockFreeStack stack = new LockFreeStack();

        for (int cycle = 0; cycle < 3; cycle++) {
            stack.push(cycle * 10 + 1);
            stack.push(cycle * 10 + 2);

            assertEquals(cycle * 10 + 2, stack.pop());
            assertEquals(cycle * 10 + 1, stack.pop());
            assertThrows(EmptyStackException.class, stack::pop);
        }
    }

    @Test
    @DisplayName("Состояние стека не меняется при бросании исключения из pop()")
    void failedPopDoesNotCorruptState() {
        LockFreeStack stack = new LockFreeStack();
        assertThrows(EmptyStackException.class, stack::pop);

        stack.push(5);
        assertEquals(5, stack.pop());
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisplayName("Конкурентный push: N продюсеров кладут уникальные значения — все попадают в стек без потерь и дублей")
    void concurrentPushPreservesAllElements() throws InterruptedException {
        LockFreeStack stack = new LockFreeStack();
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
                            stack.push(base + i);
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
            seen.add(stack.pop());
        }
        assertThrows(EmptyStackException.class, stack::pop, "После извлечения всех элементов стек должен быть пуст");
        assertEquals(total, seen.size(), "Все уникальные значения должны присутствовать ровно один раз");
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisplayName("Конкурентный pop: N потребителей разбирают предзаполненный стек без потерь и дублей")
    void concurrentPopRetrievesAllElementsExactlyOnce() throws InterruptedException {
        LockFreeStack stack = new LockFreeStack();
        int total = 40_000;
        for (int i = 0; i < total; i++) {
            stack.push(i);
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
                                collected.add(stack.pop());
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
        assertThrows(EmptyStackException.class, stack::pop);
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("Конкурентные продюсеры + потребители: ни один элемент не потерян и не задублирован")
    void concurrentProducersAndConsumers() throws InterruptedException {
        LockFreeStack stack = new LockFreeStack();
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
            assertTrue(producersDone.await(30, TimeUnit.SECONDS), "Продюсеры не завершились вовремя");
            assertTrue(consumersDone.await(30, TimeUnit.SECONDS), "Потребители не завершились вовремя");
        } finally {
            pool.shutdownNow();
        }

        Set<Integer> seen = new HashSet<>(collected);
        assertEquals(total, collected.size(), "Все положенные элементы должны быть извлечены");
        assertEquals(total, seen.size(), "Не должно быть дубликатов");
        assertThrows(EmptyStackException.class, stack::pop, "После работы потребителей стек должен быть пуст");
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisplayName("Конкурентный push сохраняет LIFO внутри одного потока-продюсера")
    void concurrentPushPreservesPerProducerLifo() throws InterruptedException {
        LockFreeStack stack = new LockFreeStack();
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
                            stack.push(base + i);
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
            int v = stack.pop();
            perProducerSequences.get(v / perProducer).add(v % perProducer);
        }

        for (int p = 0; p < producers; p++) {
            List<Integer> seq = perProducerSequences.get(p);
            assertEquals(perProducer, seq.size(), "Продюсер " + p + ": не все его элементы извлечены");
            for (int i = 0; i < perProducer; i++) {
                int expected = perProducer - 1 - i;
                assertEquals(expected, seq.get(i),
                        "Продюсер " + p + ": нарушен LIFO-порядок на позиции " + i);
            }
        }
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisplayName("Конкурентный push не должен бросать исключения")
    void concurrentPushDoesNotThrow() throws InterruptedException {
        int attempts = 50;
        AtomicReference<Throwable> firstFailure = new AtomicReference<>();

        for (int attempt = 0; attempt < attempts && firstFailure.get() == null; attempt++) {
            LockFreeStack stack = new LockFreeStack();
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
                                stack.push(base + i);
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
