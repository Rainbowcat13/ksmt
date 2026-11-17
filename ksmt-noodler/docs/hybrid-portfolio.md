# Hybrid solvers in a KSMT portfolio

Use `KPortfolioSolverManager` with `KNoodlerHybridZ3Solver`,
`KNoodlerHybridCvc5Solver` and `KNoodlerHybridYicesSolver`. These are one-argument
constructor adapters for the same `KNoodlerHybridSolver` algorithm, suitable for
KSMT's existing custom solver registration:

```kotlin
manager.registerSolver(
    KNoodlerHybridZ3Solver::class,
    KNoodlerHybridSolverUniversalConfiguration::class,
)
```

Register each selected adapter before `createPortfolioSolver`. The optional
`configurations` list has one ordinary `KSolverConfiguration` receiver per entry;
use `setStringParameter("noodler.encodingTarget", "BV")` or `"INT"` independently
for each entry. Duplicate classes can have different options. The runner records
and replays these options, assertions and scopes when it recreates a worker.
Configure before the first check as with a direct hybrid solver.

The existing portfolio waits past UNKNOWN results and worker failures for a
SAT/UNSAT winner, and exposes that winner's model or core. Workers own separate
KContexts and native solvers. Cancellation terminates workers with pending checks using the existing runner
recovery path; the next query recreates them and replays their journals. This
bypasses protocol queues occupied by blocking native checks. The existing hard
timeout also terminates an unresponsive worker. Close both the portfolio
and its manager. An injected custom backend factory cannot be serialized; use a
public single-KContext constructor adapter when registering custom backends.

The runnable example is `examples/src/main/kotlin/NoodlerPortfolioExample.kt`:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-17'
.\gradlew.bat -p examples runNoodlerPortfolioExample
```

Normally workers inherit `java.class.path`. Embedders that hide dependencies in
an isolated classloader may provide `ksmt.runner.classpath` explicitly. The module
tests set this to their resolved test runtime classpath, so worker tests run the
current local implementation.
