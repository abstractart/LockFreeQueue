package org.example;

import org.jetbrains.lincheck.datastructures.IntGen;
import org.jetbrains.lincheck.datastructures.ModelCheckingOptions;
import org.jetbrains.lincheck.datastructures.Operation;
import org.jetbrains.lincheck.datastructures.Param;
import org.jetbrains.lincheck.datastructures.StressOptions;
import org.junit.jupiter.api.Test;

// Self-test на линеаризуемость по аналогии со стеком: класс является и SUT, и последовательной
// спецификацией. Lincheck перебирает интерливинги и проверяет, что результаты конкурентного
// исполнения объяснимы какой-либо последовательной историей операций FIFO-очереди.
@Param(name = "value", gen = IntGen.class, conf = "1:5")
public class LockFreeQueueLincheckTest {

    private final LockFreeQueue queue = new LockFreeQueue();

    @Operation
    public void push(@Param(name = "value") int v) {
        queue.push(v);
    }

    @Operation
    public int pop() {
        return queue.pop();
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

    @Test
    public void modelCheckingTest() {
        new ModelCheckingOptions()
                .iterations(LincheckConfig.ITERATIONS)
                .threads(3)
                .actorsPerThread(3)
                .invocationsPerIteration(LincheckConfig.INVOCATIONS_PER_ITERATION)
                .check(this.getClass());
    }
}
