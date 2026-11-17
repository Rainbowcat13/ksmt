# Benchmark tooling migration

Executable Noodler experiments now live in
[C:\Users\danii\ksmt-noodler-experiments](../../../ksmt-noodler-experiments/README.md).
The independent composite Gradle build resolves every KSMT dependency against
the current local solver checkout. It contains no copy of the production solving
algorithm. New runs, their configuration, exact commands, records, logs, analysis
and reports remain in that workspace.

Removal followed a successful external five-configuration smoke run at
`results/smoke/20261117T104320Z-0c210447`: five independently validated SAT
assignments and five UNSAT results on two existing fixtures. This validates
migration mechanics; those fixtures do not establish application coverage.

Removed from this repository after archive verification:

- The seven files in production package `io.ksmt.solver.noodler.benchmark`.
- `core/NoodlerQfSimpleRunner.kt` and `core/NoodlerQfSimpleDebugSample.kt`.
- The obsolete diagnostic `core/NoodlerZ3Example.kt` and its launch task; current
  public API examples live in the separate `examples/` build.
- `ksmt-noodler/scripts/` executable orchestration, analysis and script tests.
- The four benchmark harness test/fixture sources, the legacy QfSimple runner
  test, and the already disabled Noodler-only corpus recorder in `ksmt-test`.
- Benchmark/diagnostic Gradle tasks, the benchmark classpath writer, and the
  `noodler.benchmark.classpath` test-worker hook.

Preserved in this repository:

- Production solvers, capability checks, configuration, diagnostic statistics,
  automata and algorithm regression tests.
- Existing local corpora, manifests, deterministic correctness fixtures and all
  historical result files under `ksmt-noodler/benchmarks/results`.
- Unrelated KSMT benchmark infrastructure and user/local files.
- Portfolio worker support, including the `ksmt-runner` test dependency and
  `ksmt.runner.classpath` hook.

Migrated exact copies include the original harness sources/tests, scripts,
legacy runners, historical documentation and historical results. Active external
sources were then adapted independently. Original copies remain under external
`archive/original-source/`, `archive/legacy-core/`, `archive/legacy-tests/`,
`archive/docs/` and `archive/results/`. The original Python scripts were copied
to external `scripts/`. Original script hashes are recorded in
`archive/migration-source-manifest.csv`.

Before removal, 89 source/archive SHA256 pairs were checked against
`archive/migration-source-manifest.csv`. An additional five source records,
including the obsolete public example and disabled corpus recorder, are recorded
in `archive/additional-migration-manifest.csv`; the example archive was verified
before its earlier removal by the examples owner. `archive/source-revision.txt`
records the original base revision. Historical measurements are never merged
into measurements for the final shared-hybrid build.
