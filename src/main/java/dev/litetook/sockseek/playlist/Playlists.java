package dev.litetook.sockseek.playlist;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Playlist files: one {@code .txt} per playlist, one track per line.
 *
 * <pre>Artist - Title | 4:09</pre>
 *
 * <p>The length after {@code |} is what protects us from downloading another version of the
 * song; a line without it is searched without the length filter.
 */
public final class Playlists {

    /** UNICODE_CHARACTER_CLASS because Python's \s and \d cover more than ASCII, and a playlist
     *  pasted out of a browser really does arrive with non-breaking spaces. */
    private static final Pattern LINE = Pattern.compile(
            "^(?<name>.+?)\\s*\\|\\s*(?<length>[\\d:.]+)\\s*$", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern SPACES = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);
    /** What a number may be made of. Java's Double.parseDouble would also swallow "4d" and
     *  "Infinity"; Python's float() takes neither, and neither do we. */
    private static final Pattern NUMBER = Pattern.compile("[0-9.eE+-]+");
    private static final Charset CP1251 = Charset.forName("windows-1251");
    /** cp1251 leaves this byte undefined, and Java's decoder quietly maps it anyway. */
    private static final byte CP1251_UNDEFINED = (byte) 0x98;
    private static final char BOM = '\ufeff';

    private Playlists() {
    }

    /**
     * {@code "4:09"} is 249, {@code "1:04:35"} is 3875, {@code "249"} is 249, {@code "4.9"} is
     * 5 - rounded, not truncated. Empty when the text is not a duration at all.
     */
    public static OptionalInt parseDuration(String text) {
        // -1 keeps the trailing empty field: "4:" is not four seconds, it is nonsense
        String[] parts = text.strip().split(":", -1);
        if (parts.length > 3) {
            return OptionalInt.empty();
        }
        double total = 0;
        for (String part : parts) {
            if (part.isEmpty() || !NUMBER.matcher(part).matches()) {
                return OptionalInt.empty();
            }
            double value;
            try {
                value = Double.parseDouble(part);
            } catch (NumberFormatException notANumber) {
                return OptionalInt.empty();
            }
            total = total * 60 + value;
        }
        if (!Double.isFinite(total) || total < 0 || total > Integer.MAX_VALUE) {
            return OptionalInt.empty();
        }
        return OptionalInt.of((int) Math.round(total));
    }

    /** A comma separates properties in a sockseek search string, and a quote ends it. */
    private static String clean(String text) {
        return SPACES.matcher(text.replace(",", " ").replace("\"", "")).replaceAll(" ").strip();
    }

    /** One line into a track. Blank lines and {@code #} comments are not tracks. */
    public static Optional<Track> parseLine(String raw) {
        String line = raw.strip();
        if (line.isEmpty() || line.startsWith("#")) {
            return Optional.empty();
        }
        OptionalInt length = OptionalInt.empty();
        Matcher matched = LINE.matcher(line);
        if (matched.matches()) {
            length = parseDuration(matched.group("length"));
            line = matched.group("name").strip();
        }
        String title = clean(line);
        if (title.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new Track("", title, length));
    }

    /** One sockseek list-file line, e.g. {@code s:"title=T, length=249"}. */
    public static String buildListLine(Track track) {
        List<String> properties = new ArrayList<>();
        if (!track.artist().isEmpty()) {
            properties.add("artist=" + track.artist());
        }
        properties.add("title=" + track.title());
        track.length().ifPresent(length -> properties.add("length=" + length));
        return "s:\"" + String.join(", ", properties) + "\"";
    }

    /** Files the tool wrote itself are not playlists of their own. */
    public static boolean isGenerated(Path path) {
        String name = path.getFileName().toString();
        return name.startsWith("tracks_") || name.endsWith(".list.txt");
    }

    /**
     * Read a playlist as UTF-8, falling back to cp1251 and then latin-1; never fails on
     * encoding. The fallback is a guess, not a detection, and says so.
     */
    public static Decoded read(Path path) throws IOException {
        byte[] data = Files.readAllBytes(path);
        Optional<String> utf8 = decode(data, StandardCharsets.UTF_8);
        if (utf8.isPresent()) {
            String text = utf8.get();
            return new Decoded(text.isEmpty() || text.charAt(0) != BOM ? text : text.substring(1),
                    Optional.empty());
        }
        if (!hasUndefinedCp1251Byte(data)) {
            Optional<String> cp1251 = decode(data, CP1251);
            if (cp1251.isPresent()) {
                return new Decoded(cp1251.get(),
                        Optional.of("not valid UTF-8, decoded as cp1251 - check the file"));
            }
        }
        return new Decoded(new String(data, StandardCharsets.ISO_8859_1),
                Optional.of("not valid UTF-8 or cp1251, decoded as latin-1 - check the file"));
    }

    private static Optional<String> decode(byte[] data, Charset charset) {
        CharsetDecoder decoder = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            return Optional.of(decoder.decode(ByteBuffer.wrap(data)).toString());
        } catch (CharacterCodingException notThisCharset) {
            return Optional.empty();
        }
    }

    private static boolean hasUndefinedCp1251Byte(byte[] data) {
        for (byte value : data) {
            if (value == CP1251_UNDEFINED) {
                return true;
            }
        }
        return false;
    }

    /** Every line of one playlist file. Lines that ask sockseek for the same thing collapse. */
    public static Converted convert(String text) {
        List<Entry> entries = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int duplicates = 0;
        int number = 0;
        for (String raw : text.lines().toList()) {
            number++;
            Optional<Track> parsed = parseLine(raw);
            if (parsed.isEmpty()) {
                String stripped = raw.strip();
                if (!stripped.isEmpty() && !stripped.startsWith("#")) {
                    warnings.add("line " + number + ": skipped (no title): '" + stripped + "'");
                }
                continue;
            }
            Track track = parsed.get();
            String listLine = buildListLine(track);
            if (!seen.add(listLine)) {
                duplicates++;
                continue;
            }
            if (track.length().isEmpty()) {
                warnings.add("line " + number
                        + ": no valid length, searching without length filter");
            }
            entries.add(new Entry(listLine, raw.strip(), track));
        }
        if (duplicates > 0) {
            warnings.add(duplicates + " duplicate line(s) dropped");
        }
        return new Converted(List.copyOf(entries), List.copyOf(warnings));
    }

    /** Every playlist file in the folder, generated ones excluded, in a stable order. */
    public static List<Path> find(Path playlistsDir) throws IOException {
        try (Stream<Path> files = Files.list(playlistsDir)) {
            return files.filter(Files::isRegularFile)
                    .filter(file -> file.getFileName().toString().endsWith(".txt"))
                    .filter(file -> !isGenerated(file))
                    .sorted(Comparator.comparing(file -> file.getFileName().toString()))
                    .toList();
        }
    }
}
