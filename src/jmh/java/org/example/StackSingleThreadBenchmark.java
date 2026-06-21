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

import java.util.concurrent.TimeUnit;

// Базовая стоимость операции без contention — нет блокировок, нет CAS-retries.
// Здесь обычно lock-free платит за volatile/CAS и аллокацию, а простой Stack
// и synchronized без contention идут плечом к плечу.
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 2)
@Fork(2)
public class StackSingleThreadBenchmark {

    @Param({"Stack", "LockedStack", "ReentrantLockStack", "LockFreeStack"})
    public String impl;

    private StackOps stack;

    @Setup(Level.Iteration)
    public void setupIteration() {
        stack = Stacks.create(impl);
        // Префилл, чтобы размер стека после bench-цикла push/pop оставался стабильным
        // и pop никогда не упирался в пустой стек.
        for (int i = 0; i < 1024; i++) {
            stack.push(i);
        }
    }

    @Benchmark
    public int pushPop() {
        stack.push(42);
        return stack.pop();
    }
}
