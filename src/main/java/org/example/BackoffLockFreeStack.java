package org.example;

import java.util.EmptyStackException;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

// Treiber-стек с экспоненциальным CAS-backoff'ом. Отличие от LockFreeStack:
// после каждого проваленного CAS выполняется spin из `spins` итераций
// Thread.onSpinWait(), где `spins` удваивается на каждом фейле (cap 1024).
// Каждый новый вызов push/pop стартует с spins=1 — нет памяти между вызовами.
//
// Смысл: под contention retry storm разбивается на разные временные окна,
// cache line меньше пинается между ядрами. В отличие от elimination, это
// работает и на **однотипных** операциях (много push одновременно), где
// elimination спариться не сможет.
//
// Progress-класс: **lock-free** — spin ограничен, park не используется.
public class BackoffLockFreeStack {

    volatile AtomicNode head;

    private static final AtomicReferenceFieldUpdater<BackoffLockFreeStack, AtomicNode> HEAD =
            AtomicReferenceFieldUpdater.newUpdater(BackoffLockFreeStack.class, AtomicNode.class, "head");

    // 1024 onSpinWait's ≈ несколько микросекунд на современных CPU — сопоставимо
    // со средним временем удержания cache-line под high contention.
    private static final int MAX_SPINS = 1024;

    void push(int val) {
        AtomicNode candidate = new AtomicNode(val);
        int spins = 1;
        while (true) {
            AtomicNode currentTop = head;
            candidate.next = currentTop;
            if (HEAD.compareAndSet(this, currentTop, candidate)) {
                return;
            }
            backoff(spins);
            spins = Math.min(spins << 1, MAX_SPINS);
        }
    }

    int pop() {
        int spins = 1;
        while (true) {
            AtomicNode currentTop = head;
            if (currentTop == null) {
                throw new EmptyStackException();
            }
            AtomicNode newTop = currentTop.next;
            if (HEAD.compareAndSet(this, currentTop, newTop)) {
                return currentTop.val;
            }
            backoff(spins);
            spins = Math.min(spins << 1, MAX_SPINS);
        }
    }

    // Non-throwing pop: returns Integer.MIN_VALUE when empty instead of allocating
    // an EmptyStackException (see LockedStack.poll).
    int poll() {
        int spins = 1;
        while (true) {
            AtomicNode currentTop = head;
            if (currentTop == null) {
                return Integer.MIN_VALUE;
            }
            AtomicNode newTop = currentTop.next;
            if (HEAD.compareAndSet(this, currentTop, newTop)) {
                return currentTop.val;
            }
            backoff(spins);
            spins = Math.min(spins << 1, MAX_SPINS);
        }
    }

    boolean isEmpty() {
        return head == null;
    }

    private static void backoff(int spins) {
        for (int i = 0; i < spins; i++) {
            Thread.onSpinWait();
        }
    }
}
