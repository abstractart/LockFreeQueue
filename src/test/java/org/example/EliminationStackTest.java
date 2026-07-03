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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EliminationStackTest {

    @Test
    @DisplayName("isEmpty() отражает состояние стека при последовательных операциях")
    void isEmptyReflectsState() {
        EliminationStack stack = new EliminationStack();
        assertTrue(stack.isEmpty());

        stack.push(1);
        assertFalse(stack.isEmpty());

        stack.pop();
        assertTrue(stack.isEmpty());
    }

    @Test
    @DisplayName("pop() на пустом стеке бросает EmptyStackException")
    void popOnEmptyStackThrows() {
        EliminationStack stack = new EliminationStack();
        assertThrows(EmptyStackException.class, stack::pop);
    }

    @Test
    @DisplayName("LIFO: последовательные push/pop сохраняют порядок")
    void lifoOrder() {
        EliminationStack stack = new EliminationStack();
        stack.push(1);
        stack.push(2);
        stack.push(3);
        assertEquals(3, stack.pop());
        assertEquals(2, stack.pop());
        assertEquals(1, stack.pop());
        assertThrows(EmptyStackException.class, stack::pop);
    }

    @Test
    @DisplayName("Поддерживаются нулевые, отрицательные и граничные значения")
    void supportsBoundaryValues() {
        EliminationStack stack = new EliminationStack();
        stack.push(0);
        stack.push(Integer.MIN_VALUE);
        stack.push(Integer.MAX_VALUE);
        assertEquals(Integer.MAX_VALUE, stack.pop());
        assertEquals(Integer.MIN_VALUE, stack.pop());
        assertEquals(0, stack.pop());
    }

    @Test
    @DisplayName("Failed pop не портит состояние стека")
    void failedPopDoesNotCorruptState() {
        EliminationStack stack = new EliminationStack();
        assertThrows(EmptyStackException.class, stack::pop);
        stack.push(42);
        assertEquals(42, stack.pop());
    }

    // Concurrent push-only: заставляет CAS на head конфликтовать, что открывает
    // путь к tryEliminatePush. В отсутствие поп-операций эти попытки завершаются
    // «положил в слот → никто не забрал → забрал обратно → return false», что
    // выпасает и spin, и возврат-к-null branch.
    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisplayName("Конкурентный push: все уникальные значения попадают в стек без потерь")
    void concurrentPushPreservesAllElements() throws InterruptedException {
        EliminationStack stack = new EliminationStack();
        int producers = 8;
        int perProducer = 5_000;
        int total = producers * perProducer;

        runConcurrent(producers, (id, latch) -> {
            latch.await();
            int base = id * perProducer;
            for (int i = 0; i < perProducer; i++) {
                stack.push(base + i);
            }
            return null;
        });

        Set<Integer> seen = new HashSet<>(total * 2);
        for (int i = 0; i < total; i++) {
            seen.add(stack.pop());
        }
        assertEquals(total, seen.size());
        assertThrows(EmptyStackException.class, stack::pop);
    }

    // Concurrent pop-only: заставляет tryEliminatePop читать пустые слоты
    // (в стеке одни pop-операции) и уходить в null-branch.
    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisplayName("Конкурентный pop: все элементы извлекаются ровно по одному разу")
    void concurrentPopExtractsExactlyOnce() throws InterruptedException {
        EliminationStack stack = new EliminationStack();
        int total = 40_000;
        for (int i = 0; i < total; i++) stack.push(i);

        ConcurrentLinkedQueue<Integer> collected = new ConcurrentLinkedQueue<>();
        int consumers = 8;
        runConcurrent(consumers, (id, latch) -> {
            latch.await();
            while (true) {
                try {
                    collected.add(stack.pop());
                } catch (EmptyStackException e) {
                    return null;
                }
            }
        });

        assertEquals(total, collected.size());
        assertEquals(total, new HashSet<>(collected).size());
        assertThrows(EmptyStackException.class, stack::pop);
    }

    // Ключевой тест на elimination: продюсеры и потребители работают одновременно,
    // так что часть операций проходит через side-array (push публикует candidate,
    // pop его подбирает). Проверяет линеаризуемость под нагрузкой И покрывает
    // «успешную элиминацию» ветку.
    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("Конкурентные push+pop: суммарно ни один элемент не потерян и не задублирован")
    void concurrentPushAndPop() throws InterruptedException {
        EliminationStack stack = new EliminationStack();
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
                final int producerId = p;
                pool.submit(() -> {
                    try {
                        start.await();
                        int base = producerId * perProducer;
                        for (int i = 0; i < perProducer; i++) stack.push(base + i);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
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
                        done.countDown();
                    }
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

    // ABA-нагрузка: pop+push одних и тех же значений на скорости. Даже если в
    // наивной реализации push бы переиспользовал ссылки, здесь каждый push
    // аллоцирует новый AtomicNode, так что ABA не возникает.
    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("ABA-нагрузка: pop+push одних и тех же значений не приводит к потерям и дубликатам")
    void abaProblemStressWorkload() throws InterruptedException {
        EliminationStack stack = new EliminationStack();
        int initial = 64;
        for (int i = 0; i < initial; i++) stack.push(i);

        int workers = 8;
        int opsPerWorker = 50_000;
        AtomicReference<Throwable> firstFailure = new AtomicReference<>();

        ExecutorService pool = Executors.newFixedThreadPool(workers);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(workers);

        try {
            for (int w = 0; w < workers; w++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < opsPerWorker; i++) {
                            int v = stack.pop();
                            stack.push(v);
                        }
                    } catch (Throwable t) {
                        firstFailure.compareAndSet(null, t);
                        if (t instanceof InterruptedException) Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertTrue(done.await(45, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }

        assertNull(firstFailure.get(), "ABA stress не должен бросать исключения");

        Set<Integer> drained = new HashSet<>();
        while (true) {
            try {
                drained.add(stack.pop());
            } catch (EmptyStackException e) {
                break;
            }
        }
        assertEquals(initial, drained.size());
        for (int i = 0; i < initial; i++) {
            assertTrue(drained.contains(i), "Значение " + i + " потеряно");
        }
    }

    @FunctionalInterface
    private interface WorkerBody {
        Void run(int id, CountDownLatch latch) throws Exception;
    }

    private void runConcurrent(int threads, WorkerBody body) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        try {
            for (int t = 0; t < threads; t++) {
                final int id = t;
                pool.submit(() -> {
                    try {
                        body.run(id, start);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } catch (Exception ignore) {
                        // тест сам проверит инвариант через собранные данные
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertTrue(done.await(25, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
    }
}
