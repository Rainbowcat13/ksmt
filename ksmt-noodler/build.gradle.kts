import java.io.File

plugins {
    id("io.ksmt.ksmt-base")
}

dependencies {
    implementation(project(":ksmt-core"))
    implementation(project(":ksmt-z3"))
    implementation(project(":ksmt-cvc5"))
    implementation(project(":ksmt-yices"))
}

tasks.register<JavaExec>("runNoodlerZ3Example") {
    group = "application"
    description = "Run Noodler ping-pong example with Z3 backend"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("io.ksmt.solver.noodler.core.NoodlerZ3ExampleKt")
    jvmArgs("-Xss8m")
}

tasks.register<JavaExec>("runNoodlerQfSimpleDebugSample") {
    group = "application"
    description = "Run a single qf_simple benchmark with Noodler and Z3, printing the Z3 model"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("io.ksmt.solver.noodler.core.NoodlerQfSimpleDebugSampleKt")
    workingDir = rootDir
    project.findProperty("noodlerDebugSample")?.toString()?.let { args("--sample", it) }
    jvmArgs("-Xss8m")
}

tasks.register<JavaExec>("prepareNoodlerQfSimpleTests") {
    group = "application"
    description = "Copy fragment-safe QF string benchmarks with extractable Noodler statements into ksmt-noodler/qf_simple_tests ordered by variable count"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("io.ksmt.solver.noodler.core.NoodlerQfSimpleRunnerKt")
    workingDir = rootDir
    args("prepare")
    jvmArgs("-Xss8m")
}

tasks.register<JavaExec>("runNoodlerQfSimpleTests") {
    group = "application"
    description = "Run Z3, cvc5, KNoodler(Z3), KNoodler(cvc5), and KNoodlerYices on ksmt-noodler/qf_simple_tests"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("io.ksmt.solver.noodler.core.NoodlerQfSimpleRunnerKt")
    workingDir = rootDir
    args("run")
    project.findProperty("noodlerSimpleFrom")?.toString()?.let { args("--from", it) }
    project.findProperty("noodlerSimpleLimit")?.toString()?.let { args("--limit", it) }
    project.findProperty("noodlerSimpleTimeoutMs")?.toString()?.let { args("--timeout-ms", it) }
    jvmArgs("-Xss8m")
}

tasks.register<JavaExec>("launchNoodlerQfSimpleTests") {
    group = "application"
    description = "Prepare ksmt-noodler/qf_simple_tests if needed, then compare Z3, cvc5, and all Noodler backends"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("io.ksmt.solver.noodler.core.NoodlerQfSimpleRunnerKt")
    workingDir = rootDir
    args("launch")
    project.findProperty("noodlerSimpleFrom")?.toString()?.let { args("--from", it) }
    project.findProperty("noodlerSimpleLimit")?.toString()?.let { args("--limit", it) }
    project.findProperty("noodlerSimpleTimeoutMs")?.toString()?.let { args("--timeout-ms", it) }
    if (project.findProperty("noodlerSimpleRebuild")?.toString()?.equals("true", ignoreCase = true) == true) {
        args("--rebuild")
    }
    jvmArgs("-Xss8m")
}

tasks.register<JavaExec>("benchmarkNoodlerSolvers") {
    group = "application"
    description = "Compare five string solvers in uniform isolated processes with independent model validation and resumable reports"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("io.ksmt.solver.noodler.benchmark.NoodlerBenchmarkRunnerKt")
    workingDir = rootDir
    args("run")
    mapOf(
        "Source" to "source", "Output" to "output", "Resume" to "resume", "Solvers" to "solvers",
        "SolveMs" to "solve-ms", "StartupParseMs" to "startup-parse-ms", "ValidationMs" to "validation-ms",
        "Repeats" to "repeats", "Prefix" to "prefix", "Offset" to "offset", "Limit" to "limit",
        "MaxJobs" to "max-jobs", "MaxWallMs" to "max-wall-ms",
        "SourceList" to "source-list", "Variant" to "variant",
        "MaxAutomataStates" to "max-automata-states", "MaxAutomataStorage" to "max-automata-storage",
        "MaxEncodingConstraints" to "max-encoding-constraints", "MaxPreprocessingUnits" to "max-preprocessing-units",
        "MaxCharacterPositions" to "max-character-positions", "MaxIdleRounds" to "max-idle-rounds",
        "OptionalPreprocessing" to "optional-preprocessing", "SmallFirstSearch" to "small-first-search",
        "AdaptiveLengthProjection" to "adaptive-length-projection",
    ).forEach { (property, option) ->
        project.findProperty("noodlerBenchmark$property")?.toString()?.let { args("--$option", it) }
    }
    jvmArgs("-Xss8m")
}

tasks.register("writeNoodlerBenchmarkClasspath") {
    group = "application"
    description = "Build and write the resolved benchmark launcher classpath for direct Java runs"
    val runtime = sourceSets["main"].runtimeClasspath
    val outputFile = layout.buildDirectory.file("noodler-benchmark-classpath.txt")
    dependsOn(runtime)
    inputs.files(runtime).withPropertyName("benchmarkRuntime")
    outputs.file(outputFile)
    doLast {
        val destination = outputFile.get().asFile
        destination.parentFile.mkdirs()
        // JVMs ignore missing Java/resources output folders; omit them from a snapshot-ready classpath.
        val entries = runtime.files.filter { it.exists() }.joinToString(File.pathSeparator) { it.absolutePath }
        destination.writeText(entries + "\n", Charsets.UTF_8)
        logger.lifecycle("Benchmark classpath: ${destination.absolutePath}")
    }
}

tasks.withType<Test>().configureEach {
    // Gradle test workers expose only their bootstrap jar through java.class.path.
    doFirst { systemProperty("noodler.benchmark.classpath", sourceSets["test"].runtimeClasspath.asPath) }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])

            addKsmtPom()
            addSourcesAndJavadoc(project)
            signKsmtPublication(project)
        }
    }
}
