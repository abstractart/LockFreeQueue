package org.example;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Group;
import org.openjdk.jmh.annotations.GroupThreads;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.util.EmptyStackException;
import java.util.concurrent.Exchanger;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

// Experiment: is ExchangerEliminationStack's heavy latency tail an inherent
// property of Exchanger, or an artifact of its two tunables — ELIM_SLOTS (8)
// and the 500 ns exchange timeout? This is a copy of ExchangerEliminationStack
// with both made per-instance @Param'd, run on the 2P+2C contended shape in
// both Throughput and SampleTime so we can watch p99/p99.9 move as we vary them.
//
// Hypothesis: 8 separate exchangers over 4 threads fragments partners so most
// exchanges never match and just churn to timeout; fewer slots (1-2) should
// raise the match rate and cut the tail. If the tail does NOT move, the µs-scale
// cost is intrinsic to the timed Exchanger rendezvous, and the report stands.
@State(Scope.Benchmark)
@BenchmarkMode({Mode.Throughput, Mode.SampleTime})
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(2)
public class ExchangerTuningBenchmark {

    @Param({"1", "2", "8"})
    public int slots;

    @Param({"100", "500", "2000"})
    public long timeoutNanos;

    private TunableExchangerStack stack;

    @Setup(Level.Iteration)
    public void setup() {
        stack = new TunableExchangerStack(slots, timeoutNanos);
        for (int i = 0; i < 10_000; i++) {
            stack.push(i);
        }
    }

    @Benchmark
    @Group("pushPop")
    @GroupThreads(2)
    public void producer() {
        stack.push(42);
    }

    @Benchmark
    @Group("pushPop")
    @GroupThreads(2)
    public int consumer() {
        try {
            return stack.pop();
        } catch (EmptyStackException e) {
            return -1;
        }
    }

    // Byte-for-byte ExchangerEliminationStack, but ELIM_SLOTS and the exchange
    // timeout are constructor args instead of static finals.
    static final class TunableExchangerStack {
        volatile AtomicNode head;

        private static final AtomicReferenceFieldUpdater<TunableExchangerStack, AtomicNode> HEAD =
                AtomicReferenceFieldUpdater.newUpdater(TunableExchangerStack.class, AtomicNode.class, "head");

        private final int elimSlots;
        private final long timeoutNanos;
        private final Exchanger<AtomicNode>[] exchangers;

        @SuppressWarnings("unchecked")
        TunableExchangerStack(int elimSlots, long timeoutNanos) {
            this.elimSlots = elimSlots;
            this.timeoutNanos = timeoutNanos;
            this.exchangers = (Exchanger<AtomicNode>[]) new Exchanger[elimSlots];
            for (int i = 0; i < elimSlots; i++) {
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

        private boolean tryEliminatePush(AtomicNode candidate) {
            int slot = ThreadLocalRandom.current().nextInt(elimSlots);
            try {
                AtomicNode received = exchangers[slot].exchange(candidate, timeoutNanos, TimeUnit.NANOSECONDS);
                return received == null;
            } catch (Exception e) {
                return false;
            }
        }

        private AtomicNode tryEliminatePop() {
            int slot = ThreadLocalRandom.current().nextInt(elimSlots);
            try {
                return exchangers[slot].exchange(null, timeoutNanos, TimeUnit.NANOSECONDS);
            } catch (Exception e) {
                return null;
            }
        }
    }
}
