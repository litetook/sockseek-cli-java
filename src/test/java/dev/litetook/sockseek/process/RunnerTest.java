package dev.litetook.sockseek.process;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The sockseek command line and the process around it.
 *
 * <p>Ported from the Python tool's test_runner.py. The rule behind most of it: this tool puts
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
        assertFalse(child.descendants().findAny().isPresent(), "and so should its children");
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
}
