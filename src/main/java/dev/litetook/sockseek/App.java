package dev.litetook.sockseek;

import java.io.BufferedReader;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * The one command of this tool.
 *
 * <p>Asks where the list comes from - a .txt file you already have, a Spotify link or a
 * SoundCloud link - then shows every playlist with how much of it is already on disk, asks
 * which one to work on, shows exactly which tracks are missing, and downloads only those after
 * an explicit yes.
 *
 * <p>This is the skeleton of the Java port: the opening menu and nothing behind it yet. The
 * working tool is the Python one, and its output is the specification this has to match.
 */
public final class App {

    /** Where the list comes from. The first question of an interactive run. */
    public enum Source {
        TXT("1", "download a playlist from a .txt file"),
        SPOTIFY("2", "save a .txt from a Spotify link, then download it"),
        SOUNDCLOUD("3", "save a .txt from a SoundCloud link, then download it");

        private final String key;
        private final String description;

        Source(String key, String description) {
            this.key = key;
            this.description = description;
        }

        public String key() {
            return key;
        }

        public String description() {
            return description;
        }

        /** The menu answer this is, by number ("2") or by name ("spotify"). */
        static Optional<Source> of(String answer) {
            String wanted = answer.strip().toLowerCase();
            return List.of(values()).stream()
                    .filter(source -> wanted.equals(source.key)
                            || wanted.equals(source.name().toLowerCase()))
                    .findFirst();
        }
    }

    /** A question to the person. Empty means input ended - a closed pipe, Ctrl+Z, Ctrl+C. */
    @FunctionalInterface
    public interface Ask {
        Optional<String> ask(String prompt);
    }

    private App() {
    }

    public static void main(String[] args) {
        // Windows consoles are not UTF-8 by default, and playlists carry names like
        // "ADELA" with an accent and "Ben Bohmer" with an umlaut: say it explicitly
        PrintStream out = new PrintStream(new FileOutputStream(FileDescriptor.out), true,
                StandardCharsets.UTF_8);
        BufferedReader in = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8));
        System.exit(run(args, prompt -> {
            out.print(prompt);
            out.flush();
            return readLine(in);
        }, out::println));
    }

    /**
     * Pick, show, confirm, download. Returns the exit code: 0 worked, 1 the tool failed,
     * 2 a setup problem, 130 Ctrl+C - the same meanings as the Python tool.
     */
    public static int run(String[] args, Ask ask, Consumer<String> out) {
        Optional<Source> source = sourceMenu(ask, out);
        if (source.isEmpty()) {
            return 0;
        }
        out.accept("");
        out.accept("Not here yet: " + source.get().name().toLowerCase()
                + " arrives with the next phase of the port. Use the Python tool meanwhile.");
        return 0;
    }

    /** The opening question. Empty means quit, and quitting is not an error. */
    static Optional<Source> sourceMenu(Ask ask, Consumer<String> out) {
        out.accept("Where from?");
        out.accept("");
        for (Source source : Source.values()) {
            out.accept("  " + source.key() + "  " + source.description());
        }
        out.accept("");
        while (true) {
            Optional<String> answer = ask.ask("Pick one (Enter quits): ");
            if (answer.isEmpty() || answer.get().isBlank()) {
                return Optional.empty();
            }
            Optional<Source> picked = Source.of(answer.get());
            if (picked.isPresent()) {
                return picked;
            }
            out.accept("Type 1, 2 or 3, or Enter to quit.");
        }
    }

    private static Optional<String> readLine(BufferedReader in) {
        try {
            return Optional.ofNullable(in.readLine());
        } catch (IOException problem) {
            return Optional.empty();
        }
    }
}
