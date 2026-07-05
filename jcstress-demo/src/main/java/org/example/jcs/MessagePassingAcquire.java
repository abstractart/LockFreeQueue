package org.example.jcs;

import org.openjdk.jcstress.annotations.Actor;
import org.openjdk.jcstress.annotations.JCStressTest;
import org.openjdk.jcstress.annotations.Outcome;
import org.openjdk.jcstress.annotations.Expect;
import org.openjdk.jcstress.annotations.State;
import org.openjdk.jcstress.infra.results.II_Result;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

// Control: same message passing, but flag is published with setRelease and read
// with getAcquire. The acquire read synchronizes-with the release write, so
// (flag==1, data==0) is FORBIDDEN. This is the ordering a correct lock-free
// head read provides.
@JCStressTest
@Outcome(id = "0, 0", expect = Expect.ACCEPTABLE, desc = "flag not seen, data not seen")
@Outcome(id = "0, 1", expect = Expect.ACCEPTABLE, desc = "flag not seen, data seen")
@Outcome(id = "1, 1", expect = Expect.ACCEPTABLE, desc = "both seen")
@Outcome(id = "1, 0", expect = Expect.FORBIDDEN,  desc = "release/acquire forbids this reorder")
@State
public class MessagePassingAcquire {

    int data;
    int flag;
    static final VarHandle FLAG;
    static {
        try {
            FLAG = MethodHandles.lookup().findVarHandle(MessagePassingAcquire.class, "flag", int.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @Actor
    public void writer() {
        data = 1;
        FLAG.setRelease(this, 1);           // release publish
    }

    @Actor
    public void reader(II_Result r) {
        r.r1 = (int) FLAG.getAcquire(this); // acquire read
        r.r2 = data;
    }
}
