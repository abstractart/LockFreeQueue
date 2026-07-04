package org.example;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

// Diagnostic microbenchmark isolating the *cost of the synchronization
// primitive itself*, uncontended (run at t=1). The stack benchmarks show
// `synchronized` beating every CAS-based stack by ~40% at t=1; a differential
// run with -XX:-EliminateLocks ruled out lock coarsening/elision as the cause.
// The leading remaining hypothesis is barrier cost on AArch64: the lock-free
// path does two seq-cst CAS per push+pop (full `dmb ish`), while the monitor
// fast path uses acquire/release (no full barrier).
//
// This bench strips away allocation, node traversal and stack semantics and
// leaves only two synchronization operations per invocation — mirroring
// push+pop = two atomic ops. @State(Scope.Thread) gives every thread its own
// field and lock, so there is never cross-thread contention: what we measure
// is pure per-operation primitive cost, at any thread count.
//
// The discriminating rows are `casVolatile` (seq-cst) vs `casRelease`
// (acquire/release) vs `monitor`. If casRelease ~= monitor and casVolatile is
// ~40% slower, the full seq-cst barrier is the culprit and the hypothesis holds.
// `plainStore` is the no-barrier floor; `reentrantLock` mirrors the AQS path.
@State(Scope.Thread)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(2)
public class SyncPrimitiveBenchmark {

    // Two distinct sentinels the field is flipped between; CAS always has a
    // correct `expected` in single-threaded steady state, so it never fails.
    private static final Object A = new Object();
    private static final Object B = new Object();

    private static final VarHandle STATE;
    static {
        try {
            STATE = MethodHandles.lookup()
                    .findVarHandle(SyncPrimitiveBenchmark.class, "state", Object.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    // Plain field: the read before each CAS is a plain read, uniform across all
    // CAS variants, so the ONLY thing that differs between them is the CAS mode.
    Object state = A;

    private final Object monitor = new Object();
    private final ReentrantLock lock = new ReentrantLock();

    private static Object flip(Object cur) {
        return cur == A ? B : A;
    }

    @Benchmark
    public Object plainStore() {
        state = flip(state);
        state = flip(state);
        return state;
    }

    @Benchmark
    public Object casPlain() {
        Object c = state;
        STATE.weakCompareAndSetPlain(this, c, flip(c));
        Object d = state;
        STATE.weakCompareAndSetPlain(this, d, flip(d));
        return state;
    }

    @Benchmark
    public Object casAcquire() {
        Object c = state;
        STATE.weakCompareAndSetAcquire(this, c, flip(c));
        Object d = state;
        STATE.weakCompareAndSetAcquire(this, d, flip(d));
        return state;
    }

    @Benchmark
    public Object casRelease() {
        Object c = state;
        STATE.weakCompareAndSetRelease(this, c, flip(c));
        Object d = state;
        STATE.weakCompareAndSetRelease(this, d, flip(d));
        return state;
    }

    // weakCompareAndSet: volatile ordering, boolean return — same intrinsic
    // family as the plain/acquire/release rows above, so this ladder isolates
    // barrier strength alone (all four differ only in memory ordering).
    @Benchmark
    public Object casWeakVolatile() {
        Object c = state;
        STATE.weakCompareAndSet(this, c, flip(c));
        Object d = state;
        STATE.weakCompareAndSet(this, d, flip(d));
        return state;
    }

    // compareAndSet: strong seq-cst CAS — exactly what the lock-free stacks use
    // via AtomicReferenceFieldUpdater. Kept as the "real stack primitive" row.
    @Benchmark
    public Object casVolatile() {
        Object c = state;
        STATE.compareAndSet(this, c, flip(c));
        Object d = state;
        STATE.compareAndSet(this, d, flip(d));
        return state;
    }

    @Benchmark
    public Object monitor() {
        synchronized (monitor) {
            state = flip(state);
        }
        synchronized (monitor) {
            state = flip(state);
        }
        return state;
    }

    @Benchmark
    public Object reentrantLock() {
        lock.lock();
        try {
            state = flip(state);
        } finally {
            lock.unlock();
        }
        lock.lock();
        try {
            state = flip(state);
        } finally {
            lock.unlock();
        }
        return state;
    }
}
