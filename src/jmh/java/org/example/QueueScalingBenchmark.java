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

// FIFO-аналог StackScalingBenchmark: симметричный push+pop, варьирование потоков через -Pjmh.threads.
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(2)
public class QueueScalingBenchmark {

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
    public int pushPop() {
        queue.push(42);
        try {
            return queue.pop();
        } catch (EmptyStackException e) {
            return -1;
        }
    }
}
