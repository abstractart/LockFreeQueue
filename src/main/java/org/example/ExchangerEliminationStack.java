package org.example;

import java.util.EmptyStackException;
import java.util.concurrent.Exchanger;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

// Экспериментальный вариант elimination stack на базе java.util.concurrent.Exchanger.
// Именно этот подход часто рекомендуют в учебных материалах — он показывает, что
// «канонический CAS-based dispatch» в моём EliminationStack.java не единственный
// способ реализовать elimination. Цель класса — измерить, насколько
// блокирующая семантика Exchanger съедает выгоду от диверта с главной CAS-точки.
//
// Отличия от EliminationStack:
//   - Слоты — это N готовых объектов Exchanger<AtomicNode>, а не элементы
//     AtomicReferenceArray.
//   - Push и pop оба зовут exchange(...) в один и тот же слот. Push кладёт
//     свой candidate, pop кладёт null. Тот, кто получит null, — успешный push;
//     тот, кто получит candidate, — успешный pop.
//   - Non-blocking семантика эмулируется через `exchange(v, timeout, NANOS)`;
//     на TimeoutException возвращаемся к главному CAS.
final class ExchangerEliminationStack {

    volatile AtomicNode head;

    private static final AtomicReferenceFieldUpdater<ExchangerEliminationStack, AtomicNode> HEAD =
            AtomicReferenceFieldUpdater.newUpdater(ExchangerEliminationStack.class, AtomicNode.class, "head");

    private static final int ELIM_SLOTS = 8;
    // Небольшой timeout — сопоставим со «спином» в EliminationStack (~32 onSpinWait).
    // Слишком короткий — не даст Exchanger'у даже войти во внутренний spin;
    // слишком длинный — тратим время на неуспешные попытки, пока main-CAS свободен.
    private static final long EXCHANGE_TIMEOUT_NANOS = 500L;

    private final Exchanger<AtomicNode>[] exchangers;

    @SuppressWarnings("unchecked")
    ExchangerEliminationStack() {
        this.exchangers = (Exchanger<AtomicNode>[]) new Exchanger[ELIM_SLOTS];
        for (int i = 0; i < ELIM_SLOTS; i++) {
            this.exchangers[i] = new Exchanger<>();
        }
    }

    void push(int val) {
        AtomicNode candidate = new AtomicNode(val);
        while (true) {
            AtomicNode currentTop = head;
            candidate.next = currentTop;
            if (HEAD.compareAndSet(this, currentTop, candidate)) {
                return;
            }
            if (tryEliminatePush(candidate)) {
                return;
            }
        }
    }

    int pop() {
        while (true) {
            AtomicNode currentTop = head;
            if (currentTop != null) {
                AtomicNode newTop = currentTop.next;
                if (HEAD.compareAndSet(this, currentTop, newTop)) {
                    return currentTop.val;
                }
            }
            AtomicNode paired = tryEliminatePop();
            if (paired != null) {
                return paired.val;
            }
            if (currentTop == null) {
                throw new EmptyStackException();
            }
        }
    }

    boolean isEmpty() {
        return head == null;
    }

    // Push кладёт свой candidate. Если партнёр — pop, pop отдаст null → мы получим null → success.
    // Если партнёр — другой push (даст свой candidate), мы получим не-null → это push↔push,
    // истинного обмена не произошло: возвращаем false, пусть main-CAS решает.
    //
    // Обработка исключений упрощена (catch Exception): TimeoutException — штатный сигнал
    // «партнёр не пришёл», InterruptedException возможен, но в бенч-контексте нас интересует
    // только «уйти обратно в main CAS». Флаг interrupt не восстанавливается — это класс
    // для сравнения перф-характеристик, а не для interrupt-aware продакшн-использования.
    private boolean tryEliminatePush(AtomicNode candidate) {
        int slot = ThreadLocalRandom.current().nextInt(ELIM_SLOTS);
        try {
            AtomicNode received = exchangers[slot].exchange(candidate, EXCHANGE_TIMEOUT_NANOS, TimeUnit.NANOSECONDS);
            return received == null;
        } catch (Exception e) {
            return false;
        }
    }

    // Pop даёт null («у меня нет значения»). Если партнёр — push, вернёт свой candidate.
    // Если партнёр — другой pop (даст null), мы получим null; это pop↔pop, реального обмена нет.
    private AtomicNode tryEliminatePop() {
        int slot = ThreadLocalRandom.current().nextInt(ELIM_SLOTS);
        try {
            return exchangers[slot].exchange(null, EXCHANGE_TIMEOUT_NANOS, TimeUnit.NANOSECONDS);
        } catch (Exception e) {
            return null;
        }
    }
}
