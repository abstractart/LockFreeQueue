package org.example;

import org.jetbrains.lincheck.datastructures.IntGen;
import org.jetbrains.lincheck.datastructures.ModelCheckingOptions;
import org.jetbrains.lincheck.datastructures.Operation;
import org.jetbrains.lincheck.datastructures.Param;
import org.jetbrains.lincheck.datastructures.StressOptions;
import org.junit.jupiter.api.Test;

// Self-test на линеаризуемость FIFO-очереди — класс является и SUT, и последовательной спецификацией.
@Param(name = "value", gen = IntGen.class, conf = "1:5")
public class LockedQueueLincheckTest {

    private final LockedQueue queue = new LockedQueue();

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
                .iterations(50)
                .threads(3)
                .actorsPerThread(3)
                .invocationsPerIteration(1000)
                .check(this.getClass());
    }

    @Test
    public void modelCheckingTest() {
        new ModelCheckingOptions()
                .iterations(50)
                .threads(3)
                .actorsPerThread(3)
                .invocationsPerIteration(1000)
                .check(this.getClass());
    }
}
