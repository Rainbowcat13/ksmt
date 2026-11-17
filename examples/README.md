# KSMT examples

This directory is a separate Gradle build. Its `includeBuild("..")` configuration
resolves **all KSMT modules from the parent checkout**, including the local
Noodler implementation. The dependency versions in `build.gradle.kts` do not
select older published artifacts while this substitution is enabled.

Use JDK 17, then run these commands from `examples/`:

```powershell
.\gradlew.bat classes test --console=plain
.\gradlew.bat runNoodlerHybridExample -PnoodlerBackend=Z3 --console=plain
.\gradlew.bat runNoodlerHybridExample -PnoodlerBackend=CVC5 --console=plain
.\gradlew.bat runNoodlerHybridExample -PnoodlerBackend=YICES --console=plain
.\gradlew.bat runNoodlerPortfolioExample --console=plain
```

[NoodlerHybridExample.kt](src/main/kotlin/NoodlerHybridExample.kt) uses ordinary
KSMT assertions. Its constructor selects the backend; `encodingTarget` separately
selects `INT` or `BV` for generated characters and DFA states. Both runs produce
`x = "abab"`. Lengths and mathematical integer arithmetic remain unbounded.
The mixed example couples an original four-bit declaration to a string length
through unsigned `mkBv2IntExpr`, producing `x = "aaa"` and a four-bit value of 3.
Original BV inputs are supported independently of the generated encoding target.

The final example asserts `x = "a"` and `x = "b"`. A throwing backend factory
and the construction/assertion/check counters verify that preflight proves UNSAT
with zero backend activity. An inconclusive preflight continues normal solving.

[NoodlerPortfolioExample.kt](src/main/kotlin/NoodlerPortfolioExample.kt) runs the
same hybrid algorithm with Z3, cvc5 and Yices through KSMT's existing worker
process portfolio. It shows independent encoding settings and reads the winner's
model.

See the [hybrid API](../ksmt-noodler/docs/hybrid-api.md),
[encoding configuration](../ksmt-noodler/docs/hybrid-configuration.md), and
[supported mixed fragment](../ksmt-noodler/docs/mixed-bitvectors.md) for resource,
model, lifecycle and syntax boundaries. `KNoodlerYicesSolver(ctx)` remains a
compatibility entry point. `KNoodlerSolver(ctx, nativeStringSolver)` is the
separate native-string preprocessing strategy.

The other [Kotlin examples](src/main/kotlin) and [Java examples](src/main/java)
cover the general KSMT API. Experiment orchestration lives in the sibling
`ksmt-noodler-experiments` workspace; it is not a library usage example.
