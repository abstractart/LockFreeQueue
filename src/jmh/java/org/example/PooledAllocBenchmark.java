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

import java.util.EmptyStackException;
import java.util.concurrent.TimeUnit;

// Allocation comparison: the pooled stack reuses fixed array slots, so a
// symmetric push+pop should allocate 0 B/op, whereas the reference-based
// LockFreeStack allocates one AtomicNode (24 B) per push. Read the
// gc.alloc.rate.norm (B/op) column, not just throughput. Symmetric push+pop
// keeps occupancy flat, so the bounded pool never overflows.
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(2)
public class PooledAllocBenchmark {

    private PooledLockFreeStack pooled;
    private LockFreeStack lockFree;

    @Setup(Level.Iteration)
    public void setup() {
        pooled = new PooledLockFreeStack(1 << 16);
        lockFree = new LockFreeStack();
        for (int i = 0; i < 1_000; i++) {
            pooled.push(i);
            lockFree.push(i);
        }
    }

    @Benchmark
    public int pooled() {
        pooled.push(42);
        try {
            return pooled.pop();
        } catch (EmptyStackException e) {
            return -1;
        }
    }

    @Benchmark
    public int lockFree() {
        lockFree.push(42);
        try {
            return lockFree.pop();
        } catch (EmptyStackException e) {
            return -1;
        }
    }
}
