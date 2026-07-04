# Benchmarks report — Linux run

JMH-based performance comparison of the thread-safe `Stack` implementations
in this repo, run on a Linux box. This is a companion to [`BENCHMARKS.md`](BENCHMARKS.md)
(which holds the original Apple M-series numbers) — kept as a **separate
file so neither machine's history overwrites the other's**. Only the
Stack family was re-run here; Queue is not covered by this file.

Source for the benchmarks: `src/jmh/java/org/example/`.

## Environment

| | |
|---|---|
| Date | 2026-07-04 |
| CPU | Intel(R) Core(TM) i5-7300HQ @ 2.50GHz, 4 cores, **1 thread/core (no SMT)**, max 3.5 GHz |
| OS | Linux 7.0.0-27-generic (x86_64) |
| JDK | OpenJDK 21.0.11 |
| Gradle | 9.6.0 (`me.champeau.jmh` 0.7.3) |
| JMH params | `-Pjmh.fork=2 -Pjmh.warmupIterations=3 -Pjmh.warmup=1s -Pjmh.iterations=5 -Pjmh.time=2s` |

> **Only 4 physical cores, no hyperthreading.** The Apple M-series
> reference machine in `BENCHMARKS.md` has more (and heterogeneous P/E)
> cores. Any row at `t≥4` here runs at or past full core saturation, and
> `t=8` genuinely oversubscribes 2 logical threads per core — treat `t=8`
> as an oversubscription stress case, not a scaling data point comparable
> 1:1 with the Mac's `t=8`.

Reproduce a single row:

```bash
./gradlew jmh -Pjmh.include=StackScalingBenchmark -Pjmh.threads=4 \
              -Pjmh.fork=2 -Pjmh.warmupIterations=3 -Pjmh.iterations=5 \
              -Pjmh.warmup=1s -Pjmh.time=2s
```

## Implementations compared

| Family | Class | Synchronization |
|---|---|---|
| Blocking | `LockedStack` | `synchronized` methods |
| Blocking | `ReentrantLockStack` | `ReentrantLock` (non-fair) |
| Lock-free | `LockFreeStack` | CAS on `head` + node `next` via `AtomicReferenceFieldUpdater` |
| Lock-free | `EliminationStack` | Treiber CAS + Hendler-Shavit elimination back-off array (8 slots) |
| Lock-free | `BackoffLockFreeStack` | Treiber CAS + exponential CAS-failure backoff (`Thread.onSpinWait()`, 1→1024 spins) |
| Lock-free | `ExchangerEliminationStack` | Treiber CAS + elimination via `java.util.concurrent.Exchanger` arena (8 exchangers, 500 ns timeout) |

Benchmark shapes (see `BENCHMARKS.md` §Methodology for full description):
`*ScalingBenchmark` (symmetric `pushPop`, threads via `-Pjmh.threads`),
`StackContentionBenchmark` (`@Group` 2 producers + 2 consumers, fixed 4),
`StackBurstyBenchmark` (`push` + ~200 CPU tokens + `pop` + ~200 tokens),
`StackAsymmetricBenchmark` (`@Group` 3 producers + 1 consumer, fixed 4).

---

## Throughput — symmetric `pushPop` (ops/μs, higher is better)

| impl | t=1 | t=2 | t=4 | t=8 | 2P + 2C |
|---|---:|---:|---:|---:|---:|
| `LockedStack` (`synchronized`) | 22.1 ± 0.4 | 12.8 ± 1.8 | 7.3 ± 0.8 | 19.9 ± 2.8 | 11.5 ± 4.1 |
| `ReentrantLockStack` (non-fair) | 25.7 ± 0.3 | 3.6 ± 0.2 | 19.1 ± 2.3 | 18.0 ± 0.9 | 10.4 ± 4.8 |
| `LockFreeStack` | **35.1 ± 0.4** | 7.1 ± 1.4 | 5.0 ± 0.4 | 5.0 ± 0.3 | 9.0 ± 0.7 |
| `EliminationStack` | 35.0 ± 0.8 | 29.0 ± 1.1 | 19.6 ± 0.3 | 19.9 ± 0.5 | 20.6 ± 0.7 |
| `ExchangerEliminationStack` | 35.6 ± 0.3 | **33.4 ± 0.5** | **32.6 ± 0.3** | **31.6 ± 0.1** | **55.1 ± 13.4** |

## Throughput — asymmetric 3 producers + 1 consumer (ops/μs, higher is better)

| impl | 3P + 1C |
|---|---:|
| `LockedStack` (`synchronized`) | 11.8 ± 6.1 |
| `ReentrantLockStack` (non-fair) | 17.0 ± 3.8 |
| `LockFreeStack` | 6.9 ± 1.1 |
| `EliminationStack` | **33.2 ± 1.6** |
| `BackoffLockFreeStack` | 27.9 ± 11.2 |
| `ExchangerEliminationStack` | 25.1 ± 10.3 |

## Throughput — bursty `push + work + pop + work` (ops/μs, higher is better)

| impl | t=2 | t=4 | t=8 |
|---|---:|---:|---:|
| `LockedStack` (`synchronized`) | 2.03 ± 0.04 | 3.94 ± 0.17 | 3.67 ± 0.44 |
| `ReentrantLockStack` (non-fair) | 1.93 ± 0.12 | 2.59 ± 0.73 | 1.23 ± 0.16 |
| `LockFreeStack` | **2.08 ± 0.03** | **4.07 ± 0.05** | **4.07 ± 0.06** |
| `EliminationStack` | 2.06 ± 0.05 | 3.82 ± 0.10 | 3.87 ± 0.12 |
| `ExchangerEliminationStack` | 1.76 ± 0.24 | 2.37 ± 0.17 | 2.62 ± 0.19 |

## Allocation (B/op, lower is better)

| impl | t=1 sym | t=2 sym | t=4 sym | t=8 sym | 2P + 2C | 3P + 1C | t=2 bursty | t=4 bursty | t=8 bursty |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| `LockedStack` | 24.0 | 24.0 | 24.0 | 24.0 | 20.9 | 17.9 | 24.1 | 24.0 | 24.0 |
| `ReentrantLockStack` | 24.0 | 41.8 | 24.2 | 24.2 | 56.2 | 17.3 | 24.5 | 30.3 | 24.6 |
| `LockFreeStack` | 24.0 | 24.0 | 24.0 | 24.0 | 17.7 | 19.6 | 24.1 | 24.0 | 24.0 |
| `EliminationStack` | 24.0 | 24.0 | 24.0 | 24.0 | 25.5 | 18.8 | 24.1 | 24.0 | 24.0 |
| `BackoffLockFreeStack` | — | — | — | — | — | 15.7 | — | — | — |
| `ExchangerEliminationStack` | 24.0 | 25.5 | 27.4 | 25.2 | 14.4 | 18.2 | 33.3 | **52.4** | 35.7 |

---

## Findings (this machine)

- **`LockFreeStack` collapses immediately at t=2**, not gradually at t≥4
  like on the Mac reference (35.1 → 7.1 ops/μs, a bigger relative drop
  than the Mac's 58.3 → 14.1). With only 4 physical cores and **no SMT**,
  every thread beyond t=1 causes real cross-core cache-line ping-pong on
  `head` with no hyperthreading to hide the latency — the collapse shows
  up one contention level earlier here than on the Mac.
- **`ExchangerEliminationStack` is the strongest contended performer
  again**, same as the Mac report — it wins t=2/t=4/t=8 symmetric and the
  2P+2C row (55.1 ops/μs, ~5× the next non-elimination contender). Doug
  Lea's arena design generalizes across both machines.
- **`ReentrantLockStack` at t=2 is unusually poor** (3.6 ops/μs, worse
  than every other impl including `LockedStack`) — non-fair barging
  apparently misbehaves at exactly 2 threads on 4 physical cores here.
  This is the one number that looks like an artifact of this specific
  environment rather than a generalizable trend; treat it with
  suspicion and rerun if it matters for a real decision.
- **Bursty workload again favors `LockFreeStack`** (4.07–4.09 ops/μs at
  t=4/t=8), matching the Mac's finding that low natural contention plus
  a cheap single-CAS fast path beats elimination/exchanger overhead when
  there's think-time between operations.
- **`EliminationStack` wins the asymmetric 3P+1C row** on this machine
  (33.2 ops/μs) instead of `ExchangerEliminationStack` (25.1, with a
  wide ±10.3 error bar) — the ranking of the two elimination-based
  stacks flips versus the Mac report. With only 4 cores and 4 threads
  total (3P+1C), scheduling noise is large (error bars overlap for
  `BackoffLockFreeStack` 27.9±11.2, `ExchangerEliminationStack`
  25.1±10.3, and `LockedStack` 11.8±6.1) — don't read too much into the
  exact ordering among the top three without more forks/iterations.
- **`ExchangerEliminationStack` allocation under bursty t=4 is again the
  outlier** (52.4 B/op vs ~24 for everything else), same mechanism as on
  the Mac: failed CAS attempts fall through to `Exchanger`'s internal
  `Node` allocation.

## Caveats

- **Small core count amplifies noise.** Several rows above have error
  bars comparable to or larger than the differences between
  implementations (e.g. `LockedStack` 2P+2C 11.5±4.1,
  `ExchangerEliminationStack` 2P+2C 55.1±13.4). Consider these directional,
  not decisive, without a higher fork/iteration count.
- Reference throughput/allocation methodology, workload descriptions,
  and the full engineering narrative behind each implementation live in
  `BENCHMARKS.md` — this file only re-runs the Stack rows to have a
  second data point on different hardware. Read `BENCHMARKS.md` first for
  context on *why* each implementation exists.
- Raw JMH console logs and `results.json` per run are not committed;
  regenerate with the command in **Environment** above if needed.
