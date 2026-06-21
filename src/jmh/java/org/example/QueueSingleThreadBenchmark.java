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

@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 2)
@Fork(2)
public class QueueSingleThreadBenchmark {

    @Param({"Queue", "LockedQueue", "ReentrantLockQueue", "LockFreeQueue"})
    public String impl;

    private QueueOps queue;

    @Setup(Level.Iteration)
    public void setupIteration() {
        queue = Queues.create(impl);
        for (int i = 0; i < 1024; i++) {
            queue.push(i);
        }
    }

    @Benchmark
    public int pushPop() {
        queue.push(42);
        return queue.pop();
    }
}
