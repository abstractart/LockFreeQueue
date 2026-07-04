package org.example;

import org.jetbrains.lincheck.datastructures.IntGen;
import org.jetbrains.lincheck.datastructures.ModelCheckingOptions;
import org.jetbrains.lincheck.datastructures.Operation;
import org.jetbrains.lincheck.datastructures.Param;
import org.jetbrains.lincheck.datastructures.StressOptions;
import org.junit.jupiter.api.Test;

// Sequential-семантика BackoffLockFreeStack совпадает с LIFO (backoff
// active только при contention, не меняет наблюдаемое поведение).
@Param(name = "value", gen = IntGen.class, conf = "1:5")
public class BackoffLockFreeStackLincheckTest {

    private final BackoffLockFreeStack stack = new BackoffLockFreeStack();

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
