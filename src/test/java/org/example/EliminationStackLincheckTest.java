package org.example;

import org.jetbrains.lincheck.datastructures.IntGen;
import org.jetbrains.lincheck.datastructures.ModelCheckingOptions;
import org.jetbrains.lincheck.datastructures.Operation;
import org.jetbrains.lincheck.datastructures.Param;
import org.jetbrains.lincheck.datastructures.StressOptions;
import org.junit.jupiter.api.Test;

// Lincheck использует тот же класс как объект под тестом и как sequential
// specification. Sequential-семантика EliminationStack совпадает с LIFO
// (elimination активируется только при contention), поэтому self-check
// валиден.
@Param(name = "value", gen = IntGen.class, conf = "1:5")
public class EliminationStackLincheckTest {

    private final EliminationStack stack = new EliminationStack();

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
