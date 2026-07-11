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
import java.util.concurrent.TimeUnit;

// Producer-consumer workload: 2 продюсера + 2 потребителя.
// Стек префилится — pop почти всегда находит элемент. EmptyStackException
// ловится, чтобы редкие гонки на полупустом стеке не ломали бенчмарк.
// Stack (не thread-safe) исключён из @Param — он бы крашился неповторимо.
//
// Меряем два режима сразу:
//   Throughput — средняя пропускная способность (ops/us);
//   SampleTime — распределение задержки одной операции, включая хвост
//                (p0.99 / p0.999 / max). Именно хвост — главный аргумент за
//                lock-free: под нагрузкой lock может выигрывать по среднему,
//                но проваливаться по p999 из-за park/unpark и priority
//                inversion. producer и consumer сэмплируются отдельно.
@State(Scope.Benchmark)
@BenchmarkMode({Mode.Throughput, Mode.SampleTime})
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(2)
public class StackContentionBenchmark {

    @Param({"LockedStack", "ReentrantLockStack", "LockFreeStack", "EliminationStack", "BackoffLockFreeStack", "ExchangerEliminationStack", "FlatCombiningStack"})
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
}
