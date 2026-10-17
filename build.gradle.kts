plugins {
    java
}

// Not the `application` plugin: its start scripts collide with the fat jar below, and
// run.cmd / run.sh already do that job the same way they do for the Python tool.
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
    // the flags and --help; the Python tool uses argparse, which has no counterpart here
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

tasks.build { dependsOn(fatJar) }

// ------------------------------------------------------------------ the gates
//
// Three checks that a unit test cannot make, each driving the finished jar rather than a
// class: the whole command over a generated fixture, the real sockseek binary in its mock
// mode, and the manuals against the options the tool really accepts. They are scripts
// because that is the right shape for them, but nobody should have to know that - the
// entry point is gradle, like everything else here.
//
//   gradlew check        tests, the manuals, and the whole command over the fixture
//   gradlew mockGate     the above plus the real binary; needs sockseek, so it is opt-in
//   gradlew record       accept today's output as the new recording, after a deliberate change

fun gate(name: String, script: String, description: String, vararg scriptArgs: String) =
    tasks.register<Exec>(name) {
        group = "verification"
        this.description = description
        dependsOn(fatJar)
        // -X utf8: the fixture is full of accents and Cyrillic on purpose, and a Windows
        // console would otherwise decode them in the code page and compare the wrong text
        commandLine(listOf("python", "-X", "utf8", "tools/gates/$script") + scriptArgs)
    }

val docsGate = gate("docsGate", "docs.py",
    "Every option the tool accepts is documented, and nothing is documented that is not there")
val snapshotGate = gate("snapshotGate", "snapshot.py",
    "The whole command over a generated fixture, every line against a recorded answer")
val mockGate = gate("mockGate", "mock.py",
    "The real sockseek binary in mock mode: the live count, the record, the skip")
val record = gate("record", "snapshot.py",
    "Accept what the tool prints today as the new recording", "--record")

tasks.check { dependsOn(docsGate, snapshotGate) }
