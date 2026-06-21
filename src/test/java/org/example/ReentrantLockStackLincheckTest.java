package org.example;

import org.jetbrains.lincheck.datastructures.IntGen;
import org.jetbrains.lincheck.datastructures.ModelCheckingOptions;
import org.jetbrains.lincheck.datastructures.Operation;
import org.jetbrains.lincheck.datastructures.Param;
import org.jetbrains.lincheck.datastructures.StressOptions;
import org.junit.jupiter.api.Test;

// Self-test на линеаризуемость LIFO-стека под ReentrantLock — класс является и SUT,
// и последовательной спецификацией.
@Param(name = "value", gen = IntGen.class, conf = "1:5")
public class ReentrantLockStackLincheckTest {

    private final ReentrantLockStack stack = new ReentrantLockStack();

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
