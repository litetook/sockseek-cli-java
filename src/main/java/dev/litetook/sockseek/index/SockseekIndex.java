package dev.litetook.sockseek.index;

import dev.litetook.sockseek.playlist.Entry;
import dev.litetook.sockseek.playlist.Track;
import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

/**
 * sockseek's {@code _index.csv}: the only link between a playlist line and a file on disk.
 *
 * <p>File names come from the uploader ({@code ... (Clean) 1A 142 [www.dj-promo.org].mp3}), so a
 * line cannot be recovered from a name. The index can, which is why "already here" is decided
 * here.
 */
public final class SockseekIndex {

    public static final String INDEX_NAME = "_index.csv";

    /**
     * The state column, verified with {@code --mock-files-dir} on 2026-10-07: 1 downloaded now,
     * 3 already present and skipped (sockseek calls that "succeeded"), 2 failed. Without 3,
     * every re-run would report everything downloaded earlier as a failure.
     */
    public static final Set<String> OK_STATES = Set.of("1", "3");

    /**
     * failurereason, read off real logs on 2026-10-07.
     *
     * <p>9 is two different things wearing one number, and the wording has to say so. It is
     * not "not on the network": run #0004 found two 320 kbps copies of a track whose row
     * said 9, refused on length. Measured again on 2026-10-18 from the other side - a file
     * sitting in the mock folder, rejected on length, produced exactly what a track nobody
     * shares produces, in the index and in the event word alike - and on a real run that one
     * code covered 104 of 135 failures. Only a second search with every condition off can
     * separate them, which is what explain_tracks buys.
     */
    private static final Map<String, String> REASON_TEXT = Map.of(
            "9", "nobody had it, OR they did and the conditions refused it - "
                    + "explain_tracks says which",
            "4", "found, but the download did not come through",
            "0", "no failure recorded");

    /**
     * What a state MEANS, for the report. Without this a row the tool never searched is
     * printed as a failure with an "unknown reason code", which reads as "we looked and found
     * nothing" - the opposite of the truth.
     *
     * <p>0 and 4 were measured on 2026-10-17: 0 is what a row left in the queue carries when a
     * run is cut short (the owner's own index had eleven of them after a timeout), and 4 is
     * what sockseek writes when {@code --skip-not-found} skips a row - also never searched.
     */
    private static final Map<String, String> STATE_TEXT = Map.of(
            "0", "never searched - the run ended before its turn",
            "1", "downloaded",
            "2", "searched and failed",
            "3", "already there, skipped",
            "4", "not searched - skipped as not found last time");

    private static final Pattern SPACES = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);
    private static final char BOM = '﻿';

    private SockseekIndex() {
    }

    /** {@code <outDir>/_index.csv}, or any index left deeper by an older version. */
    public static List<Path> findIndexFiles(Path outDir) {
        Path direct = outDir.resolve(INDEX_NAME);
        if (Files.isRegularFile(direct)) {
            return List.of(direct);
        }
        try (Stream<Path> found = Files.walk(outDir)) {
            return found.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().equals(INDEX_NAME))
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        } catch (IOException noFolder) {
            return List.of();
        }
    }

    /** Rows of the given index files, each with the folder its {@code filepath} is relative to. */
    public static List<Located> readWithFolders(List<Path> paths) {
        List<Located> located = new ArrayList<>();
        for (Path path : paths) {
            for (IndexRow row : rowsOf(path)) {
                located.add(new Located(row, path.getParent()));
            }
        }
        return List.copyOf(located);
    }

    public static List<IndexRow> read(List<Path> paths) {
        return readWithFolders(paths).stream().map(Located::row).toList();
    }

    /**
     * An index we cannot read is skipped, never fatal: it is sockseek's file, not ours.
     *
     * <p>And "never fatal" has to be true, which it was not until 2026-10-16: the catch named
     * {@code IOException | IllegalArgumentException}, while commons-csv throws
     * {@code CSVException} (an {@link IllegalStateException}) wrapped in an
     * {@link UncheckedIOException} - so a single stray quote in a file name escaped all the way
     * out of the front end as a stack trace, and no playlist could be listed at all.
     *
     * <p>Dropping the whole file is also worse than it looks: every track it says arrived would
     * read as missing and be searched again. So a malformed quote is retried leniently first,
     * the way the importer does it, because the files people have are written that way.
     */
    private static List<IndexRow> rowsOf(Path path) {
        Optional<String> text = decodeUtf8(path);
        if (text.isEmpty()) {
            return List.of();
        }
        CSVFormat.Builder shape = CSVFormat.DEFAULT.builder()
                .setHeader()
                .setSkipHeaderRecord(true);
        try {
            return parse(text.get(), shape.get());
        } catch (IOException | RuntimeException notReadable) {
            try {
                // an uploader's file name carrying a quote: sockseek quotes the field but does
                // not double the quote inside it, which is malformed CSV
                return parse(text.get(), shape.setTrailingData(true).get());
            } catch (IOException | RuntimeException reallyNotReadable) {
                return List.of();
            }
        }
    }

    private static List<IndexRow> parse(String text, CSVFormat format) throws IOException {
        try (CSVParser parser = CSVParser.parse(new StringReader(text), format)) {
            List<IndexRow> rows = new ArrayList<>();
            for (CSVRecord record : parser) {
                rows.add(IndexRow.from(record));
            }
            return rows;
        }
    }

    private static Optional<String> decodeUtf8(Path path) {
        byte[] data;
        try {
            data = Files.readAllBytes(path);
        } catch (IOException noFile) {
            return Optional.empty();
        }
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            String text = decoder.decode(ByteBuffer.wrap(data)).toString();
            return Optional.of(text.isEmpty() || text.charAt(0) != BOM ? text : text.substring(1));
        } catch (CharacterCodingException notUtf8) {
            return Optional.empty();
        }
    }

    private static String normalise(String text) {
        // toLowerCase rather than a full case fold: the two differ only for oddities like
        // the German sharp s,
        // which no file name here has yet produced
        return SPACES.matcher(text == null ? "" : text).replaceAll(" ").strip()
                .toLowerCase(Locale.ROOT);
    }

    /**
     * Index row number -> entry number, for the rows that match one.
     *
     * <p>Matched by title text, with the artist column put back in front of it - that is the
     * shape a search with {@code --extract-artist} leaves. A row that matches nothing is paired
     * by position only when the counts are equal: no guessing.
     */
    public static Map<Integer, Integer> match(List<Entry> entries, List<IndexRow> rows) {
        Map<Integer, Integer> mapping = new HashMap<>();
        Set<Integer> taken = new HashSet<>();
        for (int r = 0; r < rows.size(); r++) {
            Set<String> candidates = keys(normalise(rows.get(r).artist()),
                    normalise(rows.get(r).title()));
            for (int e = 0; e < entries.size(); e++) {
                if (taken.contains(e)) {
                    continue;
                }
                Track track = entries.get(e).track();
                Set<String> keys = keys(normalise(track.artist()), normalise(track.title()));
                if (keys.stream().anyMatch(candidates::contains)) {
                    mapping.put(r, e);
                    taken.add(e);
                    break;
                }
            }
        }
        // There was a positional fallback here until 2026-10-19: when the counts matched, an
        // unmatched row r was bound to entry r. It compared a ROW number against `taken`,
        // which holds ENTRY numbers, so with one leftover row in the file it could bind a
        // junk row to an innocent playlist line - and a line whose junk row says state=1
        // with a filepath that exists reads as "already here" and is never searched again.
        // Leftover rows are not exotic: fixing a typo in a line that had already been
        // downloaded leaves one. Title matching carries the load; guessing cost more than it
        // ever earned.
        return Map.copyOf(mapping);
    }

    /**
     * Smallest file that counts as a track that arrived.
     *
     * <p>1 KB, and the number is deliberately small: what this catches is the zero-byte or
     * all-but-empty leftover that {@code isRegularFile} said yes to, which then counted as
     * "already here" for ever - the one hole the top-up exists to close. It does NOT claim to
     * judge audio: nothing here opens or decodes the file, that decision (2026-10-16) stands,
     * and a transfer cut off halfway is normally not under its final name at all, because
     * sockseek writes to .incomplete and renames when it is done.
     */
    static final long SMALLEST_REAL_FILE = 1024;

    /** On disk, and big enough to be a track rather than the start of one. */
    private static boolean isReal(Path path) {
        try {
            return Files.isRegularFile(path) && Files.size(path) >= SMALLEST_REAL_FILE;
        } catch (IOException cannotTell) {
            return false;
        }
    }

    private static Set<String> keys(String artist, String title) {
        return artist.isEmpty() ? Set.of(title) : Set.of(title, artist + " - " + title);
    }

    /**
     * Which lines already have a file, and which do not.
     *
     * <p>A row counts only when BOTH hold: its state says arrived, and the file is really on
     * disk. A row whose file was deleted reads as missing again, and no index at all means
     * everything is missing - exactly right for a first run.
     */
    public static Status playlistStatus(List<Entry> entries, Path outDir) {
        List<Located> located = readWithFolders(findIndexFiles(outDir));
        List<IndexRow> rows = located.stream().map(Located::row).toList();
        Map<Integer, Integer> mapping = match(entries, rows);
        Map<String, Path> files = new LinkedHashMap<>();
        for (int r = 0; r < located.size(); r++) {
            Integer e = mapping.get(r);
            IndexRow row = located.get(r).row();
            if (e == null || !OK_STATES.contains(row.state().strip())) {
                continue;
            }
            String relative = row.filepath().strip();
            if (relative.isEmpty()) {
                continue;
            }
            // normalised: the index writes "./Name.mp3", and a path carrying that "." is
            // not equal to the plain one even when it is the same file
            Path path = located.get(r).folder().resolve(relative).normalize();
            if (isReal(path)) {
                files.putIfAbsent(entries.get(e).original(), path);
            }
        }
        return new Status(
                entries.stream().filter(entry -> files.containsKey(entry.original())).toList(),
                entries.stream().filter(entry -> !files.containsKey(entry.original())).toList(),
                Map.copyOf(files));
    }

    /** Playlist line -> its index row, to read the state and the reason code off it. */
    public static Map<String, IndexRow> rowsByEntry(List<Entry> entries, List<IndexRow> rows) {
        Map<Integer, Integer> mapping = match(entries, rows);
        Map<String, IndexRow> found = new LinkedHashMap<>();
        for (int r = 0; r < rows.size(); r++) {
            Integer e = mapping.get(r);
            if (e != null) {
                found.putIfAbsent(entries.get(e).original(), rows.get(r));
            }
        }
        return Map.copyOf(found);
    }

    /**
     * Every index row whose state is not OK. An empty {@link Failure#playlistLine()} means the
     * row matches no line in this playlist.
     */
    public static List<Failure> failures(List<Entry> entries, List<IndexRow> rows) {
        Map<Integer, Integer> mapping = match(entries, rows);
        List<Failure> failures = new ArrayList<>();
        for (int r = 0; r < rows.size(); r++) {
            IndexRow row = rows.get(r);
            if (OK_STATES.contains(row.state().strip())) {
                continue;
            }
            Integer e = mapping.get(r);
            failures.add(new Failure(e == null ? "" : entries.get(e).original(), row.title(),
                    row.length(), row.state(), row.failureReason()));
        }
        return List.copyOf(failures);
    }

    /** One log line for a failed track. */
    public static String format(Failure failure) {
        String reason = failure.failureReason().isBlank() ? "?" : failure.failureReason().strip();
        String state = failure.state().isEmpty() ? "?" : failure.state().strip();
        // the state first, because for 0 and 4 it is the whole story: those tracks were never
        // searched, and printing only the reason code calls them failures of the search
        String meaning = STATE_TEXT.containsKey(state) && !OK_STATES.contains(state)
                && !state.equals("2")
                ? STATE_TEXT.get(state)
                : REASON_TEXT.getOrDefault(reason, "unknown reason code");
        String head = "FAILED  state=" + state + " reason=" + reason + " (" + meaning + ")  ";
        return failure.playlistLine().isEmpty()
                ? head + "(no matching playlist line, index title: " + failure.indexTitle() + ")"
                : head + failure.playlistLine();
    }
}
