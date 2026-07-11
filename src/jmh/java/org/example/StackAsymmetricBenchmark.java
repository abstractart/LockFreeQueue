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

// Асимметричный producer-heavy workload: 3 продюсера + 1 потребитель.
// В такой конфигурации у push нет естественного pop-партнёра для каждой
// операции (их поступает в 3 раза больше), поэтому elimination спариться
// не может и большая часть push'ей всё равно бьёт на `head`. Стек растёт
// в течение итерации — поэтому @Setup(Level.Iteration) пересоздаёт его,
// а heap увеличен через jvmArgs '-Xmx3g' (см. build.gradle).
//
// Специально показывает, где adaptive backoff даёт эффект, которого не
// даёт elimination — push↔push contention.
//
// Меряем два режима сразу: Throughput и SampleTime. В push-heavy конфигурации
// хвост задержки (p0.99/p0.999) особенно показателен — 3 продюсера дерутся за
// `head`, и разница между backoff и «голым» CAS-retry видна именно в хвосте,
// а не в среднем. producer и consumer сэмплируются отдельно.
@State(Scope.Benchmark)
@BenchmarkMode({Mode.Throughput, Mode.SampleTime})
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(2)
public class StackAsymmetricBenchmark {

    @Param({
        "LockedStack",
        "ReentrantLockStack",
        "LockFreeStack",
        "EliminationStack",
        "BackoffLockFreeStack",
        "ExchangerEliminationStack",
    })
    public String impl;

    private StackOps stack;

    @Setup(Level.Iteration)
    public void setupIteration() {
        stack = Stacks.create(impl);
        for (int i = 0; i < 100_000; i++) {
            stack.push(i);
        }
    }

    @Benchmark
    @Group("pushHeavy")
    @GroupThreads(3)
    public void producer() {
        stack.push(42);
    }

    @Benchmark
    @Group("pushHeavy")
    @GroupThreads(1)
    public int consumer() {
        try {
            return stack.pop();
        } catch (EmptyStackException e) {
            return -1;
        }
    }
}
