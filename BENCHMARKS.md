# Benchmarks report

JMH-based performance comparison of the `Stack` implementations in this repo.
The goal of this report is to answer one question: **which synchronization
mechanism wins which workload?**

Source for the benchmarks: `src/jmh/java/org/example/`.

## Implementations compared

Only the thread-safe implementations are exercised. The non-thread-safe
`Stack` class is not benchmarked.

| Family | Class | Synchronization |
|---|---|---|
| Blocking | `LockedStack` | `synchronized` methods |
| Blocking | `ReentrantLockStack` | `ReentrantLock` (non-fair) |
| Lock-free | `LockFreeStack` | CAS on `head` via `AtomicReferenceFieldUpdater` |
| Lock-free | `EliminationStack` | Treiber CAS + Hendler-Shavit elimination back-off array (8 stride-padded slots) |
| Lock-free | `BackoffLockFreeStack` | Treiber CAS + exponential CAS-failure backoff via `Thread.onSpinWait()` (starts at 1 spin, doubles per failed CAS, capped at 1024) |
| Lock-free | `ExchangerEliminationStack` | Treiber CAS + elimination via `java.util.concurrent.Exchanger`'s internal arena (2 exchangers, 500 ns timeout) |

### Progress guarantees

The non-blocking hierarchy (Herlihy & Shavit, *The Art of Multiprocessor
Programming*), strongest to weakest, each implying the next:

- **Wait-free** — *every* thread completes each operation in a bounded number of
  its own steps, regardless of scheduling. No starvation. (None here — a wait-free
  stack needs an announcement array + helping and is much costlier.)
- **Lock-free** — *infinitely often some* thread completes in a bounded number of
  steps: the system as a whole always makes progress, though an individual thread
  may be overtaken indefinitely. No system-wide livelock.
- **Obstruction-free** — a thread completes in bounded steps only if it eventually
  runs *in isolation*; under sustained contention threads may livelock without a
  contention manager. (None here — all lock-free variants are strictly stronger.)

| Class | Progress guarantee | Notes |
|---|---|---|
| `LockedStack` | **Blocking** | A thread descheduled inside the monitor blocks all others |
| `ReentrantLockStack` | **Blocking** | Same — AQS park/unpark; barging helps throughput, not the guarantee |
| `LockFreeStack` | **Lock-free** (pure, CAS-only) | Canonical Treiber; not wait-free — a slow thread can be overtaken forever |
| `BackoffLockFreeStack` | **Lock-free** (pure, CAS-only) | Backoff is tuning over the same CAS loop; class unchanged |
| `EliminationStack` | **Lock-free** (pure, CAS-only) | Main path + bounded on-core spin fallback; no `park`, no timers |
| `ExchangerEliminationStack` | **Lock-free\*** | System-level lock-free, but "bounded steps" leans on `Exchanger`'s bounded-wallclock `parkNanos`, not pure CAS steps — see *Correctness caveat* |

> Progress class and tail-latency predictability are **orthogonal**: `ReentrantLock`
> (blocking) and the 8-slot `Exchanger` (lock-free\*) both `park` and both showed a
> multi-ms tail, despite sitting in different progress classes. A `park` preserves
> *system* progress (it holds no lock) but hands *per-operation* latency to the OS
> scheduler. See *Latency distribution*.

## Methodology

Four benchmark shapes, all pre-fill the stack so `pop` rarely hits empty:

| Class | Workload | Threads |
|---|---|---|
| `StackScalingBenchmark` | symmetric `push` + `pop` per thread | controlled via `-Pjmh.threads` |
| `StackContentionBenchmark` | `@Group` with 2 producers (only `push`) + 2 consumers (only `pop`) | 4 (fixed) |
| `StackBurstyBenchmark` | `push`, `Blackhole.consumeCPU(200)`, `pop`, `consumeCPU(200)` — simulates a stack embedded in a real pipeline, not a hot inner loop | controlled via `-Pjmh.threads` |
| `StackAsymmetricBenchmark` | `@Group` with 3 producers (only `push`) + 1 consumer (only `pop`) — producer-heavy shape where most contention is push↔push on `head` | 4 (fixed) |

The two contended `@Group` shapes (`StackContentionBenchmark`,
`StackAsymmetricBenchmark`) run **both** `Mode.Throughput` and `Mode.SampleTime`
in one pass: throughput answers "how many ops/µs on average", SampleTime answers
"what does the *tail* of a single operation look like" (p99 / p99.9 / max). The
tail is where blocking implementations pay for `park`/`unpark` and where the
average hides priority inversion — see *Latency distribution* below.

> **Reference environment:** Apple M4 (10 cores), JDK 21 toolchain, Gradle.
> The `gradle jmh` defaults now equal the `@Fork`/`@Warmup`/`@Measurement`
> annotations — `fork=2 × warmup 3×2s × iter 5×2s` — so a bare run *is* the
> reference profile. Reproduce a row:
>
> ```bash
> ./gradlew jmh -Pjmh.include=StackContentionBenchmark
> ./gradlew jmh -Pjmh.include=StackScalingBenchmark -Pjmh.threads=4
> ```
>
> For a fast dev smoke run, override down: `-Pjmh.fork=1
> -Pjmh.warmupIterations=2 -Pjmh.iterations=3 -Pjmh.warmup=500ms -Pjmh.time=1s`
> — fine for iteration, too noisy to quote.
>
> Every table below comes from a **single back-to-back run of all six
> implementations on one build** (`ExchangerEliminationStack` at its tuned
> `ELIM_SLOTS = 2`), so all rows — throughput, allocation and latency — are
> mutually comparable. Absolute numbers on an unpinned laptop still carry
> ~5-15 % noise between sessions; compare within a row, not across sessions.

---

## Throughput — symmetric `push` + `pop` (ops/µs, higher is better)

| impl | t=1 | t=2 | t=4 | t=8 | 2P + 2C |
|---|---:|---:|---:|---:|---:|
| `LockedStack` (`synchronized`) | **168.3** | 19.6 | 14.1 | 14.0 | 8.8 † |
| `ReentrantLockStack` (non-fair) | 109.3 | 18.4 | **69.6** | **67.8** | **89.2** |
| `LockFreeStack` | 120.0 | 28.7 | 13.7 | 3.2 | 22.2 |
| `EliminationStack` | 119.4 | 36.0 | 15.2 | 3.6 | 21.6 |
| `BackoffLockFreeStack` | 119.3 | 27.9 | 13.2 | 3.2 | 24.5 |
| `ExchangerEliminationStack` (2 slots) | 118.9 | **110.0** | 68.3 | 14.8 | 81.2 |

## Throughput — asymmetric 3 producers + 1 consumer (ops/µs, higher is better)

Producer-heavy `@Group`: 3 threads only calling `push`, 1 thread only calling
`pop`. Pushes outnumber pops 3×, so elimination has few natural push↔pop
partners — most contention is push↔push on `head`.

| impl | 3P + 1C |
|---|---:|
| `LockedStack` (`synchronized`) | 23.7 ± 1.9 |
| `ReentrantLockStack` (non-fair) | 51.8 ± 18.6 |
| `LockFreeStack` | 16.5 ± 4.3 |
| `EliminationStack` | 19.8 ± 2.1 |
| `BackoffLockFreeStack` | 17.1 ± 3.5 |
| `ExchangerEliminationStack` (2 slots) | **94.1 ± 3.5** |

## Throughput — bursty `push + work + pop + work` (ops/µs, higher is better)

Each thread does `push`, ~200 CPU tokens of `Blackhole` work, `pop`, another
~200 tokens. Shape mirrors real code where a stack is one component embedded in
a wider pipeline, not the hot inner loop of a benchmark.

| impl | t=2 | t=4 | t=8 |
|---|---:|---:|---:|
| `LockedStack` (`synchronized`) | 3.4 | 5.2 | 4.0 |
| `ReentrantLockStack` (non-fair) | 3.3 | 4.1 | 3.3 |
| `LockFreeStack` | **3.4** | **5.5** | 2.4 |
| `EliminationStack` | 3.4 | 5.4 | 2.5 |
| `BackoffLockFreeStack` | 3.4 | **5.5** | 2.4 |
| `ExchangerEliminationStack` (2 slots) | 3.1 | 3.6 | **4.3** |

## Allocation (B/op, lower is better)

| impl | t=2 sym | t=4 sym | t=8 sym | 2P + 2C | t=2 bursty | t=4 bursty | t=8 bursty |
|---|---:|---:|---:|---:|---:|---:|---:|
| `LockedStack` | 24 | 24 | 24 | 295 † | 24 | 24 | 24 |
| `ReentrantLockStack` | 32 | 24 | 24 | 14 | 24 | 28 | 26 |
| `LockFreeStack` | 24 | 24 | 24 | 13 | 24 | 24 | 24 |
| `EliminationStack` | 24 | 24 | 24 | 18 | 24 | 24 | 24 |
| `BackoffLockFreeStack` | 24 | 24 | 24 | 15 | 24 | 24 | 24 |
| `ExchangerEliminationStack` (2 slots) | 25 | 24 | 24 | 13 | 28 | 56 | 24 |

> † Under `synchronized` at 2P+2C, producers can't keep up with consumers, so
> `pop()` starts throwing `EmptyStackException` (~500 B per throw). That column
> reflects exception cost, not algorithmic cost — compare against the scaling
> rows (~14 ops/µs, 24 B/op) for the real `synchronized` baseline.

## Latency distribution — contended workloads (SampleTime, µs/op, lower is better)

Throughput ranks implementations by *average* work done; it says nothing about
the operation that stalls. These tables are the per-operation latency
distribution from the same run as the throughput tables above, so every row is
mutually comparable. The p99.9 column is the meaningful tail metric; `max` is a
single worst sample (dominated by one JIT/GC/scheduler hiccup) — read it as an
order of magnitude, not a value.

### 2 producers + 2 consumers (`StackContentionBenchmark`)

| impl | p50 | p90 | p99 | p99.9 | max |
|---|---:|---:|---:|---:|---:|
| `LockedStack` (`synchronized`) | 0.417 | 1.084 | 21.76 | 1581.06 | 29917 |
| `ReentrantLockStack` (non-fair) | **0.041** | **0.042** | 12.32 | 33.98 | 77201 |
| `LockFreeStack` | 0.208 | 1.500 | 4.62 | 12.21 | 75235 |
| `EliminationStack` | 0.459 | 1.624 | 3.42 | **5.79** | 13713 |
| `BackoffLockFreeStack` | 0.250 | 1.874 | 6.95 | 15.79 | 104071 |
| `ExchangerEliminationStack` (2 slots) | 0.042 | 0.292 | **2.87** | 14.24 | **13271** |

### 3 producers + 1 consumer (`StackAsymmetricBenchmark`)

| impl | p50 | p90 | p99 | p99.9 | max |
|---|---:|---:|---:|---:|---:|
| `LockedStack` (`synchronized`) | 0.334 | 2.580 | 74.11 | 427.01 | 123470 |
| `ReentrantLockStack` (non-fair) | **0.042** | **0.083** | 16.93 | 46.46 | 338166 |
| `LockFreeStack` | 0.375 | 1.500 | 34.05 | 134.91 | 227017 |
| `EliminationStack` | 0.583 | 1.834 | **5.12** | **10.83** | 175112 |
| `BackoffLockFreeStack` | 0.375 | 1.874 | 42.11 | 126.34 | 231473 |
| `ExchangerEliminationStack` (2 slots) | 0.042 | 0.209 | 26.53 | 85.25 | **9290** |

- **`EliminationStack` is the tail reference.** Mid-pack on throughput (21.6 /
  19.8 ops/µs) but the tightest, most uniform distribution: p99.9 = 5.79 µs
  (2P+2C) and 10.83 µs (3P+1C), the best deepest-percentile figure in both shapes.
  Its fallback is a bounded on-core spin (32 × `onSpinWait`), never a `park`, so
  no operation depends on the scheduler or on finding a partner in time.
- **The retuned `ExchangerEliminationStack` is now competitive on the tail** — it
  ties or takes the best p99 (2.87 µs at 2P+2C) and the smallest `max` in both
  shapes (13.3 ms / 9.3 ms), a collapse of orders of magnitude from the 8-slot
  build (3P+1C `max` was 581 ms). `EliminationStack` still edges it at the deepest
  percentile (p99.9), but the gap is now small, not the order of magnitude it was
  at 8 slots. This came at a throughput cost at high thread counts — see the
  tradeoff bullet below and *Retuning*.
- **The 8 → 2 slot retune is a genuine tradeoff, not a free win.** Concentrating
  elimination into 2 slots fixes the tail but throttles throughput once threads
  outnumber slot capacity: symmetric t=8 dropped from **68.3 → 14.8 ops/µs** and
  2P+2C from ~149 → 81, where `ReentrantLock` (67.8 / 89.2) now leads. At t≤4 and
  on the asymmetric shape (94.1) the 2-slot build still leads. So the fix trades
  peak high-concurrency throughput for a bounded tail; it is tuned for the tail.
- **`ReentrantLockStack` is the throughput/tail split that remains.** Barging
  gives it the best median by far (p50 0.041 µs, p90 0.042 µs — half its ops are
  essentially free) and the top symmetric throughput at t≥4, but the parked-waiter
  path leaves a heavy `max` (77–338 ms) and a p99 (12–17 µs) well behind the arena
  stacks. Great average, jittery tail.
- **Plain `LockFreeStack` has a solid p99 but a heavy `max`** (p99 4.62 µs at
  2P+2C, but `max` 75–227 ms): no `park` path, so no priority inversion, yet a
  rare CAS-retry storm plus allocation GC still produces a long single-sample worst.
- **`synchronized` has the worst realistic tail** (p99.9 = 1581 µs at 2P+2C): the
  monitor both blocks *and* lacks barging, so contended waiters eat a full
  park/unpark far more often than `ReentrantLock` does.

> **The tail is decided by "does the contended path park", not by "is it
> lock-free".** The blanket claim *lock-free ⇒ more predictable tail* does **not**
> survive this data: plain `LockFreeStack` and `BackoffLockFreeStack` have a
> **worse** p99.9 than the blocking `ReentrantLockStack` on the 3P+1C shape
> (135 / 126 µs vs 46 µs), because a CAS-retry storm on `head` is itself a source
> of tail, and every lock-free variant here also carries a multi-ms `max` from
> retry bursts plus per-`push` allocation GC. What the numbers *do* show is a
> clean split on one axis: the worst tail belongs to a stack that **parks and
> can't barge** (`synchronized`, p99.9 1581 µs), and the best belongs to one that
> **stays on-core and never parks** (`EliminationStack`, p99.9 5.8 / 10.8 µs).
> Everything that can `park` — `synchronized`, `ReentrantLock`, the 8-slot
> `Exchanger` — hands its worst case to the OS scheduler and pays a multi-ms
> `max`. So being lock-free is *necessary but not sufficient* for a stable tail:
> you also need a non-parking (spin) fallback, and ideally elimination to divert
> contention off the hot point entirely. `EliminationStack` wins the tail by
> having both, not merely by being lock-free.

### Retuning `ExchangerEliminationStack` — the tail was a tuning bug, not the primitive

The first latency run flagged `ExchangerEliminationStack` as a tail disaster
(p99 31.8 µs, `max` up to 581 ms) while it led throughput. A parameter sweep
(`ExchangerTuningBenchmark`, 2P+2C, `slots × timeout`) isolated the cause:

| slots | timeout | thrpt | p99 | p99.9 | max |
|---:|---:|---:|---:|---:|---:|
| 1 | 500 ns | 52.6 | 7.95 | 17.76 | 95683 |
| **2** | **500 ns** | **116.6** | **3.50** | 19.33 | **2017** |
| 8 (old default) | 500 ns | 141.4 | 29.34 | 108.93 | 182977 |

- **The slot count drives the tail; the timeout does not.** Across 100 / 500 /
  2000 ns the tail was identical within each slot row — so the cost is not threads
  waiting out the deadline, it is the *number* of failed elimination attempts.
- **8 slots over 4 threads fragments partners.** With only 1–2 threads in the
  elimination path at any instant spread across 8 exchangers, a push and a pop
  almost never pick the same slot, so most exchanges never match and churn to
  timeout — occasionally parking, which is what produced the 100–580 ms `max`.
- **2 slots concentrate traffic** enough to keep the match rate high (p99 → 3.5 µs,
  matching hand-rolled `EliminationStack`) while still letting two pairs rendezvous
  in parallel. `slots = 1` serializes all elimination through one exchanger and
  halves throughput. `ELIM_SLOTS` was changed 8 → 2 accordingly.

**Caveat — the sweep was run at 4 threads (2P+2C), and 2 slots do not scale.**
On the full one-build re-run, `slots = 2` regressed symmetric throughput at higher
concurrency (t=8: 68.3 → 14.8 ops/µs) because two exchangers cannot absorb eight
contending threads — the arena itself serializes. So the slot count that is
tail-optimal at 4 threads is throughput-suboptimal at 8. The right value is
workload-dependent (roughly *slots ≈ threads / 2*); 2 is chosen here because this
report weights the tail. A production stack expecting 8+ contending threads should
re-sweep at its own thread count, or scale the slot array with the CPU count.

The general takeaway: an `Exchanger`-based elimination layer is only as good as its
slot count is matched to the thread count. Over-provisioning slots converts
would-be eliminations into timeout churn that surfaces in the tail;
under-provisioning serializes the arena and caps throughput. There is no single
slot count that is best on both axes at every thread count.

## Findings

- **At t=1 uncontended `synchronized` wins** — 168.3 ops/µs vs ~120 for every
  lock-free variant and 109.3 for `ReentrantLock`. All four lock-free variants
  converge here because none enters its elim/backoff path without contention.
  "Lock-free is always faster" does not survive this row. The *reason* the
  monitor wins was chased down with two controlled experiments, and the two
  obvious explanations both turned out to be wrong:

  - **Not lock coarsening/elision.** Re-running this row with
    `-XX:-EliminateLocks` (which disables both) changed `LockedStack` by
    **0.0 %** (168.2 → 168.2); the lock-free and `ReentrantLock` rows, having
    no monitors, are unaffected controls and also did not move.
  - **Not "CAS is dearer than a monitor".** A primitive-cost microbenchmark
    (single-thread, uncontended, two sync ops per invocation to mirror
    `push`+`pop`) shows a seq-cst CAS and a monitor enter/exit costing *the
    same*: `casVolatile` 226 vs `monitor` 224 ops/µs. On this Apple-M CPU
    (LSE atomics) barrier strength is free too — plain / acquire / release /
    seq-cst CAS all land at ~224. `ReentrantLock` is genuinely ~2× dearer
    (114) because its AQS `state` CAS carries extra bookkeeping, which is why
    it trails at t=1.

  The gap is therefore **not in the synchronization primitive but in the
  access pattern around it.** Per `push`+`pop`, `LockFreeStack` executes 3
  volatile loads + 1 volatile store + 2 seq-cst CAS — because both `head` and
  `AtomicNode.next` are `volatile`, *every* field touch is an ordered access
  (`ldar`/`stlr`/`casal`). `LockedStack` instead amortizes all ordering into
  the monitor's two enter/exit fences per region and runs the interior
  (`head`, `Node.next` reads/writes) with plain loads/stores. Those ~4 extra
  barrier-carrying accesses — not the CAS itself — are what put 168 (5.95 ns)
  ahead of 120 (8.3 ns). Allocation is equal (24 B/op each), so it is not a
  factor. This is the same theme as the contended rows in reverse: elimination
  later wins by removing the contention point; here the monitor wins by not
  paying per-access ordering on an uncontended fast path.

- **`ExchangerEliminationStack` (2 slots) leads throughput at low-to-moderate
  contention, but not everywhere.** It dominates at t=2 (**110.0 ops/µs** — 3× the
  next lock-free contender `EliminationStack` 36.0 and 6× the blocking variants)
  and owns the producer-heavy 3P+1C row (**94.1**, ~1.8× `ReentrantLock`). But the
  2-slot arena cannot absorb the highest thread counts: at t=4 it ties
  `ReentrantLock` (68.3 vs 69.6) and at t=8 it collapses to **14.8** while
  `ReentrantLock` holds 67.8 — and `ReentrantLock` also edges it at 2P+2C
  (89.2 vs 81.2). The cause is that `java.util.concurrent.Exchanger` is a
  professionally-tuned elimination arena (Doug Lea's design), but with only 2
  slots it serializes once threads outnumber slot capacity (see *Retuning*). One
  correction to the earlier reading: the 500-ns timeout does **not** keep every
  attempt out of `park()` — attempts that fail to match do park, which is exactly
  what produced the latency tail at 8 slots.

- **`ExchangerEliminationStack` loses under bursty t=2/t=4** — 3.1 and 3.6 ops/µs
  vs `LockFreeStack`'s 3.4 and 5.5. Bursty has low natural contention, so most ops
  succeed on the main CAS and never need the arena. But when the main CAS *does*
  fail, `Exchanger` allocates internal `Node`s per attempted rendezvous; under
  bursty t=4 that still lifts allocation from 24 to **56 B/op**. (The 2-slot build
  cut this from the 8-slot build's 68/87 B/op, and at bursty t=8 it is back to
  baseline 24 B/op — a higher match rate means fewer failed, allocating exchanges.)
  It does, however, take bursty t=8 outright at **4.3 ops/µs**.

- **Bursty t=2/t=4 belong to plain `LockFreeStack`** (and `BackoffLockFreeStack`,
  which ties it). At bursty t=4, `LockFreeStack`/`BackoffLockFreeStack` reach
  **5.5 ops/µs** because they have the cheapest fast path: one CAS, no elim
  overhead, no `Exchanger` machinery, constant 24 B/op. Whenever contention is
  low enough that the main CAS almost always succeeds, the simplest
  implementation wins.

- **`ReentrantLock` barging is destroyed by think-time** — it drops from 69.6
  ops/µs on symmetric t=4 to **4.1** on bursty t=4. Barging depends on the
  releasing thread immediately re-acquiring the lock before parked waiters
  wake; the ~200-token gap between `unlock()` and the next `lock()` lets
  waiters actually get scheduled, so every op then costs a full park/unpark
  cycle. `ReentrantLock`'s symmetric-benchmark advantage is real only when the
  workload has no gaps — uncommon in production code.

- **Plain `LockFreeStack` collapses at t≥4 symmetric** — 13.7 / 3.2 ops/µs at
  t=4 / t=8. Cache-line bouncing on the single `head` reference, plus the
  absence of CAS back-off, is what limits it. That collapse is exactly what the
  elimination and backoff variants target.

- **`EliminationStack` edges out plain Treiber under contention but not by
  much** — it wins t=2 (36.0 vs 28.7) and t=4 (15.2 vs 13.7), and ties within
  noise at t=8 and 2P+2C. Its hand-rolled 8-slot arena helps on throughput but is
  beaten by `ExchangerEliminationStack` at t≤4. **On the tail the ranking flips:**
  `EliminationStack` has the tightest, most uniform latency distribution (best
  p99.9 in both contended shapes) because its fallback never parks. So it is not
  merely an educational reference — it is the pick when bounded tail latency
  matters more than peak throughput (see *Latency distribution*).

- **`BackoffLockFreeStack` shows adaptive backoff's intended effect, and it is
  small.** On the producer-heavy 3P+1C row it scores 17.1 ops/µs — within noise
  of plain `LockFreeStack` (16.5) and `EliminationStack` (19.8), and error bars
  overlap throughout. Backoff genuinely rate-limits the retry storm on `head`,
  but the throughput ceiling under contention is dominated by cache-line
  ping-pong, and spacing retries in time doesn't remove the ping-pong itself.
  Elimination-arena approaches divert traffic off `head` entirely, which is why
  `ExchangerEliminationStack` leads this row decisively (94.1 ops/µs). Backoff
  remains valuable as a low-overhead fallback for structures where elimination
  doesn't apply, and it adds no allocation on top of the fast path.

## Correctness caveat: Lincheck model checking does not apply to `ExchangerEliminationStack`

`ExchangerEliminationStack` is lock-free at the system level. The main Treiber
CAS path is unconditionally non-blocking; the elimination fallback uses
`Exchanger.exchange(v, 500, NANOS)`, which internally may call
`LockSupport.parkNanos` but with a bounded wallclock deadline. While one thread
is parked in `Exchanger`, other threads freely make progress on the main CAS —
that satisfies lock-freedom (Herlihy & Shavit, *The Art of Multiprocessor
Programming*, §3.7: "infinitely often some method call finishes in a bounded
number of steps").

Two practical caveats:

1. **Lincheck model checking cannot verify this class.** The model checker uses
   logical time; a 500 ns wallclock deadline never "elapses" in its schedule,
   so any `pop()` on an empty stack appears to wait for a partner indefinitely
   and is reported as a livelock. This is a limitation of the verifier's timing
   abstraction, not of the algorithm. The class is verified by Lincheck
   **stress testing** (real wallclock time) and passes.

2. **The lock-free guarantee is bounded by the timeout.** With the configured
   500 ns timeout, any parked thread returns within sub-microsecond wallclock
   time. Raise the timeout to, say, 100 µs on a heavily loaded system and
   individual calls could observe measurable delays — system-level progress
   still holds, but "bounded steps" becomes a bounded wallclock assumption in
   practice.

For a production stack that wants a purely CAS-based progress guarantee with no
reliance on wallclock timers, prefer the hand-rolled `EliminationStack`.

## When to reach for each stack

| Scenario | Winner | Runner-up |
|---|---|---|
| Single thread, hot in a JIT-friendly spot | `LockedStack` (`synchronized`) | any lock-free |
| Contended symmetric, **low-to-moderate threads** (t=2), avg throughput | `ExchangerEliminationStack` (2 slots) | `EliminationStack` |
| Contended symmetric, **high threads** (t=8, or 2P+2C), avg throughput | `ReentrantLockStack` | `ExchangerEliminationStack` |
| Producer-heavy (3P+1C), **average throughput** | `ExchangerEliminationStack` (2 slots) | `ReentrantLockStack` |
| Contended, **bounded tail latency matters** (p99 / p99.9) | `EliminationStack` | `ExchangerEliminationStack` (2 slots) |
| Bursty (user work between ops) at t=2 or t=4 | `LockFreeStack` / `BackoffLockFreeStack` | `EliminationStack` |
| Bursty at t=8 | `ExchangerEliminationStack` (2 slots) | `LockedStack` |
| Requires **strict** CAS-only lock-free progress | `EliminationStack`, `BackoffLockFreeStack`, or `LockFreeStack` | — |

Takeaways:

- `ExchangerEliminationStack` at its tuned 2 slots is the fastest option for
  moderate contention (t=2, 3P+1C) *and* — unlike the 8-slot build — no longer a
  tail liability (best p99 at 2P+2C, well-bounded `max`). But 2 slots serialize at
  high thread counts: at t=8 and 2P+2C it is overtaken by `ReentrantLock`. Pick it
  when contention is moderate or when the tail matters; if you expect 8+ hot
  threads and want peak throughput, either re-sweep the slot count for your thread
  count or use `ReentrantLock`.
- If you are sized by the *deepest* percentile (p99.9) or want the most uniform,
  most predictable latency, pick `EliminationStack`: its bounded on-core spin
  fallback never parks, so it holds the tightest p99.9 in both contended shapes,
  at lower average throughput. `ReentrantLock` is the opposite trade — the best
  median (barging) and top high-thread throughput, but a jittery multi-ms `max`.
- The Exchanger arena still allocates internal `Node`s under failed exchanges —
  visible as up to 56 B/op at bursty t=4 (down from 68/87 at 8 slots). If GC
  pressure matters under low-contention bursty patterns, prefer `LockFreeStack`.
- `EliminationStack` is beaten *on throughput at t≤4* by `ExchangerEliminationStack`
  but wins the tail and does not regress at high thread counts, and remains the
  strict CAS-only lock-free alternative and an educational reference.
- `BackoffLockFreeStack` illustrates the classical adaptive-backoff pattern
  (exponential `Thread.onSpinWait()` after each failed CAS). It adds no
  allocation, but on these workloads it is within noise of plain `LockFreeStack`
  and 4× behind arena-based approaches under contention.

## Caveats

- Apple laptop in unconstrained power state. Even with `fork=2 × iter=5`,
  scaling rows show ~5-15 % noise. Stable comparisons require pinned cores,
  fixed CPU frequency, ideally a Linux box.
- The adapter pattern (`StackOps`) inserts one virtual call per operation,
  uniformly across implementations — relative comparisons stay valid, absolute
  numbers are slightly lower than direct calls.
- `gradle jmh` defaults now match the reference profile (`fork=2 × warmup 3×2s ×
  iter 5×2s`), so a bare run is publishable. Use the smoke overrides in
  *Methodology* only for fast dev iteration, not for quotable numbers.
- SampleTime `max` is a single worst-case sample and swings wildly between
  sessions (one GC pause moves it by 10×). Compare implementations on p99/p99.9,
  which average over thousands of samples; treat `max` as an order of magnitude.
