package org.example;

import org.jetbrains.lincheck.datastructures.IntGen;
import org.jetbrains.lincheck.datastructures.ModelCheckingOptions;
import org.jetbrains.lincheck.datastructures.Operation;
import org.jetbrains.lincheck.datastructures.Param;
import org.jetbrains.lincheck.datastructures.StressOptions;
import org.junit.jupiter.api.Test;

// Verifies PooledLockFreeStack is linearizable. This is also the real test of
// its ABA defence: the pool reuses slot indices, so without the packed version
// counter an interleaving could let a stale CAS on `top` succeed against a
// recycled index and corrupt the stack. Lincheck explores those interleavings;
// if the version scheme were wrong, it would produce a non-linearizable history.
// The default capacity (1024) far exceeds the few elements 3x3 actors can stack,
// so the pool never exhausts and push never throws during the check.
@Param(name = "value", gen = IntGen.class, conf = "1:5")
public class PooledLockFreeStackLincheckTest {

    private final PooledLockFreeStack stack = new PooledLockFreeStack();

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
