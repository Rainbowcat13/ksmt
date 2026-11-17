plugins {
    kotlin("jvm") version "1.7.20"
    java
}

repositories {
    mavenCentral()
}

dependencies {
    // core
    implementation("io.ksmt:ksmt-core:0.5.29")
    // z3 solver
    implementation("io.ksmt:ksmt-z3:0.5.29")
    implementation("io.ksmt:ksmt-noodler:0.5.29")
    implementation("io.ksmt:ksmt-cvc5:0.5.29")
    implementation("io.ksmt:ksmt-yices:0.5.29")
    // Runner and portfolio solver
    implementation("io.ksmt:ksmt-runner:0.5.29")

    testImplementation(kotlin("test-junit5"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.8.2")
}

tasks.test {
    useJUnitPlatform()
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

tasks.register<JavaExec>("runNoodlerHybridExample") {
    group = "application"
    description = "Run ordinary string, INT/BV, mixed input and solver-free UNSAT examples"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("NoodlerHybridExampleKt")
    args(project.findProperty("noodlerBackend")?.toString() ?: "Z3")
}

tasks.register<JavaExec>("runNoodlerPortfolioExample") {
    group = "application"
    description = "Run the shared hybrid solver with three backends in a KSMT portfolio"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("NoodlerPortfolioExampleKt")
}
