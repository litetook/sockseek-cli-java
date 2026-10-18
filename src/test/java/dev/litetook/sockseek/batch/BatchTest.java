package dev.litetook.sockseek.batch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.litetook.sockseek.process.Options;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The run itself: what is searched, what is reported, and what makes a run a failure. */
class BatchTest {

    private final List<String> said = new ArrayList<>();

    private RunConfig.Builder config(Path app) throws IOException {
        Files.createDirectories(app.resolve("Playlists"));
        return RunConfig.builder(app)
                .sockseek(app.resolve("sockseek.exe"))
                .options(new Options(-1, false, 3, null))
                .retries(1)
                .timeoutMinutes(2);
    }

    private void playlist(Path app, String name, String text) throws IOException {
        Files.createDirectories(app.resolve("Playlists"));
        Files.writeString(app.resolve("Playlists").resolve(name + ".txt"), text);
    }

    private int run(RunConfig config) {
        return RunBatch.run(config, List.of(), List.of(), said::add, null);
    }

    private boolean saidSomethingWith(String part) {
        return said.stream().anyMatch(line -> line.contains(part));
    }

    // ------------------------------------------------------------------ setup problems

    @Test
    void aMissingPlaylistsFolderIsCreatedAndTheRunStops(@TempDir Path app) {
        assertEquals(2, run(RunConfig.builder(app).dryRun(true).build()));
        assertTrue(Files.isDirectory(app.resolve("Playlists")));
        assertTrue(saidSomethingWith("put playlist .txt files there"), said.toString());
    }

    @Test
    void anEmptyPlaylistsFolderIsAnError(@TempDir Path app) throws IOException {
        assertEquals(2, run(config(app).dryRun(true).build()));
        assertTrue(saidSomethingWith("no playlist .txt files"), said.toString());
    }

    @Test
    void onlyWithNoMatchNamesThePlaylistAndStops(@TempDir Path app) throws IOException {
        playlist(app, "mix", "A - One | 1:00\n");
        assertEquals(2, run(config(app).dryRun(true).only("other").build()));
        assertTrue(saidSomethingWith("no playlist named 'other'"), said.toString());
    }

    @Test
    void aMissingSockseekIsAnErrorBeforeAnythingElse(@TempDir Path app) throws IOException {
        playlist(app, "mix", "A - One | 1:00\n");
        assertEquals(2, run(config(app).build()));
        assertTrue(saidSomethingWith("sockseek not found"), said.toString());
    }

    // ------------------------------------------------------------------ a dry run

    @Test
    void aDryRunWritesTheListAndPrintsTheCommandWithoutStartingAnything(@TempDir Path app)
            throws IOException {
        playlist(app, "mix", "A - One | 1:00\nB - Two | 2:00\n");
        assertEquals(0, run(config(app).dryRun(true).build()));
        String list = Files.readString(app.resolve("work").resolve("mix.list.txt"));
        assertEquals("s:\"title=A - One, length=60\"\ns:\"title=B - Two, length=120\"\n", list);
        assertTrue(saidSomethingWith("dry run: "), said.toString());
        assertTrue(saidSomethingWith("all done, nothing downloaded"), said.toString());
    }

    @Test
    void onlyPicksOnePlaylist(@TempDir Path app) throws IOException {
        playlist(app, "mix", "A - One | 1:00\n");
        playlist(app, "other", "B - Two | 2:00\n");
        assertEquals(0, run(config(app).dryRun(true).only("mix").build()));
        assertTrue(saidSomethingWith("=== mix"), said.toString());
        assertFalse(saidSomethingWith("=== other"), said.toString());
    }

    // ------------------------------------------------------------------ the run number

    @Test
    void aFreshFolderStartsAtOneAndSaysTheCounterWasRebuilt(@TempDir Path app) throws IOException {
        Files.createDirectories(app.resolve("logs"));
        Runs.Numbered first = Runs.nextRunNumber(app.resolve("logs"));
        assertEquals(1, first.number());
        assertTrue(first.warnings().get(0).contains("rebuilt"), first.warnings().toString());
        assertEquals(2, Runs.nextRunNumber(app.resolve("logs")).number());
        assertEquals("0002", Runs.label(2));
        assertEquals("12345", Runs.label(12345));   // past 9999 it just gets longer
    }

    @Test
    void aGarbledCounterIsRebuiltFromTheLogFileNames(@TempDir Path app) throws IOException {
        Path logs = app.resolve("logs");
        Files.createDirectories(logs);
        Files.writeString(logs.resolve(Runs.SEQ_NAME), "not a number\n");
        Files.writeString(logs.resolve("run_0007_2026-10-15_10-00-00.log"), "");
        Runs.Numbered next = Runs.nextRunNumber(logs);
        assertEquals(8, next.number());
        assertTrue(next.warnings().get(0).contains("highest number found 7"),
                next.warnings().toString());
    }

    @Test
    void theRunNumberIsTheFirstLineOfTheLog(@TempDir Path app) throws IOException {
        playlist(app, "mix", "A - One | 1:00\n");
        run(config(app).dryRun(true).build());
        assertTrue(said.get(0).contains("run #0001"), said.get(0));
        try (Stream<Path> logs = Files.list(app.resolve("logs"))) {
            Path file = logs.filter(one -> one.getFileName().toString().startsWith("run_0001_"))
                    .findFirst().orElseThrow();
            assertTrue(Files.readString(file).startsWith("["), "the log keeps the timestamps");
        }
    }

    // ------------------------------------------------------------------ the command line

    @Test
    void theCommandLineIsQuotedTheWayWindowsParsesItBack() {
        // the dry-run line has to be the same text in both tools, down to the quoting
        assertEquals("a b", Runs.commandLine(List.of("a", "b")));
        assertEquals("\"a b\"", Runs.commandLine(List.of("a b")));
        assertEquals("\"\"", Runs.commandLine(List.of("")));
        assertEquals("C:\\a\\b.exe", Runs.commandLine(List.of("C:\\a\\b.exe")));
        assertEquals("\"C:\\a b\\c.exe\"", Runs.commandLine(List.of("C:\\a b\\c.exe")));
        assertEquals("s:\\\"title=X\\\"", Runs.commandLine(List.of("s:\"title=X\"")));
    }

    // ------------------------------------------------------------------ the real thing

    /** sockseek in mock mode: no login, no network, real binary. */
    @Test
    void aWholeRunAgainstTheRealSockseekInMockMode(@TempDir Path app) throws IOException {
        Path sockseek = Path.of("sockseek.exe").toAbsolutePath();
        assumeTrue(Files.isRegularFile(sockseek), "sockseek.exe is not in the repo root");

        Path mock = app.resolve("mock");
        Files.createDirectories(mock);
        for (String name : List.of("Alpha Band - First Song.mp3", "Beta Crew - Second Song.mp3")) {
            Files.write(mock.resolve(name), new byte[2000]);
        }
        playlist(app, "mix", "Alpha Band - First Song | 3:20\nBeta Crew - Second Song | 3:20\n"
                + "Missing Nowhere Track | 3:20\n");

        int code = RunBatch.run(config(app)
                .sockseek(sockseek)
                .sockseekArgs(List.of("--mock-files-dir", mock.toString(),
                        "--mock-files-no-read-tags", "--no-config", "--no-listen",
                        "--no-progress", "--length-tol=-1"))
                .build(), List.of(), List.of(), said::add, null);

        assertEquals(0, code, said.toString());
        Path out = app.resolve("output").resolve("mix");
        try (Stream<Path> files = Files.walk(out)) {
            List<String> audio = files.filter(one -> one.toString().endsWith(".mp3"))
                    .map(one -> out.relativize(one).toString()).sorted().toList();
            // flat, next to the index, with no sub-folder per list
            assertEquals(2, audio.size(), audio.toString());
            assertTrue(audio.stream().noneMatch(name -> name.contains(java.io.File.separator)),
                    audio.toString());
        }
        assertTrue(Files.isRegularFile(out.resolve("_index.csv")));
        assertTrue(saidSomethingWith("OK      Alpha Band - First Song | 3:20"), said.toString());
        assertTrue(saidSomethingWith("FAILED"), said.toString());
        assertTrue(saidSomethingWith("2 arrived, 1 still missing"), said.toString());
    }

    @Test
    void aBinaryThatCannotStartFailsTheRunRatherThanBlamingTheNetwork(@TempDir Path app)
            throws IOException {
        playlist(app, "mix", "A - One | 1:00\n");
        Path fake = app.resolve("sockseek.exe");
        Files.writeString(fake, "not an executable", StandardCharsets.UTF_8);
        int code = RunBatch.run(config(app)
                .sockseek(fake)
                .sockseekArgs(List.of("--no-config"))
                .build(), List.of(), List.of(), said::add, null);
        assertEquals(1, code, said.toString());
        assertTrue(saidSomethingWith("could not be started"), said.toString());
        assertTrue(saidSomethingWith("this run broke on: mix"), said.toString());
    }
}
