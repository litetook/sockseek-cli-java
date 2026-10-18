package dev.litetook.sockseek.gates;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * What the three end-to-end checks share: a folder to work in, and a way to run the tool.
 *
 * <p>These are not unit tests. A unit test drives a class; these drive the jar people
 * download, as a process, over a folder laid out the way a real installation is - which is
 * the only way to catch the things that live between the parts: the command line that gets
 * built, the order lines are printed in, what the index means after a run.
 *
 * <p>No check ever runs inside a real installation. Reading a file there is one thing;
 * starting a download, moving a run counter or rewriting an index is another, and a check
 * that can do either is a check nobody will dare run twice. Every one of them builds a
 * temporary folder and works there.
 *
 * <p>They were Python scripts under tools/gates until 2026-10-17, driven by their own Gradle
 * tasks. The owner's call: this is a Java project, so its checks are Java and run with
 * {@code gradlew test} like everything else.
 */
public final class Gates {

    /** Where the fixture and the recordings live, relative to the project folder. */
    public static final Path DATA = Path.of("src", "test", "resources", "gates");
    public static final Path FIXTURE = DATA.resolve("fixture");
    public static final Path SNAPSHOTS = DATA.resolve("snapshots");

    /** Set {@code -Dgates.record=true} to accept today's output as the new recording. */
    public static final boolean RECORDING = Boolean.getBoolean("gates.record");

    /** The login never leaves the sandbox, and a dry run never signs in anyway. */
    public static final String LOCAL_CONF =
            "sockseek:\n  username: gate\n  password: gate\n";

    /** sockseek's own meaning: 1 = downloaded, 3 = was already there and skipped. */
    public static final List<String> OK_STATES = List.of("1", "3");

    private static final Pattern CLOCK = Pattern.compile("^\\[\\d\\d:\\d\\d:\\d\\d]");
    /** The sandbox is a new temporary folder every time, and its name is not behaviour. */
    private static final Pattern WHERE =
            Pattern.compile("[A-Za-z]:[\\\\/][^\\s\"]*?sockseek-gate-[^\\\\/\\s\"]+");

    private Gates() {
    }

    /** The jar under test, which the build has made before the tests run. */
    public static Path jar() {
        return Path.of(System.getProperty("gates.jar", "build/libs/sockseek-cli.jar"));
    }

    /**
     * The real sockseek binary, looked for beside the project.
     *
     * <p>That is where a run of this tool from the project folder puts it. It is 114 MB and
     * has no business in a repository, so the one check that needs it is skipped when it is
     * not there - which is what happens in CI.
     */
    public static Path sockseek() {
        String home = System.getenv("SOCKSEEK_HOME");
        Path where = home == null || home.isBlank() ? Path.of(".") : Path.of(home);
        for (String name : List.of("sockseek.exe", "sockseek")) {
            if (Files.isRegularFile(where.resolve(name))) {
                return where.resolve(name);
            }
        }
        return where.resolve("sockseek.exe");
    }

    /** Run the jar in {@code folder} with nothing on its input, and give back what it said. */
    public static List<String> run(Path folder, String... args) {
        return start(folder, List.of(java(), "-Dstdout.encoding=UTF-8", "-jar",
                jar().toAbsolutePath().toString()), args);
    }

    /** The same, but reaching into the jar for a class with a main of its own. */
    public static List<String> runClass(String className, String... args) {
        return start(Path.of("."), List.of(java(), "-Dstdout.encoding=UTF-8", "-cp",
                jar().toAbsolutePath().toString(), className), args);
    }

    private static List<String> start(Path folder, List<String> head, String... args) {
        List<String> command = new ArrayList<>(head);
        command.addAll(List.of(args));
        try {
            Process process = new ProcessBuilder(command)
                    .directory(folder.toFile())
                    .redirectInput(ProcessBuilder.Redirect.from(nowhere()))
                    .redirectErrorStream(true)
                    .start();
            String said = new String(process.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);
            if (!process.waitFor(15, java.util.concurrent.TimeUnit.MINUTES)) {
                process.destroyForcibly();
                throw new IllegalStateException("the tool did not finish in 15 minutes");
            }
            return clean(said + "\n[exit " + process.exitValue() + "]");
        } catch (IOException cannotRun) {
            throw new UncheckedIOException(cannotRun);
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(stopped);
        }
    }

    /** The clock and the sandbox's name are not behaviour; the shape of both still is. */
    private static List<String> clean(String said) {
        return said.lines()
                .map(line -> CLOCK.matcher(line).replaceFirst("[time]"))
                .map(line -> WHERE.matcher(line).replaceAll("<DIR>"))
                .toList();
    }

    private static java.io.File nowhere() {
        return new java.io.File(System.getProperty("os.name", "").toLowerCase().contains("win")
                ? "NUL" : "/dev/null");
    }

    private static String java() {
        return ProcessHandle.current().info().command()
                .orElse(System.getProperty("java.home") + "/bin/java");
    }

    // ------------------------------------------------------------------ the sandbox

    /**
     * A folder shaped like an installation, in three states.
     *
     * <p>A playlist behaves differently depending on what is already on disk, and the three
     * are the cases that differ: nothing downloaded; the index there but the files gone,
     * where the tool has to notice that a row without a file is a lie; and both there, where
     * most of the work is already done.
     */
    public enum State {
        FRESH(false, false),
        INDEX_ONLY(true, false),
        ON_DISK(true, true);

        final boolean index;
        final boolean files;

        State(boolean index, boolean files) {
            this.index = index;
            this.files = files;
        }

        public String label() {
            return name().toLowerCase().replace('_', '-');
        }
    }

    public static Path sandbox(Path into, State state) throws IOException {
        Files.createDirectories(into);
        for (String name : List.of("config.yaml", "dependencies.yaml")) {
            Files.copy(FIXTURE.resolve(name), into.resolve(name),
                    StandardCopyOption.REPLACE_EXISTING);
        }
        Files.writeString(into.resolve("config.local.yaml"), LOCAL_CONF);
        // a placeholder: --dry-run prints the command line and starts nothing
        Files.write(into.resolve("sockseek.exe"), new byte[0]);
        copyTree(FIXTURE.resolve("Playlists"), into.resolve("Playlists"));
        if (!state.index) {
            return into;
        }
        try (Stream<Path> found = Files.walk(FIXTURE.resolve("output"))) {
            for (Path index : found.filter(p -> p.getFileName().toString().equals("_index.csv"))
                    .sorted().toList()) {
                Path target = into.resolve("output").resolve(index.getParent().getFileName()
                        .toString()).resolve("_index.csv");
                Files.createDirectories(target.getParent());
                Files.copy(index, target, StandardCopyOption.REPLACE_EXISTING);
                if (state.files) {
                    placeholders(target);
                }
            }
        }
        return into;
    }

    /** An empty file wherever the index claims one arrived, so "here" means something. */
    private static void placeholders(Path index) throws IOException {
        List<String> lines = Files.readAllLines(index, StandardCharsets.UTF_8);
        if (lines.isEmpty()) {
            return;
        }
        List<String> head = List.of(lines.get(0).split(",", -1));
        for (String row : lines.subList(1, lines.size())) {
            String[] cells = row.split(",", -1);
            if (cells.length != head.size()) {
                continue;
            }
            Map<String, String> byName = new java.util.LinkedHashMap<>();
            for (int n = 0; n < head.size(); n++) {
                byName.put(head.get(n), cells[n]);
            }
            String given = byName.getOrDefault("filepath", "").strip();
            if (given.isEmpty() || !OK_STATES.contains(byName.getOrDefault("state", "").strip())) {
                continue;
            }
            Path file = index.getParent().resolve(given).normalize();
            if (!file.startsWith(index.getParent())) {
                continue;   // a row pointing outside its own folder is not ours to create
            }
            Files.createDirectories(file.getParent());
            if (!Files.exists(file)) {
                Files.write(file, new byte[0]);
            }
        }
    }

    /** Written by a run, so they are wiped between runs of the same sandbox. */
    public static void reset(Path folder) throws IOException {
        for (String name : List.of("logs", "work")) {
            delete(folder.resolve(name));
        }
    }

    public static void delete(Path folder) throws IOException {
        if (!Files.exists(folder)) {
            return;
        }
        try (Stream<Path> all = Files.walk(folder)) {
            for (Path path : all.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static void copyTree(Path from, Path to) throws IOException {
        try (Stream<Path> all = Files.walk(from)) {
            for (Path path : all.toList()) {
                Path target = to.resolve(from.relativize(path).toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    // ------------------------------------------------------------------ saying what differs

    /** The first differences between two texts, as something a person can read in a report. */
    public static String differences(List<String> recorded, List<String> now, int most) {
        List<String> lines = new ArrayList<>();
        int shown = 0;
        for (int n = 0; n < Math.max(recorded.size(), now.size()) && shown < most; n++) {
            String was = n < recorded.size() ? recorded.get(n) : "<nothing>";
            String is = n < now.size() ? now.get(n) : "<nothing>";
            if (!was.equals(is)) {
                lines.add("  line " + (n + 1) + "\n    recorded: " + was + "\n    now:      " + is);
                shown++;
            }
        }
        if (recorded.size() != now.size()) {
            lines.add("  (" + recorded.size() + " lines recorded, " + now.size() + " now)");
        }
        return String.join("\n", lines);
    }
}
