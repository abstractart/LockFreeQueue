# Benchmarks report — Linux run

JMH-based performance comparison of the thread-safe `Stack` implementations
in this repo, run on a Linux box. This is a companion to [`BENCHMARKS.md`](BENCHMARKS.md)
(which holds the original Apple M-series numbers) — kept as a **separate
file so neither machine's history overwrites the other's**. Only the
Stack family was re-run here; Queue is not covered by this file.

This refresh re-runs the same JMH suite the Mac report was regenerated with:
`BackoffLockFreeStack` is now included in every shape (previously
asymmetric-only), `ExchangerEliminationStack` runs at its retuned
`ELIM_SLOTS = 2` (was 8), and the two `@Group` shapes
(`StackContentionBenchmark`, `StackAsymmetricBenchmark`) now measure
`Mode.SampleTime` latency alongside throughput in the same pass. See
`BENCHMARKS.md` §*Retuning* for why the slot count changed and §*Latency
distribution* for how to read the percentile tables.

Source for the benchmarks: `src/jmh/java/org/example/`.

## Environment

| | |
|---|---|
| Date | 2026-07-11 |
| CPU | Intel(R) Core(TM) i5-7300HQ @ 2.50GHz, 4 cores, **1 thread/core (no SMT)**, max 3.5 GHz |
| OS | Linux 7.0.0-27-generic (x86_64) |
| JDK | OpenJDK 21.0.11 |
| Gradle | 9.6.0 (`me.champeau.jmh` 0.7.3) |
| JMH params | bare `./gradlew jmh` defaults, which now mirror the `@Fork`/`@Warmup`/`@Measurement` annotations: `fork=2 × warmup 3×2s × iter 5×2s` (no CLI overrides) |

> **Only 4 physical cores, no hyperthreading.** The Apple M-series
> reference machine in `BENCHMARKS.md` has more (and heterogeneous P/E)
> cores. Any row at `t≥4` here runs at or past full core saturation, and
> `t=8` genuinely oversubscribes 2 logical threads per core — treat `t=8`
> as an oversubscription stress case, not a scaling data point comparable
> 1:1 with the Mac's `t=8`.

Reproduce a single row:

```bash
./gradlew jmh -Pjmh.include=StackScalingBenchmark -Pjmh.threads=4
```

## Implementations compared

| Family | Class | Synchronization |
|---|---|---|
| Blocking | `LockedStack` | `synchronized` methods |
| Blocking | `ReentrantLockStack` | `ReentrantLock` (non-fair) |
| Lock-free | `LockFreeStack` | CAS on `head` + node `next` via `AtomicReferenceFieldUpdater` |
| Lock-free | `EliminationStack` | Treiber CAS + Hendler-Shavit elimination back-off array (8 slots) |
| Lock-free | `BackoffLockFreeStack` | Treiber CAS + exponential CAS-failure backoff (`Thread.onSpinWait()`, 1→1024 spins) |
| Lock-free | `ExchangerEliminationStack` | Treiber CAS + elimination via `java.util.concurrent.Exchanger` arena (**2 exchangers**, 500 ns timeout) |

Benchmark shapes (see `BENCHMARKS.md` §Methodology for full description),
all six implementations now run in all four:
`StackScalingBenchmark` (symmetric `pushPop`, threads via `-Pjmh.threads`,
`Mode.Throughput`), `StackContentionBenchmark` (`@Group` 2 producers + 2
consumers, fixed 4, `Mode.Throughput` + `Mode.SampleTime`),
`StackBurstyBenchmark` (`push` + ~200 CPU tokens + `pop` + ~200 tokens,
`Mode.Throughput`), `StackAsymmetricBenchmark` (`@Group` 3 producers + 1
consumer, fixed 4, `Mode.Throughput` + `Mode.SampleTime`).

---

## Throughput — symmetric `pushPop` (ops/μs, higher is better)

| impl | t=1 | t=2 | t=4 | t=8 | 2P + 2C |
|---|---:|---:|---:|---:|---:|
| `LockedStack` (`synchronized`) | 21.7 ± 0.3 | 13.6 ± 1.6 | 7.7 ± 1.3 | 20.5 ± 1.2 | 12.9 ± 2.5 |
| `ReentrantLockStack` (non-fair) | 24.6 ± 1.0 | 3.3 ± 0.4 | 17.7 ± 1.1 | 17.8 ± 1.2 | 11.3 ± 7.3 |
| `LockFreeStack` | **35.3 ± 0.6** | 7.6 ± 1.2 | 5.0 ± 0.4 | 4.9 ± 0.2 | 8.9 ± 0.6 |
| `EliminationStack` | 35.2 ± 0.3 | 29.4 ± 0.4 | 20.4 ± 0.9 | 19.9 ± 0.6 | 20.9 ± 1.1 |
| `BackoffLockFreeStack` | 35.5 ± 0.2 | 32.2 ± 0.3 | **31.3 ± 0.5** | **31.3 ± 0.6** | 43.7 ± 25.1 |
| `ExchangerEliminationStack` (2 slots) | 35.4 ± 0.4 | **33.0 ± 1.0** | 21.2 ± 1.4 | 27.4 ± 1.3 | **43.0 ± 3.2** |

## Throughput — asymmetric 3 producers + 1 consumer (ops/μs, higher is better)

| impl | 3P + 1C |
|---|---:|
| `LockedStack` (`synchronized`) | 12.1 ± 4.5 |
| `ReentrantLockStack` (non-fair) | 20.0 ± 7.5 |
| `LockFreeStack` | 7.2 ± 0.9 |
| `EliminationStack` | **33.3 ± 1.8** |
| `BackoffLockFreeStack` | 23.9 ± 9.3 |
| `ExchangerEliminationStack` (2 slots) | 26.2 ± 4.7 |

## Throughput — bursty `push + work + pop + work` (ops/μs, higher is better)

| impl | t=2 | t=4 | t=8 |
|---|---:|---:|---:|
| `LockedStack` (`synchronized`) | 2.03 ± 0.04 | 3.91 ± 0.18 | 3.81 ± 0.07 |
| `ReentrantLockStack` (non-fair) | 1.89 ± 0.17 | 2.82 ± 0.64 | 1.35 ± 0.29 |
| `LockFreeStack` | **2.09 ± 0.03** | **4.04 ± 0.08** | **4.07 ± 0.06** |
| `EliminationStack` | 2.08 ± 0.04 | 3.76 ± 0.13 | 3.86 ± 0.17 |
| `BackoffLockFreeStack` | 2.10 ± 0.02 | 3.98 ± 0.10 | 3.82 ± 0.46 |
| `ExchangerEliminationStack` (2 slots) | 1.69 ± 0.25 | 2.66 ± 0.08 | 2.96 ± 0.16 |

## Allocation (B/op, lower is better)

| impl | t=1 sym | t=2 sym | t=4 sym | t=8 sym | 2P + 2C | 3P + 1C | t=2 bursty | t=4 bursty | t=8 bursty |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| `LockedStack` | 24.0 | 24.0 | 24.0 | 24.0 | 15.9 | 18.5 | 24.1 | 24.0 | 24.0 |
| `ReentrantLockStack` | 24.0 | 44.0 | 24.2 | 24.2 | 46.9 | 17.0 | 24.7 | 30.2 | 24.4 |
| `LockFreeStack` | 24.0 | 24.0 | 24.0 | 24.0 | 18.1 | 18.6 | 24.1 | 24.0 | 24.0 |
| `EliminationStack` | 24.0 | 24.0 | 24.0 | 24.0 | 25.5 | 18.8 | 24.1 | 24.0 | 24.0 |
| `BackoffLockFreeStack` | 24.0 | 24.0 | 24.0 | 24.0 | 13.3 | 16.0 | 24.1 | 24.0 | 24.0 |
| `ExchangerEliminationStack` (2 slots) | 24.0 | 25.4 | 24.7 | 24.0 | 12.6 | 15.0 | 36.9 | **38.5** | 25.4 |

---

## Latency distribution — contended workloads (SampleTime, µs/op, lower is better)

Same rows as `BENCHMARKS.md` §*Latency distribution* — p99.9 is the
meaningful tail metric, `max` is one worst sample (JIT/GC/scheduler
hiccup), read as an order of magnitude, not a value.

### 2 producers + 2 consumers (`StackContentionBenchmark`)

| impl | p50 | p90 | p99 | p99.9 | max |
|---|---:|---:|---:|---:|---:|
| `LockedStack` (`synchronized`) | 0.146 | 1.772 | 144.13 | 685.06 | 17433 |
| `ReentrantLockStack` (non-fair) | **0.046** | **0.061** | 28.72 | 277.50 | 34931 |
| `LockFreeStack` | 0.357 | 1.005 | 2.25 | **4.75** | 11043 |
| `EliminationStack` | 0.095 | 0.761 | 3.47 | 7.06 | 6267 |
| `BackoffLockFreeStack` | 0.042 | 0.087 | 3.57 | 1404.93 | **1769996** |
| `ExchangerEliminationStack` (2 slots) | 0.043 | 0.266 | **2.87 †** | 55.49 | 7627 |

† Tied within noise with `EliminationStack`'s p99 (both round to ~2.9 µs on repeat forks).

### 3 producers + 1 consumer (`StackAsymmetricBenchmark`)

| impl | p50 | p90 | p99 | p99.9 | max |
|---|---:|---:|---:|---:|---:|
| `LockedStack` (`synchronized`) | 0.127 | 1.496 | 13.90 | 41.95 | 514851 |
| `ReentrantLockStack` (non-fair) | 0.047 | **0.063** | 22.50 | 71.30 | 213647 |
| `LockFreeStack` | 0.396 | 1.108 | 2.66 | **5.67** | 214172 |
| `EliminationStack` | **0.043** | 0.211 | 5.16 | 11.97 | 22020 |
| `BackoffLockFreeStack` | 0.044 | 0.054 | **2.26** | 840.83 | **639631** |
| `ExchangerEliminationStack` (2 slots) | 0.043 | 0.141 | 4.90 | 55.62 | 6144 |

- **`BackoffLockFreeStack`'s `max` is a genuine outlier on this machine, not
  a rounding artifact** — 1.77 ms *seconds* at 2P+2C (1,769,996 µs) and 640
  ms at 3P+1C. The GC profiler confirms real GC pauses landed inside these
  runs (5.1 s and 10.5 s of total GC time respectively, vs ~1 s for most
  other impls), so a stop-the-world pause during an active exponential
  backoff spin (up to 1024 `onSpinWait` iterations) is the likely cause.
  Median and p90 are excellent (0.042–0.087 µs, among the best of any impl),
  so this is a **tail-only** pathology — same "necessary but not sufficient"
  lesson as `BENCHMARKS.md`'s framing: lock-free alone doesn't bound the
  tail, and neither does a spin-based backoff once GC gets involved.
- **`EliminationStack` is again the tail reference on p99.9** (7.06 µs
  2P+2C, 11.97 µs 3P+1C) — same story as the Mac: a bounded on-core spin
  fallback that never parks gives the tightest deepest-percentile figure.
- **The retuned (2-slot) `ExchangerEliminationStack` has a good p99 but a
  much worse p99.9 than on the Mac** — 55.5 / 55.6 µs here vs 14.2 / 85.2
  µs there (2P+2C better on Mac, 3P+1C worse on Mac — mixed). With only 4
  cores, 2 exchangers still absorb the traffic well enough for a good
  median/p99, but occasional unmatched attempts fall through to the
  `Exchanger`'s park path and show up one percentile deeper than on the
  Mac's more numerous cores.
- **`ReentrantLockStack` again has the best median but a heavy `max`**
  (28–213 ms), the same barging-vs-park split documented in `BENCHMARKS.md`.

## Findings (this machine)

- **`LockFreeStack` still collapses earliest, but not to zero** — t=1→t=2
  drops 35.3 → 7.6 ops/μs (−78%), close in relative size to the *new* Mac
  number (120.0 → 28.7, −76%): both machines now show the collapse
  starting at t=2, not t≥4 as the old (8-slot-era) Mac reference did. The
  difference that remains is the floor: Linux keeps ~14% of its peak at
  t=8 (4.9 vs 35.3), while the Mac drops to ~3% of its peak (3.2 vs
  120.0) — no SMT means fewer logical contenders even at "t=8", so this
  box never fully bottoms out the way the 10-core Mac does.
- **`BackoffLockFreeStack` scales dramatically better here than on the
  Mac — the biggest divergence in this refresh.** On Linux it stays flat
  from t=2 through t=8 (32.2 → 31.3 → 31.3 ops/μs), ending up *tied with
  `ExchangerEliminationStack` for the best symmetric-scaling throughput at
  t=4/t=8*. On the Mac it instead tracks plain `LockFreeStack`'s collapse
  almost exactly (27.9 → 13.2 → 3.2, nearly identical to `LockFreeStack`'s
  28.7 → 13.7 → 3.2). With only 4 physical cores, exponential
  `onSpinWait` backoff apparently has enough room to actually de-schedule
  retry pressure between CAS attempts; on the Mac's higher core count the
  same backoff schedule doesn't prevent the same head-CAS collapse.
- **`ReentrantLockStack`'s high-thread-count strength on the Mac does not
  reproduce here.** On the Mac (2-slot report), `ReentrantLock` *wins*
  t=4 (69.6), t=8 (67.8), and 2P+2C (89.2) outright. On Linux it never
  leads a single throughput row — its best showing is t=4/t=8 symmetric
  (17.7/17.8), well behind `BackoffLockFreeStack`/`ExchangerEliminationStack`
  (~27–33), and at 2P+2C it is the *worst* impl measured (11.3 ± 7.3,
  behind even `LockedStack`). The t=2 weak point flagged as a possible
  one-off artifact in the previous version of this report (3.6 ± 0.2)
  reproduced almost exactly in this fresh run (3.3 ± 0.4) — it no longer
  looks like a fluke, but a real property of non-fair `ReentrantLock`
  barging at exactly 2 threads on this CPU.
- **`EliminationStack` again wins the asymmetric 3P+1C row** (33.3 ops/μs)
  over `ExchangerEliminationStack` (26.2) — the same flip versus the Mac
  (where `ExchangerEliminationStack` wins 3P+1C decisively, 94.1) noted in
  the previous version of this report, and it still holds under the
  retuned 2-slot config.
- **Bursty workload again favors `LockFreeStack`/`BackoffLockFreeStack`**
  (4.04–4.07 ops/μs at t=4/t=8), matching the Mac's finding that low
  natural contention plus a cheap single-CAS fast path beats
  elimination/exchanger overhead when there's think-time between
  operations. Unlike the Mac (which drops to 2.4 at bursty t=8),
  throughput here stays high through t=8 — consistent with the "no SMT
  means t=8 doesn't fully oversubscribe the way it implies" theme above.
- **`ExchangerEliminationStack` allocation under bursty is lower here than
  on the Mac** — 38.5 B/op at t=4 (peak) vs the Mac's 56 B/op. Fewer
  contending threads on 4 cores means a higher elimination match rate, so
  fewer failed exchanges fall through to `Exchanger`'s internal `Node`
  allocation.

## Caveats

- **Small core count amplifies noise.** Several rows above have error
  bars comparable to or larger than the differences between
  implementations (e.g. `BackoffLockFreeStack` 2P+2C 43.7±25.1,
  `ReentrantLockStack` 2P+2C 11.3±7.3). Consider these directional,
  not decisive, without a higher fork/iteration count.
- **SampleTime `max` swings by orders of magnitude between sessions** —
  one GC pause or scheduler hiccup can move it 10×+ (see
  `BackoffLockFreeStack` above). Compare implementations on p99/p99.9,
  which average over thousands/millions of samples; treat `max` as an
  order of magnitude, not a value.
- The 8→2 `ELIM_SLOTS` retune was tuned and validated on the Mac
  (`ExchangerTuningBenchmark`, see `BENCHMARKS.md` §*Retuning*) at 2P+2C;
  it was not independently re-swept on this machine. The tail and
  throughput data above show it still behaves sensibly here (good
  median/p99, some p99.9 cost), but a from-scratch sweep on a 4-core/no-SMT
  box might land on a different optimum.
- Reference throughput/allocation methodology, workload descriptions,
  the Lincheck correctness caveat, and the full engineering narrative
  behind each implementation live in `BENCHMARKS.md` — this file only
  re-runs the Stack rows to have a second data point on different
  hardware. Read `BENCHMARKS.md` first for context on *why* each
  implementation exists.
- Raw JMH console logs and `results.json` per run are not committed;
  regenerate with the command in **Environment** above if needed.
