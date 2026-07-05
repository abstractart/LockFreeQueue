package org.example.jcs;

import org.openjdk.jcstress.annotations.Actor;
import org.openjdk.jcstress.annotations.JCStressTest;
import org.openjdk.jcstress.annotations.Outcome;
import org.openjdk.jcstress.annotations.Expect;
import org.openjdk.jcstress.annotations.State;
import org.openjdk.jcstress.infra.results.I_Result;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

// THE PROBLEM. Publish a node reference with an OPAQUE store, where the node's
// data field `val` is a plain (non-final) field. A reader reads the reference
// opaquely, then reads `val`. Opaque provides no release/acquire ordering, so
// on weak-memory hardware (Apple M) the reference store can be observed before
// the `val=42` store — the reader sees a non-null node but reads val==0.
// This is exactly the reordering a lock-free stack's head read must prevent.
@JCStressTest
@Outcome(id = "42", expect = Expect.ACCEPTABLE,             desc = "fully published")
@Outcome(id = "-1", expect = Expect.ACCEPTABLE,             desc = "not published yet")
@Outcome(id = "0",  expect = Expect.ACCEPTABLE_INTERESTING, desc = "ref seen but val==0 — UNSAFE opaque publication")
@State
public class OpaquePlainPublication {

    static class Holder {
        int val;                       // plain, NON-final
        Holder(int v) { val = v; }
    }

    Holder h;
    static final VarHandle H;
    static {
        try {
            H = MethodHandles.lookup().findVarHandle(OpaquePlainPublication.class, "h", Holder.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @Actor
    public void writer() {
        H.setOpaque(this, new Holder(42));       // opaque publish
    }

    @Actor
    public void reader(I_Result r) {
        Holder x = (Holder) H.getOpaque(this);   // opaque read
        r.r1 = (x == null) ? -1 : x.val;
    }
}
