plugins {
    id("io.ksmt.ksmt-base")
}

dependencies {
    implementation(project(":ksmt-core"))
    implementation(project(":ksmt-z3"))
    implementation(project(":ksmt-cvc5"))
    implementation(project(":ksmt-yices"))
    testImplementation(project(":ksmt-runner"))
}

tasks.withType<Test>().configureEach {
    doFirst { systemProperty("ksmt.runner.classpath", sourceSets["test"].runtimeClasspath.asPath) }
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
