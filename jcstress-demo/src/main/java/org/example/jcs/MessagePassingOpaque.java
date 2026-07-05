package org.example.jcs;

import org.openjdk.jcstress.annotations.Actor;
import org.openjdk.jcstress.annotations.JCStressTest;
import org.openjdk.jcstress.annotations.Outcome;
import org.openjdk.jcstress.annotations.Expect;
import org.openjdk.jcstress.annotations.State;
import org.openjdk.jcstress.infra.results.II_Result;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

// Message passing with TWO INDEPENDENT variables (no address dependency, unlike
// the reference-publication test). data is plain; flag is published/read OPAQUE.
// On weak-memory hardware the reader can observe flag==1 while data==0, because
// opaque carries no release/acquire ordering. This is the reorder that the
// acquire barrier on a lock-free head read exists to forbid.
@JCStressTest
@Outcome(id = "0, 0", expect = Expect.ACCEPTABLE,             desc = "flag not seen, data not seen")
@Outcome(id = "0, 1", expect = Expect.ACCEPTABLE,             desc = "flag not seen, data seen")
@Outcome(id = "1, 1", expect = Expect.ACCEPTABLE,             desc = "both seen")
@Outcome(id = "1, 0", expect = Expect.ACCEPTABLE_INTERESTING, desc = "flag seen but data==0 — OPAQUE REORDER")
@State
public class MessagePassingOpaque {

    int data;
    int flag;
    static final VarHandle FLAG;
    static {
        try {
            FLAG = MethodHandles.lookup().findVarHandle(MessagePassingOpaque.class, "flag", int.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @Actor
    public void writer() {
        data = 1;
        FLAG.setOpaque(this, 1);           // opaque publish
    }

    @Actor
    public void reader(II_Result r) {
        r.r1 = (int) FLAG.getOpaque(this); // opaque read
        r.r2 = data;                       // independent plain read (no dependency)
    }
}
