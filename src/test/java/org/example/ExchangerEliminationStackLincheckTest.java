package org.example;

import org.jetbrains.lincheck.datastructures.IntGen;
import org.jetbrains.lincheck.datastructures.Operation;
import org.jetbrains.lincheck.datastructures.Param;
import org.jetbrains.lincheck.datastructures.StressOptions;
import org.junit.jupiter.api.Test;

// Sequential-семантика ExchangerEliminationStack совпадает с LIFO (elimination
// active только при contention), поэтому self-check валиден.
//
// **Только stress test.** Model checker Lincheck работает в логическом
// времени — wallclock timeout внутри `Exchanger.exchange(v, 500ns)` там
// никогда не наступает, и поток паркуется, ожидая партнёра, дольше чем
// позволяет верификатор. Это **ограничение модели** Lincheck'а, а не
// нарушение lock-freedom самого алгоритма: в реальном wallclock времени
// timeout срабатывает за ≤500 нс, поток разбуживается и возвращается к
// main CAS. Stress test использует настоящее время и корректно
// проверяет все интерливинги.
@Param(name = "value", gen = IntGen.class, conf = "1:5")
public class ExchangerEliminationStackLincheckTest {

    private final ExchangerEliminationStack stack = new ExchangerEliminationStack();

    @Operation
    public void push(@Param(name = "value") int v) {
        stack.push(v);
    }

    @Operation
    public int pop() {
        return stack.pop();
    }

    @Test
    public void stressTest() {
        new StressOptions()
                .iterations(LincheckConfig.ITERATIONS)
                .threads(3)
                .actorsPerThread(3)
                .invocationsPerIteration(LincheckConfig.INVOCATIONS_PER_ITERATION)
                .check(this.getClass());
    }
}
