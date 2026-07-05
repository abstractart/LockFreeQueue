package org.example;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.EmptyStackException;
import java.util.concurrent.TimeUnit;

// Diagnostic: does the *acquire barrier on the head read* cost measurable
// throughput? Both stacks below are byte-for-byte identical Treiber stacks —
// the ONLY difference is how they read `head` in the push/pop retry loop:
//
//   AcquireHeadStack : VarHandle.getVolatile  -> AArch64 `ldar` (acquire)
//   OpaqueHeadStack  : VarHandle.getOpaque    -> AArch64 `ldr`  (plain, but
//                                                coherent: the retry loop still
//                                                observes other threads' writes
//                                                and makes progress)
//
// The seq-cst CAS is unchanged in both, so the RMW is still atomic. Opaque only
// drops the *ordering* on the head read; that makes the opaque variant
// correctness-UNSAFE (a popper may see a published node reference without a
// happens-before to the pusher's non-final `val` write, so it can read a stale
// `val`). It is here purely to price the acquire barrier, NOT as a usable stack.
//
// Prediction from the t=1 analysis: opaque should be slightly faster at t=1,
// where per-access ordering is the whole tax and there is no contention noise.
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(2)
public class HeadReadModeBenchmark {

    // Mirrors AtomicNode: val is a plain (non-final) int, next is volatile.
    static final class Node {
        int val;
        volatile Node next;
        Node(int val) { this.val = val; }
    }

    static final class AcquireHeadStack {
        private Node head;
        private static final VarHandle H;
        static {
            try {
                H = MethodHandles.lookup().findVarHandle(AcquireHeadStack.class, "head", Node.class);
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }
        void push(int v) {
            Node n = new Node(v);
            while (true) {
                Node t = (Node) H.getVolatile(this);
                n.next = t;
                if (H.compareAndSet(this, t, n)) return;
            }
        }
        int pop() {
            while (true) {
                Node t = (Node) H.getVolatile(this);
                if (t == null) throw new EmptyStackException();
                Node nx = t.next;
                if (H.compareAndSet(this, t, nx)) return t.val;
            }
        }
    }

    static final class OpaqueHeadStack {
        private Node head;
        private static final VarHandle H;
        static {
            try {
                H = MethodHandles.lookup().findVarHandle(OpaqueHeadStack.class, "head", Node.class);
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }
        void push(int v) {
            Node n = new Node(v);
            while (true) {
                Node t = (Node) H.getOpaque(this);
                n.next = t;
                if (H.compareAndSet(this, t, n)) return;
            }
        }
        int pop() {
            while (true) {
                Node t = (Node) H.getOpaque(this);
                if (t == null) throw new EmptyStackException();
                Node nx = t.next;
                if (H.compareAndSet(this, t, nx)) return t.val;
            }
        }
    }

    private AcquireHeadStack acquireStack;
    private OpaqueHeadStack opaqueStack;

    @Setup(Level.Iteration)
    public void setup() {
        acquireStack = new AcquireHeadStack();
        opaqueStack = new OpaqueHeadStack();
        for (int i = 0; i < 10_000; i++) {
            acquireStack.push(i);
            opaqueStack.push(i);
        }
    }

    @Benchmark
    public int acquireHead() {
        acquireStack.push(42);
        try {
            return acquireStack.pop();
        } catch (EmptyStackException e) {
            return -1;
        }
    }

    @Benchmark
    public int opaqueHead() {
        opaqueStack.push(42);
        try {
            return opaqueStack.pop();
        } catch (EmptyStackException e) {
            return -1;
        }
    }
}
