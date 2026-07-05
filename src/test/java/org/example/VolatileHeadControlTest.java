package org.example;

import org.jetbrains.lincheck.datastructures.IntGen;
import org.jetbrains.lincheck.datastructures.ModelCheckingOptions;
import org.jetbrains.lincheck.datastructures.Operation;
import org.jetbrains.lincheck.datastructures.Param;
import org.junit.jupiter.api.Test;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.EmptyStackException;

// Control for OpaqueBlindSpotLincheckTest: identical Treiber stack but the head
// is read with getVolatile instead of getOpaque. This one IS guaranteed
// linearizable by the JMM (the volatile read synchronizes-with the seq-cst
// CAS). Lincheck passes it — exactly as it passes the opaque version. Same
// green for both is the point: Lincheck cannot see the memory-ordering
// difference that decides weak-memory correctness.
@Param(name = "value", gen = IntGen.class, conf = "1:5")
public class VolatileHeadControlTest {

    private SafeNode head;
    private static final VarHandle H;
    static {
        try {
            H = MethodHandles.lookup().findVarHandle(VolatileHeadControlTest.class, "head", SafeNode.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @Operation
    public void push(@Param(name = "value") int v) {
        SafeNode n = new SafeNode(v);
        while (true) {
            SafeNode t = (SafeNode) H.getVolatile(this);  // <-- volatile read
            n.next = t;
            if (H.compareAndSet(this, t, n)) return;
        }
    }

    @Operation
    public int pop() {
        while (true) {
            SafeNode t = (SafeNode) H.getVolatile(this);  // <-- volatile read
            if (t == null) throw new EmptyStackException();
            SafeNode nx = t.next;
            if (H.compareAndSet(this, t, nx)) return t.val;
        }
    }

    @Test
    public void modelCheckingTest() {
        new ModelCheckingOptions().iterations(LincheckConfig.ITERATIONS).threads(3).actorsPerThread(3)
                .invocationsPerIteration(LincheckConfig.INVOCATIONS_PER_ITERATION).check(this.getClass());
    }
}
