package dev.litetook.sockseek.process;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Hearing about every track the moment it finishes, without taking the console away.
 *
 * <p>sockseek can be told to run a command when a job reaches its end - {@code --on-complete} -
 * and that command is an ordinary child process, so the download display keeps the terminal and
 * the person keeps its keys. This asks it to append one line per track to a file, and reads
 * that file while the run goes on.
 *
 * <p>Why this and not {@code --progress-json}, which was the plan until 2026-10-17: that stream
 * names a track in {@code download_start} and identifies it by a GUID in
 * {@code download_progress}, with no field joining the two, and it arrives twice per file.
 * {@code --on-complete} hands over {@code row} - the line number in the very list file this
 * tool wrote - so the join is exact and costs nothing. Measured in mock mode the same day.
 *
 * <p>The line is three machine-safe fields on purpose: {@code row} is a number, {@code state}
 * and {@code failure-reason} are words out of a fixed set. No title, no path, nothing a track
 * called {@code AC/DC - T.N.T. & more} could break, and nothing {@code cmd /c echo} would read
 * as a redirection.
 *
 * <p>What it cannot see: a track skipped by sockseek's own {@code --skip-not-found} fires no
 * action at all - not for {@code when=any} and not for an explicit
 * {@code when=not-found-last-time} either, whatever the help says (measured 2026-10-17). This
 * tool does not use that flag; it filters such tracks out of the list itself, where they are
 * counted in the open.
 */
public final class Events {

    /** How long between looks at the file. Short enough to feel live, cheap enough to ignore. */
    public static final long POLL_MILLIS = 500;

    private Events() {
    }

    /** One finished track: which line of our list it was, how it ended, and why. */
    public record Event(int row, String state, String reason) {

        /** sockseek's word for "nobody on the network is sharing this". */
        public static final String NOT_FOUND = "NoSearchResults";

        public boolean succeeded() {
            return state.equals("Succeeded");
        }

        /**
         * The track ended badly, as opposed to arriving, being there already, or being
         * called off by the person watching.
         *
         * <p>Measured states: {@code Succeeded}, {@code AlreadyExists}, {@code Failed}.
         * Anything else sockseek may report is read as a failure, which is the safe way
         * round: a state we do not know is not something to call a success.
         *
         * <p>{@code Cancelled} is the exception. sockseek's on-complete selectors name it, so
         * it can happen when someone presses {@code c} in its console - and then the track
         * failed at nothing, we simply stopped asking. Writing that down as a failure would
         * put it in the record and count it towards "failing three runs in a row", which
         * would be the tool blaming Soulseek for a decision the person made. ⚠ Not measured:
         * cancelling a 146-track playlist on 2026-10-17 produced no event of this kind at all
         * for the jobs that were dropped - they simply ended silently.
         */
        public boolean failed() {
            return !succeeded() && !state.equals("AlreadyExists")
                    && !state.equals("Cancelled");
        }

        /** The track was searched for and nobody had it - the only class worth remembering. */
        public boolean notOnNetwork() {
            return reason.equals(NOT_FOUND);
        }
    }

    /**
     * The {@code --on-complete} value for one run.
     *
     * <p>{@code lock} serialises the action so two tracks finishing together cannot interleave
     * halfway through a line; {@code hidden} keeps the console window of each {@code cmd} off
     * the screen. The path is absolute because the command inherits sockseek's folder, not ours.
     */
    public static String argument(Path file) {
        return "when=any hidden lock -- cmd /c >> \"" + file.toAbsolutePath() + "\" echo "
                + "{row};{state};{failure-reason}";
    }

    /** One line into an event, or nothing when it is a partial write or something else. */
    public static Optional<Event> parse(String line) {
        String[] fields = line.strip().split(";", -1);
        if (fields.length != 3) {
            return Optional.empty();
        }
        int row;
        try {
            row = Integer.parseInt(fields[0].strip());
        } catch (NumberFormatException notARow) {
            return Optional.empty();
        }
        if (row < 1) {
            return Optional.empty();
        }
        return Optional.of(new Event(row, fields[1].strip(), fields[2].strip()));
    }

    /**
     * Reads a file that someone else is still appending to.
     *
     * <p>Only whole lines are given out: a line the writer is halfway through is kept until its
     * newline arrives. Everything here is best-effort - a file that never appears, or cannot be
     * read, simply yields no events, and the run is not told off for it.
     */
    public static final class Tail {

        private final Path file;
        private final Consumer<Event> onEvent;
        private final StringBuilder partial = new StringBuilder();
        private long offset;
        private volatile long lastEventAt = System.nanoTime();
        private volatile int seen;

        public Tail(Path file, Consumer<Event> onEvent) {
            this.file = file;
            this.onEvent = onEvent;
        }

        /** How long since the last finished track, in seconds. Starts counting at birth. */
        public double silentSeconds() {
            return (System.nanoTime() - lastEventAt) / 1_000_000_000.0;
        }

        public int seen() {
            return seen;
        }

        /** Read whatever has been appended since last time. Never throws. */
        public void poll() {
            try (InputStream stream = Files.newInputStream(file)) {
                long skipped = stream.skip(offset);
                if (skipped < offset) {
                    // the file got shorter than it was: something replaced it, start again
                    offset = 0;
                    partial.setLength(0);
                    return;
                }
                byte[] fresh = stream.readAllBytes();
                if (fresh.length == 0) {
                    return;
                }
                offset += fresh.length;
                partial.append(new String(fresh, StandardCharsets.UTF_8));
            } catch (IOException notYet) {
                return;   // the file is not there yet, or is being written to this instant
            }
            int newline;
            while ((newline = partial.indexOf("\n")) >= 0) {
                String line = partial.substring(0, newline);
                partial.delete(0, newline + 1);
                Optional<Event> event = parse(line);
                if (event.isPresent()) {
                    seen++;
                    lastEventAt = System.nanoTime();
                    onEvent.accept(event.get());
                }
            }
        }

        /** Everything still unread, for after the process has gone. */
        public void drain() {
            poll();
        }
    }

    /** Every event in a finished file, in the order they were written. */
    public static List<Event> readAll(Path file) {
        List<Event> found = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                parse(line).ifPresent(found::add);
            }
        } catch (IOException | RuntimeException noFile) {
            return List.of();
        }
        return List.copyOf(found);
    }
}
