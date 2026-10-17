package dev.litetook.sockseek.process;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Runs a program and hands back what it printed.
 *
 * <p>An interface so the tests never shell out: the fragile part is someone else's program.
 */
@FunctionalInterface
public interface ProcessRunner {

    /** What a finished program left behind. Some callers want only stdout, some want both. */
    record Output(int code, String out, String err) {

        public String text() {
            return out;
        }

        public String both() {
            return out + err;
        }
    }

    Output run(List<String> command);

    /** Five minutes: a set of fifty tracks is resolved one track at a time. */
    int TIMEOUT_SECONDS = 300;

    /**
     * The real thing.
     *
     * <p>The child is told to print UTF-8. yt-dlp is written in Python, and on Windows such
     * a child writes its output in the console code page unless this environment variable
     * says otherwise, while we read it as UTF-8. Measured 2026-10-14: every accented title
     * came back damaged, and the damaged line went into the playlist and then into the
     * search.
     */
    static ProcessRunner system() {
        return command -> {
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.environment().put("PYTHONIOENCODING", "utf-8");
            builder.redirectErrorStream(false);
            Process process = null;
            try {
                process = builder.start();
                // Both pipes are drained AT THE SAME TIME, and that is the whole point.
                // Reading stdout to the end first and stderr after it deadlocks the moment
                // the child fills the stderr pipe buffer: it blocks writing, so it never
                // exits, so stdout never ends - and the timeout below could not save us
                // either, because it was only reached after both reads returned. yt-dlp with
                // --ignore-errors on a large SoundCloud set writes one ERROR line per failed
                // item, which is exactly how to fill that buffer (found by an independent
                // review, 2026-10-16).
                Future<String> out = read(process.getInputStream());
                Future<String> err = read(process.getErrorStream());
                if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    Runner.killTree(process);
                    return new Output(-1, text(out), text(err));
                }
                return new Output(process.exitValue(), text(out), text(err));
            } catch (IOException cannotStart) {
                throw new UncheckedIOException(cannotStart);
            } catch (InterruptedException interrupted) {
                if (process != null) {
                    Runner.killTree(process);
                }
                Thread.currentThread().interrupt();
                return new Output(-1, "", "");
            }
        };
    }

    /** One daemon thread per pipe: the reader must not outlive the run it belongs to. */
    private static Future<String> read(java.io.InputStream stream) {
        ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "sockseek-pipe");
            thread.setDaemon(true);
            return thread;
        });
        try {
            return worker.submit(() -> new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        } finally {
            worker.shutdown();
        }
    }

    /** What the pipe held, or nothing: a child killed by the timeout may never close it. */
    private static String text(Future<String> pipe) {
        try {
            return pipe.get(10, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return "";
        } catch (ExecutionException | TimeoutException unreadable) {
            return "";
        }
    }
}
