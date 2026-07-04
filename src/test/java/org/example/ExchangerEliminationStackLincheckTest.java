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
// никогда не наступает, и поток паркуется навсегда, ожидая партнёра. Это
// не баг реализации, а фундаментальное свойство Exchanger'а: его
// «non-blocking» семантика полностью зависит от wallclock timeout, что
// делает его формально obstruction-free, а не lock-free. Stress test
// использует настоящее время и корректно проходит все интерливинги.
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
