package org.example;

import org.jetbrains.lincheck.datastructures.IntGen;
import org.jetbrains.lincheck.datastructures.ModelCheckingOptions;
import org.jetbrains.lincheck.datastructures.Operation;
import org.jetbrains.lincheck.datastructures.Param;
import org.jetbrains.lincheck.datastructures.StressOptions;
import org.junit.jupiter.api.Test;

// Sequential semantics of FlatCombiningStack are plain LIFO (the combiner
// applies operations sequentially under a single lock), so the class doubles as
// its own sequential specification for Lincheck's self-check.
//
// Both stress and model checking are run: the combiner lock is an ordinary CAS
// spinlock over a bounded critical section (serve each pending record once),
// and a non-combiner waiter re-attempts the lock every spin, so every operation
// has a path to completion — no wallclock timers are involved, unlike
// ExchangerEliminationStack, so the model checker applies cleanly here.
@Param(name = "value", gen = IntGen.class, conf = "1:5")
public class FlatCombiningStackLincheckTest {

    private final FlatCombiningStack stack = new FlatCombiningStack();

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
