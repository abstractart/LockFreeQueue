package org.example;

import org.jetbrains.lincheck.datastructures.IntGen;
import org.jetbrains.lincheck.datastructures.ModelCheckingOptions;
import org.jetbrains.lincheck.datastructures.Operation;
import org.jetbrains.lincheck.datastructures.Param;
import org.jetbrains.lincheck.datastructures.StressOptions;
import org.junit.jupiter.api.Test;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.EmptyStackException;

// Demonstrates Lincheck's blind spot for weak memory.
//
// Lincheck's model checker and stress runner explore thread INTERLEAVINGS under
// SEQUENTIAL CONSISTENCY. They do not simulate the JMM's weak-memory access
// modes: a getOpaque read and a getVolatile read are indistinguishable to
// Lincheck, because under SC every read already sees the latest write.
//
// So this OPAQUE-head Treiber stack is reported linearizable — even though by
// the JMM its opaque head read has no synchronizes-with edge to the publishing
// seq-cst CAS, so linearizability is NOT guaranteed on real weak-memory
// hardware (ARM). Compare with VolatileHeadControlTest (genuinely linearizable,
// also green): Lincheck returns the SAME verdict for both. That identical green
// is the blind spot. NonAtomicRaceTest shows Lincheck is not trivially green —
// it catches an SC-level race (no CAS).
@Param(name = "value", gen = IntGen.class, conf = "1:5")
public class OpaqueBlindSpotLincheckTest {

    private SafeNode head;
    private static final VarHandle H;
    static {
        try {
            H = MethodHandles.lookup().findVarHandle(OpaqueBlindSpotLincheckTest.class, "head", SafeNode.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @Operation
    public void push(@Param(name = "value") int v) {
        SafeNode n = new SafeNode(v);
        while (true) {
            SafeNode t = (SafeNode) H.getOpaque(this);   // <-- opaque read
            n.next = t;
            if (H.compareAndSet(this, t, n)) return;
        }
    }

    @Operation
    public int pop() {
        while (true) {
            SafeNode t = (SafeNode) H.getOpaque(this);   // <-- opaque read
            if (t == null) throw new EmptyStackException();
            SafeNode nx = t.next;
            if (H.compareAndSet(this, t, nx)) return t.val;
        }
    }

    @Test
    public void stressTest() {
        new StressOptions().iterations(LincheckConfig.ITERATIONS).threads(3).actorsPerThread(3)
                .invocationsPerIteration(LincheckConfig.INVOCATIONS_PER_ITERATION).check(this.getClass());
    }

    @Test
    public void modelCheckingTest() {
        new ModelCheckingOptions().iterations(LincheckConfig.ITERATIONS).threads(3).actorsPerThread(3)
                .invocationsPerIteration(LincheckConfig.INVOCATIONS_PER_ITERATION).check(this.getClass());
    }
}

// Safely-publishable node: final val (freeze) + volatile next (self-ordered).
// Shared by the opaque and volatile stacks.
class SafeNode {
    final int val;
    volatile SafeNode next;
    SafeNode(int val) { this.val = val; }
}

// Plain node for the non-atomic (no-CAS) race demonstration.
class PlainNode {
    int val;
    PlainNode next;
    PlainNode(int val) { this.val = val; }
}
