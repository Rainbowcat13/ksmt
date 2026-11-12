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
        languageVersion.set(JavaLanguageVersion.of(11))
    }
}
