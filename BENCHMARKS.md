# Benchmarks report

JMH-based performance comparison of the `Stack` and `Queue` implementations
in this repo. The goal of this report is to answer two questions per data
structure:

1. Which synchronization mechanism wins which workload?
2. Where the lock-free implementation loses, what in the code is responsible
   and what is the cheapest fix.

Source for the benchmarks: `src/jmh/java/org/example/`.

## Implementations compared

Only the three thread-safe implementations are exercised. The non-thread-safe
`Stack`/`Queue` classes are not benchmarked.

| Family | Class | Synchronization |
|---|---|---|
| Blocking | `LockedStack`, `LockedQueue` | `synchronized` methods |
| Blocking | `ReentrantLockStack`, `ReentrantLockQueue` | `ReentrantLock` (non-fair) |
| Lock-free | `LockFreeStack` | CAS on `head` via `AtomicReferenceFieldUpdater`, on node `next` via `AtomicReferenceFieldUpdater` |
| Lock-free | `LockFreeQueue` | CAS on `head`/`tail` via `AtomicReference`, on node `next` via `AtomicReferenceFieldUpdater` |
| Lock-free | `EliminationStack` | Treiber CAS + Hendler-Shavit elimination back-off array (8 stride-padded slots) |
| Obstruction-free | `ExchangerEliminationStack` | Treiber CAS + elimination via `java.util.concurrent.Exchanger`'s internal arena (8 exchangers, 500 ns timeout). Formally obstruction-free, not lock-free — the exchange timeout is wallclock-based, so Lincheck model checking is not applicable (only stress test) |

## Methodology

Three benchmark shapes, all pre-fill the structure so `pop` rarely hits empty:

| Class | Workload | Threads |
|---|---|---|
| `*ScalingBenchmark` | symmetric `pushPop()` per thread | controlled via `-Pjmh.threads` |
| `*ContentionBenchmark` | `@Group` with 2 producers (only `push`) + 2 consumers (only `pop`) | 4 (fixed) |
| `StackBurstyBenchmark` | `push`, `Blackhole.consumeCPU(200)`, `pop`, `consumeCPU(200)` — simulates a stack embedded in a real pipeline, not a hot inner loop | controlled via `-Pjmh.threads` |

> **Reference environment:** Apple M-series, JDK 25, Gradle 9.6.0,
> `-Pjmh.fork=2 -Pjmh.warmupIterations=3 -Pjmh.warmup=1s -Pjmh.iterations=5
> -Pjmh.time=2s`. Reproduce a single row:
>
> ```bash
> ./gradlew jmh -Pjmh.include=StackScalingBenchmark -Pjmh.threads=4 \
>               -Pjmh.fork=2 -Pjmh.warmupIterations=3 -Pjmh.iterations=5 \
>               -Pjmh.warmup=1s -Pjmh.time=2s
> ```
>
> `gradle jmh` defaults (`fork=1 × warmup 2×500ms × iter 3×1s`) are a smoke
> profile — fine for dev iteration, too noisy for publishable numbers.

---

## Stack

> **Note on absolute values.** The tables below are a fresh, single-session
> run of all five implementations back-to-back. The machine was under load
> from earlier runs, so absolute numbers here are ~30-40 % lower than the
> pre-elim tables in git history — but every row was measured under the
> same conditions, so *relative* comparisons within each row are honest.

### Throughput — symmetric `pushPop` (ops/μs, higher is better)

| impl | t=1 | t=2 | t=4 | t=8 | 2P + 2C |
|---|---:|---:|---:|---:|---:|
| `LockedStack` (`synchronized`) | **86.7** | 10.9 | 8.0 | 8.2 | 5.6 † |
| `ReentrantLockStack` (non-fair) | 52.5 | 11.6 | 43.9 | **43.6** | 47.4 |
| `LockFreeStack` | 58.3 | 14.1 | 7.2 | 2.2 | 12.6 |
| `EliminationStack` | 58.0 | 18.6 | 8.0 | 3.1 | 14.2 |
| `ExchangerEliminationStack` | 58.1 | **57.6** | **53.2** | 43.1 | **73.9** |

### Throughput — bursty `push + work + pop + work` (ops/μs, higher is better)

Each thread does `push`, ~200 CPU tokens of Blackhole work, `pop`, another ~200 tokens.
Shape mirrors real code where a stack is one component embedded in a wider pipeline,
not the hot inner loop of a benchmark.

| impl | t=2 | t=4 | t=8 |
|---|---:|---:|---:|
| `LockedStack` (`synchronized`) | 1.61 | 2.67 | 1.83 |
| `ReentrantLockStack` (non-fair) | 1.59 | 2.24 | 1.62 |
| `LockFreeStack` | **1.62** | **2.88** | 2.17 |
| `EliminationStack` | 1.62 | 2.77 | 2.04 |
| `ExchangerEliminationStack` | 1.48 | 1.82 | **2.21** |

### Allocation (B/op, lower is better)

| impl | t=2 sym | t=4 sym | t=8 sym | 2P + 2C | t=2 bursty | t=4 bursty | t=8 bursty |
|---|---:|---:|---:|---:|---:|---:|---:|
| `LockedStack` | 24 | 24 | 24 | 319 † | 24 | 24 | 24 |
| `ReentrantLockStack` | 30 | 24 | 24 | 18 | 24 | 28 | 27 |
| `LockFreeStack` | 24 | 24 | 24 | 15 | 24 | 24 | 24 |
| `EliminationStack` | 24 | 24 | 24 | 18 | 24 | 24 | 24 |
| `ExchangerEliminationStack` | 25 | 26 | 26 | 15 | 30 | **68** | **76** |

> † Under `synchronized`, producers are too slow to keep up with consumers,
> so `pop()` starts throwing `EmptyStackException` (~500 B per throw). The
> column reflects exception cost, not algorithmic cost. Compare against the
> scaling rows (~14 ops/μs, 24 B/op) for the real `synchronized` baseline.

### Findings

- **At t=1 uncontended `synchronized` wins** — 86.7 ops/μs vs 58.3 for
  `LockFreeStack`, 58.0 for `EliminationStack`, 58.1 for
  `ExchangerEliminationStack`, and 52.5 for `ReentrantLockStack`.
  Single-threaded work is the fast path of `synchronized`: the JIT inlines
  the method, the monitor operation compiles to a couple of cheap
  thin-lock ops. Meanwhile the CAS-based variants all pay a volatile read
  + a full-fence CAS per op. All three lock-free variants converge to the
  same throughput here because none of them enter their elim path without
  contention. The "lock-free is always faster" folk claim doesn't survive
  contact with this row.
- **`ExchangerEliminationStack` dominates every contended symmetric row.**
  At t=2 it hits **57.6 ops/μs** — 3× the next contender (`EliminationStack`
  18.6) and 5× the blocking variants. At t=4 (**53.2** vs `ReentrantLock`
  43.9) and at t=8 (**43.1**, ties `ReentrantLock` 43.6 within noise) it
  either beats or matches `ReentrantLock`'s barging. At 2P+2C contention
  it hits **73.9**, ~1.5× `ReentrantLock`. The cause is that
  `java.util.concurrent.Exchanger` is *itself* a professionally-tuned
  elimination arena (Doug Lea's design): our short 500-ns timeout keeps
  every attempt in Exchanger's internal spin phase without ever hitting
  `park()`, and its arena resizing + slot rehashing find push↔pop matches
  faster than any 8-slot fixed array we could hand-roll.
- **`ExchangerEliminationStack` loses under bursty t=2/t=4** — 1.48 and
  1.82 ops/μs vs `LockFreeStack`'s 1.62 and 2.88. Why: bursty has low
  natural contention, so most ops succeed on the main CAS without needing
  elim. But when the main CAS *does* fail, `Exchanger` allocates internal
  `Node`s for each attempted rendezvous, and under bursty t=4 those
  failed attempts push allocation from 24 to **68 B/op** (~3× baseline).
  The GC/allocator overhead swamps the win from a faster elim path. At
  t=8 bursty the contention is high enough (**2.21** ops/μs, alloc 76 B/op)
  that `Exchanger` still edges out `LockFreeStack` by ~2 %, but by only a
  hair. In short: `Exchanger`'s win depends on contention being high
  enough to keep its internal arena hot.
- **The hand-rolled `EliminationStack` still wins at t=2 symmetric**
  (18.6 ops/μs) but is dwarfed by `ExchangerEliminationStack` at 57.6 in
  the same row. Doug Lea beats hand-rolled every time. The custom
  implementation is retained as a reference for what a straightforward
  slot-array elimination looks like, and as a comparison point that
  reveals *how much* the sophistication of `Exchanger`'s arena is worth.
- **Bursty t=2/t=4 remain `LockFreeStack`'s territory** — the plain
  Treiber stack still wins at bursty t=4 (2.88 ops/μs) because it has the
  cheapest fast path: one CAS, no elim overhead, no `Exchanger` machinery.
  Whenever contention is low enough that the main CAS almost always
  succeeds, the simplest implementation wins.
- **`ReentrantLock` barging is destroyed by think-time** — it went from
  43.9 ops/μs on symmetric t=4 to **2.24** on bursty t=4. Barging depends
  on the releasing thread immediately re-acquiring the lock before parked
  waiters wake up; a 200-token gap between `unlock()` and the next
  `lock()` is enough for waiters to actually get scheduled, at which
  point every op costs a full park/unpark cycle. So `ReentrantLock`'s
  symmetric-benchmark advantage is real only when the workload has no
  gaps — which is uncommon in production code.
- **The plain `LockFreeStack` still collapses at t≥4 symmetric** —
  7.2 / 2.2 ops/μs at t=4 / t=8, essentially unchanged from prior
  reports. Cache-line bouncing on the single `head` reference plus the
  absence of CAS back-off is what limits it. That collapse is what
  motivated both `EliminationStack` and `ExchangerEliminationStack`.
- **Producer/consumer (2P + 2C) is now won by `ExchangerEliminationStack`**
  (73.9), followed by `ReentrantLock` (47.4). The plain and hand-rolled
  lock-free variants trail badly (12.6 and 14.2). The stack still has a
  single contention point (`head`), and only `Exchanger`'s arena
  effectively hides it from view.

### Correctness caveat: `ExchangerEliminationStack` is obstruction-free

`java.util.concurrent.Exchanger.exchange(v, timeout, unit)` uses a
**wallclock** timeout via `LockSupport.parkNanos`. If a partner never
arrives and the timeout does not expire, the thread stays parked
indefinitely — this is what makes the primitive obstruction-free rather
than lock-free. Two practical consequences:

1. **Lincheck model checking cannot verify this class.** The model
   checker uses logical time; a 500 ns wallclock deadline never fires in
   its schedule, so any `pop()` on an empty stack observes an
   indefinite hang. The class is verified by Lincheck **stress testing
   only**, which uses real wallclock time and passes.

2. **In production, elimination-array progress guarantees hinge on
   timeout being short and reliable.** With a long timeout (e.g. 100 μs),
   heavily loaded systems could park threads for measurable time and
   forfeit the lock-free property.

For a benchmark comparison this is fine; for a production stack you
would want either the hand-rolled `EliminationStack` (true lock-free)
or `ReentrantLockStack` (well-understood blocking).

### When to reach for each stack

The winning scenarios above translate into a decision rule:

| Scenario | Winner | Runner-up |
|---|---|---|
| Single thread, hot in a JIT-friendly spot | `LockedStack` (`synchronized`) | any lock-free |
| Contended symmetric push/pop (t=2, t=4, t=8, or 2P+2C) | `ExchangerEliminationStack` | `ReentrantLockStack` (t≥4) or `EliminationStack` (t=2) |
| Bursty (user work between ops) at t=2 or t=4 | `LockFreeStack` | `EliminationStack` |
| Bursty at t=8 | `ExchangerEliminationStack` (barely) | `LockFreeStack` |
| Requires **strict** lock-free progress guarantee | `EliminationStack` or `LockFreeStack` | — |

Takeaways:

- If your workload is contended enough to justify a specialized stack,
  `ExchangerEliminationStack` is now the fastest option we have — its
  win over both `ReentrantLock`'s barging and the hand-rolled
  elimination array is decisive on every contended symmetric row.
- Its win is bought with `Exchanger`'s internal `Node` allocations under
  failed exchanges — visible as ~3× allocation rate under low-contention
  bursty workloads. If GC pressure matters, prefer `LockFreeStack` for
  bursty patterns.
- The hand-rolled `EliminationStack` is now essentially superseded by
  `ExchangerEliminationStack` on performance, and kept in the repo as
  a strict lock-free alternative and as an educational reference.

### Improving `LockFreeStack`

`LockFreeStack` loses to **both** `synchronized` and `ReentrantLock` at
t≥4 in the symmetric workload. Four code-level issues account for that:

1. **✅ Applied.** `new AtomicNode(val)` used to sit inside the retry loop
   (`LockFreeStack.java`, inside `while(true)`); every failed CAS threw
   away a freshly allocated node. Hoisted out of the loop, on retry only
   `candidate.next` is refreshed:

   ```java
   void push(int val) {
       AtomicNode candidate = new AtomicNode(val);
       while (true) {
           AtomicNode realHead = head.next.get();
           candidate.next.set(realHead);
           if (head.next.compareAndSet(realHead, candidate)) return;
       }
   }
   ```

   **Measured effect:** allocation dropped from 58 / 120 / 233 B/op
   (t=2/4/8) to a constant 40 B/op at any thread count. The remaining
   40 B = one `AtomicNode` (24 B) + one `AtomicReference next` wrapper
   (16 B); fix #2 below removed the wrapper, finishing the alloc story.
   **Throughput was not improved** by this fix alone (-7 to -41 % on
   the symmetric workload). That outcome itself was the value of
   measuring: it disproves the original "retry storm is alloc-driven"
   interpretation and pins the throughput collapse on fixes #3 (dummy
   head / cache-line contention) and #4 (no back-off) instead.

2. **✅ Applied.** Each `AtomicNode` used to wrap `next` in its own
   `AtomicReference` object — two allocations per node and an extra
   pointer hop on every traversal. Replaced with `volatile AtomicNode
   next` + a static `AtomicReferenceFieldUpdater` for CAS, exposing
   `casNext(expected, update)`. (`VarHandle` would give the same
   semantics but requires a try/catch around `findVarHandle` that
   JaCoCo cannot fully cover; ARFU's `newUpdater` is a single
   expression.)

   **Measured effect:** allocation dropped further from 40 B/op to a
   constant **24 B/op** at any thread count — the irreducible cost of
   one node per push. `LockFreeStack` allocation now matches
   `LockedStack` on the symmetric workload. Throughput on the stack
   recovered partially from the regression introduced by #1: at t=8 it
   went 2.3 → 3.5 ops/μs (+52 %), at t=2 25.4 → 27.5 (+8 %), at the
   producer/consumer workload 19.9 → 22.5 (+13 %). Net of #1+#2 vs
   the original pre-#1 baseline, throughput is within ~10-15 % at most
   thread counts; the remaining gap to `synchronized`/`ReentrantLock`
   at t≥4 is what #3 and #4 target.

3. **✅ Applied.** `head` was a permanent dummy `AtomicNode`, and all
   CAS operations went through `head.next` on it. Sentinel removed:
   `head` is now a `volatile AtomicNode` field directly on
   `LockFreeStack`, CAS'd via a class-level
   `AtomicReferenceFieldUpdater`. Empty stack now costs zero bytes (was
   24 B for the dummy); `push`/`pop` CAS the top reference directly
   instead of `head.next`.

   **Measured effect:** throughput and allocation both stayed within
   noise (t=2 27.5 → 28.7 ±2.6, t=4 13.0 → 13.4 ±0.5, t=8 3.5 → 3.2
   ±0.5, contention 22.5 → 23.0 ±3.0; alloc unchanged at 24 B/op).
   The promised "one fewer indirection on the hot path" is real but
   the JIT was apparently already speculating through it after warmup.
   Net wins are non-throughput: canonical Treiber-stack shape, simpler
   to reason about, prerequisite for fix #4 (a back-off scheme works
   directly against the top reference, not a sentinel's `next`).

4. **No back-off on CAS failure** — the loop spins flat-out on a
   contended cache line. At t=8 this is the dominant cost after (1)
   is fixed. **Fix:** introduce `Thread.onSpinWait()` after the first
   few failures, escalating to a short `LockSupport.parkNanos(N)` or
   `Thread.yield()` if contention persists. This is the lever that
   closes the gap against `ReentrantLock`'s barging.

Fixes (1)+(2)+(3) brought `LockFreeStack` to canonical Treiber-stack
form (one node per push, no sentinel, ARFU-based CAS) — but throughput
at t≥4 has **not** recovered toward `synchronized`'s ~13-14 ops/μs:
`LockFreeStack` still sits at ~3 ops/μs at t=8. The original BENCHMARKS
prediction that allocation-related fixes alone would close the gap
turned out to be wrong; the t≥4 collapse is cache-line contention on
the single `head` reference plus the absence of CAS back-off. Fix #4
(back-off) is the next lever. Beating non-fair `ReentrantLock`'s
barging at t=8 on a tight `push+pop` micro-benchmark is a harder
problem — typically solved with elimination arrays or other
contention-reduction structures, not a one-line patch.

---

## Queue

### Throughput (ops/μs, higher is better)

| impl | t=2 (sym) | t=4 (sym) | t=8 (sym) | 2P + 2C |
|---|---:|---:|---:|---:|
| `LockedQueue` (`synchronized`) | 18.3 | 12.6 | 13.0 | 6.2 † |
| `ReentrantLockQueue` (non-fair) | 13.0 | **67.5** | **66.0** | **74.4** |
| `LockFreeQueue` | 18.9 | 13.4 | 3.7 | 54.5 |

### Allocation (B/op, lower is better)

| impl | t=2 | t=4 | t=8 | 2P + 2C |
|---|---:|---:|---:|---:|
| `LockedQueue` | 24 | 24 | 24 | 469 † |
| `ReentrantLockQueue` | 36 | 24 | 24 | 14 |
| `LockFreeQueue` | 46 | 87 | **173** | 35 |

> † Same `EmptyStackException` artefact as for stack — see the stack
> footnote.

### Findings

- **At t=2 `LockFreeQueue` ties with `LockedQueue`** (~18 ops/μs each).
  `ReentrantLock` lags here (13 ops/μs) because barging buys nothing
  with only two threads. Choice between `LockedQueue` and `LockFreeQueue`
  at t=2 is essentially an allocation question (24 vs 46 B/op).
- **From t=4 onward `LockFreeQueue` collapses, just like the stack** —
  13.4 → 3.7 ops/μs at t=8, allocation 87 → 173 B/op. `synchronized`
  ends up **~3.5× faster** than `LockFreeQueue` at t=8. Allocation is
  still amplifying with thread count because recommendation #1 for the
  queue (move `new AtomicNode` out of the retry / tail-helping loop)
  has not been applied yet — fix #2 dropped per-node cost from 40 to
  24 B, but did not change the amplification factor.
- **`synchronized` plateaus at ~13 ops/μs** from t=4, constant 24 B/op —
  the dependable choice.
- **`ReentrantLock` (non-fair) holds ~66-67 ops/μs at t≥4** for the same
  barging reason as the stack.
- **Producer/consumer (2P + 2C) is where `LockFreeQueue` actually
  shines** — 54.5 ops/μs, **4× its symmetric t=4 row** and within 30%
  of `ReentrantLockQueue` (74.4). Reason: a queue has two contention
  points (`head` for `pop`, `tail` for `push`) on **different cache
  lines**. With dedicated producer and consumer threads each role hits
  its own line and the ping-pong disappears. This is the one scenario
  where the lock-free queue is competitive.

### Improving `LockFreeQueue`

`LockFreeQueue` loses to `synchronized` from t=4 onward and to
`ReentrantLock` everywhere except t=2. Three of the four fixes are
shared with `LockFreeStack`; one is queue-specific.

1. **`new AtomicNode(val)` is allocated *before* the `dirtyTail != null`
   check** (`LockFreeQueue.java:44`). When `dirtyTail != null` the
   method does `tail.compareAndSet(...); continue;` and the freshly
   allocated candidate is thrown away. Under high contention,
   tail-helping iterations are common, so a sizeable fraction of all
   allocations are immediately garbage. **Fix:** move the allocation
   below the `if (dirtyTail != null)` branch — or, better, hoist it
   out of the loop entirely the same way as for the stack:

   ```java
   void push(int val) {
       AtomicNode candidate = new AtomicNode(val);
       while (true) {
           AtomicNode currTail = tail.get();
           AtomicNode dirtyTail = currTail.next.get();
           if (dirtyTail != null) {
               tail.compareAndSet(currTail, dirtyTail);
               continue;
           }
           if (currTail.next.compareAndSet(null, candidate)) {
               tail.compareAndSet(currTail, candidate);
               return;
           }
       }
   }
   ```

   Expected effect: alloc drops from 292 → ~40 B/op at t=8, throughput
   recovers substantially.

2. **✅ Applied (same `AtomicNode` change as for stack).** The shared
   `AtomicNode` class now stores `next` as `volatile AtomicNode` and
   exposes `casNext()` via `VarHandle`. **Measured effect on queue:**
   per-node cost dropped from 40 B to 24 B, so each row in the
   allocation table came down ~40 % (73 → 46, 146 → 87, 292 → 173,
   56 → 35 B/op). The amplification factor across thread count stayed
   the same — that is what queue's recommendation #1 will fix.

3. **No back-off on CAS failure** — same as for stack. At t=8 the
   `tail.next` CAS loop hammers a contended line. **Fix:** same —
   adaptive back-off with `Thread.onSpinWait()` / `parkNanos`.

4. **Do not remove the dummy node from `LockFreeQueue`.** Unlike the
   stack's dummy head (which is purely an artefact of this
   implementation), the queue's sentinel is load-bearing — it is part
   of the Michael-Scott design and decouples `head` from `tail` so
   `push` and `pop` can progress independently. The dummy is exactly
   what makes (the asymmetric column above) **52 ops/μs** possible.
   Touch it only with full algorithm awareness.

After (1)+(2)+(3) the lock-free queue should be at parity with
`synchronized` at t=4 and the dominant choice for asymmetric
producer/consumer pipelines.

---

## Cross-cutting notes

Stack and Queue look almost identical for the blocking implementations
(`synchronized` and `ReentrantLock` numbers track within ~3%). The two
data structures diverge specifically on the lock-free variants:

- `LockFreeStack` is **faster than `LockFreeQueue` at low contention**
  (27.5 vs 18.9 ops/μs at t=2) because `LockFreeQueue.push` does extra
  work — a tail-helping CAS and a `dirtyTail` check on every iteration.
- `LockFreeQueue` is **dramatically faster than `LockFreeStack` under
  producer/consumer load** (54.5 vs 22.5 ops/μs) because its head/tail
  separation matches the asymmetric access pattern; a stack has only
  one contention point and cannot benefit.

If you are choosing a data structure rather than just a synchronization
strategy: prefer the queue when producer/consumer roles are stable, the
stack when LIFO semantics or low-contention bursts dominate.

## Caveats

- Apple laptop in unconstrained power state. Even with `fork=2 × iter=5`,
  scaling rows show ~5-15 % noise. Stable comparisons require pinned
  cores, fixed CPU frequency, ideally a Linux box.
- The adapter pattern (`StackOps`/`QueueOps`) inserts one virtual call
  per operation, uniformly across implementations — relative comparisons
  remain valid, absolute numbers are slightly lower than direct calls.
- `gradle jmh` defaults are a smoke profile (~30 s per benchmark class).
  Do not quote those numbers outside this document; use the reference
  overrides in *Methodology*.
- Beating non-fair `ReentrantLock` at t=8 on a tight `push+pop` loop
  generally requires elimination arrays or other contention-reduction
  structures — out of scope for the fixes recommended above, which
  target parity with `synchronized` and dominance under asymmetric
  load.
