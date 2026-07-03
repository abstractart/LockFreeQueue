package org.example;

import java.util.EmptyStackException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

// Treiber-stack + elimination back-off array (Hendler, Shavit, Yerushalmi 2004).
// Main CAS path is identical to LockFreeStack. On CAS failure, the operation
// tries to hand off / pick up a matching operation via a side-array of slots.
// A push publishes its pre-allocated candidate node in a random slot and spins
// briefly; a concurrent pop can take the node out, at which point both ops
// linearize at the moment of the exchange. If no exchange happens the push
// reclaims its slot and retries the main CAS.
//
// Slots are strided to place each on its own cache line (64 B on x86, 128 B on
// Apple M) so slot CAS traffic does not itself become a shared contention
// point.
public class EliminationStack {
    volatile AtomicNode head;

    private static final AtomicReferenceFieldUpdater<EliminationStack, AtomicNode> HEAD =
            AtomicReferenceFieldUpdater.newUpdater(EliminationStack.class, AtomicNode.class, "head");

    private static final int ELIM_SLOTS = 8;
    private static final     int ELIM_SPIN = 32;
    // 32 references × 4 B (compressed oops) = 128 B → each slot on its own line.
    private static final int ELIM_STRIDE = 32;

    private final AtomicReferenceArray<AtomicNode> slots =
            new AtomicReferenceArray<>(ELIM_SLOTS * ELIM_STRIDE);

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

    private boolean tryEliminatePush(AtomicNode candidate) {
        int slot = ThreadLocalRandom.current().nextInt(ELIM_SLOTS) * ELIM_STRIDE;
        if (!slots.compareAndSet(slot, null, candidate)) {
            return false;
        }
        for (int i = 0; i < ELIM_SPIN; i++) {
            Thread.onSpinWait();
        }
        return !slots.compareAndSet(slot, candidate, null);
    }

    private AtomicNode tryEliminatePop() {
        int slot = ThreadLocalRandom.current().nextInt(ELIM_SLOTS) * ELIM_STRIDE;
        AtomicNode s = slots.get(slot);
        if (s == null) {
            return null;
        }
        if (slots.compareAndSet(slot, s, null)) {
            return s;
        }
        return null;
    }
}
