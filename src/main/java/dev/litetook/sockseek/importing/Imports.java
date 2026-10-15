package dev.litetook.sockseek.importing;

import dev.litetook.sockseek.playlist.Playlists;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

/** Writing a playlist file, and the two small pieces every importer shares. */
public final class Imports {

    private Imports() {
    }

    /**
     * Whitespace of every kind collapsed to one plain space.
     *
     * <p>UNICODE_CHARACTER_CLASS is the whole point: Java's {@code \s} is ASCII only, and
     * Spotify really does send a NO-BREAK SPACE after the commas in "KAROL G, Judeline".
     * Left in, that character goes into the search string, and Soulseek wants every word of
     * the query to appear in the file path - so the track would never be found. Measured
     * 2026-10-15 against the live playlist.
     */
    public static String collapse(String text) {
        return WHITESPACE.matcher(text == null ? "" : text).replaceAll(" ").strip();
    }

    private static final java.util.regex.Pattern WHITESPACE =
            java.util.regex.Pattern.compile("\\s+", java.util.regex.Pattern.UNICODE_CHARACTER_CLASS);

    /** One playlist line: {@code Name | m:ss}, or just the name when the length is unknown. */
    public static String trackLine(String name, OptionalInt length) {
        String cleaned = collapse(name);
        if (length.isEmpty()) {
            return cleaned;
        }
        return cleaned + " | " + length.getAsInt() / 60 + ":"
                + String.format("%02d", length.getAsInt() % 60);
    }

    /**
     * A duration out of an exported cell: {@code m:ss}, seconds, or milliseconds.
     *
     * <p>Milliseconds are recognised by the column name ("Duration (ms)" in a Spotify export)
     * and, when {@code guessMs} is set, by a value too large to be seconds.
     */
    static OptionalInt seconds(String text, String column, boolean guessMs) {
        String value = text == null ? "" : text.strip();
        if (value.isEmpty()) {
            return OptionalInt.empty();
        }
        if (value.indexOf(':') >= 0) {
            return Playlists.parseDuration(value);
        }
        double number;
        try {
            number = Double.parseDouble(value);
        } catch (NumberFormatException notANumber) {
            return OptionalInt.empty();
        }
        if (!Double.isFinite(number) || number <= 0) {
            return OptionalInt.empty();
        }
        if (column.toLowerCase().contains("ms")
                || (guessMs && number > Importer.IMPLAUSIBLE_SECONDS)) {
            return OptionalInt.of((int) Math.round(number / 1000));
        }
        return OptionalInt.of((int) Math.round(number));
    }

    /**
     * Write {@code <playlistsDir>/<name>.txt} from an exported list.
     *
     * <p>Nothing is written when the import found no lines, or when the playlist exists and
     * {@code overwrite} is false - a playlist is hand-edited, and replacing one silently would
     * throw that work away.
     */
    public static ImportResult importFile(Path source, Path playlistsDir, String name,
                                          boolean overwrite) throws IOException {
        Imported found = new Importer().read(source);
        if (found.lines().isEmpty()) {
            return new ImportResult(Optional.empty(), found);
        }
        String stem = name != null && !name.isBlank() ? name : stem(source);
        Optional<Path> written = writePlaylist(found.lines(), playlistsDir, stem, overwrite);
        if (written.isEmpty()) {
            List<String> warnings = new ArrayList<>(found.warnings());
            warnings.add(stem + ".txt already exists");
            return new ImportResult(Optional.empty(),
                    new Imported(found.lines(), List.copyOf(warnings), found.source()));
        }
        return new ImportResult(written, found);
    }

    /**
     * Where a playlist of that name belongs.
     *
     * <p>Public because the front end has to name the file before it is written - to ask
     * whether to replace it, and to say which one it refused to.
     */
    public static Path playlistFile(Path playlistsDir, String name) {
        return playlistsDir.resolve((name == null || name.isBlank() ? "playlist"
                : name.strip()) + ".txt");
    }

    /** The playlist file, or empty when one is already there and may not be replaced. */
    public static Optional<Path> writePlaylist(List<String> lines, Path playlistsDir, String name,
                                               boolean overwrite) throws IOException {
        Path target = playlistFile(playlistsDir, name);
        if (Files.exists(target) && !overwrite) {
            return Optional.empty();
        }
        Files.createDirectories(playlistsDir);
        Files.writeString(target, lines.isEmpty() ? "" : String.join("\n", lines) + "\n",
                StandardCharsets.UTF_8);
        return Optional.of(target);
    }

    private static String stem(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot <= 0 ? name : name.substring(0, dot);
    }
}
