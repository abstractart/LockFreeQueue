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

// Follow-up to HeadReadModeBenchmark: does making `val` final unlock a
// *correct* speedup? Here the node is `final int val` + `volatile Node next`,
// so node contents are safely published independently of the head read:
//   - val: final-field freeze semantics (visible once the ref is obtained);
//   - next: its own volatile read establishes ordering.
// So the head read no longer has to publish node contents. The question is
// whether a weaker head read is (a) faster and (b) still linearizable.
//
//   volatileHead : getVolatile  -> AArch64 `ldar`   (== current LockFreeStack)
//   acquireHead  : getAcquire   -> AArch64 `ldar`   (linearizable-safe)
//   opaqueHead   : getOpaque    -> AArch64 `ldr`    (fast; NOT guaranteed
//                                  linearizable under the JMM — opaque has no
//                                  synchronizes-with edge to the seq-cst CAS)
//
// Expectation: volatile ~= acquire (both ldar, so `final` buys no correct
// speedup on ARM); opaque is fast but its correctness is not spec-guaranteed.
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(2)
public class HeadReadFinalBenchmark {

    static final class FinalNode {
        final int val;                 // final: safe publication via freeze
        volatile FinalNode next;       // volatile: self-published
        FinalNode(int val) { this.val = val; }
    }

    // Mode selected per stack instance; branch is hoisted by the JIT since the
    // instance's mode never changes, so it does not pollute the hot path.
    enum Mode { VOLATILE, ACQUIRE, OPAQUE }

    static final class FinalStack {
        private FinalNode head;
        private final Mode mode;
        private static final VarHandle H;
        static {
            try {
                H = MethodHandles.lookup().findVarHandle(FinalStack.class, "head", FinalNode.class);
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }
        FinalStack(Mode mode) { this.mode = mode; }

        private FinalNode readHead() {
            switch (mode) {
                case VOLATILE: return (FinalNode) H.getVolatile(this);
                case ACQUIRE:  return (FinalNode) H.getAcquire(this);
                default:       return (FinalNode) H.getOpaque(this);
            }
        }

        void push(int v) {
            FinalNode n = new FinalNode(v);
            while (true) {
                FinalNode t = readHead();
                n.next = t;
                if (H.compareAndSet(this, t, n)) return;
            }
        }
        int pop() {
            while (true) {
                FinalNode t = readHead();
                if (t == null) throw new EmptyStackException();
                FinalNode nx = t.next;
                if (H.compareAndSet(this, t, nx)) return t.val;
            }
        }
    }

    private FinalStack volatileStack;
    private FinalStack acquireStack;
    private FinalStack opaqueStack;

    @Setup(Level.Iteration)
    public void setup() {
        volatileStack = new FinalStack(Mode.VOLATILE);
        acquireStack = new FinalStack(Mode.ACQUIRE);
        opaqueStack = new FinalStack(Mode.OPAQUE);
        for (int i = 0; i < 10_000; i++) {
            volatileStack.push(i);
            acquireStack.push(i);
            opaqueStack.push(i);
        }
    }

    private static int pushPop(FinalStack s) {
        s.push(42);
        try {
            return s.pop();
        } catch (EmptyStackException e) {
            return -1;
        }
    }

    @Benchmark
    public int volatileHead() { return pushPop(volatileStack); }

    @Benchmark
    public int acquireHead() { return pushPop(acquireStack); }

    @Benchmark
    public int opaqueHead() { return pushPop(opaqueStack); }
}
