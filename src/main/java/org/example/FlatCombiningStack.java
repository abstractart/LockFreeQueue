package org.example;

import java.util.EmptyStackException;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

// Flat-Combining stack (Hendler, Incze, Shavit, Tzafrir, SPAA 2010).
//
// A qualitatively different design from every other stack here: instead of each
// thread racing on `head` (CAS ping-pong) or trying to pair off (elimination),
// threads DELEGATE. Each publishes its operation into a thread-local record and
// spins; whichever thread wins a single global lock becomes the *combiner* and
// applies the whole batch of published operations sequentially on a plain,
// non-atomic stack, then hands each waiter its result.
//
// Why it can beat lock-free under high contention: the hot `head` line is
// touched by exactly one core (the combiner) for a whole batch, so the
// cache-line ping-pong that caps the Treiber/elimination variants disappears.
// N contending threads' scattered CAS traffic becomes one thread's cache-local
// sequential work. It is a *blocking* algorithm (single lock) — the point is
// that deliberate serialization removes contention rather than fighting it.
//
// Progress class: blocking. The combiner holds the lock while serving a batch;
// a waiter that is not served spins and re-attempts the lock, so it either gets
// served or becomes the next combiner. No lock-freedom is claimed.
//
// Publication records are enlisted once per thread (via a per-instance
// ThreadLocal) and left in the list; pruning of stale records — the classic FC
// optimization for fluctuating thread counts — is intentionally omitted, so the
// list length is bounded by the number of distinct threads that touch this
// instance. Adequate for fixed-thread workloads; documented as a simplification.
final class FlatCombiningStack {

    private static final int PUSH = 0;
    private static final int POP = 1;

    // Sequential stack interior. Only ever mutated by the current combiner under
    // the lock; volatile purely to bulletproof visibility between successive
    // combiners (the lock's release/acquire already establishes it).
    private volatile Node head;

    // Single combiner lock: 0 = free, 1 = held. CAS acquire, volatile-store release.
    private volatile int combiner;
    private static final AtomicIntegerFieldUpdater<FlatCombiningStack> COMBINER =
            AtomicIntegerFieldUpdater.newUpdater(FlatCombiningStack.class, "combiner");

    // Head of the publication list (lock-free push-at-front on enlist).
    private volatile PubRecord listHead;
    private static final AtomicReferenceFieldUpdater<FlatCombiningStack, PubRecord> LIST_HEAD =
            AtomicReferenceFieldUpdater.newUpdater(FlatCombiningStack.class, PubRecord.class, "listHead");

    // One record per (instance, thread). New instance -> new ThreadLocal -> fresh
    // records, so nothing leaks across stack instances (matters for Lincheck,
    // which builds a fresh instance per invocation).
    private final ThreadLocal<PubRecord> local = ThreadLocal.withInitial(PubRecord::new);

    // Per-thread request/response slot. `pending` is the one-flag handshake:
    // the requester writes op/arg then publishes with `pending = true` (release);
    // the combiner reads them (acquire on seeing pending), serves, writes
    // result/empty, then clears `pending = false` (release) which publishes the
    // result back to the requester. So op/arg/result/empty need no volatile of
    // their own — `pending` carries the happens-before in both directions.
    static final class PubRecord {
        volatile boolean pending;
        int op;
        int arg;
        int result;
        boolean empty;
        boolean enlisted;
        volatile PubRecord next;
    }

    void push(int val) {
        doOp(PUSH, val);
    }

    int pop() {
        PubRecord r = doOp(POP, 0);
        if (r.empty) {
            throw new EmptyStackException();
        }
        return r.result;
    }

    boolean isEmpty() {
        return head == null;
    }

    private PubRecord doOp(int op, int arg) {
        PubRecord r = local.get();
        r.op = op;
        r.arg = arg;
        r.empty = false;
        if (!r.enlisted) {
            enlist(r);
        }
        r.pending = true; // publish request (release)

        while (r.pending) {
            if (COMBINER.compareAndSet(this, 0, 1)) {
                try {
                    combine();
                } finally {
                    combiner = 0; // release
                }
                // Our own record is served during combine(); if a racing enlist
                // slipped in after our pass we loop and try again.
            } else {
                Thread.onSpinWait();
            }
        }
        return r;
    }

    // Serve every pending record once, applying it to the sequential interior.
    // Runs under the combiner lock, so `head` is touched single-threaded.
    private void combine() {
        for (PubRecord p = listHead; p != null; p = p.next) {
            if (!p.pending) {
                continue;
            }
            if (p.op == PUSH) {
                Node n = new Node(p.arg);
                n.next = head;
                head = n;
                p.empty = false;
            } else { // POP
                Node h = head;
                if (h == null) {
                    p.empty = true;
                } else {
                    head = h.next;
                    p.result = h.val;
                    p.empty = false;
                }
            }
            p.pending = false; // publish result to the waiter (release)
        }
    }

    private void enlist(PubRecord r) {
        r.enlisted = true;
        PubRecord old;
        do {
            old = listHead;
            r.next = old;
        } while (!LIST_HEAD.compareAndSet(this, old, r));
    }
}
