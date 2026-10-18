package dev.litetook.sockseek.gates;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.litetook.sockseek.index.Failures;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

/**
 * The real sockseek binary, driven by the real jar, with no network and no account.
 *
 * <p>sockseek has a mock mode: point it at a folder of files with {@code --mock-files-dir}
 * and it behaves as though those files were shared on Soulseek. That is the only way to
 * exercise the parts of this tool that exist only while a download is happening - the live
 * count, the record of what happened, the skip that follows from it - without touching an
 * account or the network.
 *
 * <p>Unit tests cover each of those on fakes. This covers them on the binary, which is the
 * thing that surprises you: the shape of the {@code --on-complete} line, the word in
 * {@code failure-reason}, the order events arrive in, the exit code. Every one of those was
 * measured here first, on 2026-10-17, and three of them were not what the help text promised.
 *
 * <p>It is <b>skipped</b> when there is no binary beside the project, which is what happens
 * in CI: sockseek is 114 MB and has no business in a repository. Run the tool once from the
 * project folder and it fetches one.
 *
 * <p>What this does NOT cover, and nothing offline can: whether any of it behaves the same
 * against real uploaders.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MockTest {

    private static final List<String> SHARED =
            List.of("Daft Punk - Around the World", "Fatboy Slim - Praise You");
    private static final String NOWHERE = "Nobody Has This - Ghost Track";
    private static final String MOCK =
            "--no-config --mock-files-dir mock --mock-files-no-read-tags";

    @Test
    void theLiveCountTheRecordAndTheSkip(@TempDir Path where) throws IOException {
        assumeTrue(Files.isRegularFile(Gates.sockseek()),
                "no sockseek binary beside the project - run the tool once so it fetches one");
        Path folder = build(where.resolve("sockseek-gate-mock"));
        Path outcomes = folder.resolve("outcomes.tsv");

        String said = run(folder, 0);
        assertTrue(said.contains(">> 1 of 3 done") && said.contains(">> 3 of 3 done"),
                "the live count should appear while the run is going:\n" + tail(said));
        for (String name : SHARED) {
            assertTrue(said.contains("OK      " + name), name + " should have arrived:\n"
                    + tail(said));
        }
        String written = Files.readString(outcomes, StandardCharsets.UTF_8);
        // CONFIRMED_ABSENT, not sockseek's NoSearchResults: the last pass asked for any
        // format with nothing able to refuse a file, so "no results" IS "nobody has it" -
        // decided while the run was going, with no second search anywhere
        assertTrue(written.contains(Failures.CONFIRMED_ABSENT) && written.contains(NOWHERE),
                "the one nobody has should be written down as confirmed:\n" + written);
        for (String name : SHARED) {
            assertTrue(!written.contains(name),
                    "what arrived should be forgotten, the folder knows better:\n" + written);
        }

        said = run(folder, 0);
        assertTrue(said.contains("skipping 1 track(s)"),
                "the next run should skip it rather than search again:\n" + tail(said));
        assertTrue(said.contains("cache.skip_absent_h: 0 to search them anyway"),
                "and should say so rather than going quiet:\n" + tail(said));

        said = run(folder, 0, "--skip-not-found-hours", "0");
        assertTrue(!said.contains("skipping") && said.contains(">> 1 of 1 done"),
                "zero hours should search it again:\n" + tail(said));

        // a found track whose upload dies is a different class, and is never skipped
        Gates.delete(folder.resolve("output"));
        run(folder, 9, "--skip-not-found-hours", "0");
        written = Files.readString(outcomes, StandardCharsets.UTF_8);
        assertTrue(written.contains("AllDownloadsFailed"),
                "a dead upload should be recorded as such:\n" + written);

        said = run(folder, 9);
        assertTrue(said.contains(">> 2 of 2 done") && said.contains("skipping 1 track(s)"),
                "the dead uploads are searched again; only the ghost is held back:\n"
                        + tail(said));
        written = Files.readString(outcomes, StandardCharsets.UTF_8);
        assertTrue(written.lines().anyMatch(line -> {
            String[] parts = line.split("\t");
            return parts.length > 2 && parts[1].equals("AllDownloadsFailed")
                    && parts[2].equals("2");
        }), "failing the same way twice should count up:\n" + written);
    }

    /** A folder with two tracks somebody shares and one nobody does. */
    private Path build(Path into) throws IOException {
        Files.createDirectories(into.resolve("Playlists"));
        Files.createDirectories(into.resolve("mock"));
        for (String name : SHARED) {
            Files.write(into.resolve("mock").resolve(name + ".mp3"), new byte[2_000_000]);
        }
        List<String> lines = new ArrayList<>();
        SHARED.forEach(name -> lines.add(name + " | 3:00"));
        lines.add(NOWHERE + " | 3:00");
        Files.writeString(into.resolve("Playlists").resolve("mix.txt"),
                String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
        Files.writeString(into.resolve("config.local.yaml"), Gates.LOCAL_CONF);
        Files.copy(Gates.sockseek(), into.resolve(Gates.sockseek().getFileName().toString()),
                StandardCopyOption.REPLACE_EXISTING);
        Files.copy(Gates.FIXTURE.resolve("dependencies.yaml"),
                into.resolve("dependencies.yaml"), StandardCopyOption.REPLACE_EXISTING);
        return into;
    }

    /**
     * One run. {@code fail} makes sockseek drop that many mock downloads, playing a dead
     * uploader.
     *
     * <p>The sockseek arguments are built here rather than passed in: {@code --sockseek-args}
     * is a single option, so a second one silently replaces the first - which is how the
     * first version of this check quietly tested nothing.
     */
    private String run(Path folder, int fail, String... extra) throws IOException {
        Gates.reset(folder);
        List<String> args = new ArrayList<>(List.of("--only", "mix", "--yes",
                "--length-tol", "-1"));
        args.addAll(List.of(extra));
        args.add("--sockseek-args=" + MOCK
                + (fail > 0 ? " --mock-files-fail-downloads " + fail : ""));
        return String.join("\n", Gates.run(folder, args.toArray(String[]::new)));
    }

    private static String tail(String said) {
        List<String> lines = said.lines().toList();
        return String.join("\n", lines.subList(Math.max(0, lines.size() - 12), lines.size()));
    }
}
