# Laymark 0.x implementation plan

Derived from a clause-by-clause audit of `laymark-0.x-spec.md` against the source. Every slice
cites the spec sections it satisfies.

**A slice is one PR, stacked on the previous, and is not done until the clauses it names are
implemented and verified against a real instance.** Tests come after verification, or first under
red-green-refactor — never speculatively ahead of it.

## Shipped

| # | Slice | Spec |
| --- | --- | --- |
| 0 | Scaffolding | §4, §11 |
| 1 | Protocol and plan | §5.4 |
| 2 | Launch | §5.1, §5.2 |
| 3 | Tracer bullet | §6.1, §6.2 (partial) |
| 4 | Measurement channels | §6.3 (most) |
| 5 | Scenario model | §7 (schema and placement; no content) |
| 6 | Materialisation and selection types | §5.3, §8.1–8.4 (types only) |
| 7 | Statistics and reporting | §9 (partial), §10 (partial) |

Verified in-game: launch, handshake, all channels, multi-phase capture, config-driven scenarios,
per-arm materialisation and restore, and a false-positive self-test.

## Wrong, not merely missing

These ship today and produce incorrect behaviour. They come first.

| Clause | Defect |
| --- | --- |
| §6.1 | **Phase 2 is unreachable.** `HarnessRun` positions before checking the negative precondition, so the teleport generates the target it was about to require ungenerated. The capture never brackets the arrival. |
| §8.4 | **`Selection.round` promotes everything that did not regress.** The spec promotes the *single best* per round. As written, greedy forward selection collapses into one round. |
| §6.3, §9 | **Spark's `backgroundProfiler` is on by default on the integrated server** and nothing disables or detects it. Every run to date with Spark installed is contaminated. |
| §6.2 | **Windowed 1600×900 is not enforced.** `Preset` documents the opposite. Real runs recorded 1920×1080, so the intended CPU-side sensitivity is not obtained. |
| §6.2 | **Game rules are never set.** Day/night advance, weather, spawn-chunk radius and mob spawning run at vanilla defaults inside every capture — four uncontrolled variables. |
| §6.2 | `framerateLimit` and `vsync` are configurable on `Preset`; the spec makes them non-configurable overrides. |
| §9 | **Contamination tiers inverted.** A preset reverted mid-run, and the throttle firing mid-capture, are flags; the spec hard-fails both. Readback happens once, not as a runtime invariant during a capture. |
| §6.3, §13 | **Completion-target scenarios are scored on the frame-interval mean** — precisely the quantity the spec says is not comparable across unequal windows. `millisPerChunkReceived` exists and is unused. |
| §7 | ~~**World reuse never happens.** `dependsOn` affects ordering only; every repetition gets a fresh world and discards it, so a dependent scenario measures something other than its config describes.~~ Fixed: a dependent scenario reopens its dependency's save, and a save survives until every scenario downstream of it has run. |
| §5.3 | `Materialization.verify` compares names, not hashes. A jar swapped under the same name passes. |
| §10 | Report renders `0,8%` — `String.format` without a `Locale`. |

## Remaining slices

### 8 — Correctness pass
Everything in the table above. §6.1, §6.2, §6.3, §7, §8.4, §9, §10.

### 9 — Selection driver ([#30](https://github.com/lucent-mc/laymark/issues/30))
The whole of §8.4's runtime. `Selection`, `Bundle`, `BandGate`, `Branching`, `Schedule`,
`RoundTemplate` and `JarProbe` exist in `core` with **no production call site**.

- `--candidates`, `--template`, `--baseline-interval` on the invocation.
- Baseline modes: parent-only and pack-minus-listed.
- Round loop: bundle → schedule → run → compare → rank → promote single best → re-bundle.
- Stop when the best remaining candidate regresses.
- Fail when an inherited mod requires a named candidate.
- Report projected run count before starting; branch on conflicts.
- Promotion order by biggest improver for runtime; selection still by weighted score.
- Populate per-round history and branch points in the report (§10.5).

Added from the first real two-candidate run (details in #30):
- Pool a candidate's arms into one t-based measurement per scenario; one composite **score** per
  candidate, per-scenario comparisons underneath as evidence.
- Candidate cards in the GUI: composite score, coloured per-metric deltas (mspt / fps /
  time-per-chunk / retained heap),
  and from round 2 a second row against the *original* baseline as well as the current one.
- The slate's `armsTotal` becomes the projected total across rounds, so `n/N arms in r/R runs`
  and the ETA describe the whole experiment.

### 10 — Parity and output equivalence
- §9 Stimulus parity as a hard gate, compared **across arms**: world, seed, position, camera,
  duration, effective settings. Nothing compares arm to arm today.
- §9 Output equivalence — screenshot comparison at canonical poses with a perceptual tolerance,
  plus visible-section counts. The spec calls this "the real correctness gate".
- §9 evidence 2: failure injection against every fail-closed path.

### 11 — Passes and machine profile
- §8.2 Three sequences per arm: acclimation, cold, warm. Currently one launch is one pass; no
  result carries a pass discriminator and no acclimation arm is ever constructed.
- §8.2 Record scenario-list revision and array position on every result.
- §9.6 Persist the machine profile to `~/.laymark/`, keyed by hardware fingerprint, and wire
  `widen` into `Comparison`. The widen-only rule is implemented; nothing stores or reads it.
- §9.3 1% lows alongside p99.

### 12 — Dependency sources
- §8.4 Jar-in-jar payload traversal; SHA-512 probe cache; **fail-closed on an unrecognised
  descriptor** — currently inverted to fail-open.
- §8.4 Modrinth API as the second source; explicit overrides as the third. Both `Provenance`
  constants have no producer.
- §8.4 Incompatibility as a first-class graph edge, parsed from both loaders' manifests.
- §8.4 Inlay layer scope as validation: current-layer, inherited, override or exclusion.

### 13 — Precondition machinery and content
- §6.1, §7 Chunky: generation trigger, `GenerationProgressEvent.complete()` as the barrier,
  independent sample-verification of the footprint. Zero references today.
- §6.1 ~~Phase 3's positive precondition — chunks verified on disk.~~ Done: world reuse made it
  answerable without Chunky, since the terrain comes from the scenario depended on. Chunky is still
  what a *standalone* streaming scenario would need.
- §7 The default scenario set, which the spec requires Laymark to ship: chunk generation, and
  entity culling with occluders inside the pen.
- Possible, once Chunky is in: **server-side generation with no client attached.** `doWorldLoad`
  splits cleanly at `startMemoryChannel()` — spinning the integrated server is separable from
  joining it — and Chunky supplies its own tickets and pumps the chunk system through the main
  thread executor, which the run loop drains even while `IntegratedServer.paused` is true. It also
  resets `emptyTicks`, defusing vanilla's idle pause. Costs one mixin.
  Would be a **phase of its own, not a variant**: with no client there are no frame, render-call,
  submit or GPU channels, so the signal is MSPT, work counters and wall-clock — and generation with
  nothing streaming or meshing it is not comparable to generation measured from inside the world.
- §7 Verify placement against a real `.schem`.

### 14 — Result layout and provenance
- §5.5 `experiment.json`, `runs/<run-id>/`, `environment.json`, `logs/`.
- §5.5 Samples as `samples/*.jsonl.gz`. Today they are inlined into `result.json`: **4.2 MB for one
  three-repetition scenario**, pretty-printed and uncompressed.
- §2 The five strata recorded on every result, and pooling refused across them.
- §10.6 Carry per-run, per-phase and run-level flags into the report. Every trust annotation is
  currently printed to stdout and omitted from the durable artifact.
- §10.2 Per-comparison appendix detail.
- §4 Mod inventory from FML — the only in-process check that materialisation produced the arm.

### 15 — Shipping
- §3 Embed the runner in the mod jar as an inert resource, not under `META-INF/jarjar/`.
- §5.3 Extract `laymark-runner-<version>.jar` to the instance root on first launch; never delete it.
- §5.1 Sweep leaked `laymark-*` saves at startup — a run killed mid-schedule leaves the world its
  dependents were still holding a lease on.
- §5.1 Pin heap and GC flags, which is the reason for launching directly at all.
- §5.4 Emit heartbeats and add an idle timeout; a hung harness currently consumes the full run
  timeout.
- §5.3 Document the `.layignore` entry for Inlay users.
- §12 Cut a GitHub Release with both jars, the changelog, and the compatibility matrix. There is no
  mod-host listing — see spec §3.

## Not in 0.x

Per §2: the Fabric port beyond the stub, Minecraft versions other than 26.1.2, dedicated-server and
multiplayer benchmarking, and benchmarking ancestor-layer content.
