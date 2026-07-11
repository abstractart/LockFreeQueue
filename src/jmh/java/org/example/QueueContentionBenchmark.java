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

// Producer-consumer FIFO: 2 продюсера + 2 потребителя.
// Меряем два режима сразу: Throughput (средняя пропускная способность) и
// SampleTime (распределение задержки операции, включая хвост p0.99/p0.999/max).
// producer и consumer сэмплируются отдельно.
@State(Scope.Benchmark)
@BenchmarkMode({Mode.Throughput, Mode.SampleTime})
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(2)
public class QueueContentionBenchmark {

    @Param({"LockedQueue", "ReentrantLockQueue", "LockFreeQueue"})
    public String impl;

    private QueueOps queue;

    @Setup(Level.Iteration)
    public void setupIteration() {
        queue = Queues.create(impl);
        for (int i = 0; i < 10_000; i++) {
            queue.push(i);
        }
    }

    @Benchmark
    @Group("pushPop")
    @GroupThreads(2)
    public void producer() {
        queue.push(42);
    }

    @Benchmark
    @Group("pushPop")
    @GroupThreads(2)
    public int consumer() {
        try {
            return queue.pop();
        } catch (EmptyStackException e) {
            return -1;
        }
    }
}
