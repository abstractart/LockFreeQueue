package org.example;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
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
import java.util.concurrent.TimeUnit;

// Симметричный workload (каждый поток делает push+pop) — позволяет варьировать
// степень contention через -Pjmh.threads и видеть, как throughput масштабируется
// по разным реализациям. Стек префилится: pop редко упирается в пустоту.
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(2)
public class StackScalingBenchmark {

    @Param({"LockedStack", "ReentrantLockStack", "LockFreeStack", "EliminationStack", "ExchangerEliminationStack"})
    public String impl;

    private StackOps stack;

    @Setup(Level.Iteration)
    public void setupIteration() {
        stack = Stacks.create(impl);
        for (int i = 0; i < 10_000; i++) {
            stack.push(i);
        }
    }

    @Benchmark
    public int pushPop() {
        stack.push(42);
        try {
            return stack.pop();
        } catch (EmptyStackException e) {
            return -1;
        }
    }
}
