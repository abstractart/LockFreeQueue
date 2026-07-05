# jcstress-demo — weak-memory proof for the opaque head read

Standalone [jcstress](https://github.com/openjdk/jcstress) project. **Not part of
the main build** (the root `settings.gradle` does not `include` it, so `./gradlew`
and CI ignore it). It exists to answer one question from the t=1 investigation
(see `../BENCHMARKS.md` and the Lincheck tests `OpaqueBlindSpotLincheckTest` /
`VolatileHeadControlTest`):

> Reading `head` with `getOpaque` instead of `getVolatile` makes the lock-free
> stack ~77 % faster at t=1 — but is it correct? Lincheck says "linearizable"
> for both. Is Lincheck right?

**Lincheck's model checker assumes sequential consistency, so it cannot see the
difference.** jcstress runs on the real CPU and does. This project reproduces the
underlying weak-memory reorder.

## What each test shows

| Test | Publish / read of the flag | Field | Bad outcome | Expect |
|---|---|---|---|---|
| `MessagePassingOpaque` | `setOpaque` / `getOpaque` | plain, **independent** var | `flag=1, data=0` | **observed** |
| `MessagePassingAcquire` | `setRelease` / `getAcquire` | plain, independent var | `flag=1, data=0` | forbidden |
| `OpaquePlainPublication` | `setOpaque` / `getOpaque` | plain, **via the ref** | `ref!=null, val=0` | (dodged) |
| `OpaqueFinalPublication` | `setOpaque` / `getOpaque` | **final** via the ref | `ref!=null, val=0` | forbidden (freeze) |
| `ReleaseAcquirePublication` | `setRelease` / `getAcquire` | plain via the ref | `ref!=null, val=0` | forbidden |

## Result on Apple M (quick mode, ~390 M samples each)

```
MessagePassingOpaque    1, 0 :  81,976  (0.02%)  Interesting  <-- opaque reorder observed
MessagePassingAcquire   1, 0 :       0  (0.00%)  Forbidden    <-- barrier holds
```

The opaque message-passing test observes `(flag=1, data=0)` **81,976 times**; the
release/acquire control never does. That is the ordering a correct lock-free head
read must provide, and the exact thing Lincheck is blind to.

**Why the reference-publication tests did not fire on Apple M:** the reader's
`node.val` load has an *address dependency* on the head read (`head → node.val`),
and ARM honours dependent loads; with `final val` the freeze also forbids it. So
the `final val` + `volatile next` opaque stack survives on this hardware — its
non-linearizability stays a JMM spec gap. The message-passing tests use two
*independent* variables to remove that dependency and expose the reorder directly.

## Run it

Requires a local `gradle` (this folder has no wrapper) and a JDK 21 toolchain.

```bash
# message-passing pair (fast, shows the reorder)
gradle -p jcstress-demo jcstress -Pjcargs='-m quick -v -t MessagePassing'

# all five tests
gradle -p jcstress-demo jcstress -Pjcargs='-m quick -v'
```

macOS notes: jcstress prints non-fatal probe warnings (`taskset` missing, a
`@Contended` NPE) — harmless, the tests still run. An HTML report lands in
`jcstress-demo/results/`.
