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
| Lock-free | `LockFreeStack`, `LockFreeQueue` | CAS on container head/tail via `AtomicReference`, on node `next` via `AtomicReferenceFieldUpdater` |

## Methodology

Two benchmark shapes, both pre-fill the structure so `pop` rarely hits empty:

| Class | Workload | Threads |
|---|---|---|
| `*ScalingBenchmark` | symmetric `pushPop()` per thread | controlled via `-Pjmh.threads` |
| `*ContentionBenchmark` | `@Group` with 2 producers (only `push`) + 2 consumers (only `pop`) | 4 (fixed) |

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

### Throughput (ops/μs, higher is better)

| impl | t=2 (sym) | t=4 (sym) | t=8 (sym) | 2P + 2C |
|---|---:|---:|---:|---:|
| `LockedStack` (`synchronized`) | 18.3 | 13.9 | 14.2 | 11.8 † |
| `ReentrantLockStack` (non-fair) | 18.1 | **69.3** | **68.0** | **94.1** |
| `LockFreeStack` | **27.5** | 13.0 | 3.5 | 22.5 |

### Allocation (B/op, lower is better)

| impl | t=2 | t=4 | t=8 | 2P + 2C |
|---|---:|---:|---:|---:|
| `LockedStack` | 24 | 24 | 24 | 318 † |
| `ReentrantLockStack` | 32 | 24 | 24 | 14 |
| `LockFreeStack` | 24 | 24 | 24 | 15 |

> † Under `synchronized`, producers are too slow to keep up with consumers,
> so `pop()` starts throwing `EmptyStackException` (~500 B per throw). The
> column reflects exception cost, not algorithmic cost. Compare against the
> scaling rows (~14 ops/μs, 24 B/op) for the real `synchronized` baseline.

### Findings

- **At low contention (t=2) `LockFreeStack` still leads** — 27.5 ops/μs vs
  18.3 for `synchronized`, 18.1 for `ReentrantLock`. CAS rarely needs to
  retry, the cache line bounces only between two cores. After fixes #1
  (hoist `new AtomicNode` out of retry) and #2 (`VarHandle` for `next`),
  allocation is a **constant 24 B/op at any thread count** — exactly one
  node per successful push, no more. The original code amplified
  allocation from 58 B/op at t=2 to 233 B/op at t=8; that path is gone.
- **From t=4 onward `LockFreeStack` still collapses** — 13.0 → 3.5 ops/μs
  at t=8. The two alloc-targeted fixes drove allocation to its theoretical
  floor but **did not recover throughput** under high contention. This
  pins the bottleneck on cache-line bouncing on `head.next` and the
  absence of CAS back-off (recommendations #3-#4 below). Allocation rate
  was a symptom, not the cause, of the t≥4 collapse.
- **`synchronized` is the steady baseline** — flat ~13-14 ops/μs across t≥4,
  constant 24 B/op. Nothing surprising.
- **`ReentrantLock` (non-fair) dominates the symmetric high-contention rows**
  (~68 ops/μs at t=8) thanks to **barging** — after `unlock()` the releasing
  thread usually re-acquires before any parked thread wakes up, effectively
  serialising work into batches that fit between context switches.
- **Producer/consumer (2P + 2C)** doesn't help `LockFreeStack` much
  (26.2 ops/μs vs 15.6 at sym t=4). A stack has a single contention point
  (`head`); the role split does not separate cache-line traffic. `ReentrantLock`
  is the natural winner here.

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

3. **`head` is a permanent dummy node**, and CASes go through `head.next`
   (`LockFreeStack.java:6-9, 20`). The canonical Treiber stack uses
   `AtomicReference<Node> head` directly — the top of the stack *is*
   the head reference, no sentinel. **Fix:** drop the dummy, make `head`
   the volatile/VarHandle field of (2). Saves one indirection on the
   hot path.

4. **No back-off on CAS failure** — the loop spins flat-out on a
   contended cache line. At t=8 this is the dominant cost after (1)
   is fixed. **Fix:** introduce `Thread.onSpinWait()` after the first
   few failures, escalating to a short `LockSupport.parkNanos(N)` or
   `Thread.yield()` if contention persists. This is the lever that
   closes the gap against `ReentrantLock`'s barging.

(1)+(2)+(3) bring the implementation to a canonical, well-known design and
should restore parity with `synchronized` at t=4 and approach `synchronized`
at t=8. Beating non-fair `ReentrantLock`'s barging at t=8 on a tight
`push+pop` micro-benchmark is a harder problem — typically solved with
elimination arrays or other contention-reduction structures, not a one-line
patch.

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
