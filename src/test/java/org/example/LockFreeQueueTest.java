package org.example;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.ref.WeakReference;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    @Test
    @DisplayName("ABA Problem: устаревший CAS в pop() не проходит после полного A→B→A-цикла на head")
    void abaProblemDeterministicScenarioOnHead() {
        LockFreeQueue queue = new LockFreeQueue();
        queue.push(1);
        queue.push(2);
        queue.push(3);

        // Снимок состояния «Потоком 1» прямо перед его CAS в pop():
        // он считал currHead (текущий dummy) и result (следующий узел со значением 1),
        // а затем «уснул» прямо перед head.compareAndSet.
        AtomicNode staleHead = queue.head.get();
        AtomicNode staleResult = staleHead.next.get();      // узел со значением 1

        // «Поток 2» полностью опустошает очередь и заново её заполняет —
        // классический A→B→A на head: позиция «головы» внешне выглядит так же, но узлы все новые.
        assertEquals(1, queue.pop());
        assertEquals(2, queue.pop());
        assertEquals(3, queue.pop());
        queue.push(7);
        queue.push(8);

        // «Поток 1» просыпается и пытается выполнить свой устаревший CAS.
        // Если бы реализация была подвержена ABA, наивное сравнение совпавших ссылок
        // увело бы head на уже отсоединённый узел и потеряло бы свежие данные.
        boolean staleCas = queue.head.compareAndSet(staleHead, staleResult);
        assertFalse(staleCas,
                "Устаревший CAS не должен пройти: новые push создают новые узлы, " +
                        "поэтому staleHead больше не является текущей головой очереди");

        // Состояние очереди должно сохранить FIFO для свежих значений 7 и 8.
        assertEquals(7, queue.pop());
        assertEquals(8, queue.pop());
        assertThrows(EmptyStackException.class, queue::pop);
    }

    @Test
    @DisplayName("ABA Problem: устаревший CAS в push() не проходит после A→B→A на tail")
    void abaProblemDeterministicScenarioOnTail() {
        LockFreeQueue queue = new LockFreeQueue();
        queue.push(1);

        // Снимок состояния «Потоком 1» прямо перед его CAS в push():
        // он считал currTail (узел со значением 1) и убедился, что currTail.next == null,
        // а затем «уснул» прямо перед currTail.next.compareAndSet(null, candidate).
        AtomicNode staleTail = queue.tail.get();
        assertNull(staleTail.next.get(),
                "Предусловие: в момент снимка tail.next должен быть null");

        // «Поток 2» полностью опустошает и заново заполняет очередь — staleTail оказывается
        // полностью отсоединённым от текущей структуры (A→B→A на tail).
        assertEquals(1, queue.pop());
        queue.push(2);
        queue.push(3);

        // Ключевое свойство анти-ABA: ссылка .next у уже отсоединённого узла НЕ сбрасывается обратно в null
        // в реализациях push/pop. Поэтому устаревший CAS(null, candidate) на нём провалится.
        AtomicNode candidate = new AtomicNode(999);
        boolean staleCas = staleTail.next.compareAndSet(null, candidate);
        assertFalse(staleCas,
                "Устаревший CAS на отсоединённом tail не должен пройти — иначе candidate " +
                        "был бы «привязан» к узлу, которого больше нет в очереди, и потерян");

        // Текущее состояние очереди должно быть нетронуто: 2, затем 3.
        assertEquals(2, queue.pop());
        assertEquals(3, queue.pop());
        assertThrows(EmptyStackException.class, queue::pop);
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("ABA Problem: высокочастотный pop+push одних и тех же значений не приводит к потерям и дубликатам")
    void abaProblemStressWorkload() throws InterruptedException {
        LockFreeQueue queue = new LockFreeQueue();
        int initialCount = 64;
        for (int i = 0; i < initialCount; i++) {
            queue.push(i);
        }

        int workers = 8;
        int operationsPerWorker = 50_000;

        ExecutorService pool = Executors.newFixedThreadPool(workers);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(workers);
        AtomicReference<Throwable> firstFailure = new AtomicReference<>();

        try {
            for (int w = 0; w < workers; w++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < operationsPerWorker; i++) {
                            // pop из головы + push в хвост одного и того же значения — постоянное
                            // движение значений по структуре, максимизирующее шансы спровоцировать
                            // ABA-баг как на head, так и на tail.
                            int v = queue.pop();
                            queue.push(v);
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
            assertTrue(done.await(45, TimeUnit.SECONDS), "Воркеры не завершились вовремя");
        } finally {
            pool.shutdownNow();
        }

        assertNull(firstFailure.get(),
                "Под ABA-нагрузкой не должно быть исключений, но получили: " + firstFailure.get());

        List<Integer> drained = new ArrayList<>(initialCount);
        while (true) {
            try {
                drained.add(queue.pop());
            } catch (EmptyStackException e) {
                break;
            }
        }
        Set<Integer> unique = new HashSet<>(drained);
        assertEquals(initialCount, drained.size(),
                "Общее количество элементов должно совпадать с начальным — никакое значение не должно пропасть или задублироваться");
        assertEquals(initialCount, unique.size(),
                "Все начальные значения должны присутствовать ровно по одному разу");
        for (int i = 0; i < initialCount; i++) {
            assertTrue(unique.contains(i),
                    "Значение " + i + " потеряно — возможный признак ABA-повреждения");
        }
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisplayName("Утечка памяти: после pop старые dummy/data-узлы становятся недостижимыми и собираются GC")
    void poppedNodesAreEligibleForGC() throws InterruptedException {
        LockFreeQueue queue = new LockFreeQueue();
        int n = 1000;
        for (int i = 0; i < n; i++) {
            queue.push(i);
        }

        // Захватываем WeakReference на ВСЕ узлы в цепочке (включая текущий dummy-head),
        // т.е. n+1 узел. После слива в очереди останется ровно один узел в роли head=tail
        // (последний data-узел, ставший новым dummy), а все предыдущие должны стать GC-собираемыми.
        List<WeakReference<AtomicNode>> refs = new ArrayList<>(n + 1);
        AtomicNode walker = queue.head.get();
        while (walker != null) {
            refs.add(new WeakReference<>(walker));
            walker = walker.next.get();
        }
        walker = null;
        assertEquals(n + 1, refs.size(),
                "Должны зафиксировать ровно n+1 узел (dummy + n data) перед сливом");

        for (int i = 0; i < n; i++) {
            queue.pop();
        }

        long alive = waitForGcUntilMostlyCollected(refs);
        // Один узел всегда остаётся — он закреплён за head и tail (новый dummy).
        assertTrue(alive <= 1,
                "Должно остаться не более 1 живого узла (новый dummy=head=tail), " +
                        "но осталось живых: " + alive +
                        " — реализация удерживает ссылки на отсоединённые узлы (утечка)");
    }

    private static long waitForGcUntilMostlyCollected(List<WeakReference<AtomicNode>> refs)
            throws InterruptedException {
        long alive = refs.size();
        for (int attempt = 0; attempt < 20 && alive > 1; attempt++) {
            System.gc();
            Thread.sleep(50);
            alive = refs.stream().filter(r -> r.get() != null).count();
        }
        return alive;
    }
}
