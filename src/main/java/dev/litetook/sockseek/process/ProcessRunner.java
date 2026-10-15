package dev.litetook.sockseek.process;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

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
     * <p>The child is told to print UTF-8. yt-dlp is a Python program, and on Windows a Python
     * child writes its output in the console code page unless this says otherwise, while we
     * read it as UTF-8 - which cost the Python tool every accented title until 2026-10-14.
     */
    static ProcessRunner system() {
        return command -> {
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.environment().put("PYTHONIOENCODING", "utf-8");
            builder.redirectErrorStream(false);
            try {
                Process process = builder.start();
                String out = new String(process.getInputStream().readAllBytes(),
                        StandardCharsets.UTF_8);
                String err = new String(process.getErrorStream().readAllBytes(),
                        StandardCharsets.UTF_8);
                if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    Runner.killTree(process);
                    return new Output(-1, out, err);
                }
                return new Output(process.exitValue(), out, err);
            } catch (IOException cannotStart) {
                throw new UncheckedIOException(cannotStart);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return new Output(-1, "", "");
            }
        };
    }
}
