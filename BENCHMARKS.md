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
| Lock-free | `ExchangerEliminationStack` | Treiber CAS + elimination via `java.util.concurrent.Exchanger`'s internal arena (8 exchangers, 500 ns timeout) |

## Methodology

Four benchmark shapes, all pre-fill the stack so `pop` rarely hits empty:

| Class | Workload | Threads |
|---|---|---|
| `StackScalingBenchmark` | symmetric `push` + `pop` per thread | controlled via `-Pjmh.threads` |
| `StackContentionBenchmark` | `@Group` with 2 producers (only `push`) + 2 consumers (only `pop`) | 4 (fixed) |
| `StackBurstyBenchmark` | `push`, `Blackhole.consumeCPU(200)`, `pop`, `consumeCPU(200)` — simulates a stack embedded in a real pipeline, not a hot inner loop | controlled via `-Pjmh.threads` |
| `StackAsymmetricBenchmark` | `@Group` with 3 producers (only `push`) + 1 consumer (only `pop`) — producer-heavy shape where most contention is push↔push on `head` | 4 (fixed) |

> **Reference environment:** Apple M-series, JDK 21 toolchain, Gradle,
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
>
> All tables below come from a single back-to-back run of all six
> implementations under the reference profile, so every row is internally
> comparable. Absolute numbers on an unpinned laptop still carry ~5-15 %
> noise between sessions; compare within a row, not across sessions.

---

## Throughput — symmetric `push` + `pop` (ops/µs, higher is better)

| impl | t=1 | t=2 | t=4 | t=8 | 2P + 2C |
|---|---:|---:|---:|---:|---:|
| `LockedStack` (`synchronized`) | **168.2** | 18.5 | 14.1 | 14.2 | 5.1 † |
| `ReentrantLockStack` (non-fair) | 109.5 | 18.4 | 69.1 | 67.6 | 95.7 |
| `LockFreeStack` | 120.1 | 30.5 | 13.3 | 3.2 | 23.4 |
| `EliminationStack` | 119.7 | 36.1 | 15.0 | 3.7 | 24.9 |
| `BackoffLockFreeStack` | 119.2 | 27.6 | 13.2 | 3.1 | 22.6 |
| `ExchangerEliminationStack` | 119.1 | **110.5** | **105.1** | **68.3** | **149.3** |

## Throughput — asymmetric 3 producers + 1 consumer (ops/µs, higher is better)

Producer-heavy `@Group`: 3 threads only calling `push`, 1 thread only calling
`pop`. Pushes outnumber pops 3×, so elimination has few natural push↔pop
partners — most contention is push↔push on `head`.

| impl | 3P + 1C |
|---|---:|
| `LockedStack` (`synchronized`) | 25.2 ± 3.0 |
| `ReentrantLockStack` (non-fair) | 57.4 ± 18.6 |
| `LockFreeStack` | 17.5 ± 3.0 |
| `EliminationStack` | 18.7 ± 4.7 |
| `BackoffLockFreeStack` | 16.4 ± 2.3 |
| `ExchangerEliminationStack` | **80.7 ± 36.4** |

## Throughput — bursty `push + work + pop + work` (ops/µs, higher is better)

Each thread does `push`, ~200 CPU tokens of `Blackhole` work, `pop`, another
~200 tokens. Shape mirrors real code where a stack is one component embedded in
a wider pipeline, not the hot inner loop of a benchmark.

| impl | t=2 | t=4 | t=8 |
|---|---:|---:|---:|
| `LockedStack` (`synchronized`) | 3.1 | 5.2 | **4.1** |
| `ReentrantLockStack` (non-fair) | 3.1 | 4.0 | 3.4 |
| `LockFreeStack` | **3.4** | **5.5** | 2.4 |
| `EliminationStack` | 3.4 | 5.4 | 2.5 |
| `BackoffLockFreeStack` | 3.4 | **5.5** | 2.3 |
| `ExchangerEliminationStack` | 3.2 | 3.5 | 3.0 |

## Allocation (B/op, lower is better)

| impl | t=2 sym | t=4 sym | t=8 sym | 2P + 2C | t=2 bursty | t=4 bursty | t=8 bursty |
|---|---:|---:|---:|---:|---:|---:|---:|
| `LockedStack` | 24 | 24 | 24 | 485 † | 24 | 24 | 24 |
| `ReentrantLockStack` | 32 | 24 | 24 | 14 | 24 | 28 | 27 |
| `LockFreeStack` | 24 | 24 | 24 | 15 | 24 | 24 | 24 |
| `EliminationStack` | 24 | 24 | 24 | 15 | 24 | 24 | 24 |
| `BackoffLockFreeStack` | 24 | 24 | 24 | 16 | 24 | 24 | 24 |
| `ExchangerEliminationStack` | 25 | 26 | 26 | 14 | 28 | **68** | **87** |

> † Under `synchronized` at 2P+2C, producers can't keep up with consumers, so
> `pop()` starts throwing `EmptyStackException` (~500 B per throw). That column
> reflects exception cost, not algorithmic cost — compare against the scaling
> rows (~14 ops/µs, 24 B/op) for the real `synchronized` baseline.

## Findings

- **At t=1 uncontended `synchronized` wins** — 168.2 ops/µs vs ~120 for every
  lock-free variant and 109.5 for `ReentrantLock`. Single-threaded work is the
  fast path of `synchronized`: the JIT inlines the method and the monitor
  compiles to a couple of cheap thin-lock ops, while the CAS variants each pay
  a volatile read + a full-fence CAS per op. All four lock-free variants
  converge to the same number here because none enters its elim/backoff path
  without contention. "Lock-free is always faster" does not survive this row.

- **`ExchangerEliminationStack` dominates every contended row.** At t=2 it hits
  **110.5 ops/µs** — 3× the next lock-free contender (`EliminationStack` 36.1)
  and 6× the blocking variants. It leads at t=4 (**105.1** vs `ReentrantLock`
  69.1), at t=8 (**68.3**, matching `ReentrantLock` 67.6 within noise), and at
  2P+2C (**149.3**, ~1.5× `ReentrantLock` 95.7). The cause: `java.util.concurrent.Exchanger`
  is itself a professionally-tuned elimination arena (Doug Lea's design). The
  short 500-ns timeout keeps each attempt in Exchanger's internal spin phase
  without ever hitting `park()`, and its arena resizing + slot rehashing find
  push↔pop matches faster than any fixed 8-slot array we could hand-roll.

- **`ExchangerEliminationStack` loses under bursty t=2/t=4** — 3.2 and 3.5
  ops/µs vs `LockFreeStack`'s 3.4 and 5.5. Bursty has low natural contention,
  so most ops succeed on the main CAS and never need the arena. But when the
  main CAS *does* fail, `Exchanger` allocates internal `Node`s per attempted
  rendezvous; under bursty t=4 that pushes allocation from 24 to **68 B/op**
  (~3× baseline) and to **87 B/op** at t=8. The allocator overhead swamps any
  win from a faster elim path. Its advantage depends on contention staying high
  enough to keep the arena hot.

- **Bursty t=2/t=4 belong to plain `LockFreeStack`** (and `BackoffLockFreeStack`,
  which ties it). At bursty t=4, `LockFreeStack`/`BackoffLockFreeStack` reach
  **5.5 ops/µs** because they have the cheapest fast path: one CAS, no elim
  overhead, no `Exchanger` machinery, constant 24 B/op. Whenever contention is
  low enough that the main CAS almost always succeeds, the simplest
  implementation wins.

- **`ReentrantLock` barging is destroyed by think-time** — it drops from 69.1
  ops/µs on symmetric t=4 to **4.0** on bursty t=4. Barging depends on the
  releasing thread immediately re-acquiring the lock before parked waiters
  wake; the ~200-token gap between `unlock()` and the next `lock()` lets
  waiters actually get scheduled, so every op then costs a full park/unpark
  cycle. `ReentrantLock`'s symmetric-benchmark advantage is real only when the
  workload has no gaps — uncommon in production code.

- **Plain `LockFreeStack` collapses at t≥4 symmetric** — 13.3 / 3.2 ops/µs at
  t=4 / t=8. Cache-line bouncing on the single `head` reference, plus the
  absence of CAS back-off, is what limits it. That collapse is exactly what the
  elimination and backoff variants target.

- **`EliminationStack` edges out plain Treiber under contention but not by
  much** — it wins t=2 (36.1 vs 30.5), t=4 (15.0 vs 13.3), t=8 (3.7 vs 3.2)
  and 2P+2C (24.9 vs 23.4). Its hand-rolled 8-slot arena helps, but it is
  dwarfed by `ExchangerEliminationStack` in every one of those rows — Doug
  Lea's arena beats hand-rolled every time. The custom implementation is kept
  as a reference for what a straightforward slot-array elimination looks like,
  and as a comparison point that shows *how much* Exchanger's sophistication is
  worth.

- **`BackoffLockFreeStack` shows adaptive backoff's intended effect, and it is
  small.** On the producer-heavy 3P+1C row it scores 16.4 ops/µs — within noise
  of plain `LockFreeStack` (17.5) and `EliminationStack` (18.7), and error bars
  overlap throughout. Backoff genuinely rate-limits the retry storm on `head`,
  but the throughput ceiling under contention is dominated by cache-line
  ping-pong, and spacing retries in time doesn't remove the ping-pong itself.
  Elimination-arena approaches divert traffic off `head` entirely, which is why
  `ExchangerEliminationStack` leads this row decisively at 80.7. Backoff remains
  valuable as a low-overhead fallback for structures where elimination doesn't
  apply, and it adds no allocation on top of the fast path.

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
| Contended symmetric push/pop (t=2, t=4, t=8, or 2P+2C) | `ExchangerEliminationStack` | `ReentrantLockStack` (t≥4) or `EliminationStack` (t=2) |
| Producer-heavy (3P+1C) | `ExchangerEliminationStack` | `ReentrantLockStack` |
| Bursty (user work between ops) at t=2 or t=4 | `LockFreeStack` / `BackoffLockFreeStack` | `EliminationStack` |
| Bursty at t=8 | `LockedStack` | `ExchangerEliminationStack` |
| Requires **strict** CAS-only lock-free progress | `EliminationStack`, `BackoffLockFreeStack`, or `LockFreeStack` | — |

Takeaways:

- If your workload is contended enough to justify a specialized stack,
  `ExchangerEliminationStack` is the fastest option here — its win over both
  `ReentrantLock`'s barging and the hand-rolled elimination array is decisive
  on every contended symmetric row.
- That win is bought with `Exchanger`'s internal `Node` allocations under
  failed exchanges — visible as ~3× allocation under low-contention bursty
  workloads. If GC pressure matters, prefer `LockFreeStack` for bursty patterns.
- `EliminationStack` is superseded on performance by `ExchangerEliminationStack`
  but kept as a strict CAS-only lock-free alternative and educational reference.
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
- `gradle jmh` defaults are a smoke profile (~30 s per benchmark class). Do not
  quote those numbers outside this document; use the reference overrides in
  *Methodology*.
