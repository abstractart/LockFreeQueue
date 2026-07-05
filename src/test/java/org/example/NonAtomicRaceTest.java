package org.example;

import org.jetbrains.lincheck.datastructures.IntGen;
import org.jetbrains.lincheck.datastructures.ModelCheckingOptions;
import org.jetbrains.lincheck.datastructures.Operation;
import org.jetbrains.lincheck.datastructures.Param;
import org.junit.jupiter.api.Test;

import java.util.EmptyStackException;

import static org.junit.jupiter.api.Assertions.assertThrows;

// Proof that Lincheck is NOT trivially green: this stack does a non-atomic
// read-modify-write on head (plain read, then plain write, no CAS), so a
// concurrent push is lost. That is broken even under sequential consistency,
// and Lincheck's model checker finds the interleaving and fails the check.
//
// We assert specifically an AssertionError (Lincheck throws LincheckAssertionError,
// a subclass) — NOT a broad Throwable — so a mere setup/reflection error would
// still surface as a real test failure instead of masquerading as "caught".
@Param(name = "value", gen = IntGen.class, conf = "1:5")
public class NonAtomicRaceTest {

    private PlainNode head;

    @Operation
    public void push(@Param(name = "value") int v) {
        PlainNode n = new PlainNode(v);
        n.next = head;     // plain read of head ...
        head = n;          // ... then plain write: a concurrent push is lost
    }

    @Operation
    public int pop() {
        PlainNode t = head;
        if (t == null) throw new EmptyStackException();
        head = t.next;
        return t.val;
    }

    @Test
    public void modelCheckingCatchesTheRace() {
        assertThrows(AssertionError.class, () ->
                new ModelCheckingOptions().iterations(LincheckConfig.ITERATIONS).threads(3).actorsPerThread(3)
                        .invocationsPerIteration(LincheckConfig.INVOCATIONS_PER_ITERATION).check(this.getClass()));
    }
}
