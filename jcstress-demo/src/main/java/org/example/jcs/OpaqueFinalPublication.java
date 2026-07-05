package org.example.jcs;

import org.openjdk.jcstress.annotations.Actor;
import org.openjdk.jcstress.annotations.JCStressTest;
import org.openjdk.jcstress.annotations.Outcome;
import org.openjdk.jcstress.annotations.Expect;
import org.openjdk.jcstress.annotations.State;
import org.openjdk.jcstress.infra.results.I_Result;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

// MY STACK'S PROTECTION. Same OPAQUE publication, but `val` is FINAL. Final-field
// freeze semantics guarantee that once the reference is visible, the final field
// is too — even without any release/acquire on the reference. So the (val==0)
// reorder is FORBIDDEN: this is why LockFreeStack's opaque-read variant survives
// on hardware only because AtomicNode.val would need to be final (it isn't today,
// so the real stack relies on the volatile/acquire head read instead).
@JCStressTest
@Outcome(id = "42", expect = Expect.ACCEPTABLE, desc = "fully published")
@Outcome(id = "-1", expect = Expect.ACCEPTABLE, desc = "not published yet")
@Outcome(id = "0",  expect = Expect.FORBIDDEN,  desc = "final val must be safely published")
@State
public class OpaqueFinalPublication {

    static class Holder {
        final int val;                 // FINAL
        Holder(int v) { val = v; }
    }

    Holder h;
    static final VarHandle H;
    static {
        try {
            H = MethodHandles.lookup().findVarHandle(OpaqueFinalPublication.class, "h", Holder.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @Actor
    public void writer() {
        H.setOpaque(this, new Holder(42));
    }

    @Actor
    public void reader(I_Result r) {
        Holder x = (Holder) H.getOpaque(this);
        r.r1 = (x == null) ? -1 : x.val;
    }
}
