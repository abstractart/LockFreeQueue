package org.example;

import java.util.EmptyStackException;
import java.util.concurrent.atomic.AtomicLong;

// Array-backed, pooled lock-free (Treiber) stack. "Nodes" are slots in fixed
// int arrays, addressed by index instead of object references, so push/pop
// never allocate. The other lock-free stacks in this repo rely on allocating a
// fresh AtomicNode per push plus GC to sidestep ABA (a referenced node cannot be
// reused while a stalled thread holds it). This one reuses slots and defeats ABA
// EXPLICITLY, without leaning on GC.
//
// Both the stack top and the internal free list are a single AtomicLong packing
// a monotonically increasing version in the high 32 bits and the slot index in
// the low 32 bits. Every successful CAS bumps the version, so a slot index that
// cycles back to the same value still produces a different 64-bit word and the
// CAS fails — no ABA.
//
// Trade-off vs the reference-based stacks: capacity is bounded by the array size
// (push throws when the pool is exhausted); the others are unbounded.
public class PooledLockFreeStack {

    private static final int EMPTY = -1;
    private static final long IDX_MASK = 0xFFFF_FFFFL;

    private final int[] value;
    private final int[] next;

    // (version << 32) | (index & IDX_MASK); index == EMPTY means "no head".
    private final AtomicLong top = new AtomicLong(pack(0, EMPTY));
    private final AtomicLong free;

    public PooledLockFreeStack() {
        this(1024);
    }

    public PooledLockFreeStack(int capacity) {
        value = new int[capacity];
        next = new int[capacity];
        // Thread every slot onto the free list: slot 0 -> EMPTY, slot i -> i-1,
        // free-list head = capacity-1. So all `capacity` slots start free.
        next[0] = EMPTY;
        for (int i = 1; i < capacity; i++) {
            next[i] = i - 1;
        }
        free = new AtomicLong(pack(0, capacity - 1));
    }

    private static long pack(int version, int index) {
        return ((long) version << 32) | (index & IDX_MASK);
    }

    public void push(int v) {
        int idx = allocSlot();
        if (idx == EMPTY) {
            throw new IllegalStateException("stack is full");
        }
        value[idx] = v;
        do {
            long cur = top.get();
            next[idx] = (int) cur;
            long upd = pack((int) (cur >>> 32) + 1, idx);
            if (top.compareAndSet(cur, upd)) {
                return;
            }
        } while (true);
    }

    public int pop() {
        int idx;
        do {
            long cur = top.get();
            idx = (int) cur;
            if (idx == EMPTY) {
                throw new EmptyStackException();
            }
            long upd = pack((int) (cur >>> 32) + 1, next[idx]);
            if (top.compareAndSet(cur, upd)) {
                break;
            }
        } while (true);
        int v = value[idx];
        freeSlot(idx);
        return v;
    }

    public boolean isEmpty() {
        return (int) top.get() == EMPTY;
    }

    // Pop a slot index off the versioned free list; EMPTY if the pool is drained.
    private int allocSlot() {
        do {
            long cur = free.get();
            int idx = (int) cur;
            if (idx == EMPTY) {
                return EMPTY;
            }
            long upd = pack((int) (cur >>> 32) + 1, next[idx]);
            if (free.compareAndSet(cur, upd)) {
                return idx;
            }
        } while (true);
    }

    // Push a slot index back onto the versioned free list.
    private void freeSlot(int idx) {
        do {
            long cur = free.get();
            next[idx] = (int) cur;
            long upd = pack((int) (cur >>> 32) + 1, idx);
            if (free.compareAndSet(cur, upd)) {
                return;
            }
        } while (true);
    }
}
