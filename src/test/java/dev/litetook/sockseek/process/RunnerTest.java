package dev.litetook.sockseek.process;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The sockseek command line and the process around it.
 *
 * <p>The rule behind most of it: this tool puts
 * only the length window on the command line. Every quality knob lives in sockseek.conf, and a
 * value passed here would silently beat that file - which is how pref-min-bitrate in the conf
 * was dead until 2026-10-11.
 */
class RunnerTest {

    private final List<String> logs = new ArrayList<>();

    private static List<String> command(Options options) {
        return Runner.buildCommand(Path.of("s.exe"), Path.of("l.txt"), Path.of("out"), options,
                null, List.of(), Runner.INDEX_NAME, null);
    }

    // ------------------------------------------------------------------ the command line

    @Test
    void theDefaults() {
        List<String> command = command(Options.defaults());
        assertTrue(command.contains("--length-tol=3"));
        assertFalse(command.contains("--length-tol"));   // never the space-separated form
        assertEquals("list", command.get(command.indexOf("--input-type") + 1));
        assertEquals(Runner.NAME_FORMAT, command.get(command.indexOf("--name-format") + 1));
        assertEquals(Path.of("out").resolve("_index.csv"),
                Path.of(command.get(command.indexOf("--index-path") + 1)));
        assertFalse(command.contains("--print"));
        assertFalse(command.contains("--verbose"));
    }

    @Test
    void theTuningIsLeftToTheConfFile() {
        List<String> command = command(Options.defaults());
        for (String absent : List.of("--max-stale-time", "--fast-search", "--max-retries",
                "--search-timeout", "--searches-per-time", "--searches-renew-time",
                "--concurrent-searches", "--strict-conditions", "--desperate",
                "--artist-maybe-wrong", "--remove-brackets", "--remove-ft")) {
            assertFalse(command.contains(absent), absent);
        }
    }

    @Test
    void minusOneIsJoinedWithAnEqualsSign() {
        // sockseek rejects "--length-tol -1": it reads the -1 as another option
        List<String> command = command(new Options(-1, false));
        assertTrue(command.contains("--length-tol=-1"));
        assertFalse(command.contains("--length-tol"));
        assertFalse(command.contains("-1"));
    }

    @Test
    void noQualityOptionIsEverPassedByUs() {
        for (Options options : List.of(Options.defaults(), new Options(-1, true))) {
            for (String gone : List.of("--min-bitrate", "--max-bitrate", "--pref-min-bitrate",
                    "--pref-max-bitrate", "--pref-format", "--strict-conditions")) {
                assertTrue(command(options).stream().noneMatch(part -> part.startsWith(gone)),
                        gone);
            }
        }
    }

    @Test
    void previewAsksForResultsInsteadOfDownloading() {
        assertTrue(command(new Options(3, true)).contains("--print"));
    }

    @Test
    void theVerboseLogAndTheExtraArgumentsGoLast() {
        List<String> command = Runner.buildCommand(Path.of("s.exe"), Path.of("l.txt"),
                Path.of("out"), Options.defaults(), Path.of("v.log"),
                List.of("--no-config", "--no-listen"), Runner.INDEX_NAME, null);
        assertEquals("v.log", command.get(command.indexOf("--log-file") + 1));
        assertTrue(command.contains("--verbose"));
        assertEquals(List.of("--no-config", "--no-listen"),
                command.subList(command.size() - 2, command.size()));
    }

    @Test
    void theMergedConfigIsPassedSoNoOtherFileIsPickedUp() {
        List<String> command = Runner.buildCommand(Path.of("s.exe"), Path.of("l.txt"),
                Path.of("out"), Options.defaults(), null, List.of(), Runner.INDEX_NAME,
                Path.of("work/effective.conf"));
        assertEquals("work/effective.conf".replace('/', java.io.File.separatorChar),
                command.get(command.indexOf("--config") + 1));
    }

    @Test
    void aCallerThatDecidesAboutTheConfigIsLeftAlone() {
        for (String decided : List.of("--no-config", "--config")) {
            List<String> command = Runner.buildCommand(Path.of("s.exe"), Path.of("l.txt"),
                    Path.of("out"), Options.defaults(), null, List.of(decided),
                    Runner.INDEX_NAME, Path.of("work/effective.conf"));
            assertEquals(1, command.stream().filter(part -> part.equals("--config")
                    || part.equals("--no-config")).count(), decided);
        }
    }

    // ------------------------------------------------------------------ the process

    private Status run(String what, String value, int retries, double timeoutSeconds) {
        return Runner.runWithRetry(Child.command(what, value), Path.of("."), timeoutSeconds,
                retries, logs::add, 0);
    }

    @Test
    void aCleanExitIsSuccess() {
        assertEquals(Status.OK, run("exit", "0", 2, 30));
    }

    @Test
    void aNonZeroExitIsRetriedAndThenReported() {
        // the everyday case: one track failed. The caller decides what that means, because it
        // is not the same thing as "could not start"
        assertEquals(Status.FAILED, run("exit", "3", 2, 30));
        assertEquals(2, logs.stream().filter(line -> line.contains("attempt")).count());
    }

    @Test
    void theTimeoutKillsTheProcessAndSaysSo() {
        assertEquals(Status.TIMEOUT, run("sleep", "30000", 1, 1));
        assertTrue(logs.stream().anyMatch(line -> line.contains("timeout")), logs.toString());
    }

    @Test
    void aBinaryThatCannotStartIsAnErrorAndIsNotRetried() {
        assertEquals(Status.ERROR, Runner.runWithRetry(List.of("no-such-binary-xyz"),
                Path.of("."), 5, 2, logs::add, 0));
        assertEquals(1, logs.stream().filter(line -> line.contains("attempt")).count());
    }

    @Test
    void killingTakesTheProcessAndEverythingItStarted() throws Exception {
        // a process that outlives its run would keep a lock on the output folder, and the next
        // run would fail for no visible reason
        Process child = new ProcessBuilder(Child.command("sleep", "30000")).start();
        assertTrue(child.isAlive());
        Runner.killTree(child);
        assertFalse(child.isAlive(), "the process should be gone");
        // the handles outlive the kill by a moment on Windows, so give them one - without
        // this the test fails about one run in twenty and teaches people to re-run tests
        boolean gone = false;
        for (int wait = 0; wait < 40 && !gone; wait++) {
            gone = child.descendants().findAny().isEmpty();
            if (!gone) {
                Thread.sleep(50);
            }
        }
        assertTrue(gone, "and so should its children");
    }

    // ------------------------------------------------------------------ the binary itself

    @Test
    void theTimeoutIsMinutesAndZeroMeansNone() {
        Sockseek sockseek = new Sockseek(Path.of("s.exe"), Path.of("."), Options.defaults(),
                List.of(), 10, 2, null);
        assertEquals(600.0, sockseek.timeoutSeconds());
        assertEquals(0.0, sockseek.withTimeoutMinutes(0).timeoutSeconds());
    }

    @Test
    void itKnowsWhetherItIsThere() {
        assertFalse(new Sockseek(Path.of("nope-xyz.exe"), Path.of("."), Options.defaults(),
                List.of(), 10, 2, null).isPresent());
    }

    // ------------------------------------------------------- the watcher and the silence

    @Test
    void theOnCompleteActionIsAskedForOnlyWhenThereIsSomewhereToPutIt(@TempDir Path work) {
        Path events = work.resolve("mix.events.txt");
        List<String> without = Runner.buildCommand(Path.of("s.exe"), Path.of("l.txt"),
                Path.of("out"), Options.defaults(), null, List.of(), Runner.INDEX_NAME, null);
        assertFalse(without.contains("--on-complete"));
        List<String> with = Runner.buildCommand(Path.of("s.exe"), Path.of("l.txt"),
                Path.of("out"), Options.defaults(), null, List.of(), Runner.INDEX_NAME, null,
                events);
        assertTrue(with.get(with.indexOf("--on-complete") + 1).contains("{row};{state}"));
        // a preview downloads nothing, so no track ever completes and the action is pointless
        List<String> preview = Runner.buildCommand(Path.of("s.exe"), Path.of("l.txt"),
                Path.of("out"), new Options(3, true), null, List.of(), Runner.INDEX_NAME, null,
                events);
        assertFalse(preview.contains("--on-complete"));
    }

    @Test
    void aRunThatFinishesNothingForTooLongIsStopped(@TempDir Path work) throws Exception {
        // the owner's "щоб не завтикував": a playlist that has gone quiet is not waited out
        // to the end of its wall clock, it is stopped and said to have gone quiet
        Events.Tail watch = new Events.Tail(work.resolve("never-written.txt"), event -> { });
        Path out = Files.createDirectories(work.resolve("out-quiet"));
        Status status = Runner.runProcess(Child.command("sleep", "30000"), Path.of("."),
                0, logs::add, watch, 1.5, out);
        assertEquals(Status.STALLED, status);
        assertTrue(logs.stream().anyMatch(line -> line.contains("nothing arrived on disk")),
                logs.toString());
    }

    @Test
    void everyAttemptGetsItsOwnSilenceClock(@TempDir Path work) throws Exception {
        // a retry used to inherit the previous attempt's clock, which had already run out
        Events.Tail watch = new Events.Tail(work.resolve("none.txt"), event -> { });
        Thread.sleep(300);
        assertTrue(watch.silentSeconds() > 0.2);
        watch.restart();
        assertTrue(watch.silentSeconds() < 0.2, "restart should begin the silence again");
    }

    @Test
    void aSlowDownloadThatIsStillArrivingIsNotMistakenForASilence(@TempDir Path work)
            throws Exception {
        // the case this exists for: one job left, pulling a big file from a slow uploader.
        // Nothing FINISHES for a long time, and killing it would destroy the very download
        // the person is waiting for. Bytes on disk are the second sign of life.
        Path out = Files.createDirectories(work.resolve("out"));
        Path partial = out.resolve("Artist - Title.mp3.incomplete");
        Files.writeString(partial, "x");
        Thread grow = new Thread(() -> {
            for (int n = 0; n < 40; n++) {
                try {
                    Thread.sleep(200);
                    Files.writeString(partial, "x".repeat(n * 5000));
                } catch (Exception stop) {
                    return;
                }
            }
        });
        grow.setDaemon(true);
        grow.start();
        Events.Tail watch = new Events.Tail(work.resolve("never-written.txt"), event -> { });
        Status status = Runner.runProcess(Child.command("sleep", "4000"), Path.of("."),
                0, logs::add, watch, 1.5, out);
        assertEquals(Status.OK, status, logs.toString());
    }

    @Test
    void aRunThatKeepsFinishingTracksIsLeftAlone(@TempDir Path work) throws Exception {
        Path events = work.resolve("mix.events.txt");
        Files.writeString(events, "1;Succeeded;None\n");
        List<Events.Event> seen = new ArrayList<>();
        Events.Tail watch = new Events.Tail(events, seen::add);
        Status status = Runner.runProcess(Child.command("sleep", "1200"), Path.of("."),
                0, logs::add, watch, 10);
        assertEquals(Status.OK, status);
        assertEquals(List.of(new Events.Event(1, "Succeeded", "None")), seen);
    }
}
