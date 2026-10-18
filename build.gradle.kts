plugins {
    java
}

// Not the `application` plugin: its start scripts collide with the fat jar below, and
// run.cmd / run.sh already do that job.
val mainClassName = "dev.litetook.sockseek.App"

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

dependencies {
    // the flags and --help, which are an interface and not worth hand-rolling
    implementation("info.picocli:picocli:4.7.7")
    // one JSON document: the track list inside Spotify's embed page
    implementation("com.fasterxml.jackson.core:jackson-databind:2.19.0")
    // sockseek's _index.csv quotes any field holding a comma, and file names do
    implementation("org.apache.commons:commons-csv:1.14.1")
    // the JDK unpacks zip and gzip but not tar, and the macOS/Linux assets are .tar.gz
    implementation("org.apache.commons:commons-compress:1.27.1")

    testImplementation(platform("org.junit:junit-bom:5.12.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

// One file to hand out, so run.cmd is a two-liner and nothing has to be installed.
// Written by hand rather than with a shading plugin: this is ten lines, and a plugin
// is one more thing to keep in step with Gradle.
val fatJar by tasks.registering(Jar::class) {
    archiveFileName = "sockseek-cli.jar"
    manifest { attributes("Main-Class" to mainClassName) }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(sourceSets.main.get().output)
    from({
        configurations.runtimeClasspath.get()
            .filter { it.name.endsWith(".jar") }
            .map { zipTree(it) }
    })
    // signatures of the libraries we unpack do not apply to the jar we build
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "module-info.class")
}

// The pins travel INSIDE the jar, because a release is one file: without them a fresh
// folder cannot fetch sockseek at all - measured on a clean install 2026-10-18, which
// stopped at "sockseek is not pinned in dependencies.yaml" with nothing to suggest.
// Through the resources rather than the jar task, so the tests load the same copy.
tasks.processResources { from(layout.projectDirectory.file("dependencies.yaml")) }

tasks.build { dependsOn(fatJar) }

// ------------------------------------------------------------------ the gates
//
// Three checks that a unit test cannot make, each driving the finished jar as a process
// rather than a class: the whole command over a generated fixture, the real sockseek binary
// in its mock mode, and the manuals against the options the tool really accepts. They are
// ordinary JUnit tests in dev.litetook.sockseek.gates, so `gradlew test` runs them with
// everything else - and `gradlew check`, which is what CI calls.
//
//   gradlew test                        everything; the sockseek one skips if the binary is absent
//   gradlew test -Dgates.record=true    accept today's output as the new recording
//   gradlew makeFixture                 build the fixture again from its seed

tasks.test {
    // they drive the jar people download, so it has to exist before they run
    dependsOn(fatJar)
    systemProperty("gates.jar", fatJar.get().archiveFile.get().asFile.absolutePath)
    systemProperty("gates.record", System.getProperty("gates.record") ?: "false")
}

tasks.register<JavaExec>("makeFixture") {
    group = "verification"
    description = "Build the test fixture again from its fixed seed"
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "dev.litetook.sockseek.gates.Fixture"
}
