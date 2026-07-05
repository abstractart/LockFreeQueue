package org.example.jcs;

import org.openjdk.jcstress.annotations.Actor;
import org.openjdk.jcstress.annotations.JCStressTest;
import org.openjdk.jcstress.annotations.Outcome;
import org.openjdk.jcstress.annotations.Expect;
import org.openjdk.jcstress.annotations.State;
import org.openjdk.jcstress.infra.results.I_Result;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

// THE BARRIER FIX. Same plain (non-final) `val`, but the reference is published
// with setRelease and read with getAcquire. The acquire read synchronizes-with
// the release write, so everything before the release (val=42) is visible after
// the acquire. The (val==0) reorder is FORBIDDEN. This is the ordering
// LockFreeStack actually relies on: a volatile/acquire head read.
@JCStressTest
@Outcome(id = "42", expect = Expect.ACCEPTABLE, desc = "fully published")
@Outcome(id = "-1", expect = Expect.ACCEPTABLE, desc = "not published yet")
@Outcome(id = "0",  expect = Expect.FORBIDDEN,  desc = "release/acquire must safely publish val")
@State
public class ReleaseAcquirePublication {

    static class Holder {
        int val;                       // plain, NON-final
        Holder(int v) { val = v; }
    }

    Holder h;
    static final VarHandle H;
    static {
        try {
            H = MethodHandles.lookup().findVarHandle(ReleaseAcquirePublication.class, "h", Holder.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @Actor
    public void writer() {
        H.setRelease(this, new Holder(42));      // release publish
    }

    @Actor
    public void reader(I_Result r) {
        Holder x = (Holder) H.getAcquire(this);  // acquire read
        r.r1 = (x == null) ? -1 : x.val;
    }
}
