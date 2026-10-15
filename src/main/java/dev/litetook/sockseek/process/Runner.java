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
        return List.copyOf(command);
    }

    /**
     * Run one process. The child writes straight to this console, as sockseek's progress
     * display expects.
     */
    public static Status runProcess(List<String> command, Path cwd, double timeoutSeconds,
                                    Consumer<String> log) {
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
            if (timeoutSeconds > 0) {
                if (!process.waitFor((long) (timeoutSeconds * 1000), TimeUnit.MILLISECONDS)) {
                    killTree(process);
                    log.accept(String.format("timeout after %.0f s, process killed",
                            timeoutSeconds));
                    return Status.TIMEOUT;
                }
            } else {
                process.waitFor();
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
        Status status = Status.ERROR;
        for (int attempt = 1; attempt <= retries; attempt++) {
            log.accept("attempt " + attempt + "/" + retries);
            status = runProcess(command, cwd, timeoutSeconds, log);
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
