package dev.litetook.sockseek;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * The screen, kept.
 *
 * <p>One file per start of the command - {@code logs/session_<date>.log} - holding everything
 * the person saw: the menu, the questions they were asked, their answers as echoed back, and
 * every line the engine printed. The run logs are a different thing and stay: each of those
 * covers one playlist run and begins after all the choosing is over, which is exactly the part
 * that was missing when a session went wrong.
 *
 * <p>The file is opened on the first byte written, not when the stream is made, and a run that
 * only answers a question - {@code --help}, {@code --version} - is given the bare console and
 * writes no file at all.
 */
public final class Session {

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    private Session() {
    }

    /** The console stream, with everything written to it also appended to the session file. */
    public static PrintStream tee(PrintStream console, Path logsDir) {
        Path file = logsDir.resolve("session_" + STAMP.format(LocalDateTime.now()) + ".log");
        return new PrintStream(new Fork(console, file), true, StandardCharsets.UTF_8);
    }

    /** True for a command line that only asks the tool a question and downloads nothing. */
    public static boolean onlyAsking(String[] args) {
        for (String argument : args) {
            if (argument.equals("-h") || argument.equals("--help")
                    || argument.equals("--version")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Writes to the console first, then tries the file.
     *
     * <p>Nothing here may stop the tool: a logs folder that cannot be created, a disk that is
     * full, a file locked by something else - all of it ends with the copy silently given up
     * and the console untouched. A session is worth more than its transcript.
     */
    private static final class Fork extends OutputStream {

        private final PrintStream console;
        private final Path file;
        private boolean ready;
        private boolean broken;

        private Fork(PrintStream console, Path file) {
            this.console = console;
            this.file = file;
        }

        @Override
        public void write(int oneByte) {
            write(new byte[] {(byte) oneByte}, 0, 1);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) {
            console.write(bytes, offset, length);
            console.flush();
            if (broken) {
                return;
            }
            try {
                if (!ready) {
                    Files.createDirectories(file.getParent());
                    ready = true;
                }
                try (OutputStream sink = Files.newOutputStream(file, StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND)) {
                    sink.write(bytes, offset, length);
                }
            } catch (IOException | RuntimeException cannotLog) {
                broken = true;
            }
        }

        @Override
        public void flush() {
            console.flush();
        }
    }
}
