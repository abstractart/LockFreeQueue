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
import org.openjdk.jmh.infra.Blackhole;

import java.util.EmptyStackException;
import java.util.concurrent.TimeUnit;

// Bursty workload: каждая операция окружена «пользовательской» работой,
// эмулируемой через Blackhole.consumeCPU. Так выглядит реальный код, где
// стек — один из компонентов пайплайна, а не весь горячий цикл.
// В таком режиме overhead блокировки (acquire/release, потенциальный park)
// оплачивается на каждой операции, тогда как lock-free платит один
// (в большинстве случаев успешный) CAS. Ожидаем — lock-free берёт лидерство
// при уровнях contention, где ReentrantLock ещё не может барджить в бёрсты.
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(2)
public class StackBurstyBenchmark {

    @Param({"LockedStack", "ReentrantLockStack", "LockFreeStack", "EliminationStack", "BackoffLockFreeStack", "ExchangerEliminationStack"})
    public String impl;

    // Blackhole.consumeCPU tokens между операциями стека. 200 ≈ порядка
    // 100-200 ns на десктопных CPU — сравнимо с самой стековой операцией,
    // так что оверхед синхронизации перестаёт быть 100% рабочего времени
    // потока и разница в стоимости acquire/CAS становится наблюдаемой.
    private static final int WORK_TOKENS = 200;

    private StackOps stack;

    @Setup(Level.Iteration)
    public void setupIteration() {
        stack = Stacks.create(impl);
        for (int i = 0; i < 10_000; i++) {
            stack.push(i);
        }
    }

    @Benchmark
    public int burstyPushPop() {
        stack.push(42);
        Blackhole.consumeCPU(WORK_TOKENS);
        int v;
        try {
            v = stack.pop();
        } catch (EmptyStackException e) {
            v = -1;
        }
        Blackhole.consumeCPU(WORK_TOKENS);
        return v;
    }
}
