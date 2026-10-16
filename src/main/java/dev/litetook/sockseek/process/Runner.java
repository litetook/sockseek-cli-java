package dev.litetook.sockseek.process;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Driving sockseek: the command line, the process, the retries.
 *
 * <p>One process per playlist. sockseek searches and downloads many tracks at once and has a
 * single search limiter (34 searches / 220 s) that keeps the account out of the server's
 * 30-minute ban, so starting several at once would defeat it. Slow uploaders are sockseek's own
 * job; this only puts a wall clock on an attempt.
 */
public final class Runner {

    /** One flat folder per playlist; artist and title come from the search string or the tags. */
    public static final String NAME_FORMAT = "{artist( - )title|filename}";
    public static final String INDEX_NAME = "_index.csv";
    public static final double RETRY_PAUSE_SECONDS = 10;

    private Runner() {
    }

    /**
     * The sockseek command for one list file.
     *
     * <p>{@code conf} is the merged config and is passed explicitly so sockseek cannot quietly
     * pick a different file out of %APPDATA%. It is left out when the caller already decided
     * about the config itself.
     */
    public static List<String> buildCommand(Path sockseek, Path listFile, Path outDir,
                                            Options options, Path logFile, List<String> extraArgs,
                                            String indexName, Path conf) {
        return buildCommand(sockseek, listFile, outDir, options, logFile, extraArgs, indexName,
                conf, null);
    }

    /**
     * The same, asking sockseek to report every finished track into {@code eventsFile}.
     *
     * <p>That is the whole mechanism behind the live count and the silence watchdog, and it
     * costs the run nothing it was not already paying: the action is a child process of
     * sockseek, so the console stays where it was.
     */
    public static List<String> buildCommand(Path sockseek, Path listFile, Path outDir,
                                            Options options, Path logFile, List<String> extraArgs,
                                            String indexName, Path conf, Path eventsFile) {
        List<String> command = new ArrayList<>(List.of(
                sockseek.toString(),
                listFile.toString(),
                "--input-type", "list",
                // the "=" form: sockseek reads "--length-tol -1" as another option
                "--length-tol=" + options.lengthTol(),
                "-o", outDir.toString(),
                "--name-format", NAME_FORMAT,
                "--index-path", outDir.resolve(indexName).toString()));
        if (options.preview()) {
            command.addAll(List.of("--print", "results"));
        }
        if (logFile != null) {
            command.addAll(List.of("--verbose", "--log-file", logFile.toString()));
        }
        boolean decided = extraArgs.stream()
                .anyMatch(argument -> argument.equals("--no-config")
                        || argument.startsWith("--config"));
        if (conf != null && !decided) {
            command.addAll(List.of("--config", conf.toString()));
        }
        command.addAll(extraArgs);
        // last on the line on purpose: its value is a whole shell command, by far the longest
        // thing here, and keeping it at the end leaves the readable part of a dry-run line
        // readable - and lets the parity gate cut the line at it, since the Python oracle has
        // no counterpart for it at all
        if (eventsFile != null && !options.preview()) {
            command.addAll(List.of("--on-complete", Events.argument(eventsFile)));
        }
        return List.copyOf(command);
    }

    /**
     * Run one process. The child writes straight to this console, as sockseek's progress
     * display expects.
     */
    public static Status runProcess(List<String> command, Path cwd, double timeoutSeconds,
                                    Consumer<String> log) {
        return runProcess(command, cwd, timeoutSeconds, log, null, 0);
    }

    /**
     * The same, watched.
     *
     * <p>{@code watch} is read every half second while the child runs, which is both how the
     * live progress is printed and how a silence is noticed. {@code stallSeconds} is the
     * silence that means the run is no longer going anywhere; 0 turns that off.
     *
     * <p>A silence is not the same thing as the wall clock running out, and the two are
     * reported apart: the timeout says "this playlist was given N minutes and used them",
     * the silence says "nothing has finished for N seconds, it is stuck".
     */
    public static Status runProcess(List<String> command, Path cwd, double timeoutSeconds,
                                    Consumer<String> log, Events.Tail watch,
                                    double stallSeconds) {
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(cwd.toFile())
                .inheritIO();
        Process process;
        try {
            process = builder.start();
        } catch (IOException cannotStart) {
            log.accept("cannot start: " + cannotStart.getMessage());
            return Status.ERROR;
        }
        try {
            long started = System.nanoTime();
            while (!process.waitFor(Events.POLL_MILLIS, TimeUnit.MILLISECONDS)) {
                if (watch != null) {
                    watch.poll();
                }
                double ran = (System.nanoTime() - started) / 1_000_000_000.0;
                if (timeoutSeconds > 0 && ran >= timeoutSeconds) {
                    killTree(process);
                    stopped(log, String.format("timeout after %.0f s", timeoutSeconds));
                    return Status.TIMEOUT;
                }
                if (watch != null && stallSeconds > 0 && watch.silentSeconds() >= stallSeconds) {
                    killTree(process);
                    stopped(log, String.format("nothing finished for %.0f s (%d track(s) had "
                            + "finished before that)", stallSeconds, watch.seen()));
                    return Status.STALLED;
                }
            }
            if (watch != null) {
                watch.drain();
            }
        } catch (InterruptedException interrupted) {
            killTree(process);
            Thread.currentThread().interrupt();
            return Status.ERROR;
        }
        int code = process.exitValue();
        if (code == 0) {
            return Status.OK;
        }
        log.accept("exit code " + code);
        return Status.FAILED;
    }

    /**
     * Say that sockseek was stopped, and by whom.
     *
     * <p>The banner is not decoration. sockseek paints its progress display in place, and once
     * it is killed nothing repaints it - so the last frame it drew sits on the screen forever,
     * counters frozen, looking exactly like a hang. The owner read it as one on 2026-10-17 and
     * asked why the tool was stuck, while it had moved on minutes earlier. The blank line
     * pushes the dead frame up and the line below says who is speaking now.
     */
    private static void stopped(Consumer<String> log, String why) {
        log.accept("");
        log.accept("--- sockseek stopped: " + why + ", process tree killed. Anything above "
                + "this line is its last screen, frozen - not a live one. What follows is "
                + "this tool.");
    }

    /**
     * Kill the process and everything it started.
     *
     * <p>{@code ProcessHandle.descendants()} does on every platform what the Python tool needs
     * {@code taskkill /F /T} for on Windows.
     */
    public static void killTree(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        try {
            process.waitFor(5, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Run the command up to {@code retries} times; gives back the status of the last attempt.
     *
     * <p>The status, not a yes/no: sockseek exits non-zero whenever a single track fails, which
     * is the normal case, while "could not start" and "killed by the timeout" mean the tool
     * itself failed. Only the caller can tell those apart, so it gets the distinction.
     *
     * <p>A retry earns tracks: on run #0002 an uploader that timed out on the first attempt
     * delivered on the second.
     */
    public static Status runWithRetry(List<String> command, Path cwd, double timeoutSeconds,
                                      int retries, Consumer<String> log, double pauseSeconds) {
        return runWithRetry(command, cwd, timeoutSeconds, retries, log, pauseSeconds, null, 0);
    }

    /** The same, with the watcher of {@link #runProcess(List, Path, double, Consumer,
     *  Events.Tail, double)}. */
    public static Status runWithRetry(List<String> command, Path cwd, double timeoutSeconds,
                                      int retries, Consumer<String> log, double pauseSeconds,
                                      Events.Tail watch, double stallSeconds) {
        Status status = Status.ERROR;
        for (int attempt = 1; attempt <= retries; attempt++) {
            log.accept("attempt " + attempt + "/" + retries);
            status = runProcess(command, cwd, timeoutSeconds, log, watch, stallSeconds);
            if (status == Status.OK || status == Status.ERROR) {
                return status;
            }
            if (attempt < retries && pauseSeconds > 0) {
                try {
                    Thread.sleep((long) (pauseSeconds * 1000));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return status;
                }
            }
        }
        return status;
    }
}
